package io.droptracker.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.gson.Gson;
import static net.runelite.http.api.RuneLiteAPI.GSON;
import com.google.gson.annotations.SerializedName;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.api.UrlManager;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.api.GroupConfig;
import io.droptracker.models.submissions.SubmissionType;
import io.droptracker.models.submissions.ValidSubmission;
import io.droptracker.util.ChatMessageUtil;
import lombok.Getter;
import lombok.Setter;
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

    @Getter
    private static class ApiResponse {
        @SerializedName("notice")
        private String notice;

        @SerializedName("rank_update")
        private String rankUpdate;
    }

    public interface SubmissionUpdateCallback {
        void onSubmissionsUpdated();
    }

    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final ChatMessageUtil chatMessageUtil;
    private final Gson gson;
    private final OkHttpClient okHttpClient;
    private final Client client;
    private final ClientThread clientThread;
    private final UrlManager urlManager;
    private final DrawManager drawManager;

    // Store a list of submissions that the player has received which qualified for a notification to be sent
    private List<ValidSubmission> validSubmissions = new ArrayList<>();

    /// Callback for UI updates when submissions change
    @Setter
    private SubmissionUpdateCallback updateCallback;
    @Setter
    private boolean updatesEnabled = true;

    @Inject
    private ScheduledExecutorService executor;

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
    }

    /**
     * Notifies the UI callback that submissions have been updated
     */
    private void notifyUpdateCallback() {
        if (updatesEnabled && updateCallback != null) {
            updateCallback.onSubmissionsUpdated();
        }
    }

    public void sendDataToDropTracker(CustomWebhookBody webhook, SubmissionType type) {
        /* Drops are still handled in a separate method due to the way values are handled */
        /* Here, we send the webhook and submission type, and check against the stored group config values
         * to determine whether the submission "should" create a notification on Discord based on their group settings.
         * If so, we create a ValidSubmission object and add it to the in-memory list, allowing the UI to display the submission,
         * and later updating the status of it with whether or not it properly got sent to the API and had its notifications
         * processed properly for the target group(s).
         */
        Boolean requiredScreenshot = false;
        Boolean shouldHideDm = config.hideDMs();

        if (type == SubmissionType.DROP) {
            // We do not need to do anything for drop submissions as the required processing is done prior to being sent here
        }
        if (type == SubmissionType.KILL_TIME) {
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
                            pbSubmission = new ValidSubmission(webhook, groupConfig.getGroupId(), SubmissionType.KILL_TIME);
                            addSubmissionToMemory(pbSubmission);
                        } else {
                            pbSubmission.addGroupId(groupConfig.getGroupId());
                        }
                    }
                }
            }
        } else if (type == SubmissionType.COLLECTION_LOG) {
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
                        clogSubmission = new ValidSubmission(webhook, groupConfig.getGroupId(), SubmissionType.COLLECTION_LOG);
                        addSubmissionToMemory(clogSubmission);
                    } else {
                        clogSubmission.addGroupId(groupConfig.getGroupId());
                    }
                }
            }
        } else if (type == SubmissionType.COMBAT_ACHIEVEMENT) { // combat achievements
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
                        caSubmission = new ValidSubmission(webhook, groupConfig.getGroupId(), SubmissionType.COMBAT_ACHIEVEMENT);
                        addSubmissionToMemory(caSubmission);
                    } else {
                        caSubmission.addGroupId(groupConfig.getGroupId());
                    }
                }
            }
        } else if (type == SubmissionType.ADVENTURE_LOG) {
            // Nothing extra needs to be done for adventure log data
            requiredScreenshot = false;
        } else if (type == SubmissionType.EXPERIENCE || type == SubmissionType.EXPERIENCE_MILESTONE) {
            /* We don't need to take screenshots for experience or experience milestones */
            requiredScreenshot = false;
        } else if (type == SubmissionType.LEVEL_UP) {
            CustomWebhookBody.Embed embed = webhook.getEmbeds().get(0);
            // Check the skills that leveled up
            for (CustomWebhookBody.Field field : embed.getFields()) {
                String fieldName = field.getName();
                if (fieldName.endsWith("_level") && !fieldName.equals("total_level") && !fieldName.equals("combat_level")) {
                    int newLevel = Integer.parseInt(field.getValue());
                    // Check if this level qualifies for a screenshot
                    if (newLevel >= config.minLevelToScreenshot()) {
                        requiredScreenshot = true;
                        break;
                    }
                }
            }
        } else if (type == SubmissionType.QUEST_COMPLETION) {
            // TODO -- need to add group config values for tracking for ValidSubmission object creation where necessary later
            if (!config.trackQuests()) {
                return;
            }
            if (config.screenshotQuests()) {
                // TODO -- need to add group config values for tracking for ValidSubmission object creation where necessary later
                requiredScreenshot = true;
            }
        } else if (type == SubmissionType.PET) {
            // TODO -- need to add group config values for tracking for ValidSubmission object creation where necessary later
            if (config.screenshotPets()) {
                requiredScreenshot = true;
            }
        }

        // UI notification is handled by addSubmissionToMemory() when submissions are actually added

        if (requiredScreenshot) {
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
        /* Temporarily returning here for testing purposes -- will not send to server */
        ValidSubmission dropSubmission = null;
        for (GroupConfig groupConfig : api.getGroupConfigs()) {
            if (groupConfig.isSendDrops() == true && totalValue >= groupConfig.getMinimumDropValue()) {
                // Check if group allows stacked items
                if (!groupConfig.isSendStackedItems() && totalValue > singleValue) {
                    continue; // Skip this group if items were stacked but group disabled that
                }

                if (groupConfig.isOnlyScreenshots() == true) {
                    if (requiredScreenshot == false) {
                        continue; // Skip this group if screenshots required but not happening
                    }
                }

                // Create or find existing submission for this webhook
                if (dropSubmission == null) {
                    dropSubmission = new ValidSubmission(customWebhookBody, groupConfig.getGroupId(), SubmissionType.DROP);
                    addSubmissionToMemory(dropSubmission);
                } else {
                    dropSubmission.addGroupId(groupConfig.getGroupId());
                }
            }
        }

        // Notify UI if submissions were added
        if (dropSubmission != null) {
            notifyUpdateCallback();
        }

        if (requiredScreenshot) {
            boolean shouldHideDm = config.hideDMs();
            captureScreenshotWithPrivacy(customWebhookBody, shouldHideDm);
        } else {
            sendDataToDropTracker(customWebhookBody, (byte[]) null);
        }
    }

    private void sendDataToDropTracker(CustomWebhookBody customWebhookBody, byte[] screenshot) {
        if (isFakeWorld()) {
            return;
        }
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
                                ApiResponse apiResponse = gson.fromJson(bodyString, ApiResponse.class);
                                if (apiResponse != null) {
                                    String noticeMessage = apiResponse.getNotice();
                                    if (noticeMessage != null && !noticeMessage.isEmpty()) {
                                        chatMessageUtil.sendChatMessage(noticeMessage);
                                    }
                                    String updateMessage = apiResponse.getRankUpdate();
                                    if (updateMessage != null && !updateMessage.isEmpty()) {
                                        chatMessageUtil.sendChatMessage(updateMessage);
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
        if (validSubmissions.size() > 20) {
            // Remove oldest submissions once the list starts to exceed 20
            validSubmissions.remove(0);
        }
        validSubmissions.add(validSubmission);
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
