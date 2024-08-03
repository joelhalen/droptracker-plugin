package io.droptracker.api;

import com.google.gson.Gson;
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
import java.util.HashMap;
import java.util.Map;

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
            return "http://www.droptracker.io:21220/";
        }
        else {
            return "";
        }
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
        String userToken = config.token();
        String playerName;
        if (client.getLocalPlayer() != null) {
            try {
                playerName = client.getLocalPlayer().getName();
            } catch (NullPointerException e) {
                playerName = "Unknown";
            }
        } else {
            playerName = "Unknown";
        }

            Map<String, Object> data = new HashMap<>();
            data.put("player_name", playerName);
            data.put("server_id", serverId);
            data.put("auth_token", userToken);
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
