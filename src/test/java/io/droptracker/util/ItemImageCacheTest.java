package io.droptracker.util;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Before;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The panel's item icons must never land on the game thread in one burst:
 * sprite renders are paced per frame and callbacks run on the EDT.
 */
public class ItemImageCacheTest {
    /** Runs queued work one "frame" at a time; re-queues suppliers that return false. */
    private static final class FrameClientThread extends ClientThread {
        private ArrayDeque<BooleanSupplier> queue = new ArrayDeque<>();

        @Override
        public void invokeLater(Runnable r) {
            queue.add(() -> {
                r.run();
                return true;
            });
        }

        @Override
        public void invokeLater(BooleanSupplier r) {
            queue.add(r);
        }

        void frame() {
            ArrayDeque<BooleanSupplier> now = queue;
            queue = new ArrayDeque<>();
            for (BooleanSupplier r : now) {
                if (!r.getAsBoolean()) {
                    queue.add(r);
                }
            }
        }

        boolean idle() {
            return queue.isEmpty();
        }
    }

    private FrameClientThread clientThread;
    private AtomicInteger renders;
    private ItemImageCache cache;

    @Before
    public void setUp() {
        clientThread = new FrameClientThread();
        renders = new AtomicInteger();
        cache = new ItemImageCache(id -> {
            renders.incrementAndGet();
            AsyncBufferedImage image = new AsyncBufferedImage(clientThread, 36, 32,
                BufferedImage.TYPE_INT_ARGB);
            image.loaded();
            return image;
        }, clientThread);
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test
    public void rendersAtMostAFewSpritesPerFrame() {
        for (int id = 1; id <= 10; id++) {
            assertNull(cache.get(id, null));
        }
        assertEquals("nothing renders at request time", 0, renders.get());

        clientThread.frame();
        assertEquals(ItemImageCache.LOADS_PER_FRAME, renders.get());
        clientThread.frame();
        assertEquals(2 * ItemImageCache.LOADS_PER_FRAME, renders.get());
        clientThread.frame();
        assertEquals(10, renders.get());
        clientThread.frame(); // stores the last batch
        assertTrue(clientThread.idle());
    }

    @Test
    public void callbacksRunOnTheEdtAndHitsSkipTheLoader() throws Exception {
        List<Boolean> onEdt = new ArrayList<>();
        cache.get(42, image -> onEdt.add(SwingUtilities.isEventDispatchThread()));
        cache.get(42, image -> onEdt.add(SwingUtilities.isEventDispatchThread()));

        clientThread.frame(); // render
        clientThread.frame(); // onLoaded re-posts to the client thread, then stores
        flushEdt();

        assertEquals("one render for two requests", 1, renders.get());
        assertEquals(2, onEdt.size());
        assertTrue(onEdt.get(0) && onEdt.get(1));

        BufferedImage hit = cache.get(42, image -> onEdt.add(false));
        assertNotNull(hit);
        assertSame(hit, cache.get(42, null));
        assertEquals(1, renders.get());
        assertTrue("a hit schedules nothing on the client thread", clientThread.idle());
    }
}
