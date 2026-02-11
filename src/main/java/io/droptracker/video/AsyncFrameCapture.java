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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Async PBO-based frame capture for GPU-accelerated screenshot readback.
 * Adapted from the osrs-tracker-plugin by Dennis De Vulder (BSD 2-Clause).
 *
 * Uses a small ring of Pixel Buffer Objects (PBOs) to avoid stalling the
 * client/GL thread during glReadPixels and to move memory copies off the GL thread.
 * The DMA transfer happens asynchronously:
 * - Frame N: issue glReadPixels into a free PBO slot (returns immediately, async DMA)
 * - Later frame: map a completed PBO, pass mapped buffer to async encoder thread
 * - On encoder completion: schedule GL-thread unmap and recycle the slot
 *
 * Client thread cost per frame stays low (buffer binds + async readPixels + map handoff)
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

    // Small PBO ring so mapped slots can stay in-flight while background threads process.
    private static final int PBO_COUNT = 4;
    private static final byte PBO_STATE_FREE = 0;
    private static final byte PBO_STATE_DMA_PENDING = 1;
    private static final byte PBO_STATE_MAPPED_IN_FLIGHT = 2;

    private final int[] pboIds = new int[PBO_COUNT];
    private final byte[] pboStates = new byte[PBO_COUNT];
    private final ConcurrentLinkedQueue<Integer> completedPboUnmaps = new ConcurrentLinkedQueue<>();
    private int writeSearchStartIndex = 0;

    // Dimensions tracking for resize detection
    private int lastWidth = 0;
    private int lastHeight = 0;

    // State
    private boolean pboInitialized = false;
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
        writeSearchStartIndex = 0;
        completedPboUnmaps.clear();
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

        // Schedule PBO cleanup on the GL thread once mapped in-flight buffers are drained.
        if (pboInitialized)
        {
            requestCleanupWhenIdle();
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
        drainCompletedUnmaps();

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
                    if (hasMappedPbosInFlight())
                    {
                        // Wait for writer thread to finish with mapped buffers before reallocating.
                        return;
                    }
                    cleanupPBOs();
                }
                initPBOs(width, height);
            }

            // Step 1: Map one completed DMA PBO and hand it to encoder thread.
            int readIndex = findPendingReadPbo();
            if (readIndex >= 0)
            {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[readIndex]);
                ByteBuffer mappedBuffer = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);

                if (mappedBuffer != null)
                {
                    pboStates[readIndex] = PBO_STATE_MAPPED_IN_FLIGHT;
                    ByteBuffer readOnlyPixels = mappedBuffer.asReadOnlyBuffer();
                    readOnlyPixels.rewind();
                    recorder.submitCapturedFrame(readOnlyPixels, lastWidth, lastHeight, () -> completedPboUnmaps.offer(readIndex));
                }
                else
                {
                    pboStates[readIndex] = PBO_STATE_FREE;
                }

                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            }

            // Step 2: Issue async glReadPixels into one free PBO slot.
            int writeIndex = findFreeWritePbo();
            if (writeIndex >= 0)
            {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[writeIndex]);
                GL11.glReadBuffer(GL11.GL_FRONT);
                GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                pboStates[writeIndex] = PBO_STATE_DMA_PENDING;
                writeSearchStartIndex = (writeIndex + 1) % PBO_COUNT;
            }
        }
        catch (Exception e)
        {
            log.error("Error in PBO frame capture", e);
        }
    }

    /**
     * Initializes PBO ring buffers for the given dimensions.
     */
    private void initPBOs(int width, int height)
    {
        int bufferSize = width * height * 4; // RGBA

        GL15.glGenBuffers(pboIds);

        for (int i = 0; i < PBO_COUNT; i++)
        {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[i]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, bufferSize, GL15.GL_STREAM_READ);
            pboStates[i] = PBO_STATE_FREE;
        }

        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);

        lastWidth = width;
        lastHeight = height;
        pboInitialized = true;

        log.debug("PBOs initialized: {}x{} (count={})", width, height, PBO_COUNT);
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
        for (int i = 0; i < PBO_COUNT; i++)
        {
            pboIds[i] = 0;
            pboStates[i] = PBO_STATE_FREE;
        }
        completedPboUnmaps.clear();
        pboInitialized = false;
        lastWidth = 0;
        lastHeight = 0;
    }

    /**
     * Drains completed encoder callbacks and unmaps PBOs on the GL thread.
     */
    private void drainCompletedUnmaps()
    {
        Integer pboIndex;
        while ((pboIndex = completedPboUnmaps.poll()) != null)
        {
            if (pboIndex < 0 || pboIndex >= PBO_COUNT || pboStates[pboIndex] != PBO_STATE_MAPPED_IN_FLIGHT)
            {
                continue;
            }

            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[pboIndex]);
            GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            pboStates[pboIndex] = PBO_STATE_FREE;
        }
    }

    private int findPendingReadPbo()
    {
        for (int i = 0; i < PBO_COUNT; i++)
        {
            if (pboStates[i] == PBO_STATE_DMA_PENDING)
            {
                return i;
            }
        }
        return -1;
    }

    private int findFreeWritePbo()
    {
        for (int offset = 0; offset < PBO_COUNT; offset++)
        {
            int idx = (writeSearchStartIndex + offset) % PBO_COUNT;
            if (pboStates[idx] == PBO_STATE_FREE)
            {
                return idx;
            }
        }
        return -1;
    }

    private boolean hasMappedPbosInFlight()
    {
        for (byte state : pboStates)
        {
            if (state == PBO_STATE_MAPPED_IN_FLIGHT)
            {
                return true;
            }
        }
        return false;
    }

    private void requestCleanupWhenIdle()
    {
        drawManager.requestNextFrameListener(image -> {
            drainCompletedUnmaps();
            if (hasMappedPbosInFlight())
            {
                requestCleanupWhenIdle();
                return;
            }
            cleanupPBOs();
        });
    }

}
