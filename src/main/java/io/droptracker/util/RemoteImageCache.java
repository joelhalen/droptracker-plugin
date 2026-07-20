package io.droptracker.util;

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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Tiny async cache for server-hosted icons (team pieces, NPC/skill task
 * icons). Safety contract (EVENT_PLUGIN_NOTIFICATIONS_PLAN): only DropTracker
 * hosts are ever fetched, and bytes go through ImageIO — a non-image body
 * (HTML error page, redirect target) decodes to null and renders nothing.
 * Same pattern as the hub-approved lootboard fetch in PanelElements.
 */
@Slf4j
@Singleton
public class RemoteImageCache {
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_DIMENSION = 512;

    private static final Set<String> ALLOWED_HOSTS = Set.of(
        "droptracker.io", "www.droptracker.io", "api.droptracker.io");

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
        this.httpClient = httpClient;
        this.executor = executor;
    }

    public static boolean isAllowedUrl(@Nullable String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed != null && "https".equals(parsed.scheme())
            && ALLOWED_HOSTS.contains(parsed.host().toLowerCase());
    }

    /**
     * Cached image for the URL, or null while it loads (or when the URL is
     * not an allowed DropTracker host / not an image). Kicks off at most one
     * background fetch per URL; {@code onLoaded} runs off-EDT after a
     * successful load so callers can repaint.
     */
    @Nullable
    public BufferedImage get(@Nullable String url, @Nullable Runnable onLoaded) {
        if (!isAllowedUrl(url)) {
            return null;
        }
        BufferedImage cached = cache.get(url);
        if (cached != null) {
            return cached;
        }
        if (inFlight.putIfAbsent(url, Boolean.TRUE) == null) {
            CompletableFuture.runAsync(() -> {
                try {
                    BufferedImage image = fetch(url);
                    if (image != null
                            && image.getWidth() <= MAX_DIMENSION
                            && image.getHeight() <= MAX_DIMENSION) {
                        cache.put(url, image);
                        if (onLoaded != null) {
                            onLoaded.run();
                        }
                    }
                } catch (Exception e) {
                    log.debug("icon fetch failed for {}: {}", url, e.getMessage());
                } finally {
                    inFlight.remove(url);
                }
            }, executor);
        }
        return null;
    }

    @Nullable
    private BufferedImage fetch(String url) throws Exception {
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
