package io.droptracker.api;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.models.api.GroupConfig;
import io.droptracker.models.api.GroupSearchResult;
import io.droptracker.models.api.PlayerSearchResult;
import io.droptracker.models.api.TopGroupResult;
import io.droptracker.models.api.TopPlayersResult;
import io.droptracker.util.DebugLogger;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import net.runelite.api.Client;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
public class DropTrackerApi {
    private static final int GROUP_CONFIG_REFRESH_INTERVAL_SECONDS = 120;
    private static final int GROUP_CONFIG_RETRY_INTERVAL_SECONDS = 60;
    /** How long an aggregate /panel_data snapshot stays fresh for top-list reads. */
    private static final int PANEL_DATA_TTL_SECONDS = 60;
    /** After an aggregate fetch fails, skip further attempts for this long. */
    private static final int PANEL_DATA_FAILURE_BACKOFF_SECONDS = 300;
    /** Maximum number of uuids sent in one batch POST /check request. */
    private static final int CHECK_BATCH_LIMIT = 100;

    private final DropTrackerConfig config;
    @Inject
    private Gson gson;
    @Inject
    private OkHttpClient httpClient;

    /**
     * Client used for lightweight panel-data GETs (searches, top lists, configs, news).
     * Derived from the injected RuneLite client but with tight connect/read timeouts so a
     * slow or unresponsive API never hangs panel loads for minutes. Submission uploads
     * keep using the injected {@link #httpClient}, since those payloads can be large.
     */
    private OkHttpClient panelHttpClient;
    @Inject
    private DropTrackerPlugin plugin;

    @Inject
    private Client client;

    public List<GroupConfig> groupConfigs = new ArrayList<>();

    private int lastGroupConfigUpdateUnix = 0;
    private int lastGroupConfigLoadAttemptUnix = 0;
    private boolean isLoadingGroupConfigs = false;
    private long lastGroupConfigAccountHash = -1L;
    private String lastGroupConfigPlayerName = null;

    public int lastCommunicationTime = (int) (System.currentTimeMillis() / 1000);

    /**
     * Latest snapshot from the aggregate GET /panel_data endpoint. Guarded by
     * {@link #panelDataLock}; may be null when the endpoint has never succeeded
     * (e.g. custom API endpoints that predate the route).
     */
    private final Object panelDataLock = new Object();
    private PanelData cachedPanelData;
    private long cachedPanelDataAtMs = 0;
    /** Last time an aggregate fetch failed; used to briefly stop retrying old endpoints. */
    private long lastPanelDataFailureAtMs = 0;

    /** Optional callback invoked after group configs are successfully loaded */
    private Runnable onGroupConfigsLoadedCallback;
    
   

    @Inject
    public DropTrackerApi(DropTrackerConfig config, Gson gson, OkHttpClient httpClient, DropTrackerPlugin plugin, Client client) {
            this.config = config;
            this.gson = gson;
            this.httpClient = httpClient;
            this.plugin = plugin;
            this.client = client;
            this.panelHttpClient = httpClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Set a callback to be invoked when group configs are successfully loaded.
     */
    public void setOnGroupConfigsLoadedCallback(Runnable callback) {
        this.onGroupConfigsLoadedCallback = callback;
    }

    /* ===================== Aggregate panel data (GET /panel_data) ===================== */

    /**
     * Response shape of the aggregate GET /panel_data endpoint, which bundles
     * everything the side panel needs to boot in a single round-trip.
     */
    public static class PanelData {
        @SerializedName("configs")
        public List<GroupConfig> configs;
        @SerializedName("player_found")
        public Boolean playerFound;
        @SerializedName("top_groups")
        public TopGroupResult topGroups;
        @SerializedName("top_players")
        public TopPlayersResult topPlayers;
        @SerializedName("welcome")
        public String welcome;
        @SerializedName("news")
        public String news;
        @SerializedName("version")
        public VersionInfo version;
    }

    /**
     * Fetches the aggregate panel data snapshot, caching it on success. Both query
     * params are optional; without them the response simply omits player configs.
     * Returns null on any failure (network error, non-200 from an older custom API
     * endpoint that doesn't have the route yet, malformed body) so callers can fall
     * back to the individual endpoints. Performs blocking I/O - never call on the EDT.
     */
    private PanelData fetchPanelData(String playerName, Long accountHash) {
        if (!config.useApi()) {
            return null;
        }
        // Back off briefly after a failure so custom endpoints that predate the
        // route aren't hit with a doomed extra request on every panel load.
        synchronized (panelDataLock) {
            if (lastPanelDataFailureAtMs > 0
                && (System.currentTimeMillis() - lastPanelDataFailureAtMs) < PANEL_DATA_FAILURE_BACKOFF_SECONDS * 1000L) {
                return null;
            }
        }
        HttpUrl base = HttpUrl.parse(getApiUrl() + "/panel_data");
        if (base == null) {
            return null;
        }
        HttpUrl.Builder urlBuilder = base.newBuilder();
        if (playerName != null && !playerName.isEmpty()) {
            urlBuilder.addQueryParameter("player_name", playerName);
        }
        if (accountHash != null && accountHash != -1L) {
            urlBuilder.addQueryParameter("acc_hash", String.valueOf(accountHash));
        }

        Request request = new Request.Builder().url(urlBuilder.build()).build();
        try (Response response = panelHttpClient.newCall(request).execute()) {
            lastCommunicationTime = (int) (System.currentTimeMillis() / 1000);
            if (!response.isSuccessful() || response.body() == null) {
                return recordPanelDataFailure("HTTP " + response.code());
            }
            PanelData data = gson.fromJson(response.body().string(), PanelData.class);
            if (data == null) {
                return recordPanelDataFailure("empty body");
            }
            synchronized (panelDataLock) {
                cachedPanelData = data;
                cachedPanelDataAtMs = System.currentTimeMillis();
                lastPanelDataFailureAtMs = 0;
            }
            return data;
        } catch (IOException | JsonSyntaxException e) {
            return recordPanelDataFailure(e.getMessage());
        }
    }

    private PanelData recordPanelDataFailure(String reason) {
        synchronized (panelDataLock) {
            lastPanelDataFailureAtMs = System.currentTimeMillis();
        }
        log.debug("/panel_data fetch failed; falling back to individual endpoints: {}", reason);
        return null;
    }

    /** Cache-only read of the latest aggregate snapshot; safe to call from the EDT. */
    private PanelData getCachedPanelData() {
        synchronized (panelDataLock) {
            return cachedPanelData;
        }
    }

    /**
     * Returns a fresh aggregate snapshot, fetching one if the cache is stale. The
     * fetch happens under the lock so concurrent panel loaders share one request.
     * Returns null when the aggregate endpoint is unavailable. Blocking - off-EDT only.
     */
    private PanelData getPanelDataFreshOrFetch() {
        synchronized (panelDataLock) {
            if (cachedPanelData != null
                && (System.currentTimeMillis() - cachedPanelDataAtMs) < PANEL_DATA_TTL_SECONDS * 1000L) {
                return cachedPanelData;
            }
            return fetchPanelData(null, null);
        }
    }
    


    /* Group Configs */
    @SuppressWarnings({ "null" })
    public synchronized void loadGroupConfigs(String playerName) throws IOException {
        if (!config.useApi()) {
            return;
        }
        
        long accountHash = client.getAccountHash();
        if (accountHash == -1 || playerName == null || playerName.isEmpty()) {
            return;
        }

        int nowUnix = (int) (System.currentTimeMillis() / 1000);
        boolean accountOrPlayerChanged = accountHash != lastGroupConfigAccountHash
            || !playerName.equals(lastGroupConfigPlayerName);

        // If we changed account/player, allow an immediate refresh to avoid stale data.
        if (accountOrPlayerChanged) {
            groupConfigs = new ArrayList<>();
            lastGroupConfigUpdateUnix = 0;
        } else {
            int minInterval = lastGroupConfigUpdateUnix > 0
                ? GROUP_CONFIG_REFRESH_INTERVAL_SECONDS
                : GROUP_CONFIG_RETRY_INTERVAL_SECONDS;
            if ((nowUnix - lastGroupConfigLoadAttemptUnix) < minInterval) {
                return;
            }
        }

        // Prevent concurrent loading
        if (isLoadingGroupConfigs) {
            return;
        }

        lastGroupConfigLoadAttemptUnix = nowUnix;
        lastGroupConfigAccountHash = accountHash;
        lastGroupConfigPlayerName = playerName;
        isLoadingGroupConfigs = true;
        
        CompletableFuture.runAsync(() -> {
            String responseData = null;
            try {
                // Prefer the aggregate /panel_data endpoint: one round-trip refreshes the
                // configs plus the top lists, welcome/news text and version info.
                PanelData panelData = fetchPanelData(playerName, accountHash);
                if (panelData != null) {
                    if (panelData.configs != null && !Boolean.FALSE.equals(panelData.playerFound)) {
                        groupConfigs = new ArrayList<>(panelData.configs);
                        lastGroupConfigUpdateUnix = (int) (System.currentTimeMillis() / 1000);
                        if (onGroupConfigsLoadedCallback != null) {
                            try {
                                onGroupConfigsLoadedCallback.run();
                            } catch (Exception callbackEx) {
                                log.debug("Error in group config loaded callback: " + callbackEx.getMessage());
                            }
                        }
                    } else {
                        // Unknown player: same as the old /load_config 404 - leave
                        // lastGroupConfigUpdateUnix unset so the retry schedule applies.
                        log.debug("/panel_data returned no configs for {}; will retry", playerName);
                    }
                    return;
                }

                // Fallback: the legacy per-purpose /load_config endpoint.
                String apiUrl = getApiUrl();
                HttpUrl baseUrl = HttpUrl.parse(apiUrl + "/load_config");
                if (baseUrl == null) {
                    return;
                }
                HttpUrl url = baseUrl.newBuilder()
                    .addQueryParameter("player_name", playerName)
                    .addQueryParameter("acc_hash", String.valueOf(accountHash))
                    .build();
                
                Request request = new Request.Builder().url(url).build();

                try (Response response = panelHttpClient.newCall(request).execute()) {
                    lastCommunicationTime = (int) (System.currentTimeMillis() / 1000);

                    if (!response.isSuccessful()) {
                        throw new IOException("API request failed with status: " + response.code());
                    }
                    
                    if (response.body() == null) {
                        throw new IOException("Empty response body");
                    }
                    
                    responseData = response.body().string();
                    
                    // Parse the response
                    GroupConfig[] configArray = gson.fromJson(responseData, GroupConfig[].class);
                    List<GroupConfig> parsedConfigs = new ArrayList<>(Arrays.asList(configArray));
                    
                    if (parsedConfigs != null) {
                        groupConfigs = parsedConfigs;
                    }
                    
                    lastGroupConfigUpdateUnix = (int) (System.currentTimeMillis() / 1000);
                    
                    // Notify listeners that group configs are now available
                    if (onGroupConfigsLoadedCallback != null) {
                        try {
                            onGroupConfigsLoadedCallback.run();
                        } catch (Exception callbackEx) {
                            log.debug("Error in group config loaded callback: " + callbackEx.getMessage());
                        }
                    }
                    
                } catch (IOException e) {
                    log.debug("Couldn't load group config in side panel (IOException) " + e);
                } catch (JsonSyntaxException e) {
                    log.debug("Couldn't load group config in side panel (JsonSyntaxException) " + e);
                } catch (Exception e) {
                    log.debug("Couldn't load group config in side panel (Exception) " + e);
                }
            } finally {
                isLoadingGroupConfigs = false;
            }
        }).exceptionally(ex -> {
            log.debug(ex.getMessage());
            isLoadingGroupConfigs = false;
            return null;
        });
    }

    /**
     * Returns the most recently loaded group configs from memory. This never performs
     * any network I/O, so it is safe to call from the Swing EDT. Callers that want
     * fresher data should invoke {@link #refreshGroupConfigsAsync()} first.
     */
    public List<GroupConfig> getGroupConfigs() {
        if (!config.useApi()) {
            return null;
        }
        return groupConfigs;
    }

    /**
     * Schedules a rate-limited asynchronous refresh of the group configs. The actual
     * network request runs off the calling thread, so this is safe to call from the EDT.
     */
    public void refreshGroupConfigsAsync() {
        if (!config.useApi()) {
            return;
        }
        try {
            loadGroupConfigs(plugin.getLocalPlayerName());
        } catch (IOException e) {
            log.debug("Couldn't refresh group configs " + e);
        }
    }

    /* Submissions */
    public String generateGuidForSubmission() {
        long timestamp = System.currentTimeMillis() / 1000L;
        long accountHash = client != null ? client.getAccountHash() : -1L;
        long randomPart = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        return timestamp + "-" + accountHash + "-" + randomPart;
    }
    


    @SuppressWarnings("null")
    public TopGroupResult getTopGroups() {
        if (!config.useApi()) {
            return null;
        }
        // Prefer the aggregate snapshot; one /panel_data request serves both top lists.
        PanelData panelData = getPanelDataFreshOrFetch();
        if (panelData != null && panelData.topGroups != null && panelData.topGroups.getGroups() != null) {
            return panelData.topGroups;
        }
        String apiUrl = getApiUrl();
        try {
            HttpUrl url = HttpUrl.parse(apiUrl + "/top_groups");
            Request request = new Request.Builder().url(url).build();
            try (Response response = panelHttpClient.newCall(request).execute()) {
                lastCommunicationTime = (int) (System.currentTimeMillis() / 1000);
                if (!response.isSuccessful()) {
                    throw new IOException("API request failed with status: " + response.code());
                }
                if (response.body() == null) {
                    throw new IOException("Empty response body");
                } else {
                    String responseData = response.body().string();
                    return this.gson.fromJson(responseData, TopGroupResult.class);
                }
            }
        } catch (IOException e) {
            log.debug("Couldn't get top groups (IOException) " + e);
            return null;
        }
    }

    @SuppressWarnings("null")
    public TopPlayersResult getTopPlayers() {
        if (!config.useApi()) {
            return null;
        }
        // Prefer the aggregate snapshot; one /panel_data request serves both top lists.
        PanelData panelData = getPanelDataFreshOrFetch();
        if (panelData != null && panelData.topPlayers != null && panelData.topPlayers.getPlayers() != null) {
            return panelData.topPlayers;
        }
        String apiUrl = getApiUrl();
        try {
            HttpUrl url = HttpUrl.parse(apiUrl + "/top_players");
            Request request = new Request.Builder().url(url).build();
            try (Response response = panelHttpClient.newCall(request).execute()) {
                lastCommunicationTime = (int) (System.currentTimeMillis() / 1000);
                if (!response.isSuccessful()) {
                    throw new IOException("API request failed with status: " + response.code());
                }
                if (response.body() == null) {
                    throw new IOException("Empty response body");
                } else {
                    String responseData = response.body().string();
                    return gson.fromJson(responseData, TopPlayersResult.class);
                }
            }
        } catch (IOException e) {
            log.debug("Couldn't get top players (IOException) " + e);
            return null;
        }
    }

    /**
     * Sends a request to the API to search for a group and returns the GroupSearchResult.
     */
    @SuppressWarnings("null")
    public GroupSearchResult searchGroup(String groupName) throws IOException {
        if (!config.useApi()) {
            return null;
        }

        String apiUrl = getApiUrl();
        HttpUrl baseUrl = HttpUrl.parse(apiUrl + "/group_search");

        if (baseUrl == null) {
            throw new IllegalArgumentException("Invalid URL");
        }
        HttpUrl url = baseUrl.newBuilder()
            .addQueryParameter("name", groupName)
            .build();

        Request request = new Request.Builder().url(url).build();
        lastCommunicationTime = (int) (System.currentTimeMillis() / 1000);

        try (Response response = panelHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API request failed with status: " + response.code());
            }

            if (response.body() == null) {
                throw new IOException("Empty response body");
            }

            String responseData = response.body().string();
            try {
                return gson.fromJson(responseData, GroupSearchResult.class);
            } catch (JsonSyntaxException e) {
                throw new IOException("Malformed /group_search response", e);
            }
        }
    }

    /**
     * Sends a request to the API to look up a player's data and returns the PlayerSearchResult.
     */
    @SuppressWarnings("null")
    public PlayerSearchResult lookupPlayer(String playerName) throws IOException {
        if (!config.useApi()) {
            return null;
        }

        String apiUrl = getApiUrl();
        HttpUrl baseUrl = HttpUrl.parse(apiUrl + "/player_search");

        if (baseUrl == null) {
            throw new IllegalArgumentException("Invalid URL");
        }
        HttpUrl url = baseUrl.newBuilder()
            .addQueryParameter("name", playerName)
            .build();

        Request request = new Request.Builder().url(url).build();
        lastCommunicationTime = (int) (System.currentTimeMillis() / 1000);
        try (Response response = panelHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API request failed with status: " + response.code());
            }

            if (response.body() == null) {
                throw new IOException("Empty response body");
            }

            String responseData = response.body().string();
            try {
                return gson.fromJson(responseData, PlayerSearchResult.class);
            } catch (JsonSyntaxException e) {
                throw new IOException("Malformed /player_search response", e);
            }
        }
    }

    public String getApiUrl() {
        if (config.customApiEndpoint().equals("")) {
            return config.useApi() ? "https://api.droptracker.io" : "";
        } else {
            if (!config.customApiEndpoint().startsWith("http")) {
                return "http://" + config.customApiEndpoint();
            }
            return config.customApiEndpoint();
        }
    }

    /** Version metadata returned by GET /plugin_version. */
    public static class VersionInfo {
        @SerializedName("latest_version")
        public String latestVersion;
        @SerializedName("minimum_version")
        public String minimumVersion;
        @SerializedName("message")
        public String message;
    }

    /**
     * Fetches the latest/minimum plugin version from the API. The endpoint
     * requires no auth, so this works even with the API integration disabled
     * (falls back to the default API host in that case). Returns null on any
     * failure — the version check is best-effort and must never break startup.
     */
    public VersionInfo fetchVersionInfo() {
        // Prefer version info already delivered by the aggregate /panel_data snapshot.
        PanelData panelData = getCachedPanelData();
        if (panelData != null && panelData.version != null && panelData.version.latestVersion != null) {
            return panelData.version;
        }
        String apiUrl = getApiUrl();
        if (apiUrl == null || apiUrl.isEmpty()) {
            apiUrl = "https://api.droptracker.io";
        }
        HttpUrl url = HttpUrl.parse(apiUrl + "/plugin_version");
        if (url == null) {
            return null;
        }
        Request request = new Request.Builder().url(url).build();
        try (Response response = panelHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            return gson.fromJson(response.body().string(), VersionInfo.class);
        } catch (Exception e) {
            log.debug("Version check failed: {}", e.getMessage());
            return null;
        }
    }


    /**
     * Check whether a submission with the given uuid has been processed by the API.
     * Sends a JSON body {"uuid": "..."} to {apiUrl}/check via POST.
     * Returns true if the API explicitly reports the submission as processed.
     */
    public boolean checkSubmissionProcessed(String uuid) throws IOException {
        if (!config.useApi() || uuid == null || uuid.isEmpty()) {
            return false;
        }

        String apiUrl = getApiUrl();
        HttpUrl url = HttpUrl.parse(apiUrl + "/check");
        if (url == null) {
            throw new IllegalArgumentException("Invalid URL");
        }

        // Build JSON body
        String jsonBody = gson.toJson(java.util.Collections.singletonMap("uuid", uuid));
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), jsonBody);

        Request request = new Request.Builder()
            .url(url)
            .post(body)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            lastCommunicationTime = (int) (System.currentTimeMillis() / 1000);
            
            if (!response.isSuccessful()) {
                return false;
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                return false;
            }
            String responseData = responseBody.string();
            
            try {
                // Response is expected to contain at least { processed: boolean } for the given uuid
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> map = gson.fromJson(responseData, java.util.Map.class);
                Object processedVal = map != null ? map.get("processed") : null;
                
                if (processedVal instanceof Boolean) {
                    return (Boolean) processedVal;
                }
                // Some APIs might return status: "processed"
                Object statusVal = map != null ? map.get("status") : null;
                
                if (statusVal instanceof String) {
                    return "processed".equalsIgnoreCase((String) statusVal);
                }
            } catch (Exception e) {
                // If parsing fails, consider it not processed
                return false;
            }
        }

        return false;
    }

    /** One entry in the batch POST /check response. */
    private static class CheckResultEntry {
        @SerializedName("uuid")
        String uuid;
        @SerializedName("processed")
        Boolean processed;
        @SerializedName("status")
        String status;
    }

    /** Response shape of the batch POST /check form. */
    private static class BatchCheckResponse {
        @SerializedName("results")
        List<CheckResultEntry> results;
    }

    /**
     * Batch form of {@link #checkSubmissionProcessed(String)}: checks up to
     * {@value #CHECK_BATCH_LIMIT} submission uuids in a single POST /check request
     * ({"uuids": [...]}). Returns a map of uuid to processed-state for the uuids the
     * API reported on. "pending" is a normal long-lived state, not an error.
     *
     * @throws IOException when the request fails or the body is unparseable (e.g. an
     *         older custom endpoint that only supports the single-uuid form) so the
     *         caller can fall back to per-uuid checks for this poll tick.
     */
    public Map<String, Boolean> checkSubmissionsProcessed(List<String> uuids) throws IOException {
        if (!config.useApi() || uuids == null || uuids.isEmpty()) {
            return java.util.Collections.emptyMap();
        }

        List<String> capped = uuids.size() > CHECK_BATCH_LIMIT
            ? new ArrayList<>(uuids.subList(0, CHECK_BATCH_LIMIT))
            : uuids;

        HttpUrl url = HttpUrl.parse(getApiUrl() + "/check");
        if (url == null) {
            throw new IllegalArgumentException("Invalid URL");
        }

        String jsonBody = gson.toJson(java.util.Collections.singletonMap("uuids", capped));
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), jsonBody);
        Request request = new Request.Builder()
            .url(url)
            .post(body)
            .build();

        try (Response response = panelHttpClient.newCall(request).execute()) {
            lastCommunicationTime = (int) (System.currentTimeMillis() / 1000);

            if (!response.isSuccessful()) {
                throw new IOException("Batch /check failed with status: " + response.code());
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty batch /check response body");
            }

            BatchCheckResponse parsed;
            try {
                parsed = gson.fromJson(responseBody.string(), BatchCheckResponse.class);
            } catch (JsonSyntaxException e) {
                throw new IOException("Malformed batch /check response", e);
            }
            if (parsed == null || parsed.results == null) {
                throw new IOException("Batch /check response missing results");
            }

            Map<String, Boolean> results = new java.util.HashMap<>();
            for (CheckResultEntry entry : parsed.results) {
                if (entry == null || entry.uuid == null) {
                    continue;
                }
                boolean processed = Boolean.TRUE.equals(entry.processed)
                    || "processed".equalsIgnoreCase(entry.status);
                results.put(entry.uuid, processed);
            }
            return results;
        }
    }

    // ========== Video Upload ==========

    /**
     * Response from the presigned video upload URL endpoint.
     */
    public static class PresignedUrlResponse {
        public String uploadUrl;
        public String key;
        public boolean quotaExceeded = false;
        public String message;
    }

    /**
     * Gets a presigned upload URL from the API for uploading video frames.
     * The server returns an upload URL (e.g. to cloud storage) and a key
     * that can later be included in the webhook to reference the video.
     *
     * @param fps The frames per second the video was recorded at
     * @return The presigned URL response, or null on failure
     */
    public PresignedUrlResponse getPresignedVideoUploadUrl(int fps) {
        if (!config.useApi()) {
            log.warn("Skipping presigned video URL request: API disabled (useApi=false)");
            return null;
        }

        String apiUrl = getApiUrl();
        if (client == null || client.getAccountHash() == -1) {
            log.warn("Cannot request presigned URL: missing acc_hash");
            return null;
        }

        HttpUrl base = HttpUrl.parse(apiUrl + "/presigned_upload_url");
        if (base == null) {
            log.warn("Invalid presigned upload base URL: {}", apiUrl);
            return null;
        }

        HttpUrl url = base.newBuilder()
            .addQueryParameter("fps", String.valueOf(fps))
            .addQueryParameter("acc_hash", String.valueOf(client.getAccountHash()))
            .build();

        log.debug("Requesting presigned video upload URL (fps={}, acc_hash={})", fps, client.getAccountHash());

        Request request = new Request.Builder()
            .url(url)
            .get()
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            lastCommunicationTime = (int) (System.currentTimeMillis() / 1000);
            ResponseBody responseBody = response.body();
            String body = responseBody != null ? responseBody.string() : "";

            if (response.code() == 402) {
                // Quota exceeded
                PresignedUrlResponse result = new PresignedUrlResponse();
                result.quotaExceeded = true;
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> map = gson.fromJson(body, java.util.Map.class);
                    result.message = map != null && map.get("message") != null
                        ? String.valueOf(map.get("message"))
                        : "Daily video limit reached";
                } catch (Exception e) {
                    result.message = "Daily video limit reached";
                }
                log.warn("Presigned video upload URL request quota exceeded: {}", result.message);
                return result;
            }

            if (!response.isSuccessful()) {
                log.error("Failed to get presigned URL: {} - {}. Body: {}", response.code(), response.message(), body);
                return null;
            }

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = gson.fromJson(body, java.util.Map.class);
            if (map == null || !map.containsKey("upload_url") || !map.containsKey("key")) {
                log.error("Presigned URL response missing required fields");
                return null;
            }

            PresignedUrlResponse result = new PresignedUrlResponse();
            result.uploadUrl = String.valueOf(map.get("upload_url"));
            result.key = String.valueOf(map.get("key"));
            return result;
        } catch (IOException e) {
            log.error("Error getting presigned video upload URL", e);
            return null;
        }
    }

    /**
     * Reports a failed video upload so the server marks the pending upload
     * ticket as failed with the real reason, instead of leaving it to age out
     * as "presigned URL expired". Fire-and-forget: report failures are only
     * logged, never surfaced to the user.
     *
     * @param key The video object key from the presigned upload response
     * @param reason Short description of why the upload failed
     */
    public void reportVideoUploadFailure(String key, String reason) {
        if (!config.useApi() || key == null || key.isEmpty()) {
            return;
        }
        if (client == null || client.getAccountHash() == -1) {
            return;
        }

        java.util.Map<String, String> payload = new java.util.HashMap<>();
        payload.put("key", key);
        payload.put("acc_hash", String.valueOf(client.getAccountHash()));
        payload.put("reason", reason != null ? reason : "unknown");

        Request request = new Request.Builder()
            .url(getApiUrl() + "/video/upload-failed")
            .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), gson.toJson(payload)))
            .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                log.debug("Failed to report video upload failure for key {}", key, e);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) {
                response.close();
            }
        });
    }

    /**
     * Asynchronously fetches the latest welcome string to avoid blocking the EDT.
     * @param callback Function to call with the result when ready
     */
    public void getLatestWelcomeString(java.util.function.Consumer<String> callback) {
        String endpoint;
        if (config.useApi()) {
            // Serve from the aggregate snapshot when available (cache-only, EDT-safe);
            // the periodic /panel_data refresh keeps it current.
            PanelData panelData = getCachedPanelData();
            if (panelData != null && panelData.welcome != null && !panelData.welcome.isEmpty()) {
                final String welcome = panelData.welcome;
                javax.swing.SwingUtilities.invokeLater(() -> callback.accept(welcome));
                return;
            }
            endpoint = getApiUrl() + "/latest_welcome";
        } else {
            endpoint = "https://droptracker-io.github.io/content/welcome.txt";
        }

        Request request = new Request.Builder().url(endpoint).build();
        panelHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                // Run callback on EDT to update UI safely
                javax.swing.SwingUtilities.invokeLater(() ->
                    callback.accept("Welcome to the DropTracker!"));
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                try (Response autoCloseResponse = response; ResponseBody responseBody = response.body()) {
                    String result;
                    if (response.isSuccessful() && responseBody != null) {
                        result = responseBody.string();
                    } else {
                        result = "Welcome to the DropTracker!";
                    }
                    
                    // Run callback on EDT to update UI safely
                    javax.swing.SwingUtilities.invokeLater(() -> callback.accept(result));
                }
            }
        });
    }

    /**
     * Asynchronously fetches the latest update string to avoid blocking the EDT.
     * @param callback Function to call with the result when ready
     */
    public void getLatestUpdateString(java.util.function.Consumer<String> callback) {
        String endpoint;
        if (config.useApi()) {
            // Serve from the aggregate snapshot when available (cache-only, EDT-safe);
            // the periodic /panel_data refresh keeps it current.
            PanelData panelData = getCachedPanelData();
            if (panelData != null && panelData.news != null && !panelData.news.isEmpty()) {
                final String news = panelData.news;
                javax.swing.SwingUtilities.invokeLater(() -> callback.accept(news));
                return;
            }
            endpoint = getApiUrl() + "/latest_news";
        } else {
            endpoint = "https://droptracker-io.github.io/content/news.txt";
        }

        Request request = new Request.Builder().url(endpoint).build();
        panelHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                // Run callback on EDT to update UI safely
                javax.swing.SwingUtilities.invokeLater(() ->
                    callback.accept("Error fetching latest update: " + e.getMessage()));
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                try (Response autoCloseResponse = response; ResponseBody responseBody = response.body()) {
                    String result;
                    if (response.isSuccessful() && responseBody != null) {
                        result = responseBody.string();
                    } else {
                        result = "Error fetching latest update: " + (responseBody != null ? responseBody.string() : "Unknown error");
                    }
                    
                    // Run callback on EDT to update UI safely
                    javax.swing.SwingUtilities.invokeLater(() -> callback.accept(result));
                }
            }
        });
    }

    /**
     * Query the API (or github pages) for a list of 'valuable' untradeables; or in other words,
     * a list of items that have modified 'true' values based on what they are components towards
     * For example, a bludgeon axon should be worth 1/3 the price of a bludgeon.
     * Since this list should be updated infrequently, we can simply load only if not present.
     */
    public ArrayList<Integer> getValuedUntradeables() {
        String valued;
        /* Only use github pages URL, as our API is sometimes not responding fast enough currently... */

        String url = "https://droptracker-io.github.io/content/valued_items.txt";
        try {
            Request request = new Request.Builder().url(url).build();
            try (Response response = panelHttpClient.newCall(request).execute()) {
                lastCommunicationTime = (int) (System.currentTimeMillis() / 1000);
                if (!response.isSuccessful()) {
                    throw new IOException("API request failed with status: " + response.code());
                }
                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    throw new IOException("Empty response body");
                } else {
                    valued = responseBody.string();
                    String[] valuedList = valued.split(",");
                    ArrayList<Integer> itemIdList = new ArrayList<>();
                    for (String itemIdString : valuedList) {
                        try {
                            String idStripped = itemIdString.replace("\"", "").replace("[", "").replace("]", "");
                            int itemId = Integer.parseInt(idStripped.trim());
                            itemIdList.add(itemId);
                        } catch (NumberFormatException e) {
                            // Handle cases where a part of the string isn't a valid integer
                            DebugLogger.log("[DropTrackerApi][untradeables] skipped invalid itemId token=" + itemIdString);
                        }
                    }
                    DebugLogger.log("[DropTrackerApi][untradeables] loaded itemId count=" + itemIdList.size());
                    return itemIdList;
                }
            }
        } catch (IOException e) {
            DebugLogger.log("[DropTrackerApi][untradeables] failed to load from "
                + (config.useApi() ? "API" : "GitHub") + "; reason=" + e.getMessage());
            return null;
        }
    }

    public interface PanelDataLoadedCallback {
        void onDataLoaded(Map<String, Object> data);
    }
}
