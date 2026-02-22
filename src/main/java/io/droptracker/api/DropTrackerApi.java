package io.droptracker.api;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class DropTrackerApi {
    private static final int GROUP_CONFIG_REFRESH_INTERVAL_SECONDS = 120;
    private static final int GROUP_CONFIG_RETRY_INTERVAL_SECONDS = 60;

    private final DropTrackerConfig config;
    @Inject
    private Gson gson;
    @Inject
    private OkHttpClient httpClient;
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

    /** Optional callback invoked after group configs are successfully loaded */
    private Runnable onGroupConfigsLoadedCallback;
    
   

    @Inject
    public DropTrackerApi(DropTrackerConfig config, Gson gson, OkHttpClient httpClient, DropTrackerPlugin plugin, Client client) {
            this.config = config;
            this.gson = gson;
            this.httpClient = httpClient;
            this.plugin = plugin;
            this.client = client;
    }

    /**
     * Set a callback to be invoked when group configs are successfully loaded.
     */
    public void setOnGroupConfigsLoadedCallback(Runnable callback) {
        this.onGroupConfigsLoadedCallback = callback;
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
                
                try (Response response = httpClient.newCall(request).execute()) {
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

    /* Get all players' group configs from memory */
    public List<GroupConfig> getGroupConfigs() {
        if (!config.useApi()) {
            return null;
        }
        try {
            loadGroupConfigs(plugin.getLocalPlayerName());
        } catch (IOException e) {
            log.debug("Couldn't get group config in side panel (IOException) " + e);
        }
        
        return groupConfigs;
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
        String apiUrl = getApiUrl();
        try {
            HttpUrl url = HttpUrl.parse(apiUrl + "/top_groups");
            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
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
        String apiUrl = getApiUrl();
        try {
            HttpUrl url = HttpUrl.parse(apiUrl + "/top_players");
            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
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
    @SuppressWarnings({ "null", "unchecked" })
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

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API request failed with status: " + response.code());
            }

            if (response.body() == null) {
                throw new IOException("Empty response body");
            }

            String responseData = response.body().string();
            
            // Try to parse as GroupSearchResult first
            try {
                return gson.fromJson(responseData, GroupSearchResult.class);
            } catch (Exception e) {
                // If direct parsing fails, try to parse as Map and convert
                // NOTE: This feels very ugly - we own the API. We should know the returned format
                Map<String, Object> responseMap = gson.fromJson(responseData, Map.class);
                String jsonString = gson.toJson(responseMap);
                return gson.fromJson(jsonString, GroupSearchResult.class);
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
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API request failed with status: " + response.code());
            }

            if (response.body() == null) {
                throw new IOException("Empty response body");
            }

            String responseData = response.body().string();
            
            // Try to parse as PlayerSearchResult first
            try {
                return gson.fromJson(responseData, PlayerSearchResult.class);
            } catch (Exception e) {
                // If direct parsing fails, try to parse as Map and convert
                // NOTE: We own the API - we should make sure we return values that can be parsed
                @SuppressWarnings("unchecked")
                Map<String, Object> responseMap = gson.fromJson(responseData, Map.class);
                String jsonString = gson.toJson(responseMap);
                return gson.fromJson(jsonString, PlayerSearchResult.class);
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

        log.info("Requesting presigned video upload URL (fps={}, acc_hash={})", fps, client.getAccountHash());

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
     * Asynchronously fetches the latest welcome string to avoid blocking the EDT.
     * @param callback Function to call with the result when ready
     */
    public void getLatestWelcomeString(java.util.function.Consumer<String> callback) {
        String endpoint;
        if (config.useApi()) {
            endpoint = getApiUrl() + "/latest_welcome";
        } else {
            endpoint = "https://droptracker-io.github.io/content/welcome.txt";
        }

        Request request = new Request.Builder().url(endpoint).build();
        httpClient.newCall(request).enqueue(new Callback() {
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
            endpoint = getApiUrl() + "/latest_news";
        } else {
            endpoint = "https://droptracker-io.github.io/content/news.txt";
        }

        Request request = new Request.Builder().url(endpoint).build();
        httpClient.newCall(request).enqueue(new Callback() {
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
            try (Response response = httpClient.newCall(request).execute()) {
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
