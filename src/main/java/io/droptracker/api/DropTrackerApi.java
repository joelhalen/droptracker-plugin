package io.droptracker.api;

import com.google.gson.Gson;
import io.droptracker.DropTrackerConfig;
import io.droptracker.models.api.GroupSearchResult;
import io.droptracker.models.api.PlayerSearchResult;
import io.droptracker.models.api.TopGroupResult;
import okhttp3.*;

import javax.inject.Inject;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class DropTrackerApi {
    private final DropTrackerConfig config;
    @Inject
    private Gson gson;
    @Inject
    private OkHttpClient httpClient;

    public int lastCommunicationTime = (int) (System.currentTimeMillis() / 1000);
    
    @Inject
    public DropTrackerApi(DropTrackerConfig config, Gson gson, OkHttpClient httpClient) {
            this.config = config;
            this.gson = gson;
            this.httpClient = httpClient;
        }

    @SuppressWarnings("null")
    public TopGroupResult getTopGroups() throws IOException {
        String apiUrl = getApiUrl();
        try{
        HttpUrl url = HttpUrl.parse(apiUrl + "/top_groups");
        Request request = new Request.Builder().url(url).build();
        Response response = httpClient.newCall(request).execute();
        lastCommunicationTime = (int) (System.currentTimeMillis() / 1000);
        if (!response.isSuccessful()) {
            throw new IOException("API request failed with status: " + response.code());
        }
        if (response.body() == null) {
            throw new IOException("Empty response body");
        } else {
            String responseData = response.body().string();
            return TopGroupResult.fromJson(responseData);
        }
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Sends a request to the API to search for a group and returns the GroupSearchResult.
     */
    @SuppressWarnings("null")
    public GroupSearchResult searchGroup(String groupName) throws IOException {
        // Check if the API is enabled
        if (!config.useApi()) {
            throw new IllegalStateException("API is not enabled in the plugin config");
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
                Map<String, Object> responseMap = gson.fromJson(responseData, Map.class);
                return GroupSearchResult.fromJsonMap(responseMap);
            }
        }
    }

    public String getCurrentLatency() {
        try {
        String url = getApiUrl() + "/ping";
        long startTime = System.currentTimeMillis();
        try (Response response = httpClient.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful()) {
                return "? ms";
            }
        } catch (IOException e) {
            e.printStackTrace();
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
    public PlayerSearchResult lookupPlayerNew(String playerName) throws IOException {
        // Check if the API is enabled
        if (!config.useApi()) {
            throw new IllegalStateException("API is not enabled in the plugin config");
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
                Map<String, Object> responseMap = gson.fromJson(responseData, Map.class);
                return PlayerSearchResult.fromJsonMap(responseMap);
            }
        }
    }

    public CompletionStage<Map<String, Object>> lookupPlayer(String playerName) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

        // Check if the API is enabled
        if (!config.useApi()) {
            future.completeExceptionally(new IllegalStateException("API is not enabled in the plugin config"));
            return future;
        }

        String apiUrl = getApiUrl();
        HttpUrl url = HttpUrl.parse(apiUrl + "/player_lookup/" + playerName);

        if (url == null) {
            future.completeExceptionally(new IllegalArgumentException("Invalid URL"));
            return future;
        }

        Request request = new Request.Builder().url(url).build();
        lastCommunicationTime = (int) (System.currentTimeMillis() / 1000);  
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                future.completeExceptionally(e);
            }

            @SuppressWarnings({ "null" })
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    String responseData = responseBody.string();
                    Map<String, Object> responseMap = gson.fromJson(responseData, Map.class);

                    if (!response.isSuccessful()) {
                        // Return the error message as part of the response to be handled
                        future.complete(responseMap);
                        return;
                    }

                    // If response is successful, return the response map
                    future.complete(responseMap);
                } catch (IllegalStateException e) {
                    // If the webserver is down or malfunctioning, this is the likely outcome
                    future.cancel(true);
                }
            }
        });

        return future;
    }

    public String getApiUrl() {
        return config.useApi() ? "https://api.droptracker.io" : "";
    }

    @SuppressWarnings("null")
    public String getLatestUpdateString() {
        String endpoint;
        if (config.useApi()) {
            endpoint = "https://api.droptracker.io/latest_news";
        } else {
            endpoint = "https://droptracker-io.github.io/content/news.txt";
        }

        try {
            Request request = new Request.Builder().url(endpoint).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().string();
                } else {
                    return "Error fetching latest update";
                }
            }
        } catch (IOException e) {
            return "Error fetching latest update";
        }
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
