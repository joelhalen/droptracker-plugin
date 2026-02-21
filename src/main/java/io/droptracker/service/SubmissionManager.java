package io.droptracker.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.api.UrlManager;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.api.GroupConfig;
import io.droptracker.models.submissions.SubmissionStatus;
import io.droptracker.models.submissions.SubmissionType;
import io.droptracker.models.submissions.ValidSubmission;
import io.droptracker.util.ChatMessageUtil;
import io.droptracker.util.DebugLogger;
import io.droptracker.video.VideoQuality;
import io.droptracker.video.VideoRecorder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
import net.runelite.api.annotations.Component;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.Text;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final VideoRecorder videoRecorder;
    private final NearbyPlayerTracker nearbyPlayerTracker;

    /** Thread-safe list of submissions the player has received which qualified for notifications */
    @Getter
    private final List<ValidSubmission> validSubmissions = new CopyOnWriteArrayList<>();

    /** Callback for UI updates when submissions change */
    @Setter
    private SubmissionUpdateCallback updateCallback;
    @Setter
    private boolean updatesEnabled = true;
    @Inject
    private ScheduledExecutorService executor;

    /** Total GP value of drops submitted this session */
    @Getter
    private long sessionTotalValue = 0L;

    /** Debounced save handle */
    private ScheduledFuture<?> pendingSave;
    private final AtomicBoolean saveScheduled = new AtomicBoolean(false);

    /** Directory for persisting submissions */
    private static final String PERSISTENCE_DIR = "droptracker";
    private static final String PERSISTENCE_FILE_PREFIX = "submissions_";
    private static final int MAX_PERSISTED_SUBMISSIONS = 50;

    /** Pending webhooks that arrived before group configs were loaded */
    private final List<PendingEvent> pendingEvents = new CopyOnWriteArrayList<>();
    private volatile boolean groupConfigsLoaded = false;

    private static final long BASE_RETRY_DELAY_MS = 1000L;

    @Inject
    public SubmissionManager(
        DropTrackerConfig config,
        DropTrackerApi api,
        ChatMessageUtil chatMessageUtil,
        Gson gson,
        OkHttpClient okHttpClient,
        Client client,
        ClientThread clientThread,
        UrlManager urlManager,
        DrawManager drawManager,
        VideoRecorder videoRecorder,
        NearbyPlayerTracker nearbyPlayerTracker
    ) {
        this.config = config;
        this.api = api;
        this.chatMessageUtil = chatMessageUtil;
        this.gson = gson;
        this.okHttpClient = okHttpClient;
        this.client = client;
        this.clientThread = clientThread;
        this.urlManager = urlManager;
        this.drawManager = drawManager;
        this.videoRecorder = videoRecorder;
        this.nearbyPlayerTracker = nearbyPlayerTracker;
    }

    // ========== Widget Helpers ==========

    public static void hideWidget(Client client, ClientThread clientThread, @Component int info) {
        Widget widget = client.getWidget(info);
        if (widget != null) {
            widget.setHidden(true);
        }
    }

    public static void showWidget(Client client, ClientThread clientThread, @Component int info) {
        clientThread.invoke(() -> {
            Widget widget = client.getWidget(info);
            if (widget != null)
                widget.setHidden(false);
        });
    }

    // ========== Public Entry Points for Event Handlers ==========

    /**
     * Entry point for non-drop events (PBs, CLogs, CAs, Pets, Quests, Levels, XP, etc.)
     */
    public void sendDataToDropTracker(CustomWebhookBody webhook, SubmissionType type) {
        boolean requiredScreenshot = false;
        boolean shouldHideDm = config.hideDMs();
        debugLogEventFlow("received", type, "entry=non-drop, useApi=" + config.useApi() + ", hideDMs=" + shouldHideDm);

        // Check if the user has this event type enabled locally
        switch (type) {
            case DROP:
                // Drops use the value-based overload; this should not be called for drops
                debugLogEventFlow("received", type, "non-drop entrypoint invoked for DROP type; ignoring");
                break;
            case KILL_TIME:
                if (!config.pbEmbeds()) {
                    debugLogEventFlow("skipped", type, "pbEmbeds=false");
                    return;
                }
                boolean isPb = isFieldTrue(webhook, "is_pb");
                if (config.screenshotPBs() && isPb) {
                    requiredScreenshot = true;
                }
                if (!isPb) {
                    // Non-PB kill times: just send, no ValidSubmission tracking
                    debugLogEventFlow("send", type, "non-pb kill time; direct send; screenshotRequired=" + requiredScreenshot);
                    if (requiredScreenshot) {
                        captureAndSend(webhook, null, shouldHideDm);
                    } else {
                        sendWebhookDirect(webhook, null, null, null);
                    }
                    return;
                }
                break;
            case COLLECTION_LOG:
                if (!config.clogEmbeds()) {
                    debugLogEventFlow("skipped", type, "clogEmbeds=false");
                    return;
                }
                if (config.screenshotNewClogs()) requiredScreenshot = true;
                break;
            case COMBAT_ACHIEVEMENT:
                if (!config.caEmbeds()) {
                    debugLogEventFlow("skipped", type, "caEmbeds=false");
                    return;
                }
                if (config.screenshotCAs()) requiredScreenshot = true;
                break;
            case LEVEL_UP:
                if (!config.levelEmbed()) {
                    debugLogEventFlow("skipped", type, "levelEmbed=false");
                    return;
                }
                if (config.screenshotLevel()) requiredScreenshot = true;
                break;
            case QUEST_COMPLETION:
                if (!config.questsEmbed()) {
                    debugLogEventFlow("skipped", type, "questsEmbed=false");
                    return;
                }
                if (config.screenshotQuests()) requiredScreenshot = true;
                break;
            case PET:
                if (!config.petEmbeds()) {
                    debugLogEventFlow("skipped", type, "petEmbeds=false");
                    return;
                }
                if (config.screenshotPets()) requiredScreenshot = true;
                break;
            case EXPERIENCE:
            case EXPERIENCE_MILESTONE:
                // No screenshots for experience events
                break;
            case ADVENTURE_LOG:
                // No extra processing needed
                break;
        }

        // Create ValidSubmission if the event qualifies for any group
        ValidSubmission submission = createSubmissionIfQualified(webhook, type, requiredScreenshot, 0, 0);
        debugLogEventFlow("qualification", type, "screenshotRequired=" + requiredScreenshot + ", " + summarizeSubmission(submission));

        if (requiredScreenshot) {
            debugLogEventFlow("capture", type, "captureAndSend path selected");
            captureAndSend(webhook, submission, shouldHideDm);
        } else {
            debugLogEventFlow("send", type, "sendWebhookDirect path selected");
            sendWebhookDirect(webhook, null, null, submission);
        }
    }

    /**
     * Entry point for drop events (value-based qualification)
     */
    public void sendDataToDropTracker(CustomWebhookBody customWebhookBody, int totalValue, int singleValue, boolean valueModified) {
        if (!config.lootEmbeds()) {
            debugLogEventFlow("skipped", SubmissionType.DROP, "lootEmbeds=false");
            return;
        }

        addParticipantsField(customWebhookBody);

        boolean requiredScreenshot = (config.screenshotDrops() && totalValue > config.screenshotValue()) || valueModified;
        debugLogEventFlow("received", SubmissionType.DROP, "totalValue=" + totalValue + ", singleValue=" + singleValue
                + ", valueModified=" + valueModified + ", screenshotRequired=" + requiredScreenshot
                + ", screenshotThreshold=" + config.screenshotValue());

        // Create ValidSubmission if the drop qualifies for any group
        ValidSubmission submission = createSubmissionIfQualified(customWebhookBody, SubmissionType.DROP, requiredScreenshot, totalValue, singleValue);
        debugLogEventFlow("qualification", SubmissionType.DROP, summarizeSubmission(submission));

        // Update session value statistics
        this.sessionTotalValue += (long) totalValue;
        if (submission != null) {
            submission.setTotalValue(totalValue);
        }

        if (requiredScreenshot) {
            boolean shouldHideDm = config.hideDMs();
            debugLogEventFlow("capture", SubmissionType.DROP, "captureAndSend path selected; hideDMs=" + shouldHideDm);
            captureAndSend(customWebhookBody, submission, shouldHideDm);
        } else {
            debugLogEventFlow("send", SubmissionType.DROP, "sendWebhookDirect path selected");
            sendWebhookDirect(customWebhookBody, null, null, submission);
        }
    }

    private void addParticipantsField(CustomWebhookBody webhook) {
        if (webhook == null || webhook.getEmbeds() == null) {
            return;
        }

        List<String> members = nearbyPlayerTracker.getNearbyPlayerNames(20);
        String membersValue = members.isEmpty() ? "none" : String.join(",", members);

        for (CustomWebhookBody.Embed embed : webhook.getEmbeds()) {
            if (embed == null) {
                continue;
            }

            boolean hasMembersField = false;
            for (CustomWebhookBody.Field field : embed.getFields()) {
                if (field != null && "members".equalsIgnoreCase(field.getName())) {
                    hasMembersField = true;
                    break;
                }
            }

            if (!hasMembersField) {
                embed.addField("members", membersValue, false);
            }
        }
    }

    // ========== Unified Qualification Logic ==========

    /**
     * Checks all group configurations to determine if this event qualifies for tracked notifications.
     * Creates a single ValidSubmission covering all qualifying groups, or returns null if none qualify.
     */
    private ValidSubmission createSubmissionIfQualified(
            CustomWebhookBody webhook, SubmissionType type,
            boolean hasScreenshot, int totalValue, int singleValue) {

        if (!config.useApi()) {
            debugLogEventFlow("qualification", type, "useApi=false; skipping group qualification");
            return null;
        }

        List<GroupConfig> groupConfigs = api.getGroupConfigs();

        // If group configs haven't loaded yet, queue the event for later evaluation
        if (groupConfigs == null || groupConfigs.isEmpty()) {
            if (!groupConfigsLoaded) {
                pendingEvents.add(new PendingEvent(webhook, type, hasScreenshot, totalValue, singleValue));
                debugLogEventFlow("qualification", type, "group configs unavailable; queued for later evaluation");
            }
            debugLogEventFlow("qualification", type, "groupConfigs unavailable; queued=" + (!groupConfigsLoaded));
            return null;
        }

        ValidSubmission submission = null;

        for (GroupConfig groupConfig : groupConfigs) {
            String failReason = getQualificationFailureReason(type, groupConfig, hasScreenshot, totalValue, singleValue);
            if (failReason != null) {
                debugLogEventFlow("qualification-skip", type, "group=" + groupConfig.getGroupId() + ", reason=" + failReason);
                continue;
            }

            if (submission == null) {
                submission = new ValidSubmission(webhook, groupConfig.getGroupId(), type);
                addSubmissionToMemory(submission);
            } else {
                submission.addGroupId(groupConfig.getGroupId());
            }
            debugLogEventFlow("qualification-pass", type, "group=" + groupConfig.getGroupId());
        }

        if (submission == null) {
            debugLogEventFlow("qualification", type, "no groups qualified");
        } else {
            debugLogEventFlow("qualification", type, summarizeSubmission(submission));
        }
        return submission;
    }

    /**
     * Returns null when the event qualifies, otherwise a concise reason it was excluded.
     */
    private String getQualificationFailureReason(SubmissionType type, GroupConfig groupConfig,
                                                 boolean hasScreenshot, int totalValue, int singleValue) {
        // If the group requires screenshots and we don't have one, skip
        if (groupConfig.isOnlyScreenshots() && !hasScreenshot) {
            return "group requires screenshot";
        }

        switch (type) {
            case DROP:
                if (!groupConfig.isSendDrops()) return "group sendDrops=false";
                if (totalValue < groupConfig.getMinimumDropValue()) {
                    return "totalValue(" + totalValue + ") < groupMin(" + groupConfig.getMinimumDropValue() + ")";
                }
                // Check stacked items: if group doesn't allow stacked and total > single, skip
                if (!groupConfig.isSendStackedItems() && totalValue > singleValue && singleValue > 0) {
                    return "stacked item and group sendStackedItems=false";
                }
                return null;

            case KILL_TIME:
                return groupConfig.isSendPbs() ? null : "group sendPbs=false";

            case COLLECTION_LOG:
                return groupConfig.isSendClogs() ? null : "group sendClogs=false";

            case COMBAT_ACHIEVEMENT:
                return groupConfig.isSendCAs() ? null : "group sendCAs=false";

            case LEVEL_UP:
                return groupConfig.isSendXP() ? null : "group sendXP=false";

            case QUEST_COMPLETION:
                return groupConfig.isSendQuests() ? null : "group sendQuests=false";

            case PET:
                return groupConfig.isSendPets() ? null : "group sendPets=false";

            case EXPERIENCE:
            case EXPERIENCE_MILESTONE:
                // XP events typically don't create group notifications,
                // but respect the group config if present
                return groupConfig.isSendXP() ? null : "group sendXP=false";

            case ADVENTURE_LOG:
                // Adventure log currently has no group-level toggle
                return "adventure log unsupported for group notifications";

            default:
                return "unsupported submission type";
        }
    }

    // ========== Send Logic ==========

    /**
     * Sends a webhook directly (with optional screenshot, video key, and ValidSubmission tracking).
     * This is the single send method -- the ValidSubmission is passed through, not looked up.
     *
     * @param webhook The webhook body to send
     * @param screenshot Optional JPEG screenshot bytes
     * @param videoKey Optional cloud storage key for an uploaded video, or null
     * @param submission Optional ValidSubmission for status tracking
     */
    private void sendWebhookDirect(CustomWebhookBody webhook, byte[] screenshot, String videoKey, ValidSubmission submission) {
        if (isFakeWorld()) {
            debugLogEventFlow("skipped", submission != null ? submission.getType() : null, "fake world; webhook not sent");
            return;
        }

        debugLogEventFlow("send", submission != null ? submission.getType() : null,
                "attempting direct send; hasScreenshot=" + (screenshot != null) + ", hasVideoKey=" + (videoKey != null && !videoKey.isEmpty())
                        + ", " + summarizeSubmission(submission));

        if (submission != null) {
            submission.markAsSending();
            if (screenshot != null) {
                submission.setScreenshotData(screenshot);
            }
            notifyUpdateCallback();
            schedulePersistence();
        }

        // If a video key is present, inject it into the webhook embeds so the API can reference it
        if (videoKey != null && !videoKey.isEmpty()) {
            for (CustomWebhookBody.Embed embed : webhook.getEmbeds()) {
                embed.addField("video_key", videoKey, true);
            }
        }

        sendWebhookWithRetry(webhook, screenshot, 0, submission);
    }

    private void sendWebhookWithRetry(CustomWebhookBody webhook, byte[] screenshot, int attempt, ValidSubmission submission) {
        if (isFakeWorld()) {
            debugLogEventFlow("skipped", submission != null ? submission.getType() : null, "fake world during retry attempt=" + attempt);
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
                debugLogEventFlow("dispatch", submission != null ? submission.getType() : null,
                        "using random webhook endpoint (API disabled); attempt=" + attempt);
            } catch (Exception e) {
                debugLogEventFlow("failed", submission != null ? submission.getType() : null,
                        "failed to resolve webhook endpoint: " + e.getMessage());
                return;
            }
        } else {
            url = api.getApiUrl() + "/webhook";
            debugLogEventFlow("dispatch", submission != null ? submission.getType() : null,
                    "using API webhook endpoint; attempt=" + attempt + ", url=" + url);
        }
        HttpUrl u = HttpUrl.parse(url);
        if (u == null || !urlManager.isValidDiscordWebhookUrl(u)) {
            log.debug("Invalid or malformed webhook URL: {}", url);
            debugLogEventFlow("failed", submission != null ? submission.getType() : null, "invalid webhook URL: " + url);
            return;
        }

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                debugLogEventFlow("retry", submission != null ? submission.getType() : null,
                        "network failure on attempt=" + attempt + ": " + e.getMessage());
                scheduleRetryOrFail(webhook, screenshot, submission, attempt, e);
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
                        debugLogEventFlow("response", submission != null ? submission.getType() : null,
                                "unsuccessful HTTP response code=" + code + ", message=" + response.message() + ", attempt=" + attempt);

                        if (code == 400 || code == 401 || code == 403) {
                            if (submission != null) {
                                submission.markAsFailed("HTTP " + code);
                                notifyUpdateCallback();
                                schedulePersistence();
                            }
                            debugLogEventFlow("failed", submission != null ? submission.getType() : null,
                                    "terminal HTTP failure code=" + code + "; no retry");
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

                        scheduleRetryOrFail(webhook, screenshot, submission, attempt, new IOException("HTTP " + code + ": " + response.message()));
                        return;
                    }

                    // Success
                    if (submission != null) {
                        submission.markAsSuccess();
                        notifyUpdateCallback();
                        schedulePersistence();
                    }
                    debugLogEventFlow("response", submission != null ? submission.getType() : null,
                            "success HTTP response; attempt=" + attempt + ", " + summarizeSubmission(submission));

                    // Check if the API has already processed this submission
                    if (config.useApi() && submission != null && submission.getUuid() != null && !submission.getUuid().isEmpty()) {
                        executor.submit(() -> {
                            try {
                                boolean processed = api.checkSubmissionProcessed(submission.getUuid());
                                if (processed) {
                                    submission.markAsProcessed();
                                    notifyUpdateCallback();
                                    schedulePersistence();
                                    debugLogEventFlow("processed", submission.getType(),
                                            "API check confirmed processed; uuid=" + submission.getUuid());
                                }
                            } catch (IOException ignored) {
                                debugLogEventFlow("processed", submission.getType(),
                                        "API check failed for uuid=" + submission.getUuid() + ": " + ignored.getMessage());
                            }
                        });
                    }
                }
            }
        });
    }

    private void scheduleRetryOrFail(CustomWebhookBody webhook, byte[] screenshot, ValidSubmission submission, int attempt, Throwable e) {
        int maxAttempts = 10;
        if (attempt < maxAttempts) {
            long delay = BASE_RETRY_DELAY_MS * (1L << Math.min(attempt, 16));
            if (submission != null) {
                submission.markAsRetrying();
                notifyUpdateCallback();
                schedulePersistence();
            }
            executor.schedule(() -> sendWebhookWithRetry(webhook, screenshot, attempt + 1, submission), delay, TimeUnit.MILLISECONDS);
            log.debug("Scheduled webhook retry in {} ms (attempt {}/{})", delay, attempt + 1, maxAttempts);
            debugLogEventFlow("retry", submission != null ? submission.getType() : null,
                    "scheduled retry in " + delay + "ms (nextAttempt=" + (attempt + 1) + "/" + maxAttempts + ")"
                            + (e != null && e.getMessage() != null ? ", reason=" + e.getMessage() : ""));
        } else {
            if (submission != null) {
                String reason = e != null && e.getMessage() != null ? e.getMessage() : "Retry limit reached";
                submission.markAsFailed(reason);
                notifyUpdateCallback();
                schedulePersistence();
            }
            log.warn("Exhausted retry attempts when sending webhook");
            debugLogEventFlow("failed", submission != null ? submission.getType() : null,
                    "retry limit exhausted after " + maxAttempts + " attempts"
                            + (e != null && e.getMessage() != null ? ", lastError=" + e.getMessage() : ""));
        }
    }

    // ========== Capture (Screenshot or Video) ==========

    /**
     * Routes capture to either screenshot-only or video based on the configured capture mode.
     *
     * @param webhook The webhook body to send after capture
     * @param submission Optional ValidSubmission for tracking
     * @param hideDMs Whether to hide PM chat during capture
     */
    private void captureAndSend(CustomWebhookBody webhook, ValidSubmission submission, boolean hideDMs) {
        VideoQuality captureMode = config.captureMode();
        debugLogEventFlow("capture", submission != null ? submission.getType() : null,
                "captureMode=" + captureMode + ", useApi=" + config.useApi() + ", hideDMs=" + hideDMs);

        if (captureMode.requiresVideo() && config.useApi()) {
            log.info("Capture mode is VIDEO; capturing clip.");
            debugLogEventFlow("capture", submission != null ? submission.getType() : null, "capturing video");
            captureVideoAndSend(webhook, submission, hideDMs);
        } else {
            debugLogEventFlow("capture", submission != null ? submission.getType() : null,
                    captureMode.requiresVideo() ? "video requested but API disabled; screenshot fallback" : "capturing screenshot");
            captureScreenshotAndSend(webhook, submission, hideDMs);
        }
    }

    /**
     * Captures a single screenshot and sends the webhook with it.
     * This is the original screenshot-only path.
     */
    private void captureScreenshotAndSend(CustomWebhookBody webhook, ValidSubmission submission, boolean hideDMs) {
        if (hideDMs) {
            hideWidget(client, clientThread, InterfaceID.PmChat.CONTAINER);
        }

        drawManager.requestNextFrameListener(image -> {
            BufferedImage bufferedImage = (BufferedImage) image;
            if (hideDMs) {
                showWidget(client, clientThread, InterfaceID.PmChat.CONTAINER);
            }

            byte[] imageBytes = null;
            try {
                imageBytes = convertImageToByteArray(bufferedImage);
                if (imageBytes.length > 5 * 1024 * 1024) {
                    // TODO: perform compression here
                }
            } catch (IOException e) {
                log.error("Error converting image to byte array", e);
                debugLogEventFlow("capture", submission != null ? submission.getType() : null,
                        "screenshot conversion failed: " + e.getMessage());
            }

            debugLogEventFlow("capture", submission != null ? submission.getType() : null,
                    "screenshot captured; bytes=" + (imageBytes != null ? imageBytes.length : 0));
            sendWebhookDirect(webhook, imageBytes, null, submission);
        });
    }

    /**
     * Captures a video clip (pre-event buffer + post-event recording) and uploads it
     * via a presigned URL, then sends the webhook with the screenshot and video key.
     * Falls back to screenshot-only if video capture or upload fails.
     */
    private void captureVideoAndSend(CustomWebhookBody webhook, ValidSubmission submission, boolean hideDMs) {
        if (hideDMs) {
            hideWidget(client, clientThread, InterfaceID.PmChat.CONTAINER);
        }

        videoRecorder.captureEventVideo((screenshotBytes, videoFrames, fps) -> {
            if (hideDMs) {
                showWidget(client, clientThread, InterfaceID.PmChat.CONTAINER);
            }

            // If no video frames were captured, fall back to screenshot-only
            if (videoFrames == null || videoFrames.isEmpty()) {
                log.warn("No video frames captured, falling back to screenshot-only");
                debugLogEventFlow("capture", submission != null ? submission.getType() : null,
                        "no video frames captured; fallback to screenshot");
                sendWebhookDirect(webhook, screenshotBytes, null, submission);
                return;
            }

            log.info("Captured video frames: frames={}, fps={}", videoFrames.size(), fps);

            // Upload video frames via presigned URL on a background thread
            executor.submit(() -> {
                String videoKey = uploadVideoFrames(videoFrames, fps);

                // If upload failed, fall back to screenshot-only (keeps existing behavior).
                if (videoKey == null || videoKey.isEmpty()) {
                    log.warn("Video upload failed; sending screenshot-only");
                    debugLogEventFlow("capture", submission != null ? submission.getType() : null,
                            "video upload failed; fallback to screenshot");
                    sendWebhookDirect(webhook, screenshotBytes, null, submission);
                    return;
                }

                // Video mode should replace screenshot attachments (do not send image.jpeg alongside).
                log.info("Video uploaded (key={}); sending webhook without screenshot attachment", videoKey);
                debugLogEventFlow("capture", submission != null ? submission.getType() : null,
                        "video uploaded; sending with videoKey=" + videoKey);
                sendWebhookDirect(webhook, null, videoKey, submission);
            });
        });
    }

    /**
     * Uploads MJPEG video frames to cloud storage via a presigned URL obtained from the API.
     * Frames are streamed directly to the upload URL to minimize memory overhead.
     *
     * @param frames List of JPEG frame byte arrays
     * @param fps The frames per second the video was recorded at
     * @return The video storage key if upload succeeded, or null on failure
     */
    private String uploadVideoFrames(List<byte[]> frames, int fps) {
        if (!config.useApi()) {
            // Video uploads require the API (presigned upload URL + key).
            // If the user has API disabled, we should be loud about why uploads never happen.
            log.warn("Video upload skipped: API disabled (useApi=false). Falling back to screenshot-only.");
            return null;
        }

        try {
            if (frames == null || frames.isEmpty()) {
                log.warn("Video upload skipped: no frames provided");
                return null;
            }

            // Step 1: Get a presigned upload URL from the API
            DropTrackerApi.PresignedUrlResponse presigned = api.getPresignedVideoUploadUrl(fps);

            if (presigned == null) {
                log.warn("Video upload failed: could not obtain presigned upload URL (null response)");
                return null;
            }
            log.info("Presigned: {}", GSON.toJson(presigned));

            if (presigned.message != null && !presigned.message.isEmpty()) {
                if (presigned.message.contains("missing upgrade")) {
                    chatMessageUtil.sendChatMessage("Video capture requires you to be a member of a tier 3 group in the DropTracker. Consider subscribing to unlock this feature.");
                    return null;
                }
            }

            if (presigned.quotaExceeded) {
                log.warn("Video upload skipped: quota exceeded ({})", presigned.message);
                return null;
            }

            // Step 2: Stream MJPEG frames to the presigned URL
            long totalSize = 0;
            for (byte[] frame : frames) {
                totalSize += frame.length;
            }

            final long finalTotalSize = totalSize;
            final List<byte[]> finalFrames = frames;

            // Use streaming RequestBody to avoid buffering all frames in memory
            RequestBody streamingBody = new RequestBody() {
                @Override
                public MediaType contentType() {
                    return MediaType.parse("application/octet-stream");
                }

                @Override
                public long contentLength() {
                    return finalTotalSize;
                }

                @Override
                public void writeTo(okio.BufferedSink sink) throws IOException {
                    // MJPEG format: concatenated JPEG frames
                    for (byte[] frame : finalFrames) {
                        sink.write(frame);
                    }
                    sink.flush();
                }
            };

            Request uploadRequest = new Request.Builder()
                .url(presigned.uploadUrl)
                .addHeader("Content-Type", "application/octet-stream")
                .addHeader("Content-Length", String.valueOf(totalSize))
                .put(streamingBody)
                .build();

            try (Response response = okHttpClient.newCall(uploadRequest).execute()) {
                if (response.isSuccessful()) {
                    log.debug("Video uploaded successfully: key={}, size={}KB, frames={}",
                        presigned.key, totalSize / 1024, frames.size());
                    return presigned.key;
                } else {
                    log.warn("Video upload failed: HTTP {} {} (frames={}, size={}KB)",
                        response.code(), response.message(), frames.size(), totalSize / 1024);
                    return null;
                }
            }
        } catch (Exception e) {
            log.warn("Video upload failed with exception: {}", e.getMessage());
            log.debug("Video upload exception details", e);
            return null;
        }
    }

    private static byte[] convertImageToByteArray(BufferedImage bufferedImage) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ImageIO.write(bufferedImage, "jpeg", byteArrayOutputStream);
        return byteArrayOutputStream.toByteArray();
    }

    // ========== Submission Management ==========

    public void addSubmissionToMemory(ValidSubmission validSubmission) {
        // Prune oldest processed submissions to stay within limits
        while (validSubmissions.size() >= MAX_PERSISTED_SUBMISSIONS) {
            // Prefer removing oldest processed submission first
            ValidSubmission toRemove = null;
            for (ValidSubmission s : validSubmissions) {
                if (s.getStatus() == SubmissionStatus.PROCESSED) {
                    toRemove = s;
                    break;
                }
            }
            if (toRemove != null) {
                validSubmissions.remove(toRemove);
            } else {
                // Remove the oldest submission regardless
                validSubmissions.remove(0);
            }
        }
        validSubmissions.add(validSubmission);
        notifyUpdateCallback();
        schedulePersistence();
    }

    /**
     * Retry a failed submission using the stored webhook data
     */
    public void retrySubmission(ValidSubmission validSubmission) {
        if (validSubmission == null || validSubmission.getOriginalWebhook() == null) {
            log.warn("Cannot retry submission: missing webhook data");
            return;
        }
        validSubmission.markAsRetrying();
        notifyUpdateCallback();
        schedulePersistence();
        // Use stored screenshot data if available
        byte[] screenshot = validSubmission.getScreenshotData();
        sendWebhookWithRetry(validSubmission.getOriginalWebhook(), screenshot, 0, validSubmission);
    }

    /**
     * Remove a submission from the list (e.g., when user dismisses it)
     */
    public void removeSubmission(ValidSubmission validSubmission) {
        validSubmissions.remove(validSubmission);
        notifyUpdateCallback();
        schedulePersistence();
    }

    // ========== Statistics (derived from list) ==========

    /**
     * Count of all submissions that qualified for group notifications this session
     */
    public int getTotalSubmissions() {
        return validSubmissions.size();
    }

    /**
     * Count of submissions that were successfully sent or processed
     */
    public int getNotificationsSent() {
        int count = 0;
        for (ValidSubmission s : validSubmissions) {
            if (s.getStatus() == SubmissionStatus.SENT || s.getStatus() == SubmissionStatus.PROCESSED) {
                count++;
            }
        }
        return count;
    }

    /**
     * Count of submissions that failed
     */
    public int getFailedSubmissions() {
        int count = 0;
        for (ValidSubmission s : validSubmissions) {
            if (s.getStatus() == SubmissionStatus.FAILED) {
                count++;
            }
        }
        return count;
    }

    /**
     * Total GP value of drops submitted (session-only, not derived from list because
     * non-qualifying drops also contribute to GP value)
     */
    public long getTotalValue() {
        return sessionTotalValue;
    }

    // ========== Pending Status Polling ==========

    /**
     * Poll the API to update statuses of pending submissions.
     */
    public void checkPendingStatuses() {
        if (!config.useApi()) {
            return;
        }
        executor.submit(() -> {
            try {
                boolean changed = false;
                for (ValidSubmission submission : validSubmissions) {
                    SubmissionStatus status = submission.getStatus();
                    if (status == null || status.isTerminal()) {
                        continue;
                    }
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
                        log.debug("/check failed for uuid {}: {}", uuid, e.getMessage());
                    }
                }
                if (changed) {
                    notifyUpdateCallback();
                    schedulePersistence();
                }
            } catch (Exception e) {
                log.debug("Error while checking pending statuses: {}", e.getMessage());
            }
        });
    }

    /**
     * Check if there are any submissions in active (non-terminal) states
     */
    public boolean hasActiveSubmissions() {
        for (ValidSubmission submission : validSubmissions) {
            SubmissionStatus status = submission.getStatus();
            if (status != null && status.isActive()) {
                return true;
            }
        }
        return false;
    }

    // ========== Group Config Loading Notification ==========

    /**
     * Called when group configs have been loaded. Re-evaluates any events that were
     * queued while configs were unavailable.
     */
    public void onGroupConfigsLoaded() {
        this.groupConfigsLoaded = true;
        if (pendingEvents.isEmpty()) {
            return;
        }

        debugLogEventFlow("qualification", null, "group configs loaded; replaying pendingEvents=" + pendingEvents.size());
        List<PendingEvent> toProcess = new ArrayList<>(pendingEvents);
        pendingEvents.clear();

        for (PendingEvent event : toProcess) {
            // Re-run qualification now that configs are available
            createSubmissionIfQualified(event.webhook, event.type, event.hasScreenshot, event.totalValue, event.singleValue);
        }

        if (!validSubmissions.isEmpty()) {
            notifyUpdateCallback();
            schedulePersistence();
        }
    }

    // ========== Persistence ==========

    /**
     * Load persisted submissions from disk for the given account hash.
     */
    public void loadSubmissions(String accountHash) {
        if (accountHash == null || accountHash.isEmpty() || "-1".equals(accountHash)) {
            return;
        }

        Path filePath = getSubmissionFilePath(accountHash);
        if (!Files.exists(filePath)) {
            return;
        }

        executor.submit(() -> {
            try {
                String json = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
                Type listType = new TypeToken<List<ValidSubmission>>() {}.getType();
                Gson persistGson = new GsonBuilder().create();
                List<ValidSubmission> loaded = persistGson.fromJson(json, listType);
                if (loaded != null && !loaded.isEmpty()) {
                    // Only load submissions that are still actionable (not yet processed, or recently processed)
                    for (ValidSubmission s : loaded) {
                        // Fix null status from old persistence format
                        if (s.getStatus() == null) {
                            s.markAsFailed("Unknown state after restart");
                        }
                        // Don't re-add if we already have it in memory (by UUID)
                        if (s.getUuid() != null && findByUuid(s.getUuid()) != null) {
                            continue;
                        }
                        validSubmissions.add(s);
                    }
                    debugLogEventFlow("persistence", null, "loaded submissions count=" + loaded.size() + ", accountHash=" + accountHash);
                    notifyUpdateCallback();
                }
            } catch (Exception e) {
                log.debug("Failed to load persisted submissions: {}", e.getMessage());
            }
        });
    }

    /**
     * Save current submissions to disk. This is debounced to avoid excessive I/O.
     */
    private void schedulePersistence() {
        if (executor == null) {
            return;
        }
        if (saveScheduled.compareAndSet(false, true)) {
            pendingSave = executor.schedule(() -> {
                saveScheduled.set(false);
                persistSubmissions();
            }, 2, TimeUnit.SECONDS);
        }
    }

    private void persistSubmissions() {
        String accountHash = client != null ? String.valueOf(client.getAccountHash()) : null;
        if (accountHash == null || "-1".equals(accountHash)) {
            return;
        }

        try {
            Path filePath = getSubmissionFilePath(accountHash);
            Files.createDirectories(filePath.getParent());

            // Only persist submissions that have webhook data (can be retried) or are recent
            List<ValidSubmission> toSave = new ArrayList<>();
            for (ValidSubmission s : validSubmissions) {
                toSave.add(s);
                if (toSave.size() >= MAX_PERSISTED_SUBMISSIONS) {
                    break;
                }
            }

            Gson persistGson = new GsonBuilder().setPrettyPrinting().create();
            String json = persistGson.toJson(toSave);
            Files.write(filePath, json.getBytes(StandardCharsets.UTF_8));
            debugLogEventFlow("persistence", null, "persisted submissions count=" + toSave.size() + ", accountHash=" + accountHash);
        } catch (Exception e) {
            log.debug("Failed to persist submissions: {}", e.getMessage());
        }
    }

    private Path getSubmissionFilePath(String accountHash) {
        return RuneLite.RUNELITE_DIR.toPath().resolve(PERSISTENCE_DIR).resolve(PERSISTENCE_FILE_PREFIX + accountHash + ".json");
    }

    private ValidSubmission findByUuid(String uuid) {
        if (uuid == null) return null;
        for (ValidSubmission s : validSubmissions) {
            if (uuid.equals(s.getUuid())) {
                return s;
            }
        }
        return null;
    }

    // ========== Utilities ==========

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

    /**
     * Check if a specific embed field has value "true"
     */
    private boolean isFieldTrue(CustomWebhookBody webhook, String fieldName) {
        if (webhook == null || webhook.getEmbeds() == null) return false;
        for (CustomWebhookBody.Embed embed : webhook.getEmbeds()) {
            for (CustomWebhookBody.Field field : embed.getFields()) {
                if (field.getName().equalsIgnoreCase(fieldName)
                        && field.getValue().equalsIgnoreCase("true")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void notifyUpdateCallback() {
        if (updatesEnabled && updateCallback != null) {
            updateCallback.onSubmissionsUpdated();
        }
    }

    private void debugLogEventFlow(String stage, SubmissionType type, String message) {
        DebugLogger.log("[SubmissionManager][" + stage + "][" + (type != null ? type : "UNKNOWN") + "] " + message);
    }

    private String summarizeSubmission(ValidSubmission submission) {
        if (submission == null) {
            return "submission=null";
        }
        return "submissionUuid=" + submission.getUuid()
                + ", status=" + submission.getStatus()
                + ", groups=" + Arrays.toString(submission.getGroupIds());
    }

    // ========== Legacy API compatibility ==========

    /** @deprecated Use derived methods instead. Kept for backward compat. */
    @Deprecated
    public String getRetryStatusText() {
        return "";
    }

    /** @deprecated No-op. Kept for backward compat. */
    @Deprecated
    public void clearRetryQueue() {
        notifyUpdateCallback();
    }

    /** @deprecated No-op. Kept for backward compat. */
    @Deprecated
    public void setRetryProcessingEnabled(boolean enabled) {
        // No-op
    }

    // ========== Inner Classes ==========

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

    /**
     * Represents an event that arrived before group configs were loaded.
     * Stored temporarily until configs arrive, then re-evaluated.
     */
    private static class PendingEvent {
        final CustomWebhookBody webhook;
        final SubmissionType type;
        final boolean hasScreenshot;
        final int totalValue;
        final int singleValue;

        PendingEvent(CustomWebhookBody webhook, SubmissionType type, boolean hasScreenshot, int totalValue, int singleValue) {
            this.webhook = webhook;
            this.type = type;
            this.hasScreenshot = hasScreenshot;
            this.totalValue = totalValue;
            this.singleValue = singleValue;
        }
    }
}
