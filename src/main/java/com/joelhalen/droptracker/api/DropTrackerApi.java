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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.inject.Inject;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class DropTrackerApi {

    private final OkHttpClient httpClient;
    private final DropTrackerPlugin plugin;
    private final DropTrackerPluginConfig config;
    private String username;
    private String apiKey;
    public Long monthlyTotalPlayer;
    public String motd = "";
    private Map<String, String> serverIdToWebhookUrlMap;
    private Map<String, Integer> serverMinimumLootVarMap;
    private Map<String, Long> clanServerDiscordIDMap;
    private Map<String, String> serverIdToClanNameMap;
    private Map<String, Boolean> serverIdToConfirmedOnlyMap;
    public Map<String, Integer> clanWiseOldManGroupIDMap;
    private Boolean hasSentMOTD = false;
    public Map<String, Boolean> clanEventActiveMap;
    public Long monthlyTotalServer;
    public Map<String, Object> currentLootMap = new HashMap<>();
    private String messageString;
    private volatile String teamName;
    private volatile JSONObject currentTask;
    private CompletableFuture<Void> initializer;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Inject
    public DropTrackerApi(OkHttpClient httpClient, DropTrackerPlugin plugin, DropTrackerPluginConfig config) {
        this.httpClient = httpClient;
        this.plugin = plugin;
        this.config = config;
        initializer = initializeServerIdToWebhookUrlMap();
    }

    public static String formatNumber(double number) {
        char[] suffix = {' ', 'K', 'M', 'B', 'T'};
        int idx = 0;

        while (number >= 1000 && idx < suffix.length - 1) {
            number /= 1000.0;
            idx++;
        }

        return String.format("%.1f%c", number, suffix[idx]);
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



    public CompletableFuture<Void> initializeServerIdToWebhookUrlMap() {
        return CompletableFuture.runAsync(() -> {
            serverIdToClanNameMap = new HashMap<>();
            serverMinimumLootVarMap = new HashMap<>();
            serverIdToConfirmedOnlyMap = new HashMap<>();
            clanServerDiscordIDMap = new HashMap<>();
            clanWiseOldManGroupIDMap = new HashMap<>();
            clanEventActiveMap = new HashMap<>();
            Request request = new Request.Builder()
                    .url("http://api.droptracker.io/api/config")
                    .build();
            try {
                Response response = httpClient.newCall(request).execute();
                String jsonData = response.body().string();
                JSONObject jsonResponse = new JSONObject(jsonData);
                if ("OK".equals(jsonResponse.getString("status"))) {
                    JSONArray jsonArray = jsonResponse.getJSONArray("server_settings");

                    for (int i = 0; i < jsonArray.length(); i++) {
                        JSONObject jsonObject = jsonArray.getJSONObject(i);
                        if (jsonObject.has("motd")) {
                            this.motd = jsonObject.getString("motd");
                        }
                        serverIdToClanNameMap.put(
                                String.valueOf(jsonObject.getInt("serverId")),
                                jsonObject.getString("serverName")
                        );
                        serverMinimumLootVarMap.put(
                                String.valueOf(jsonObject.getInt("serverId")),
                                jsonObject.getInt("minimumLoot")
                        );
                        serverIdToConfirmedOnlyMap.put(
                                String.valueOf(jsonObject.getInt("serverId")),
                                jsonObject.getInt("confOnly") != 0
                        );
                        clanServerDiscordIDMap.put(
                                String.valueOf(jsonObject.getInt("serverId")),
                                jsonObject.getLong("discordServerId")
                        );
                        clanWiseOldManGroupIDMap.put(
                                String.valueOf(jsonObject.getInt("serverId")),
                                jsonObject.getInt("womGroup")
                        );
                        clanEventActiveMap.put(
                                String.valueOf(jsonObject.getInt("serverId")),
                                jsonObject.getInt("eventActive") != 0
                        );
                    }
                    if(hasSentMOTD == false && serverIdToClanNameMap.containsKey(config.serverId())) {
                        ChatMessageBuilder messageString = new ChatMessageBuilder();
                        messageString.append(ChatColorType.NORMAL).append("[").append(ChatColorType.HIGHLIGHT)
                                .append(serverIdToClanNameMap.get(config.serverId()))
                                .append(ChatColorType.NORMAL)
                                .append("] ")
                                .append(motd);
                        sendChatMessage(new ChatMessage(), messageString);
                    }
                } else{
                }

            } catch(JSONException e){
                e.printStackTrace();
            } catch(IOException e){
                e.printStackTrace();
            }
        });
    }
    public void sendXP(exp expData) {
        initializer.thenRun(() -> {
            if (expData == null || plugin == null || config == null || this.clanServerDiscordIDMap == null) {
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
            RequestBody body = RequestBody.create(JSON, json.toString());
            Request request = new Request.Builder()
                    .url("http://api.droptracker.io/api/xp")
                    .post(body)
                    .addHeader("Authorization", "Bearer " + expData.getApiKey())
                    .build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    e.printStackTrace();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (response.isSuccessful()) {
                        } else {
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        });
    }
    public String getTeamName() {
        return teamName;
    }
    public Long getMonthlyTotalPlayer() {
            return this.monthlyTotalPlayer;
    }
    public Long getMonthlyTotalServer() {
        return this.monthlyTotalServer;
    }
    public JSONObject getCurrentTask() {
        return this.currentTask;
    }
    public JSONObject fetchLootStatistics(String playerName, String serverId, String authKey) throws IOException, ExecutionException, InterruptedException {
        initializer.get();
        HttpUrl url = new HttpUrl.Builder()
                .scheme("http")
                .host("api.droptracker.io")
                .addPathSegments("api/loot/total")
                .addQueryParameter("player_name", playerName)
                .addQueryParameter("server_id", serverId)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer " + authKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                JSONObject jsonResponse = new JSONObject(responseBody);
                this.monthlyTotalPlayer = jsonResponse.optLong("monthly_total_player", 0);
                this.monthlyTotalServer = jsonResponse.optLong("overall_month_server", 0);
                return jsonResponse;
            } else {
                return null;
            }
        }
    }
    public void fetchCurrentTask(String playerName, String authKey) throws IOException {

        JSONObject json = new JSONObject();
        json.put("currentname", playerName);
        json.put("authKey", authKey);
        json.put("serverid", clanServerDiscordIDMap.get(config.serverId()));


        RequestBody body = RequestBody.create(MediaType.get("application/json; charset=utf-8"), json.toString());

        Request request = new Request.Builder()
                .url("http://www.droptracker.io/api/events/get_task")
                .post(body)
                .addHeader("Authorization", "Bearer " + authKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                JSONObject jsonResponse = new JSONObject(responseBody);
                String status = jsonResponse.optString("status", "");
                String message = jsonResponse.optString("message", "");
                JSONObject task = jsonResponse.optJSONObject("task");

                if ("OK".equals(status)) {
                    System.out.println("Raw output: " + responseBody);
                    this.teamName = jsonResponse.optString("teamname", "");
                    System.out.println("Team name set: " + teamName);
                    this.currentTask = new JSONObject(responseBody);

//                    if (task != null) {
//                        System.out.println("Task object: " + task.toString());
//
//                        // Initialize and populate currentTask
//                        this.currentTask = new JSONObject();
//                        this.currentTask.put("task", task.optString("task"));
//                        this.currentTask.put("per", task.optBoolean("per"));
//                        this.currentTask.put("quantity", task.optInt("current_progress"));
//                        this.currentTask.put("required_amount", task.optInt("required_quantity"));
//
//                        System.out.println("Debug fetchCurrentTask: TeamName - " + this.teamName);
//                        System.out.println("Debug fetchCurrentTask: CurrentTask - " + this.currentTask.toString());
//                    }
                }
            }
        }
    }

    public void sendDropToApi(String playerName, String npcName, int npcLevel, int itemId, String itemName,
                               String memberList, int quantity, int value, int nonMembers, String authKey, String imageUrl)
            throws IOException {

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

        RequestBody body = RequestBody.create(MediaType.get("application/json; charset=utf-8"), json.toString());

        Request request = new Request.Builder()
                .url("http://api.droptracker.io/api/drops")
                .post(body)
                .addHeader("Authorization", "Bearer " + authKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String responseBody = response.body().string();
                JSONObject jsonResponse = new JSONObject(responseBody);

                String status = jsonResponse.optString("status", "");
                String message = jsonResponse.optString("message", "");
                String newTotal = jsonResponse.optString("new_total","");
                double newTotalDouble = Double.parseDouble(newTotal);
                String formattedTotal = formatNumber(newTotalDouble);
                fetchCurrentTask(playerName, String.valueOf(plugin.getClanDiscordServerID(config.serverId())));
                if (currentTask != null) {
                    System.out.println("Current task for team: " + currentTask.optString("task", ""));
                    String niceNameTask = currentTask.optString("task", "");
                    System.out.println(niceNameTask);
                    System.out.println(itemName);
                    if(itemName.equals(niceNameTask)) {
                        System.out.println("Player has completed their task (or part) of it!!!");
                    }
                } else {
                    System.out.println("[API] Current task for team is null.");

                }
                if ("OK".equals(status)) {
                    if ("Success".equals(message)) {
                        if (value < plugin.getServerMinimumLoot(config.serverId())) {
                            return;
                        }
                        ChatMessageBuilder messageString = new ChatMessageBuilder();
                        messageString.append(ChatColorType.NORMAL).append("[").append(ChatColorType.HIGHLIGHT)
                                .append("DropTracker")
                                .append(ChatColorType.NORMAL)
                                .append("] ")
                                .append(ChatColorType.NORMAL)
                                .append(" Drop submitted. Your new total: ")
                                .append(ChatColorType.HIGHLIGHT)
                                .append(formattedTotal);
                        sendChatMessage(new ChatMessage(), messageString);
                    }
                }
            }
        }
    }
    /** GETTER METHODS ***/
    public Map<String, String> getServerIdToClanNameMap() {
        return serverIdToClanNameMap;
    }

    public Map<String, Integer> getServerMinimumLootVarMap() {
        return serverMinimumLootVarMap;
    }
    public Map<String, Boolean> getServerIdToConfirmedOnlyMap() {
        return serverIdToConfirmedOnlyMap;
    }

    public Map<String, Long> getClanServerDiscordIDMap() {
        return clanServerDiscordIDMap;
    }

    public Map<String, Integer> getClanWiseOldManGroupIDMap() {
        return clanWiseOldManGroupIDMap;
    }

    public Map<String, Boolean> getClanEventActiveMap() {
        return clanEventActiveMap;
    }



}