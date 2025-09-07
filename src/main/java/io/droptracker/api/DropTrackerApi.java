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
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import net.runelite.api.Client;

import javax.inject.Inject;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class DropTrackerApi {
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
    private boolean isLoadingGroupConfigs = false;

    public int lastCommunicationTime = (int) (System.currentTimeMillis() / 1000);
    
   

    @Inject
    public DropTrackerApi(DropTrackerConfig config, Gson gson, OkHttpClient httpClient, DropTrackerPlugin plugin, Client client) {
            this.config = config;
            this.gson = gson;
            this.httpClient = httpClient;
            this.plugin = plugin;
            this.client = client;
    }
    


    /* Group Configs */
    @SuppressWarnings({ "null" })
    public synchronized void loadGroupConfigs(String playerName) throws IOException {
        if (!config.useApi()) {
            return;
        }
        
        if (client.getAccountHash() == -1 || playerName == null) {
            return;
        }
        
        // Prevent concurrent loading
        if (isLoadingGroupConfigs) {
            return;
        }
        
        isLoadingGroupConfigs = true;
        
        CompletableFuture.runAsync(() -> {
            String responseData = null;
            try {
                String apiUrl = getApiUrl();
                String fullUrl = apiUrl + "/load_config?player_name=" + playerName + "&acc_hash=" + client.getAccountHash();
                
                HttpUrl url = HttpUrl.parse(fullUrl);
                if (url == null) {
                    return;
                }
                
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
        /* Reload group configs if they haven't been updated in the last 120 seconds */
        if (lastGroupConfigUpdateUnix < (int) (System.currentTimeMillis() / 1000) - 120 && !isLoadingGroupConfigs) {
            try {
                loadGroupConfigs(plugin.getLocalPlayerName());
            } catch (IOException e) {
                log.debug("Couldn't get group config in side panel (IOException) " + e);
            }
        }
        
        return groupConfigs;
    }

    /* Get a specific group config from memory */
    public GroupConfig getGroupConfig(String groupName) {
        if (!config.useApi()) {
            return null;
        }
        return this.getGroupConfigs().stream()
            .filter(groupConfig -> groupConfig.getGroupName().equals(groupName))
            .findFirst()
            .orElse(null);
    }

    /* Submissions */
    public String generateGuidForSubmission() {
        long timestamp = System.currentTimeMillis() / 1000L;
        long accountHash = client != null ? client.getAccountHash() : -1L;
        long randomPart = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        return timestamp + "-" + accountHash + "-" + randomPart;
    }
    


    @SuppressWarnings("null")
    public TopGroupResult getTopGroups() throws IOException {
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
    public TopPlayersResult getTopPlayers() throws IOException {
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
        HttpUrl url = HttpUrl.parse(apiUrl + "/group_search?name=" + groupName);

        if (url == null) {
            throw new IllegalArgumentException("Invalid URL");
        }

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

    public String getCurrentLatency() {
        if (!config.useApi()) {
            return "? ms";
        }
        try {
            String url = getApiUrl() + "/ping";
            long startTime = System.currentTimeMillis();
            try (Response response = httpClient.newCall(new Request.Builder().url(url).build()).execute()) {
                if (!response.isSuccessful()) {
                    return "? ms";
                }
            } catch (IOException e) {
                log.debug("Couldn't get current latency (IOException) " + e);
                return "? ms";
            }
            long endTime = System.currentTimeMillis();
            return String.valueOf((int) (endTime - startTime)) + "ms";
        } catch (Exception e) {
            return "? ms";
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
        HttpUrl url = HttpUrl.parse(apiUrl + "/player_search?name=" + playerName);

        if (url == null) {
            throw new IllegalArgumentException("Invalid URL");
        }

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
        return config.useApi() ? "https://api.droptracker.io" : "";
    }


    /**
     * Asynchronously fetches the latest welcome string to avoid blocking the EDT.
     * @param callback Function to call with the result when ready
     */
    public void getLatestWelcomeString(java.util.function.Consumer<String> callback) {
        String endpoint;
        if (config.useApi()) {
            endpoint = "https://api.droptracker.io/latest_welcome";
        } else {
            endpoint = "https://droptracker-io.github.io/content/welcome.txt";
        }

        Request request = new Request.Builder().url(endpoint).build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Run callback on EDT to update UI safely
                javax.swing.SwingUtilities.invokeLater(() -> 
                    callback.accept("Welcome to the DropTracker!"));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
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
            endpoint = "https://api.droptracker.io/latest_news";
        } else {
            endpoint = "https://droptracker-io.github.io/content/news.txt";
        }

        Request request = new Request.Builder().url(endpoint).build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Run callback on EDT to update UI safely
                javax.swing.SwingUtilities.invokeLater(() -> 
                    callback.accept("Error fetching latest update: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
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

    public interface PanelDataLoadedCallback {
        void onDataLoaded(Map<String, Object> data);
    }

    public static String formatNumber(double number) {
        if (number == 0) {
            return "0";
        }
        String[] units = new String[]{"", "K", "M", "B", "T"};
        int unit = (int) Math.floor((Math.log10(number) / 3));

        if (unit >= units.length) unit = units.length - 1;

        double num = number / Math.pow(1000, unit);
        DecimalFormat df = new DecimalFormat("#.#");
        String formattedNum = df.format(num);
        return formattedNum + units[unit];
    }
}
