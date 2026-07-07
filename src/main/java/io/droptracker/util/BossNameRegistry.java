package io.droptracker.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Data-driven registry mapping adventure-log / chat short boss names to their
 * canonical long names. The mappings live in
 * {@code /io/droptracker/boss_names.json} and were extracted verbatim from the
 * former switch statement in {@code WidgetEventHandler.longBossName}.
 *
 * Dynamic behavior (the {@code " (echo)"} suffix recursion and the
 * unmapped-name fallback) intentionally remains in code rather than data.
 */
@Slf4j
@Singleton
public final class BossNameRegistry {

    private static final String RESOURCE_PATH = "/io/droptracker/boss_names.json";
    private static final String ECHO_SUFFIX = " (echo)";

    private final Map<String, String> mappings;

    @Inject
    public BossNameRegistry(Gson gson) {
        this.mappings = loadMappings(gson);
    }

    private static Map<String, String> loadMappings(Gson gson) {
        try (InputStream is = BossNameRegistry.class.getResourceAsStream(RESOURCE_PATH);
             Reader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(is), StandardCharsets.UTF_8))) {
            Map<String, String> mappings = gson.fromJson(reader,
                    new TypeToken<Map<String, String>>() {}.getType());
            return Collections.unmodifiableMap(Objects.requireNonNull(mappings));
        } catch (Exception e) {
            log.error("Failed to load boss name mappings from {}", RESOURCE_PATH, e);
            return Collections.emptyMap();
        }
    }

    /**
     * Resolves a short boss name to its canonical long name.
     * Names ending in {@code " (echo)"} (case-insensitive) are resolved by
     * recursing on the base name and re-appending {@code " (Echo)"}.
     *
     * @param shortName the short name to resolve
     * @return the canonical name, or the input unchanged when unmapped
     */
    public String canonicalName(String shortName) {
        return canonicalName(shortName, UnaryOperator.identity());
    }

    /**
     * Resolves a short boss name to its canonical long name, applying
     * {@code unmappedFallback} to the (possibly echo-stripped, lowercased)
     * name when no mapping exists. This mirrors the exact recursion order of
     * the original {@code longBossName} implementation.
     *
     * @param shortName        the short name to resolve
     * @param unmappedFallback transformation applied to unmapped names
     * @return the canonical name, or the fallback-transformed input when unmapped
     */
    public String canonicalName(String shortName, UnaryOperator<String> unmappedFallback) {
        String lowerBoss = shortName.toLowerCase();
        if (lowerBoss.endsWith(ECHO_SUFFIX)) {
            String actualBoss = lowerBoss.substring(0, lowerBoss.length() - ECHO_SUFFIX.length());
            return canonicalName(actualBoss, unmappedFallback) + " (Echo)";
        }

        String mapped = mappings.get(lowerBoss);
        return mapped != null ? mapped : unmappedFallback.apply(shortName);
    }

    /**
     * @return the number of short-name mappings loaded from the data file
     */
    public int size() {
        return mappings.size();
    }
}
