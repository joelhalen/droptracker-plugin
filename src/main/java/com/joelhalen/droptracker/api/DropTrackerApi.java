package com.joelhalen.droptracker.api;

import com.joelhalen.droptracker.DropTrackerPlugin;
import com.joelhalen.droptracker.DropTrackerPluginConfig;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.QueuedMessage;
import okhttp3.*;
import com.google.gson.Gson;
import org.json.JSONObject;

import javax.inject.Inject;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class DropTrackerApi {

    private final OkHttpClient httpClient;
    private final DropTrackerPlugin plugin;
    private final DropTrackerPluginConfig config;
    private String username;
    private String apiKey;
    private String messageString;
    private String teamName = "";
    private JSONObject currentTask;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Inject
    public DropTrackerApi(OkHttpClient httpClient, DropTrackerPlugin plugin, DropTrackerPluginConfig config) {
        this.httpClient = httpClient;
        this.plugin = plugin;
        this.config = config;
    }


    public void setUsername(String username) {
        this.username = username;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
    public void sendChatMessage(ChatMessage message, ChatMessageBuilder messageString) {
        plugin.chatMessageManager.queue(QueuedMessage.builder()
                .type(ChatMessageType.CONSOLE)
                .runeLiteFormattedMessage(messageString.build())
                .build());
    }
    private void makeRequest(Request request) {
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Handle failure
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                // Handle success
                response.close();
            }
        });
    }
    public void sendXP(exp expData) {
        if(expData == null || plugin == null || config == null) {
            System.out.println("Null object: " + plugin + config + expData);
            return;
        }
        JSONObject json = new JSONObject();
        json.put("skill", expData.getSkill());
        json.put("amount", expData.getAmount());
        json.put("currentTotal", expData.getCurrentTotal());
        json.put("apiKey", expData.getApiKey());
        json.put("accountHash", expData.getAccountHash());
        json.put("serverid", plugin.getClanDiscordServerID(config.serverId()));
        json.put("currentname", plugin.getLocalPlayerName());

        // Create request body
        RequestBody body = RequestBody.create(JSON, json.toString());

        // Build the request
        Request request = new Request.Builder()
                .url("http://api.droptracker.io/api/xp")
                .post(body)
                .addHeader("Authorization", "Bearer " + expData.getApiKey())
                .build();

        // Make the request
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
            try {
                    if (response.isSuccessful()) {
                        // Handle successful response
                    } else {
                        // Handle error
                    }
            } finally {
                response.close();
            }
            }
        });
    }
    public String getTeamName() {
        return teamName;
    }

    public JSONObject getCurrentTask() {
        return currentTask;
    }
    public JSONObject fetchLootStatistics(String playerName, String serverId, String authKey) throws IOException {
        // Create a new OkHttpClient (you can reuse the existing one if you have it)

        // Build the URL including query parameters
        HttpUrl url = new HttpUrl.Builder()
                .scheme("http")
                .host("api.droptracker.io")
                .addPathSegments("api/loot/total")
                .addQueryParameter("player_name", playerName)
                .addQueryParameter("server_id", serverId)
                .build();

        // Create a new Request
        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer " + authKey)
                .build();

        // Make the request and handle the response
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                JSONObject jsonResponse = new JSONObject(responseBody);
                return jsonResponse;  // You may want to extract and return specific fields
            } else {
                System.out.println("Received an unsuccessful HTTP response.");
                System.out.println("HTTP Status Code: " + response.code());
                System.out.println("HTTP Status Message: " + response.message());
                if (response.body() != null) {
                    System.out.println("HTTP Response Body: " + response.body().string());
                }
                return null;  // Or throw an exception
            }
        }
    }

    public void fetchCurrentTask(String playerName, String authKey) throws IOException {

        // Create JSON object to send player's name and authentication key
        JSONObject json = new JSONObject();
        json.put("currentname", playerName);
        json.put("authKey", authKey);
        json.put("serverid", plugin.getClanDiscordServerID(config.serverId()));

        // Create a RequestBody containing your JSON data
        RequestBody body = RequestBody.create(MediaType.get("application/json; charset=utf-8"), json.toString());

        // Create a new Request
        Request request = new Request.Builder()
                .url("http://www.droptracker.io/api/events/get_task")
                .post(body)
                .addHeader("Authorization", "Bearer " + authKey)
                .build();

        // Make the request
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                JSONObject jsonResponse = new JSONObject(responseBody);
                System.out.println(responseBody);
                String status = jsonResponse.optString("status", "");
                String message = jsonResponse.optString("message", "");
                String teamName = jsonResponse.optString("teamname", "");
                JSONObject task = jsonResponse.optJSONObject("task");

                if ("OK".equals(status)) {
                    // Save the team name and task to instance variables
                    this.teamName = jsonResponse.optString("teamname", "");
                    if (task != null) {
                        this.currentTask = task;
                    }
                    System.out.println("DropTrackerApi - Updated teamName: " + this.teamName);
                    System.out.println("DropTrackerApi - Updated currentTask: " + this.currentTask.optString("quantity", "") + this.currentTask.optString("task", ""));
                } else {
                    System.out.println("Received an OK response but the status is not OK.");
                }
            } else {
                System.out.println("Received an unsuccessful HTTP response.");
                System.out.println("HTTP Status Code: " + response.code());
                System.out.println("HTTP Status Message: " + response.message());
                if (response.body() != null) {
                    System.out.println("HTTP Response Body: " + response.body().string());
                }
            }
        }
    }
    public void sendDropToApi(String playerName, String npcName, int npcLevel, int itemId, String itemName,
                               String memberList, int quantity, int value, int nonMembers, String authKey, String imageUrl)
            throws IOException {


        // Create JSON object to hold your data
        JSONObject json = new JSONObject();
        json.put("playerName", playerName);
        json.put("npcName", npcName);
        json.put("npcLevel", npcLevel);
        json.put("itemId", itemId);
        json.put("itemName", itemName);
        json.put("memberList", memberList);
        json.put("quantity", quantity);
        json.put("value", value);
        json.put("nonMembers", nonMembers);
        json.put("apiKey", authKey);
        json.put("imageUrl", imageUrl);
        json.put("serverid", plugin.getClanDiscordServerID(config.serverId()));
        json.put("currentname", plugin.getLocalPlayerName());

        // Create a RequestBody containing your JSON data
        RequestBody body = RequestBody.create(MediaType.get("application/json; charset=utf-8"), json.toString());

        // Create a new Request
        Request request = new Request.Builder()
                .url("http://api.droptracker.io/api/drops")
                .post(body)
                .addHeader("Authorization", "Bearer " + authKey)
                .build();

        // Make the request
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                JSONObject jsonResponse = new JSONObject(responseBody);

                String status = jsonResponse.optString("status", "");
                String message = jsonResponse.optString("message", "");

                if ("OK".equals(status)) {
                    if ("Success".equals(message)) {
                        if (value < plugin.getServerMinimumLoot(config.serverId())) {
                            return;
                        }
                        ChatMessageBuilder messageString = new ChatMessageBuilder();
                        messageString.append(ChatColorType.NORMAL).append("[").append(ChatColorType.HIGHLIGHT)
                                .append("DropTracker")
                                .append(ChatColorType.NORMAL)
                                .append("]")
                                .append(ChatColorType.HIGHLIGHT)
                                .append(itemName)
                                .append(ChatColorType.NORMAL)
                                .append(" has successfully been submitted to the database.");
                        sendChatMessage(new ChatMessage(), messageString);
                    } else {
                        System.out.println("Drop was submitted, value didn't qualify for notification.");
                    }
                } else {
                    System.out.println("Received an OK response but the status is not OK.");
                }
            } else {
                System.out.println("Received an unsuccessful HTTP response.");
            }
        }
    }

    // Add more methods to interact with other API endpoints
}