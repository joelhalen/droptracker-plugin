package io.droptracker.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.droptracker.DropTrackerConfig;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import okhttp3.*;

import javax.inject.Inject;
import javax.swing.*;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class DropTrackerApi {
    private final DropTrackerConfig config;
    @Inject
    public ChatMessageManager msgManager;
    @Inject
    private Client client;
    @Inject
    private Gson gson;
    @Inject
    private OkHttpClient httpClient;
    private PanelDataLoadedCallback dataLoadedCallback;

    @Inject
    public DropTrackerApi(DropTrackerConfig config, ChatMessageManager chatMessageManager, Gson gson, OkHttpClient httpClient, Client client) {
        super();
        this.config = config;
        this.msgManager = chatMessageManager;
        this.gson = gson;
        this.httpClient = httpClient;
        this.client = client;
    }

    public void setDataLoadedCallback(PanelDataLoadedCallback callback) {
        this.dataLoadedCallback = callback;
    }
    public interface PanelDataLoadedCallback {
        void onDataLoaded(Map<String, Object> data);
    }

    public String getApiUrl() {
        if (config.useApi()) {
            return "https://www.droptracker.io/";
        }
        else {
            return "";
        }
    }
    public void sendKillTimeData(String playerName, String npcName, String currentPb, String currentTime) {
        String apiUrl = getApiUrl();
        HttpUrl url = HttpUrl.parse(apiUrl + "/api/kills/pb");

        String serverId = config.serverId();
        String authKey = config.authKey();

        Map<String, Object> data = new HashMap<>();
        data.put("player_name", playerName);
        data.put("npc_name", npcName);
        data.put("current_pb", currentPb);
        data.put("current_time", currentTime);
        data.put("server_id", serverId);
        data.put("auth_token", authKey);

        String json = gson.toJson(data);

        RequestBody body = RequestBody.create(MediaType.get("application/json; charset=utf-8"), json);

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        CompletableFuture.runAsync(() -> {
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected code " + response);
                }
                if (response.body() != null) {
                    response.body().close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
    public void loadPanelData(boolean forced) {
        if (!config.useApi()) {
            if (forced) {
                ChatMessageBuilder messageResponse = new ChatMessageBuilder();
                messageResponse.append(ChatColorType.NORMAL).append("[").append(ChatColorType.HIGHLIGHT)
                        .append("DropTracker")
                        .append(ChatColorType.NORMAL)
                        .append("] ")
                        .append("You do not have the API enabled in your plugin config! Unable to refresh data.");
                msgManager.queue(QueuedMessage.builder()
                        .type(ChatMessageType.CONSOLE)
                        .runeLiteFormattedMessage(messageResponse.build())
                        .build());
            }
            return;
        }

        String apiUrl = getApiUrl();
        HttpUrl url = HttpUrl.parse(apiUrl + "/api/client_data");

        if (url == null) {
            return;
        }

        String serverId = config.serverId();
        String authKey = config.authKey();
        String playerName;
        if (!config.registeredName().equals("")) {
            playerName = config.registeredName();
        } else {
            try {
                if (client.getLocalPlayer() != null) {
                    try {
                        playerName = client.getLocalPlayer().getName();
                    } catch (NullPointerException e) {
                        playerName = "Unknown";
                    }
                } else {
                    playerName = "Unknown";
                }
            } catch (NullPointerException e) {
                playerName = "Unknown";
            }
        }

            Map<String, Object> data = new HashMap<>();
            data.put("player_name", playerName);
            data.put("server_id", serverId);
            data.put("auth_token", authKey);
            String json = gson.toJson(data);

            RequestBody body = RequestBody.create(MediaType.get("application/json; charset=utf-8"), json);

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    e.printStackTrace();
                    SwingUtilities.invokeLater(() -> {
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (ResponseBody responseBody = response.body()) {
                        if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
                        String responseData = responseBody.string();
                        Map<String, Object> responseMap = gson.fromJson(responseData, Map.class);

                        SwingUtilities.invokeLater(() -> {
                            if (dataLoadedCallback != null) {
                                dataLoadedCallback.onDataLoaded(responseMap);
                            }
                        });
                    }
                }
            });
        }
    public CompletableFuture<Void> sendDropData(String playerName, String dropType, String npcName, int itemId, String itemName, int quantity, int geValue, String authKey, String imageUrl) {
            HttpUrl url = HttpUrl.parse(getApiUrl() + "api/drops/submit");
            String serverId = config.serverId();
            String notified_str = "1";
            String formDropType = dropType.equals("player") ? "pvp" : "normal";
            FormBody.Builder formBuilder = new FormBody.Builder()
                    .add("drop_type", formDropType)
                    .add("auth_token", authKey)
                    .add("item_name", itemName)
                    .add("item_id", String.valueOf(itemId))
                    .add("player_name", playerName);
                    if (!config.registeredName().equals("") && (!config.registeredName().equals(client.getLocalPlayer().getName()))) {
                        formBuilder.add("real_name", client.getLocalPlayer().getName());
                    }
                    formBuilder.add("server_id", serverId)
                    .add("quantity", String.valueOf(quantity))
                    .add("value", String.valueOf(geValue))
                    .add("nonmember", "0")
                    .add("member_list", "")
                    .add("image_url", imageUrl)
                    .add("npc_name", npcName)
                    .add("webhook", config.webhook())
                    .add("webhookValue", String.valueOf(config.webhookValue()))
                    .add("sheet", config.sheetID())
                    .add("notified", notified_str);
            Request request = new Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .post(formBuilder.build())
                    .build();
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    e.printStackTrace();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                    }
                    response.close();
                }
            });
        });
        return future;
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
