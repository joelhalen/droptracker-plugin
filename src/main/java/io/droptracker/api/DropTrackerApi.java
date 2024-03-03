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
import net.runelite.client.game.ItemStack;
import okhttp3.*;

import javax.inject.Inject;
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

    @Inject
    public DropTrackerApi(DropTrackerConfig config, ChatMessageManager chatMessageManager, Gson gson, OkHttpClient httpClient) {
        super();
        this.config = config;
        this.msgManager = chatMessageManager;
        this.gson = gson;
        this.httpClient = httpClient;
    }

    public String getApiUrl() {
        if (config.useApi()) {
            return "https://www.droptracker.io/";
        }
        else {
            return "http://null.com";
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
    public void prepareDropData(ItemStack items) {

    }
    public CompletableFuture<Void> sendDropData(String playerName, String npcName, int itemId, String itemName, int quantity, int geValue, String authKey, String imageUrl) {
        HttpUrl url = HttpUrl.parse(getApiUrl() + "api/drops/submit");
        String dropType = "normal";
        String serverId = config.serverId();
        String notified_str = "1";
        FormBody.Builder formBuilder = new FormBody.Builder()
                .add("drop_type", dropType)
                .add("auth_token", authKey)
                .add("item_name", itemName)
                .add("item_id", String.valueOf(itemId))
                .add("player_name", playerName)
                .add("server_id", serverId)
                .add("quantity", String.valueOf(quantity))
                .add("value", String.valueOf(geValue))
                .add("nonmember", "0")
                .add("member_list", "")
                .add("image_url", imageUrl)
                .add("npc_name", npcName)
                .add("webhook", config.webhook())
                .add("sheet", config.sheetID())
                .add("notified", notified_str);
        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(formBuilder.build())
                .build();
        return CompletableFuture.runAsync(() -> {
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
                String responseData = response.body().string();
                JsonParser parser = new JsonParser();
                JsonObject jsonObj = parser.parse(responseData).getAsJsonObject();
                if (jsonObj.has("success") && config.chatMessages()) {
                    String playerTotalLoot = jsonObj.has("totalLootValue") ? jsonObj.get("totalLootValue").getAsString() : "0";
                    ChatMessageBuilder messageResponse = new ChatMessageBuilder();
                    NumberFormat playerLootFormat = NumberFormat.getNumberInstance();
                    String playerLootString = formatNumber(Double.parseDouble(playerTotalLoot));
                    messageResponse.append(ChatColorType.NORMAL).append("[").append(ChatColorType.HIGHLIGHT)
                            .append("DropTracker")
                            .append(ChatColorType.NORMAL)
                            .append("] ")
                            .append("Your drop has been submitted! You now have a total of " + playerLootString);
                    msgManager.queue(QueuedMessage.builder()
                            .type(ChatMessageType.CONSOLE)
                            .runeLiteFormattedMessage(messageResponse.build())
                            .build());
                } else {
                }
            } catch (IOException e) {
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
