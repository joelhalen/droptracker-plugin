package io.droptracker.service;

import com.google.gson.Gson;
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
import okhttp3.*;
import okio.Buffer;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static net.runelite.http.api.RuneLiteAPI.GSON;

@Slf4j
@Singleton
public class SubmissionManager {

    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final ChatMessageUtil chatMessageUtil;
    private final Gson gson;
    private final OkHttpClient okHttpClient;
    private final Client client;
    private final ClientThread clientThread;
    private final UrlManager urlManager;
    private final DrawManager drawManager;
    /// Store a list of submissions that the player has received which qualified for a notification to be sent
    @Getter
    private final List<ValidSubmission> validSubmissions = new ArrayList<>();
    /// Callback for UI updates when submissions change
    @Setter
    private SubmissionUpdateCallback updateCallback;
    @Setter
    private boolean updatesEnabled = true;
    @Inject
    private ScheduledExecutorService executor;

    // Variables to store counts and values for the UI
    public int totalSubmissions = 0;
    public int notificationsSent = 0;
    public int failedSubmissions = 0;
    public Long totalValue = 0L;
    
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
        var requiredScreenshot = false;
        var shouldHideDm = config.hideDMs();

        switch (type) {
            case DROP:
                // We do not need to do anything for drop submissions as the required processing is done prior to being sent here
                break;

            case KILL_TIME:
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
                        if (groupConfig.isSendPbs()) {
                            if (groupConfig.isOnlyScreenshots()) {
                                if (!requiredScreenshot) {
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
                break;

            case COLLECTION_LOG:
                if (!config.clogEmbeds()) {
                    return;
                }
                if (config.screenshotNewClogs()) {
                    requiredScreenshot = true;
                }
                // Create ValidSubmission for collection log entries
                ValidSubmission clogSubmission = null;
                for (GroupConfig groupConfig : api.getGroupConfigs()) {
                    if (groupConfig.isSendClogs()) {
                        if (groupConfig.isOnlyScreenshots()) {
                            if (!requiredScreenshot) {
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
                break;

            case COMBAT_ACHIEVEMENT:
                // combat achievements
                if (!config.caEmbeds()) {
                    return;
                }
                if (config.screenshotCAs()) {
                    requiredScreenshot = true;
                }
                // Create ValidSubmission for combat achievements
                ValidSubmission caSubmission = null;
                for (GroupConfig groupConfig : api.getGroupConfigs()) {
                    if (groupConfig.isSendCAs()) {
                        if (groupConfig.isOnlyScreenshots()) {
                            if (!requiredScreenshot) {
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
                break;

            case LEVEL_UP:
                if (!config.levelEmbed()){
                    return;
                }
                if(config.screenshotLevel()){
                    requiredScreenshot = true;
                }
                CustomWebhookBody.Embed embed = webhook.getEmbeds().get(0);
                // Check the skills that leveled up
                for (CustomWebhookBody.Field field : embed.getFields()) {
                    String fieldName = field.getName();
                    if (fieldName.endsWith("_level") && !fieldName.equals("total_level") && !fieldName.equals("combat_level")) {
                        int newLevel = Integer.parseInt(field.getValue());
                        // Check if this level qualifies for a screenshot
                        if (newLevel >= config.minLevelToScreenshot()) {
                            break;
                        }
                    }
                }
                break;

            case QUEST_COMPLETION:
                // TODO -- need to add group config values for tracking for ValidSubmission object creation where necessary later
                if (!config.questsEmbed()) {
                    return;
                }
                if (config.screenshotQuests()) {
                    // TODO -- need to add group config values for tracking for ValidSubmission object creation where necessary later
                    requiredScreenshot = true;
                }
                break;

            case EXPERIENCE:
                /* We don't need to take screenshots for experience */
                break;

            case EXPERIENCE_MILESTONE:
                /* We don't need to take screenshots for experience milestones */
                break;

            case ADVENTURE_LOG:
                // Nothing extra needs to be done for adventure log data
                break;

            case PET:
                // TODO -- need to add group config values for tracking for ValidSubmission object creation where necessary later
                if(!config.petEmbeds()){
                    break;
                }
                if (config.screenshotPets()) {
                    requiredScreenshot = true;
                }
                break;
        }

        // UI notification is handled by addSubmissionToMemory() when submissions are actually added

        if (requiredScreenshot) {
            captureScreenshotWithPrivacy(webhook, shouldHideDm);
        } else {
            sendDataToDropTracker(webhook, (byte[]) null);
        }
    }

    public void sendDataToDropTracker(CustomWebhookBody customWebhookBody, int totalValue, int singleValue) {
        // Handles sending drops exclusively
        if (!config.lootEmbeds()) {
            return;
        }

        boolean requiredScreenshot = config.screenshotDrops() && totalValue > config.screenshotValue();

        // Create ValidSubmission for drops only when they qualify for group notifications
        ValidSubmission dropSubmission = null;
        
        // Check each group configuration to see if this drop qualifies
        if (config.useApi()) {
            for (GroupConfig groupConfig : api.getGroupConfigs()) {
                if (groupConfig.isSendDrops() && totalValue >= groupConfig.getMinimumDropValue()) {
                    // Check if group allows stacked items
                    if (!groupConfig.isSendStackedItems() && totalValue > singleValue) {
                        continue; // Skip this group if items were stacked but group disabled that
                    }

                    if (groupConfig.isOnlyScreenshots()) {
                        if (!requiredScreenshot) {
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
        }
        
        // Update total value statistics  
        this.totalValue += (long) totalValue;

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
        sendWebhookWithRetry(customWebhookBody, screenshot, 0);
    }

    private static final long BASE_RETRY_DELAY_MS = 1000L;

    private void sendWebhookWithRetry(CustomWebhookBody webhook, byte[] screenshot, int attempt) {
        if (isFakeWorld()) {
            return;
        }

        MultipartBody.Builder requestBodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", GSON.toJson(webhook));

        if (screenshot != null) {
            requestBodyBuilder.addFormDataPart("file", "image.jpeg",
                    RequestBody.create(MediaType.parse("image/jpeg"), screenshot));
        }

        MultipartBody requestBody = requestBodyBuilder.build();

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

        ValidSubmission validSubmission = findValidSubmissionForWebhook(webhook);

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                scheduleRetryOrFail(webhook, screenshot, validSubmission, attempt, e);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                try (ResponseBody body = response.body()) {
                    if (config.useApi()) {
                        api.lastCommunicationTime = (int) (System.currentTimeMillis() / 1000);
                        if (body != null) {
                            try {
                                String bodyString = body.string();
                                if (!bodyString.isEmpty()) {
                                    ApiResponse apiResponse = gson.fromJson(bodyString, ApiResponse.class);
                                    if (apiResponse != null) {
                                        String noticeMessage = apiResponse.getNotice();
                                        if (noticeMessage != null && !noticeMessage.isEmpty() && config.receiveInGameMessages()) {
                                            chatMessageUtil.sendChatMessage(noticeMessage);
                                        }
                                        String updateMessage = apiResponse.getRankUpdate();
                                        if (updateMessage != null && !updateMessage.isEmpty() && config.receiveInGameMessages()) {
                                            chatMessageUtil.sendChatMessage(updateMessage);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                log.debug("Failed to parse API response: {}", e.getMessage());
                            }
                        }
                    }

                    if (!response.isSuccessful()) {
                        int code = response.code();

                        if (code == 400 || code == 401 || code == 403) {
                            if (validSubmission != null) {
                                validSubmission.markAsFailed("HTTP " + code);
                                notifyUpdateCallback();
                            }
                            return;
                        }

                        if (code == 404) {
                            executor.submit(() -> {
                                try {
                                    urlManager.fetchNewList();
                                } catch (Exception ex) {
                                    log.debug("Failed to fetch new webhook list: {}", ex.getMessage());
                                }
                            });
                        }

                        scheduleRetryOrFail(webhook, screenshot, validSubmission, attempt, new IOException("HTTP " + code + ": " + response.message()));
                        return;
                    }

                    // Success
                    if (validSubmission != null) {
                        validSubmission.markAsSuccess();
                        notifyUpdateCallback();
                    }
                    notificationsSent++;

                    if (config.useApi() && validSubmission != null && validSubmission.getUuid() != null && !validSubmission.getUuid().isEmpty()) {
                        executor.submit(() -> {
                            try {
                                boolean processed = api.checkSubmissionProcessed(validSubmission.getUuid());
                                if (processed) {
                                    validSubmission.markAsProcessed();
                                    if (updatesEnabled) {
                                        notifyUpdateCallback();
                                    }
                                }
                            } catch (IOException ignored) {
                            }
                        });
                    }
                }
            }
        });
    }

    private void scheduleRetryOrFail(CustomWebhookBody webhook, byte[] screenshot, ValidSubmission validSubmission, int attempt, Throwable e) {
        int maxAttempts = 10;
        if (attempt < maxAttempts) {
            long delay = BASE_RETRY_DELAY_MS * (1L << Math.min(attempt, 16));
            if (validSubmission != null) {
                validSubmission.markAsRetrying();
                notifyUpdateCallback();
            }
            executor.schedule(() -> sendWebhookWithRetry(webhook, screenshot, attempt + 1), delay, java.util.concurrent.TimeUnit.MILLISECONDS);
            log.debug("Scheduled webhook retry in {} ms (attempt {}/{})", delay, attempt + 1, maxAttempts);
        } else {
            if (validSubmission != null) {
                String reason = e != null && e.getMessage() != null ? e.getMessage() : "Retry limit reached";
                validSubmission.markAsFailed(reason);
                notifyUpdateCallback();
            }
            failedSubmissions++;
            log.warn("Exhausted retry attempts when sending webhook");
        }
    }

    public void addSubmissionToMemory(ValidSubmission validSubmission) {
        if (validSubmissions.size() > 20) {
            // Remove oldest submissions once the list starts to exceed 20
            validSubmissions.remove(0);
        }
        validSubmissions.add(validSubmission);
        
        // Increment total submissions when player generates a qualifying submission
        totalSubmissions++;
        
        notifyUpdateCallback();
    }

    /**
     * Retry a failed submission using the stored webhook data
     *
     * @param validSubmission The submission to retry
     */
    public void retrySubmission(ValidSubmission validSubmission) {
        if (validSubmission == null || validSubmission.getOriginalWebhook() == null) {
            log.warn("Cannot retry submission: missing webhook data");
            return;
        }
        validSubmission.markAsRetrying();
        notifyUpdateCallback();
        sendWebhookWithRetry(validSubmission.getOriginalWebhook(), null, 0);
    }

    /**
     * Remove a submission from the in-memory list (e.g., when user dismisses it)
     *
     * @param validSubmission The submission to remove
     */
    public void removeSubmission(ValidSubmission validSubmission) {
        validSubmissions.remove(validSubmission);
        notifyUpdateCallback();
    }
    
    /**
     * Get retry service statistics for UI display
     */
    public String getRetryStatusText() {
        // Simple status for UI: based on last communication time; detailed queue/health removed
        return "";
    }
    
    /**
     * Clear the retry queue (for manual intervention)
     */
    public void clearRetryQueue() {
        // No-op after removing queue; keep method to avoid UI breakage if referenced elsewhere
        notifyUpdateCallback();
    }
    
    /**
     * Enable or disable retry processing
     */
    public void setRetryProcessingEnabled(boolean enabled) {
        // No-op in simplified retry model
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
    
    /**
     * Process a queued submission from the retry service
     */
    // Queue processing removed in simplified retry model
    
    /**
     * Find a ValidSubmission that matches the given webhook
     */
    private ValidSubmission findValidSubmissionForWebhook(CustomWebhookBody webhook) {
        if (webhook == null) return null;
        
        // Try to match based on webhook content - prefer identity match
        for (ValidSubmission submission : validSubmissions) {
            if (submission.getOriginalWebhook() == webhook) {
                return submission;
            }
        }
        
        // Try to match based on recent submissions with pending status
        for (int i = validSubmissions.size() - 1; i >= 0 && i >= validSubmissions.size() - 5; i--) {
            ValidSubmission submission = validSubmissions.get(i);
            if ("pending".equals(submission.getStatus()) || "retrying".equals(submission.getStatus())) {
                return submission;
            }
        }
        
        return null;
    }
    
    /**
     * Determine the submission type from webhook content
     */
    private SubmissionType getSubmissionTypeFromWebhook(CustomWebhookBody webhook) {
        if (webhook == null || webhook.getEmbeds() == null || webhook.getEmbeds().isEmpty()) {
            return SubmissionType.DROP; // Default fallback
        }
        
        CustomWebhookBody.Embed embed = webhook.getEmbeds().get(0);
        String title = embed.getTitle();
        
        if (title != null) {
            String lowerTitle = title.toLowerCase();
            if (lowerTitle.contains("personal best") || lowerTitle.contains("pb")) {
                return SubmissionType.KILL_TIME;
            }
            if (lowerTitle.contains("collection log") || lowerTitle.contains("clog")) {
                return SubmissionType.COLLECTION_LOG;
            }
            if (lowerTitle.contains("combat achievement") || lowerTitle.contains("ca")) {
                return SubmissionType.COMBAT_ACHIEVEMENT;
            }
            if (lowerTitle.contains("level") || lowerTitle.contains("leveled")) {
                return SubmissionType.LEVEL_UP;
            }
            if (lowerTitle.contains("quest")) {
                return SubmissionType.QUEST_COMPLETION;
            }
            if (lowerTitle.contains("pet")) {
                return SubmissionType.PET;
            }
            if (lowerTitle.contains("experience") || lowerTitle.contains("xp")) {
                return SubmissionType.EXPERIENCE;
            }
        }
        
        return SubmissionType.DROP; // Default fallback
    }

    /**
     * Poll the API to update statuses of pending submissions. Only runs if API is enabled.
     * This should be invoked periodically by the UI timer when the side panel is visible.
     */
    public void checkPendingStatuses() {
        if (!config.useApi()) {
            return;
        }
        // Avoid blocking EDT: schedule on executor
        executor.submit(() -> {
            try {
                boolean changed = false;
                for (ValidSubmission submission : new java.util.ArrayList<>(validSubmissions)) {
                    String status = submission.getStatus();
                    if (status == null) {
                        continue;
                    }
                    // Poll anything not completed (processed) and not failed
                    if (!"processed".equalsIgnoreCase(status) && !"failed".equalsIgnoreCase(status)) {
                        String uuid = submission.getUuid();
                        if (uuid == null || uuid.isEmpty()) {
                            continue;
                        }
                        try {
                            boolean processed = api.checkSubmissionProcessed(uuid);
                            if (processed) {
                                submission.markAsProcessed();
                                changed = true;
                            }
                        } catch (IOException e) {
                            // Swallow per-submission errors to continue polling others
                            log.debug("/check failed for uuid {}: {}", uuid, e.getMessage());
                        }
                    }
                }
                if (changed && updatesEnabled) {
                    notifyUpdateCallback();
                }
            } catch (Exception e) {
                log.debug("Error while checking pending statuses: {}", e.getMessage());
            }
        });
    }

    public interface SubmissionUpdateCallback {
        void onSubmissionsUpdated();
    }

    @Getter
    private static class ApiResponse {
        @SerializedName("notice")
        private String notice;

        @SerializedName("rank_update")
        private String rankUpdate;
        
        @SerializedName("status")
        private String status;
        
        @SerializedName("processed")
        private Boolean processed;
        
        @SerializedName("submission_id")
        private String submissionId;
    }

}
