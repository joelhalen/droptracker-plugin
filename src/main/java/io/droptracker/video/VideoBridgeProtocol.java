/*
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

/**
 * PluginMessage protocol shared between the DropTracker plugin and the
 * DropTracker Video Capture companion plugin.
 *
 * IMPORTANT: this file is duplicated in both repositories (hub plugins cannot
 * share code at compile time - they load in separate classloaders). Keep the
 * two copies in sync and bump PROTOCOL_VERSION on any breaking change.
 *
 * All payload values must be JDK types (String, Integer, Double, Boolean,
 * byte[], java.util.List, ...) so they can safely cross plugin classloaders.
 */
public final class VideoBridgeProtocol
{
    private VideoBridgeProtocol()
    {
    }

    /** Bump on any breaking change to message names or payload shapes. */
    public static final int PROTOCOL_VERSION = 1;

    /** Shared PluginMessage namespace for both directions. */
    public static final String NAMESPACE = "droptracker-video";

    // ---- Messages: main plugin -> companion ----

    /** Presence probe; companion replies with MSG_ACK. */
    public static final String MSG_HELLO = "hello";

    /** Start continuous recording. Payload: KEY_FPS, KEY_JPEG_QUALITY, KEY_DURATION_MS. */
    public static final String MSG_START = "start";

    /** Update capture settings while recording. Payload: KEY_FPS, KEY_JPEG_QUALITY, KEY_DURATION_MS. */
    public static final String MSG_UPDATE = "update";

    /** Stop recording and release the frame buffer. */
    public static final String MSG_STOP = "stop";

    /** Capture an event clip. Payload: KEY_REQUEST_ID, KEY_POST_EVENT_MS. */
    public static final String MSG_CAPTURE = "capture";

    // ---- Messages: companion -> main plugin ----

    /** Sent when the companion starts up. Payload: KEY_PROTOCOL_VERSION, KEY_PLUGIN_VERSION. */
    public static final String MSG_READY = "ready";

    /** Reply to MSG_HELLO. Payload: KEY_PROTOCOL_VERSION, KEY_PLUGIN_VERSION, KEY_RECORDING, KEY_GPU_UNAVAILABLE. */
    public static final String MSG_ACK = "ack";

    /** Recording state change. Payload: KEY_RECORDING, KEY_GPU_UNAVAILABLE. */
    public static final String MSG_STATE = "state";

    /** Clip result. Payload: KEY_REQUEST_ID, KEY_SCREENSHOT, KEY_FRAMES, KEY_FPS. */
    public static final String MSG_CAPTURE_COMPLETE = "capture-complete";

    /** Sent when the companion shuts down. */
    public static final String MSG_BYE = "bye";

    // ---- Payload keys ----

    public static final String KEY_PROTOCOL_VERSION = "protocolVersion";
    public static final String KEY_PLUGIN_VERSION = "pluginVersion";
    public static final String KEY_FPS = "fps";
    public static final String KEY_JPEG_QUALITY = "jpegQuality";
    public static final String KEY_DURATION_MS = "durationMs";
    public static final String KEY_POST_EVENT_MS = "postEventMs";
    public static final String KEY_REQUEST_ID = "requestId";
    public static final String KEY_RECORDING = "recording";
    public static final String KEY_GPU_UNAVAILABLE = "gpuUnavailable";
    /** byte[] JPEG screenshot, may be null on capture failure. */
    public static final String KEY_SCREENSHOT = "screenshot";
    /** java.util.List of byte[] JPEG frames, null when no video was captured. */
    public static final String KEY_FRAMES = "frames";

    /** Display name of the companion plugin (matches its @PluginDescriptor). */
    public static final String COMPANION_PLUGIN_NAME = "DropTracker Video Capture";
}
