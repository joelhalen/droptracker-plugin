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

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.DrawManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Async PBO-based frame capture for GPU-accelerated screenshot readback.
 * Adapted from the osrs-tracker-plugin by Dennis De Vulder (BSD 2-Clause).
 *
 * Uses double-buffered Pixel Buffer Objects (PBOs) to avoid stalling the
 * client/GL thread during glReadPixels. The DMA transfer happens asynchronously:
 * - Frame N: issue glReadPixels into PBO[current] (returns immediately, async DMA)
 * - Frame N+1: map PBO[prev] and read back pixels (DMA already complete, no stall)
 *
 * Client thread cost per frame: ~0.1ms (two buffer binds + one async readPixels)
 * vs ~13-28ms with synchronous screenshot() calls.
 *
 * When no GL context is available (GPU plugin disabled), notifies VideoRecorder
 * which stops recording and falls back to screenshot-only mode for events.
 */
@Slf4j
public class AsyncFrameCapture
{
    private final DrawManager drawManager;
    private final VideoRecorder recorder;
    private final Runnable onPboUnsupported;

    // Double-buffered PBOs
    private final int[] pboIds = new int[2];
    private int pboIndex = 0;

    // Dimensions tracking for resize detection
    private int lastWidth = 0;
    private int lastHeight = 0;

    // State
    private boolean pboInitialized = false;
    private boolean firstFrame = true;
    private volatile boolean pboUnsupported = false;
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    // Frame listener reference for unregistration
    private Runnable everyFrameListener;

    // Time tracking for frame rate control - uses fixed-rate advancement
    // to prevent drift when game FPS doesn't evenly divide capture FPS
    private long nextCaptureTimeNs = 0;

    /**
     * @param drawManager The RuneLite DrawManager for frame listeners
     * @param recorder The VideoRecorder to submit encoded frames to
     * @param onPboUnsupported Callback invoked (on GL thread) when PBO is detected as unsupported,
     *                         allowing VideoRecorder to fall back to screenshot-only mode
     */
    public AsyncFrameCapture(DrawManager drawManager, VideoRecorder recorder, Runnable onPboUnsupported)
    {
        this.drawManager = drawManager;
        this.recorder = recorder;
        this.onPboUnsupported = onPboUnsupported;
    }

    /**
     * Starts async PBO capture by registering an every-frame listener.
     * The listener runs on the GL thread inside processDrawComplete(), after swapBuffers.
     */
    public void start()
    {
        if (everyFrameListener != null)
        {
            return;
        }

        shutdownRequested.set(false);
        pboUnsupported = false;
        firstFrame = true;
        pboIndex = 0;
        nextCaptureTimeNs = 0;

        everyFrameListener = this::onFrame;
        drawManager.registerEveryFrameListener(everyFrameListener);
        log.debug("PBO double-buffered capture registered");
    }

    /**
     * Stops async PBO capture. Unregisters the listener and schedules
     * PBO cleanup on the GL thread via requestNextFrameListener.
     */
    public void stop()
    {
        shutdownRequested.set(true);

        if (everyFrameListener != null)
        {
            drawManager.unregisterEveryFrameListener(everyFrameListener);
            everyFrameListener = null;
        }

        // Schedule PBO cleanup on the GL thread
        if (pboInitialized)
        {
            drawManager.requestNextFrameListener(image -> {
                cleanupPBOs();
            });
        }

        log.debug("PBO capture stopped");
    }

    /**
     * @return true if PBO was detected as unsupported (no GL context, no GL 2.1, etc.)
     */
    public boolean isPboUnsupported()
    {
        return pboUnsupported;
    }

    /**
     * Called every frame on the GL thread. Handles time gating, PBO ping-pong,
     * and submitting readback data to the VideoRecorder for async encoding.
     */
    private void onFrame()
    {
        if (shutdownRequested.get() || !recorder.isCurrentlyRecording())
        {
            return;
        }

        if (pboUnsupported)
        {
            return;
        }

        // Time gating: fixed-rate advancement prevents drift.
        long nowNs = System.nanoTime();
        int captureFps = recorder.getCaptureFps();
        if (captureFps <= 0)
        {
            return;
        }

        long frameIntervalNs = 1_000_000_000L / captureFps;
        if (nextCaptureTimeNs == 0)
        {
            nextCaptureTimeNs = nowNs;
        }
        if (nowNs < nextCaptureTimeNs)
        {
            return;
        }
        nextCaptureTimeNs += frameIntervalNs;
        if (nowNs - nextCaptureTimeNs > frameIntervalNs)
        {
            nextCaptureTimeNs = nowNs + frameIntervalNs;
        }

        if (!recorder.canAcceptFrame())
        {
            return;
        }

        try
        {
            GLCapabilities caps;
            try
            {
                caps = GL.getCapabilities();
            }
            catch (IllegalStateException e)
            {
                pboUnsupported = true;
                log.debug("No OpenGL context available, triggering fallback");
                onPboUnsupported.run();
                return;
            }

            if (caps == null || !caps.OpenGL21)
            {
                pboUnsupported = true;
                log.debug("OpenGL 2.1 not available, triggering fallback");
                onPboUnsupported.run();
                return;
            }

            int width, height;
            try (MemoryStack stack = MemoryStack.stackPush())
            {
                IntBuffer viewport = stack.mallocInt(4);
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
                width = viewport.get(2);
                height = viewport.get(3);
            }

            if (width <= 0 || height <= 0)
            {
                return;
            }

            // Lazy init or resize PBOs
            if (!pboInitialized || width != lastWidth || height != lastHeight)
            {
                if (pboInitialized)
                {
                    cleanupPBOs();
                }
                initPBOs(width, height);
                firstFrame = true;
            }

            int prevIndex = 1 - pboIndex;
            int currentIndex = pboIndex;

            // Step 1: Read back previous frame's PBO (DMA already complete)
            if (!firstFrame)
            {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[prevIndex]);
                ByteBuffer mappedBuffer = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);

                if (mappedBuffer != null)
                {
                    int dataSize = lastWidth * lastHeight * 4;
                    byte[] pixelCopy = new byte[dataSize];
                    mappedBuffer.rewind();
                    mappedBuffer.get(pixelCopy);
                    GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);

                    ByteBuffer pixelData = ByteBuffer.wrap(pixelCopy);
                    recorder.submitCapturedFrame(pixelData, lastWidth, lastHeight);
                }
                else
                {
                    GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                }
            }

            // Step 2: Issue async glReadPixels into current PBO (returns immediately)
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[currentIndex]);
            GL11.glReadBuffer(GL11.GL_FRONT);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0);

            // Unbind PBO to avoid interfering with RuneLite's rendering
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);

            // Swap ping-pong index
            pboIndex = prevIndex;
            firstFrame = false;
        }
        catch (Exception e)
        {
            log.error("Error in PBO frame capture", e);
        }
    }

    /**
     * Initializes double-buffered PBOs for the given dimensions.
     */
    private void initPBOs(int width, int height)
    {
        int bufferSize = width * height * 4; // RGBA

        GL15.glGenBuffers(pboIds);

        for (int i = 0; i < 2; i++)
        {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[i]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, bufferSize, GL15.GL_STREAM_READ);
        }

        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);

        lastWidth = width;
        lastHeight = height;
        pboInitialized = true;

        log.debug("PBOs initialized: {}x{}", width, height);
    }

    /**
     * Cleans up PBO resources. Must be called on the GL thread.
     */
    private void cleanupPBOs()
    {
        if (!pboInitialized)
        {
            return;
        }

        GL15.glDeleteBuffers(pboIds);
        pboIds[0] = 0;
        pboIds[1] = 0;
        pboInitialized = false;
        lastWidth = 0;
        lastHeight = 0;
    }
}
