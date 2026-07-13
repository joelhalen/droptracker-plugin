/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved.
 *
 * Copyright (c) 2024, joelhalen <andy@joelhalen.net>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package io.droptracker.video;

import io.droptracker.DropTrackerConfig;
import io.droptracker.util.ChatMessageUtil;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridge to the "DropTracker Video Capture" companion plugin.
 *
 * All OpenGL/LWJGL frame capture lives in the companion plugin (installed
 * separately from the Plugin Hub). This class drives it over RuneLite's
 * {@link PluginMessage} event bus - see {@link VideoBridgeProtocol} - and
 * transparently falls back to a locally captured screenshot when the
 * companion is not installed, disabled, incompatible, or unresponsive.
 *
 * The public surface mirrors the old in-plugin VideoRecorder so callers
 * (SubmissionManager, DropTrackerPlugin) are unaffected by the split.
 */
@Slf4j
@Singleton
public class VideoCaptureBridge
{
    // 4 seconds post-event gives time for interfaces/celebrations to appear
    // (6 seconds pre-event + 4 seconds post-event = 10 seconds total)
    private static final int DEFAULT_POST_EVENT_MS = 4000;

    // Extra time allowed for the companion to finalize + encode before we
    // give up on a capture request and fall back to a screenshot.
    private static final int CAPTURE_TIMEOUT_GRACE_MS = 10000;

    // Delay after a hello probe before concluding the companion is absent.
    private static final int PRESENCE_CHECK_DELAY_MS = 2000;

    // Minimum time between "install the companion plugin" chat nudges.
    private static final long NUDGE_INTERVAL_MS = TimeUnit.MINUTES.toMillis(10);

    // Blur kernel for sensitive content protection (15x15 box blur for heavy blur)
    private static final int BLUR_RADIUS = 15;

    private final DropTrackerConfig config;
    private final Client client;
    private final DrawManager drawManager;
    private final EventBus eventBus;
    private final PluginManager pluginManager;
    private final ChatMessageUtil chatMessageUtil;

    private final ScheduledExecutorService scheduler;
    private final ExecutorService asyncWriter;

    /** Capture requests awaiting a capture-complete reply, keyed by requestId. */
    private final ConcurrentHashMap<String, VideoCallback> pendingCaptures = new ConcurrentHashMap<>();

    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicLong lastNudgeAt = new AtomicLong(0);

    private volatile boolean companionAlive = false;
    private volatile boolean companionCompatible = true;
    private volatile boolean companionRecording = false;
    private volatile boolean companionGpuUnavailable = false;

    @Inject
    public VideoCaptureBridge(
        DropTrackerConfig config,
        Client client,
        DrawManager drawManager,
        EventBus eventBus,
        PluginManager pluginManager,
        ChatMessageUtil chatMessageUtil)
    {
        this.config = config;
        this.client = client;
        this.drawManager = drawManager;
        this.eventBus = eventBus;
        this.pluginManager = pluginManager;
        this.chatMessageUtil = chatMessageUtil;

        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "DropTracker-VideoBridge-Scheduler");
            t.setDaemon(true);
            return t;
        });
        this.asyncWriter = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DropTracker-VideoBridge-Writer");
            t.setDaemon(true);
            return t;
        });
    }

    // ---- Lifecycle (called from DropTrackerPlugin) ----

    /**
     * Registers for bridge messages and, when video capture is configured,
     * probes for the companion plugin and asks it to start recording.
     */
    public void startUp()
    {
        if (registered.compareAndSet(false, true))
        {
            eventBus.register(this);
        }

        if (config.captureMode().requiresVideo())
        {
            engageCompanion(false);
        }
    }

    /**
     * Asks the companion to stop recording and stops listening for messages.
     * Pending captures are completed empty so callers are not left hanging.
     */
    public void shutDown()
    {
        // Unconditional: a stray stop with no companion listening is a no-op,
        // but a recording companion must not keep capturing after we detach.
        post(VideoBridgeProtocol.MSG_STOP, new HashMap<>());

        if (registered.compareAndSet(true, false))
        {
            eventBus.unregister(this);
        }

        pendingCaptures.forEach((id, callback) -> safeComplete(callback, null, null, 0));
        pendingCaptures.clear();
    }

    /**
     * Called when the captureMode config changes. Starts or stops companion
     * recording accordingly, nudging the user if the companion is missing.
     */
    public void onCaptureModeChanged()
    {
        if (config.captureMode().requiresVideo())
        {
            engageCompanion(true);
        }
        else
        {
            post(VideoBridgeProtocol.MSG_STOP, new HashMap<>());
        }
    }

    /**
     * @return true if the companion plugin is present, protocol-compatible and recording
     */
    public boolean isVideoCaptureActive()
    {
        return isCompanionUsable() && companionRecording;
    }

    /**
     * @return true if the companion reported that no GL context is available (GPU plugin off)
     */
    public boolean isGpuUnavailable()
    {
        return companionGpuUnavailable;
    }

    // ---- Capture API (mirrors the old VideoRecorder surface) ----

    /**
     * Triggers a video/screenshot capture for an event based on configured quality settings.
     * Uses default 4-second post-event duration to capture celebration animations.
     *
     * @param callback Called when capture is complete with screenshot bytes and optionally video frames
     */
    public void captureEventVideo(VideoCallback callback)
    {
        captureEventVideo(callback, DEFAULT_POST_EVENT_MS);
    }

    /**
     * Triggers a video/screenshot capture for an event based on configured quality settings.
     * Delegates to the companion plugin when available, otherwise captures a screenshot locally.
     *
     * @param callback Called when capture is complete with screenshot bytes and optionally video frames
     * @param postEventMs Duration in milliseconds to continue recording after the event
     */
    public void captureEventVideo(VideoCallback callback, int postEventMs)
    {
        if (!config.captureMode().requiresVideo())
        {
            captureScreenshotOnly(callback);
            return;
        }

        if (!isCompanionUsable())
        {
            // User wants video but the companion isn't available - remind them
            // (rate-limited) and degrade to a screenshot so the submission still goes out.
            nudgeCompanionMissing();
            captureScreenshotOnly(callback);
            return;
        }

        final String requestId = UUID.randomUUID().toString();
        pendingCaptures.put(requestId, callback);

        Map<String, Object> payload = new HashMap<>();
        payload.put(VideoBridgeProtocol.KEY_REQUEST_ID, requestId);
        payload.put(VideoBridgeProtocol.KEY_POST_EVENT_MS, postEventMs);
        post(VideoBridgeProtocol.MSG_CAPTURE, payload);

        // If the companion never replies (disabled mid-capture, crash, ...),
        // fall back to a local screenshot so the submission is not lost.
        scheduler.schedule(() -> {
            VideoCallback pending = pendingCaptures.remove(requestId);
            if (pending != null)
            {
                log.warn("Companion video capture timed out after {}ms; falling back to screenshot", postEventMs + CAPTURE_TIMEOUT_GRACE_MS);
                companionAlive = false;
                captureScreenshotOnly(pending);

                // Re-probe: if the companion is alive but was merely slow, its
                // ack restores the bridge for the next capture.
                Map<String, Object> hello = new HashMap<>();
                hello.put(VideoBridgeProtocol.KEY_PROTOCOL_VERSION, VideoBridgeProtocol.PROTOCOL_VERSION);
                post(VideoBridgeProtocol.MSG_HELLO, hello);
            }
        }, postEventMs + CAPTURE_TIMEOUT_GRACE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Captures a single screenshot locally (no companion involvement, no video).
     * Applies blur if sensitive content (like bank PIN) is visible.
     *
     * @param callback Called when screenshot capture is complete
     */
    public void captureScreenshotOnly(VideoCallback callback)
    {
        // Check for sensitive content before requesting the frame
        final boolean shouldBlur = isSensitiveContentVisible();

        drawManager.requestNextFrameListener(image -> {
            asyncWriter.submit(() -> {
                try
                {
                    BufferedImage screenshot = ImageUtil.bufferedImageFromImage(image);

                    // Apply blur if sensitive content was detected
                    if (shouldBlur)
                    {
                        screenshot = applyHeavyBlur(screenshot);
                    }

                    byte[] screenshotBytes = imageToBytes(screenshot);
                    safeComplete(callback, screenshotBytes, null, 0);
                }
                catch (Exception e)
                {
                    log.error("Failed to capture screenshot", e);
                    safeComplete(callback, null, null, 0);
                }
            });
        });
    }

    // ---- Companion messaging ----

    /**
     * Probes for the companion and asks it to (re)start recording with the
     * current quality settings.
     *
     * @param nudgeIfMissing when true, checks shortly afterwards whether the
     *                       companion answered and nudges the user if not
     */
    private void engageCompanion(boolean nudgeIfMissing)
    {
        Map<String, Object> hello = new HashMap<>();
        hello.put(VideoBridgeProtocol.KEY_PROTOCOL_VERSION, VideoBridgeProtocol.PROTOCOL_VERSION);
        post(VideoBridgeProtocol.MSG_HELLO, hello);

        // Posting start unconditionally is harmless: with no companion
        // subscribed the message is simply dropped.
        postStart();

        if (nudgeIfMissing)
        {
            scheduler.schedule(() -> {
                if (!isCompanionUsable())
                {
                    nudgeCompanionMissing();
                }
            }, PRESENCE_CHECK_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void postStart()
    {
        VideoQuality quality = config.captureMode();
        Map<String, Object> payload = new HashMap<>();
        payload.put(VideoBridgeProtocol.KEY_FPS, quality.getFps());
        payload.put(VideoBridgeProtocol.KEY_JPEG_QUALITY, quality.getJpegQuality());
        payload.put(VideoBridgeProtocol.KEY_DURATION_MS, quality.getDurationMs());
        post(VideoBridgeProtocol.MSG_START, payload);
    }

    private void post(String name, Map<String, Object> payload)
    {
        eventBus.post(new PluginMessage(VideoBridgeProtocol.NAMESPACE, name, payload));
    }

    @Subscribe
    public void onPluginMessage(PluginMessage message)
    {
        if (!VideoBridgeProtocol.NAMESPACE.equals(message.getNamespace()))
        {
            return;
        }

        Map<String, Object> data = message.getData();

        switch (message.getName())
        {
            case VideoBridgeProtocol.MSG_READY:
                handleCompanionPresent(data);
                // Companion (re)started while we're running - re-engage if video is configured
                if (companionCompatible && config.captureMode().requiresVideo())
                {
                    postStart();
                }
                break;
            case VideoBridgeProtocol.MSG_ACK:
                handleCompanionPresent(data);
                companionRecording = getBoolean(data, VideoBridgeProtocol.KEY_RECORDING, companionRecording);
                companionGpuUnavailable = getBoolean(data, VideoBridgeProtocol.KEY_GPU_UNAVAILABLE, companionGpuUnavailable);
                break;
            case VideoBridgeProtocol.MSG_STATE:
                companionRecording = getBoolean(data, VideoBridgeProtocol.KEY_RECORDING, false);
                companionGpuUnavailable = getBoolean(data, VideoBridgeProtocol.KEY_GPU_UNAVAILABLE, false);
                break;
            case VideoBridgeProtocol.MSG_BYE:
                companionAlive = false;
                companionRecording = false;
                break;
            case VideoBridgeProtocol.MSG_CAPTURE_COMPLETE:
                handleCaptureComplete(data);
                break;
            default:
                // Our own outbound messages (hello/start/stop/update/capture)
                // arrive here too since the namespace is shared - ignore them.
                break;
        }
    }

    private void handleCompanionPresent(Map<String, Object> data)
    {
        int protocolVersion = getInt(data, VideoBridgeProtocol.KEY_PROTOCOL_VERSION, -1);
        companionAlive = true;
        companionCompatible = protocolVersion == VideoBridgeProtocol.PROTOCOL_VERSION;

        if (!companionCompatible)
        {
            log.warn("Companion plugin protocol mismatch: ours={}, theirs={}",
                VideoBridgeProtocol.PROTOCOL_VERSION, protocolVersion);
            nudgeCompanionMissing();
        }
        else
        {
            log.debug("Companion video plugin detected (version {})",
                getString(data, VideoBridgeProtocol.KEY_PLUGIN_VERSION));
        }
    }

    private void handleCaptureComplete(Map<String, Object> data)
    {
        String requestId = getString(data, VideoBridgeProtocol.KEY_REQUEST_ID);
        if (requestId == null)
        {
            return;
        }

        VideoCallback callback = pendingCaptures.remove(requestId);
        if (callback == null)
        {
            // Already timed out and handled via screenshot fallback
            log.debug("Dropping late capture-complete for request {}", requestId);
            return;
        }

        byte[] screenshot = null;
        Object rawScreenshot = data.get(VideoBridgeProtocol.KEY_SCREENSHOT);
        if (rawScreenshot instanceof byte[])
        {
            screenshot = (byte[]) rawScreenshot;
        }

        List<byte[]> frames = null;
        Object rawFrames = data.get(VideoBridgeProtocol.KEY_FRAMES);
        if (rawFrames instanceof List)
        {
            @SuppressWarnings("unchecked")
            List<byte[]> cast = (List<byte[]>) rawFrames;
            frames = cast;
        }

        int fps = getInt(data, VideoBridgeProtocol.KEY_FPS, 0);

        safeComplete(callback, screenshot, frames, fps);
    }

    private boolean isCompanionUsable()
    {
        return companionAlive && companionCompatible;
    }

    private void safeComplete(VideoCallback callback, byte[] screenshot, List<byte[]> frames, int fps)
    {
        try
        {
            callback.onComplete(screenshot, frames, fps);
        }
        catch (Exception e)
        {
            log.error("Video capture callback failed", e);
        }
    }

    // ---- Companion install/enable nudge ----

    /**
     * Tells the user (at most once per {@link #NUDGE_INTERVAL_MS}) that video capture
     * needs the companion plugin, with wording matched to whether it is missing,
     * disabled, or outdated.
     */
    private void nudgeCompanionMissing()
    {
        long now = System.currentTimeMillis();
        long last = lastNudgeAt.get();
        if (now - last < NUDGE_INTERVAL_MS || !lastNudgeAt.compareAndSet(last, now))
        {
            return;
        }

        final String message;
        if (companionAlive && !companionCompatible)
        {
            message = "Your DropTracker and DropTracker Video Capture plugins are out of sync. "
                + "Update both plugins in the Plugin Hub to record video. Screenshots will be used until then.";
        }
        else if (isCompanionInstalled())
        {
            message = "Video capture requires the 'DropTracker Video Capture' plugin, which is installed but turned off. "
                + "Enable it in your plugin list to record video. Screenshots will be used until then.";
        }
        else
        {
            message = "Video capture requires the free 'DropTracker Video Capture' companion plugin. "
                + "Install it from the Plugin Hub to record video. Screenshots will be used until then.";
        }

        log.info("Companion nudge: {}", message);
        chatMessageUtil.sendChatMessage(message);
    }

    private boolean isCompanionInstalled()
    {
        try
        {
            for (Plugin plugin : pluginManager.getPlugins())
            {
                if (VideoBridgeProtocol.COMPANION_PLUGIN_NAME.equals(plugin.getName()))
                {
                    return true;
                }
            }
        }
        catch (Exception e)
        {
            log.debug("Failed to scan plugin list for companion", e);
        }
        return false;
    }

    // ---- Local screenshot support (no GL involved) ----

    /**
     * Checks if any sensitive content is currently visible that should be blurred.
     * This includes:
     * - Login screen (protects username/email input)
     * - Login screen authenticator (protects 2FA codes)
     * - Logging in state (transitional state during login)
     * - Bank PIN entry interface
     * - Bank PIN settings interface
     *
     * @return true if sensitive content is visible
     */
    private boolean isSensitiveContentVisible()
    {
        try
        {
            // Check for login screen states - blur any screen where user might enter credentials
            GameState gameState = client.getGameState();
            if (gameState == GameState.LOGIN_SCREEN ||
                gameState == GameState.LOGIN_SCREEN_AUTHENTICATOR ||
                gameState == GameState.LOGGING_IN)
            {
                return true;
            }

            // Check for Bank PIN interface (InterfaceID.BANKPIN_KEYPAD = 213)
            Widget bankPinWidget = client.getWidget(InterfaceID.BANKPIN_KEYPAD, 0);
            if (bankPinWidget != null && !bankPinWidget.isHidden())
            {
                return true;
            }

            // Check for Bank PIN settings interface (InterfaceID.BANKPIN_SETTINGS = 14)
            Widget bankPinSettingsWidget = client.getWidget(InterfaceID.BANKPIN_SETTINGS, 0);
            if (bankPinSettingsWidget != null && !bankPinSettingsWidget.isHidden())
            {
                return true;
            }
        }
        catch (Exception e)
        {
            // Silently ignore widget access errors
            log.debug("Error checking for sensitive content: {}", e.getMessage());
        }

        return false;
    }

    /**
     * Applies a heavy box blur to the image to obscure sensitive content.
     *
     * @param image The image to blur
     * @return A heavily blurred version of the image
     */
    private BufferedImage applyHeavyBlur(BufferedImage image)
    {
        int width = image.getWidth();
        int height = image.getHeight();

        // Create a copy to work with - use TYPE_INT_RGB for proper color handling
        BufferedImage blurred = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        // Copy original to blurred
        java.awt.Graphics2D g = blurred.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();

        // Apply multiple passes of box blur for heavy blur effect
        for (int pass = 0; pass < 3; pass++)
        {
            blurred = boxBlur(blurred, BLUR_RADIUS);
        }

        // Add a semi-transparent overlay to further obscure
        g = blurred.createGraphics();
        g.setColor(new java.awt.Color(0, 0, 0, 100)); // Semi-transparent black
        g.fillRect(0, 0, width, height);

        // Add warning text
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 24));
        String warningText = "SENSITIVE CONTENT HIDDEN";
        java.awt.FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(warningText);
        int textX = (width - textWidth) / 2;
        int textY = height / 2;
        g.drawString(warningText, textX, textY);
        g.dispose();

        return blurred;
    }

    /**
     * Simple box blur implementation.
     */
    private BufferedImage boxBlur(BufferedImage image, int radius)
    {
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        int[] pixels = new int[width * height];
        int[] result = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        // Horizontal pass
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                int r = 0, g = 0, b = 0, count = 0;

                for (int kx = -radius; kx <= radius; kx++)
                {
                    int px = Math.max(0, Math.min(width - 1, x + kx));
                    int pixel = pixels[y * width + px];
                    r += (pixel >> 16) & 0xFF;
                    g += (pixel >> 8) & 0xFF;
                    b += pixel & 0xFF;
                    count++;
                }

                result[y * width + x] = (0xFF << 24) | ((r / count) << 16) | ((g / count) << 8) | (b / count);
            }
        }

        // Vertical pass
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                int r = 0, g = 0, b = 0, count = 0;

                for (int ky = -radius; ky <= radius; ky++)
                {
                    int py = Math.max(0, Math.min(height - 1, y + ky));
                    int pixel = result[py * width + x];
                    r += (pixel >> 16) & 0xFF;
                    g += (pixel >> 8) & 0xFF;
                    b += pixel & 0xFF;
                    count++;
                }

                pixels[y * width + x] = (0xFF << 24) | ((r / count) << 16) | ((g / count) << 8) | (b / count);
            }
        }

        output.setRGB(0, 0, width, height, pixels, 0, width);
        return output;
    }

    /**
     * Converts a BufferedImage to JPEG byte array.
     */
    private byte[] imageToBytes(BufferedImage image) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", baos);
        return baos.toByteArray();
    }

    // ---- Defensive payload extraction (values cross plugin classloaders as plain Objects) ----

    private static int getInt(Map<String, Object> data, String key, int defaultValue)
    {
        Object value = data != null ? data.get(key) : null;
        return value instanceof Number ? ((Number) value).intValue() : defaultValue;
    }

    private static boolean getBoolean(Map<String, Object> data, String key, boolean defaultValue)
    {
        Object value = data != null ? data.get(key) : null;
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    private static String getString(Map<String, Object> data, String key)
    {
        Object value = data != null ? data.get(key) : null;
        return value instanceof String ? (String) value : null;
    }

    /**
     * Callback interface for video capture completion.
     */
    public interface VideoCallback
    {
        /**
         * Called when video capture is complete.
         *
         * @param screenshotBytes JPEG screenshot bytes, or null on failure
         * @param videoFrames List of JPEG frame bytes for the video, or null if screenshot-only
         * @param fps The frames per second the video was recorded at (0 if no video)
         */
        void onComplete(byte[] screenshotBytes, List<byte[]> videoFrames, int fps);
    }
}
