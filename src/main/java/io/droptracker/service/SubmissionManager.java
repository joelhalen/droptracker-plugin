package io.droptracker.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;
import static net.runelite.http.api.RuneLiteAPI.GSON;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.droptracker.DebugLogger;
import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.api.UrlManager;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.api.GroupConfig;
import io.droptracker.models.ValidSubmission;
import io.droptracker.util.ChatMessageUtil;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
import net.runelite.api.annotations.Component;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Call;
import okhttp3.Callback;
import okio.Buffer;

@Slf4j
@Singleton
public class SubmissionManager {

    public interface SubmissionUpdateCallback {
        void onSubmissionsUpdated();
    }

    private DropTrackerConfig config;
    private DropTrackerApi api;
    private ChatMessageUtil chatMessageUtil;
    private Gson gson;
    private OkHttpClient okHttpClient;
    @Inject
    private Client client;
    @Inject
    private ClientThread clientThread;
    @Inject
    private UrlManager urlManager;
    @Inject
    private DrawManager drawManager;
    
    // Store a list of submissions that the player has received which qualified for a notification to be sent
    private List<ValidSubmission> validSubmissions = new ArrayList<>();
    
    // Callback for UI updates
    private SubmissionUpdateCallback updateCallback;

    private final ExecutorService executor = new ThreadPoolExecutor(
            2, // core pool size
            10, // maximum pool size
            60L, TimeUnit.SECONDS, // keep alive time
            new LinkedBlockingQueue<>(),
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r);
                    t.setUncaughtExceptionHandler((thread, ex) -> {
                        log.error("Uncaught exception in executor thread", ex);
                        DebugLogger.logSubmission("Executor thread died: " + ex.getMessage());
                    });
                    return t;
                }
            });
            
    @Inject
    public SubmissionManager(DropTrackerConfig config, DropTrackerApi api, ChatMessageUtil chatMessageUtil, Gson gson, OkHttpClient okHttpClient, Client client, ClientThread clientThread, UrlManager urlManager, DrawManager drawManager) {
        this.config = config;
        this.api = api;
        this.chatMessageUtil = chatMessageUtil;
        this.gson = gson;
        this.okHttpClient = okHttpClient;
        this.client = client;
        this.clientThread = clientThread;
        this.urlManager = urlManager;
        this.drawManager = drawManager;
        System.out.println("SubmissionManager instance created: " + this.hashCode());
    }

    /**
     * Sets the callback for UI updates when submissions change
     * @param callback The callback to notify when submissions are updated
     */
    public void setUpdateCallback(SubmissionUpdateCallback callback) {
        System.out.println("setUpdateCallback called with: " + (callback != null ? "non-null callback" : "null callback") + " on instance: " + this.hashCode());
        if (callback == null) {
            System.out.println("Stack trace for null callback:");
            Thread.dumpStack();
        }
        this.updateCallback = callback;
    }

    /**
     * Notifies the UI callback that submissions have been updated
     */
    private void notifyUpdateCallback() {
        System.out.println("called notifyUpdateCallback on instance: " + this.hashCode());
        if (updateCallback != null) {
            System.out.println("Callback is non-null, invoking callback");
            updateCallback.onSubmissionsUpdated();
        } else {
            System.out.println("updateCallback is null - no UI update will occur on instance: " + this.hashCode());
        }
    }

    public void sendDataToDropTracker(CustomWebhookBody webhook, String type) {
        /*
         * Requires a type ID to be passed
         * "1" = a "Kill Time" or "KC" submission
         * "2" = a "Collection Log" submission
         * "3" = a "Combat Achievement" submission
         */
        System.out.println("Sending data to DropTracker API with type: " + type);
        System.out.println("Webhook: " + webhook.toString());
        Boolean requiredScreenshot = false;
        List<ValidSubmission> validSubmissions = new ArrayList<>();
        if (type.equalsIgnoreCase("1")) {
            // Kc / kill time
            List<CustomWebhookBody.Embed> embeds = webhook.getEmbeds();
            if (!config.pbEmbeds()) {
                return;
            }
            boolean isPb = false;
            for (CustomWebhookBody.Embed embed : embeds) {
                for (CustomWebhookBody.Field field : embed.getFields()) {
                    if (field.getName().equalsIgnoreCase("is_pb")) {
                        if (field.getValue().equalsIgnoreCase("true")) {
                            isPb = true;
                        }
                    }
                }
            }
            if (config.screenshotPBs() && isPb) {
                requiredScreenshot = true;
            }
            if (isPb) {
                ValidSubmission pbSubmission = null;
                for (GroupConfig groupConfig : api.getGroupConfigs()) {
                    if (groupConfig.isSendPbs() == true) {
                        if (groupConfig.isOnlyScreenshots() == true) {
                            if (requiredScreenshot == false) {
                                continue; // Skip this group if screenshots required but not happening
                            }
                        }
                        
                        // Create or find existing submission for this webhook
                        if (pbSubmission == null) {
                            pbSubmission = new ValidSubmission(webhook, groupConfig.getGroupId(), "pb");
                            addSubmissionToMemory(pbSubmission);
                        } else {
                            pbSubmission.addGroupId(groupConfig.getGroupId());
                        }
                    }
                }
            }
        } else if (type.equalsIgnoreCase("2")) {
            if (!config.clogEmbeds()) {
                return;
            }
            if (config.screenshotNewClogs()) {
                requiredScreenshot = true;
            }
            // Create ValidSubmission for collection log entries
            ValidSubmission clogSubmission = null;
            for (GroupConfig groupConfig : api.getGroupConfigs()) {
                if (groupConfig.isSendClogs() == true) {
                    if (groupConfig.isOnlyScreenshots() == true) {
                        if (requiredScreenshot == false) {
                            continue; // Skip this group if screenshots required but not happening
                        }
                    }
                    
                    // Create or find existing submission for this webhook
                    if (clogSubmission == null) {
                        clogSubmission = new ValidSubmission(webhook, groupConfig.getGroupId(), "clog");
                        addSubmissionToMemory(clogSubmission);
                    } else {
                        clogSubmission.addGroupId(groupConfig.getGroupId());
                    }
                }
            }
        } else if (type.equalsIgnoreCase("3")) { // combat achievements
            if (!config.caEmbeds()) {
                return;
            }
            if (config.screenshotCAs()) {
                requiredScreenshot = true;
            }
            // Create ValidSubmission for combat achievements
            ValidSubmission caSubmission = null;
            for (GroupConfig groupConfig : api.getGroupConfigs()) {
                if (groupConfig.isSendCAs() == true) {
                    if (groupConfig.isOnlyScreenshots() == true) {
                        if (requiredScreenshot == false) {
                            continue; // Skip this group if screenshots required but not happening
                        }
                    }
                    
                    // Create or find existing submission for this webhook
                    if (caSubmission == null) {
                        caSubmission = new ValidSubmission(webhook, groupConfig.getGroupId(), "ca");
                        addSubmissionToMemory(caSubmission);
                    } else {
                        caSubmission.addGroupId(groupConfig.getGroupId());
                    }
                }
            }
        }

        // UI notification is handled by addSubmissionToMemory() when submissions are actually added

        if (requiredScreenshot) {
            boolean shouldHideDm = config.hideDMs();
            captureScreenshotWithPrivacy(webhook, shouldHideDm);
        } else {
            sendDataToDropTracker(webhook, (byte[]) null);
        }
    }

    public void sendDataToDropTracker(CustomWebhookBody customWebhookBody, int totalValue) {
        // Handles sending drops exclusively - for individual items use sendDataToDropTracker(webhook, totalValue, singleValue)
        sendDataToDropTracker(customWebhookBody, totalValue, totalValue);
    }
    

    public void sendDataToDropTracker(CustomWebhookBody customWebhookBody, int totalValue, int singleValue) {
        // Handles sending drops exclusively
        if (!config.lootEmbeds()) {
            return;
        }
        
        boolean requiredScreenshot = false;
        if (config.screenshotDrops() && totalValue > config.screenshotValue()) {
            requiredScreenshot = true;
        }
        
        // Create ValidSubmission for drops
        ValidSubmission dropSubmission = new ValidSubmission(customWebhookBody, "2", "drop");
        addSubmissionToMemory(dropSubmission);
        return;
        /* Temporarily returning here for testing purposes -- will not send to server */
        // for (GroupConfig groupConfig : api.getGroupConfigs()) {
        //     if (groupConfig.isSendDrops() == true && totalValue >= groupConfig.getMinimumDropValue()) {
        //         // Check if group allows stacked items
        //         if (!groupConfig.isSendStackedItems() && totalValue > singleValue) {
        //             continue; // Skip this group if items were stacked but group disabled that
        //         }
                
        //         if (groupConfig.isOnlyScreenshots() == true) {
        //             if (requiredScreenshot == false) {
        //                 continue; // Skip this group if screenshots required but not happening
        //             }
        //         }
                
        //         // Create or find existing submission for this webhook
        //         if (dropSubmission == null) {
        //             dropSubmission = new ValidSubmission(customWebhookBody, groupConfig.getGroupId(), "drop");
        //             addSubmissionToMemory(dropSubmission);
        //         } else {
        //             dropSubmission.addGroupId(groupConfig.getGroupId());
        //         }
        //     }
        // }
        
        // Notify UI if submissions were added
        // if (dropSubmission != null) {
        //     notifyUpdateCallback();
        // }

        // if (requiredScreenshot) {
        //     boolean shouldHideDm = config.hideDMs();
        //     captureScreenshotWithPrivacy(customWebhookBody, shouldHideDm);
        // } else {
        //     sendDataToDropTracker(customWebhookBody, (byte[]) null);
        // }
    }

    private void sendDataToDropTracker(CustomWebhookBody customWebhookBody, byte[] screenshot) {
        if (isFakeWorld()) {
            return;
        }
        String logText = String.valueOf(gson.toJson(customWebhookBody));
        DebugLogger.logSubmission("Submission with API " + (config.useApi() ? "enabled" : "disabled")
                + " and screenshot " + (screenshot != null ? "true" : "false") + " -- raw json: " + logText);
        MultipartBody.Builder requestBodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", GSON.toJson(customWebhookBody));

        if (screenshot != null) {
            requestBodyBuilder.addFormDataPart("file", "image.jpeg",
                    RequestBody.create(MediaType.parse("image/jpeg"), screenshot));
        }

        MultipartBody requestBody = requestBodyBuilder.build();
        for (MultipartBody.Part part : requestBody.parts()) {
            Headers headers = part.headers();
            if (headers != null) {
                for (int i = 0; i < headers.size(); i++) {
                }
            }

            // Try to read the body content
            RequestBody body = part.body();
            if (body != null) {
                // Safely check content type
                MediaType contentType = body.contentType();
                if (contentType != null &&
                        (contentType.toString().contains("text") ||
                                contentType.toString().contains("json"))) {
                    Buffer buffer = new Buffer();
                    try {
                        body.writeTo(buffer);
                    } catch (IOException e) {
                    }
                } else {
                }
            }
        }
        String url;
        if (!config.useApi()) {
            try {
                url = UrlManager.getRandomUrl();
            } catch (Exception e) {
                DebugLogger.logSubmission("Failed to get webhook URL, skipping submission: " + e.getMessage());
                DebugLogger.logSubmission("Webhook submission skipped - no valid URL: " + e.getMessage());
                return; // Exit gracefully
            }
        } else {
            url = api.getApiUrl() + "/webhook";
        }
        HttpUrl u = HttpUrl.parse(url);
        if (u == null || !urlManager.isValidDiscordWebhookUrl(u)) {
            log.debug("Invalid or malformed webhook URL: {}", url);
            return;
        }
        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();
        if (config.useApi()) {
            api.lastCommunicationTime = (int) (System.currentTimeMillis() / 1000); // Update the last communication time
                                                                                   // if the api is being used
        }
        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("Error submitting: ", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (config.useApi()) {
                    // Try to get response body, but don't consume it
                    ResponseBody body = response.peekBody(Long.MAX_VALUE);
                    if (body != null) {
                        String bodyString = body.string();
                        if (!bodyString.isEmpty()) {
                            try {
                                JsonElement jsonElement = new JsonParser().parse(bodyString);
                                if (jsonElement.isJsonObject()) {
                                    JsonObject jsonObject = jsonElement.getAsJsonObject();
                                    if (jsonObject.has("notice")) {
                                        String noticeMessage = jsonObject.get("notice").getAsString();
                                        if (noticeMessage != null && !noticeMessage.isEmpty()) {
                                            chatMessageUtil.sendChatMessage(noticeMessage);
                                        }
                                    }
                                    if (jsonObject.has("rank_update")) {
                                        String updateMessage = jsonObject.get("rank_update").getAsString();
                                        if (updateMessage != null && !updateMessage.isEmpty()) {
                                            chatMessageUtil.sendChatMessage(updateMessage);
                                        }
                                    }
                                }
                            } catch (Exception e) {

                            }
                        }
                    }
                }

                if (response.isSuccessful()) {
                } else if (response.code() == 429) {
                    sendDataToDropTracker(customWebhookBody, screenshot);
                } else if (response.code() == 400) {

                    response.close();
                    return;

                } else if (response.code() == 404) {
                    // On the first 404 error, we'll populate the list with new ones.
                    executor.submit(() -> {
                        try {
                            urlManager.fetchNewList();
                        } catch (Exception e) {
                            log.error("Failed to fetch new webhook list", e);
                        }
                    });
                    sendDataToDropTracker(customWebhookBody, screenshot);

                } else {
                    sendDataToDropTracker(customWebhookBody, screenshot);
                }
                response.close();
            }
        });

    }


    public void addSubmissionToMemory(ValidSubmission validSubmission) {
        System.out.println("Adding submission to memory: " + validSubmission.getUuid() + " on instance: " + this.hashCode());
        if (validSubmissions.size() > 20) {
            // Remove oldest submissions once the list starts to exceed 20
            validSubmissions.remove(0);
        }
        validSubmissions.add(validSubmission);
        System.out.println("Submission added to memory: " + validSubmission.getUuid() + " on instance: " + this.hashCode());
        notifyUpdateCallback();
    }

    public List<ValidSubmission> getValidSubmissions() {
        return validSubmissions;
    }

    /**
     * Retry a failed submission using the stored webhook data
     * @param validSubmission The submission to retry
     */
    public void retrySubmission(ValidSubmission validSubmission) {
        if (validSubmission == null || validSubmission.getOriginalWebhook() == null) {
            log.warn("Cannot retry submission: missing webhook data");
            return;
        }
        
        // Update status to indicate retry attempt
        validSubmission.setStatus("retrying");
        
        // Send the original webhook data again
        sendDataToDropTracker(validSubmission.getOriginalWebhook(), (byte[]) null);
        
        // Log the retry attempt
        DebugLogger.logSubmission("Retrying submission with UUID: " + validSubmission.getUuid());
    }

    /**
     * Remove a submission from the in-memory list (e.g., when user dismisses it)
     * @param validSubmission The submission to remove
     */
    public void removeSubmission(ValidSubmission validSubmission) {
        validSubmissions.remove(validSubmission);
        notifyUpdateCallback();
    }

    /**
     * Clear all stored submissions
     */
    public void clearAllSubmissions() {
        validSubmissions.clear();
        notifyUpdateCallback();
    }

    /**
     * Update the status of a submission based on API response
     * @param uuid The UUID of the submission to update
     * @param status The new status ("success", "failed", "processed", etc.)
     * @param response The response message from the API
     */
    public void updateSubmissionStatus(String uuid, String status, String response) {
        if (uuid == null) return;
        
        for (ValidSubmission submission : validSubmissions) {
            if (uuid.equals(submission.getUuid())) {
                submission.setStatus(status);
                if ("success".equals(status) || "processed".equals(status)) {
                    submission.setTimeProcessedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));
                }
                
                // Add response to retry responses array if it's a retry
                if (response != null && !"pending".equals(status)) {
                    String[] currentResponses = submission.getRetryResponses();
                    String[] newResponses = Arrays.copyOf(currentResponses, currentResponses.length + 1);
                    newResponses[currentResponses.length] = response;
                    submission.setRetryResponses(newResponses);
                }
                notifyUpdateCallback();
                break;
            }
        }
    }


    private boolean isFakeWorld() {
        var worldType = client.getWorldType();
        return worldType.contains(WorldType.BETA_WORLD)
                || worldType.contains(WorldType.DEADMAN)
                || worldType.contains(WorldType.FRESH_START_WORLD)
                || worldType.contains(WorldType.LAST_MAN_STANDING)
                || worldType.contains(WorldType.NOSAVE_MODE)
                || worldType.contains(WorldType.PVP_ARENA)
                || worldType.contains(WorldType.QUEST_SPEEDRUNNING)
                || worldType.contains(WorldType.SEASONAL)
                || worldType.contains(WorldType.TOURNAMENT_WORLD);
    }

    public String sanitize(String str) {
        if (str == null || str.isEmpty())
            return "";
        return Text.removeTags(str.replace("<br>", "\n")).replace('\u00A0', ' ').trim();
    }

    public static void modWidget(boolean shouldHide, Client client, ClientThread clientThread, @Component int info) {
        clientThread.invoke(() -> {
            Widget widget = client.getWidget(info);
            if (widget != null) {
                widget.setHidden(shouldHide);
            }
        });
    }

    private static byte[] convertImageToByteArray(BufferedImage bufferedImage) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ImageIO.write(bufferedImage, "jpeg", byteArrayOutputStream);
        return byteArrayOutputStream.toByteArray();
    }

    private void captureScreenshotWithPrivacy(CustomWebhookBody webhook, boolean hideDMs) {
        // First hide DMs if configured
        modWidget(hideDMs, client, clientThread, UrlManager.PRIVATE_CHAT_WIDGET);

        drawManager.requestNextFrameListener(image -> {
            BufferedImage bufferedImage = (BufferedImage) image;

            // Restore DM visibility immediately after capturing
            modWidget(false, client, clientThread, UrlManager.PRIVATE_CHAT_WIDGET);

            byte[] imageBytes = null;
            try {
                imageBytes = convertImageToByteArray(bufferedImage);
                if (imageBytes.length > 5 * 1024 * 1024) {
                    // perform compression here
                }
            } catch (IOException e) {
                log.error("Error converting image to byte array", e);
            }
            sendDataToDropTracker(webhook, imageBytes);
        });
    }

}
