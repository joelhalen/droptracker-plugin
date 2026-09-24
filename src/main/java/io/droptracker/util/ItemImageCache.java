package io.droptracker.util;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * Item sprites for the side panel, without leaning on the game thread.
 *
 * <p>Two things about {@link ItemManager#getImage} make it a poor fit for a
 * panel that rebuilds on every event update. Its cache holds only 128
 * images, so a big bingo board evicts its own icons and every rebuild renders
 * them all again. And {@link AsyncBufferedImage#onLoaded} on an image that has
 * already loaded does not run the callback: it posts it to the client thread.
 * A rebuild used to queue one such callback per icon, image scaling and Swing
 * layout included, and the game thread ran the lot in a single frame. That is
 * the hitch players felt when an event pop-up landed mid-fight.
 *
 * <p>Here, loaded sprites stay in our own larger cache, new sprites are
 * rendered at most {@value #LOADS_PER_FRAME} per frame, and callbacks always
 * run on the EDT. The game thread only ever does the sprite render itself.
 */
@Singleton
public class ItemImageCache {
    private static final int MAX_ENTRIES = 512;
    static final int LOADS_PER_FRAME = 4;

    private final IntFunction<AsyncBufferedImage> loader;
    private final ClientThread clientThread;

    /** Access-ordered LRU. Guarded by {@code this}. */
    private final Map<Integer, BufferedImage> loaded =
        new LinkedHashMap<Integer, BufferedImage>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, BufferedImage> eldest) {
                return size() > MAX_ENTRIES;
            }
        };
    /** Callbacks per item still loading; a key here means it is queued or rendering. Guarded by {@code this}. */
    private final Map<Integer, List<Consumer<BufferedImage>>> waiting = new HashMap<>();
    /** Item ids not yet handed to the ItemManager. Guarded by {@code this}. */
    private final ArrayDeque<Integer> queue = new ArrayDeque<>();
    /** True while a drain is scheduled on the client thread. Guarded by {@code this}. */
    private boolean draining;

    @Inject
    public ItemImageCache(ItemManager itemManager, ClientThread clientThread) {
        this(itemManager::getImage, clientThread);
    }

    /** Test seam: ItemManager cannot be constructed outside the client. */
    ItemImageCache(IntFunction<AsyncBufferedImage> loader, ClientThread clientThread) {
        this.loader = loader;
        this.clientThread = clientThread;
    }

    /**
     * The sprite for {@code itemId} if it is already here, else null. On a
     * miss the sprite is queued, and {@code onReady} runs on the EDT once it
     * has been rendered. Callable from any thread.
     */
    @Nullable
    public BufferedImage get(int itemId, @Nullable Consumer<BufferedImage> onReady) {
        boolean schedule;
        synchronized (this) {
            BufferedImage image = loaded.get(itemId);
            if (image != null) {
                return image;
            }
            List<Consumer<BufferedImage>> callbacks = waiting.get(itemId);
            if (callbacks == null) {
                callbacks = new ArrayList<>(1);
                waiting.put(itemId, callbacks);
                queue.addLast(itemId);
            }
            if (onReady != null) {
                callbacks.add(onReady);
            }
            schedule = !draining;
            draining = true;
        }
        if (schedule) {
            clientThread.invokeLater(this::drain);
        }
        return null;
    }

    /**
     * Client thread: render up to {@value #LOADS_PER_FRAME} queued sprites.
     * Returning false asks the ClientThread to run this again next frame.
     */
    boolean drain() {
        for (int i = 0; i < LOADS_PER_FRAME; i++) {
            Integer itemId;
            synchronized (this) {
                itemId = queue.pollFirst();
                if (itemId == null) {
                    draining = false;
                    return true;
                }
            }
            // On the client thread this renders the sprite inline, unless it
            // was still in the ItemManager's own cache.
            AsyncBufferedImage image = loader.apply(itemId);
            final int id = itemId;
            image.onLoaded(() -> store(id, image));
        }
        synchronized (this) {
            if (queue.isEmpty()) {
                draining = false;
                return true;
            }
            return false;
        }
    }

    private void store(int itemId, BufferedImage image) {
        List<Consumer<BufferedImage>> callbacks;
        synchronized (this) {
            loaded.put(itemId, image);
            callbacks = waiting.remove(itemId);
        }
        if (callbacks == null || callbacks.isEmpty()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            for (Consumer<BufferedImage> callback : callbacks) {
                callback.accept(image);
            }
        });
    }
}
