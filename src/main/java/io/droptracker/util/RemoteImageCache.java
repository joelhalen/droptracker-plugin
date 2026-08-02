package io.droptracker.util;

import io.droptracker.api.DropTrackerUrls;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Tiny async cache for server-hosted icons (team pieces, NPC/skill task icons).
 *
 * <p>Keys are <em>relative paths</em> under {@code /img/}, e.g.
 * {@code "npcdb/2215.png"}, not URLs: the API is not permitted to tell the plugin
 * which host to contact. {@link DropTrackerUrls#image} anchors the path onto a
 * hardcoded base and rejects anything that tries to escape it, redirects are
 * disabled, and bytes go through ImageIO so a non-image body decodes to null and
 * renders nothing.
 */
@Slf4j
@Singleton
public class RemoteImageCache {
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_DIMENSION = 512;

    private final Map<String, BufferedImage> cache =
        Collections.synchronizedMap(new LinkedHashMap<String, BufferedImage>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                return size() > MAX_ENTRIES;
            }
        });
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();

    private final OkHttpClient httpClient;
    private final ScheduledExecutorService executor;

    @Inject
    public RemoteImageCache(OkHttpClient httpClient, ScheduledExecutorService executor) {
        // Redirects off: the base is hardcoded, and following one would hand the
        // choice of host back to the server.
        this.httpClient = httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
        this.executor = executor;
    }

    /**
     * Cached image for the given {@code /img/}-relative path, or null while it
     * loads (or when the path is absent, malformed, or not an image). Kicks off at
     * most one background fetch per path; {@code onLoaded} runs off-EDT after a
     * successful load so callers can repaint.
     */
    @Nullable
    public BufferedImage get(@Nullable String imagePath, @Nullable Runnable onLoaded) {
        HttpUrl url = DropTrackerUrls.image(imagePath);
        if (url == null) {
            return null;
        }
        final String key = imagePath;
        BufferedImage cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (inFlight.putIfAbsent(key, Boolean.TRUE) == null) {
            CompletableFuture.runAsync(() -> {
                try {
                    BufferedImage image = fetch(url);
                    if (image != null
                            && image.getWidth() <= MAX_DIMENSION
                            && image.getHeight() <= MAX_DIMENSION) {
                        cache.put(key, image);
                        if (onLoaded != null) {
                            onLoaded.run();
                        }
                    }
                } catch (Exception e) {
                    log.debug("icon fetch failed for {}: {}", key, e.getMessage());
                } finally {
                    inFlight.remove(key);
                }
            }, executor);
        }
        return null;
    }

    @Nullable
    private BufferedImage fetch(HttpUrl url) throws Exception {
        Request request = new Request.Builder().url(url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                return null;
            }
            // A non-image body (HTML error page, redirect target) decodes to null and renders nothing.
            return ImageIO.read(body.byteStream());
        }
    }
}
