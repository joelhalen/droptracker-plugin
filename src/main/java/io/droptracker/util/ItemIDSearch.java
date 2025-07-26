package io.droptracker.util;


import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.Reader;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * This thread-safe singleton holds a mapping of item names to their item id, using the RuneLite API.
 * <p>
 * Unlike {@link net.runelite.client.game.ItemManager#search(String)}, this mapping supports untradable items.
 *
 * Author: iProdigy ( https://github.com/pajlads/DinkPlugin )
 */

@Slf4j
@Singleton
public class ItemIDSearch {
    final String ITEM_CACHE_BASE_URL = "https://static.runelite.net/cache/item/";
    private final Map<String, Integer> itemIdByName = Collections.synchronizedMap(new HashMap<>(16384));
    private @Inject OkHttpClient httpClient;
    private @Inject Gson gson;

    /**
     * @param name the exact in-game name of an item
     * @return the id associated with the item name, or null if not found
     */
    @Nullable
    public Integer findItemId(@NotNull String name) {
        return itemIdByName.get(name);
    }

    /**
     * Begins the initialization process for {@link #itemIdByName}
     * by querying item names and noted item ids from the RuneLite API,
     * before passing them to {@link #populate(Map, Set)}
     *
     * @implNote This operation does not block the current thread,
     * by utilizing OkHttp's thread pool and Java's Fork-Join common pool.
     */
    @Inject
    void init() {
        queryNamesById()
                .thenAcceptBothAsync(
                        queryNotedItemIds().exceptionally(e -> {
                            log.error("Failed to read noted items", e);
                            return Collections.emptySet();
                        }),
                        this::populate
                )
                .exceptionally(e -> {
                    log.error("Failed to read item names", e);
                    return null;
                });
    }

    /**
     * Populates {@link #itemIdByName} with the inverted mappings of {@code namesById},
     * while skipping noted items specified in {@code notedIds}.
     *
     * @param namesById a mapping of item id's to the corresponding in-game name
     * @param notedIds  the id's of noted items
     * @implNote When multiple non-noted item id's have the same in-game name, only the earliest id is saved
     */
    @VisibleForTesting
    void populate(@NotNull Map<Integer, String> namesById, @NotNull Set<Integer> notedIds) {
        namesById.forEach((id, name) -> {
            if (!notedIds.contains(id))
                itemIdByName.putIfAbsent(name, id);
        });

        log.debug("Completed initialization of item cache with {} entries", itemIdByName.size());
    }

    /**
     * @return a mapping of item ids to their in-game names, provided by the RuneLite API
     */
    private CompletableFuture<Map<Integer, String>> queryNamesById() {
        // Read JSON as Map<String, String> first
        return queryCache("names.json", Map.class)
                .thenApply(stringMap -> {
                    Map<Integer, String> result = new HashMap<>();
                    for (Object keyObj : stringMap.keySet()) {
                        String keyStr = (String) keyObj;
                        Object valObj = stringMap.get(keyStr);
                        // Defensive: valObj might be String or something else
                        String valueStr = valObj != null ? valObj.toString() : null;
                        result.put(Integer.parseInt(keyStr), valueStr);
                    }
                    return result;
                });

    }

    /**
     * @return a set of id's of noted items, provided by the RuneLite API
     */
    private CompletableFuture<Set<Integer>> queryNotedItemIds() {
        return queryCache("notes.json", Map.class)
                .thenApply(stringMap -> {
                    Set<Integer> keys = new HashSet<>();
                    for (Object keyObj : stringMap.keySet()) {
                        keys.add(Integer.parseInt((String) keyObj));
                    }
                    return keys;
                });

    }

    /**
     * @param fileName the name of the file to query from RuneLite's cache
     * @param type     a type token that indicates how the json response should be parsed
     * @return the transformed cache response, wrapped in a future
     */
    private <T> CompletableFuture<T> queryCache(@NotNull String fileName, @NotNull Class<T> clazz) {
        return readJson(httpClient, gson, ITEM_CACHE_BASE_URL + fileName, clazz);
    }

    public <T> CompletableFuture<T> readJson(@NotNull OkHttpClient httpClient, @NotNull Gson gson, @NotNull String url, @NotNull Class<T> clazz) {
        return readUrl(httpClient, url, reader -> gson.fromJson(reader, clazz));
    }

    public <T> CompletableFuture<T> readUrl(@NotNull OkHttpClient httpClient, @NotNull String url, @NotNull Function<Reader, T> transformer) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Request request = new Request.Builder().url(url).build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) {
                assert response.body() != null;
                try (Reader reader = response.body().charStream()) {
                    future.complete(transformer.apply(reader));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                } finally {
                    response.close();
                }
            }
        });
        return future;
    }
    public class IntStringEntry {
        public int key;
        public String value;
    }
    public class IntIntEntry {
        public int key;
        public int value;
    }


}
