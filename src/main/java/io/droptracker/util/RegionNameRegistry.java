package io.droptracker.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves an OSRS map region id to a human-readable area name
 * ("Theatre of Blood", "Catacombs of Kourend") and a coarse area type.
 *
 * The data in {@code /io/droptracker/region_names.json} was extracted verbatim
 * from RuneLite's {@code DiscordGameEventType} enum (BSD 2-Clause, same licence
 * as this plugin), which maintains this mapping for Discord rich presence. That
 * enum is package-private so it cannot be imported; regenerate the resource by
 * re-parsing the enum constants that carry a {@code DiscordAreaType} and one or
 * more region ids.
 *
 * Region ids are only meaningful when they come from an instance-corrected
 * {@link net.runelite.api.coords.WorldPoint} — inside an instance the raw
 * {@code getWorldLocation()} returns template coordinates that resolve to the
 * wrong area (or none at all). See {@code DeathHandler#currentLocation}.
 */
@Slf4j
@Singleton
public final class RegionNameRegistry {

    private static final String RESOURCE_PATH = "/io/droptracker/region_names.json";

    /** A named area and the coarse bucket RuneLite files it under. */
    public static final class Area {
        private final String name;
        private final String type;

        Area(String name, String type) {
            this.name = name;
            this.type = type;
        }

        /** Human-readable area name, e.g. {@code "Theatre of Blood"}. */
        public String getName() {
            return name;
        }

        /**
         * One of {@code BOSSES}, {@code RAIDS}, {@code DUNGEONS}, {@code CITIES},
         * {@code MINIGAMES}, {@code REGIONS}.
         */
        public String getType() {
            return type;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Deserialization shape of the JSON resource. */
    private static final class AreaEntry {
        String name;
        String type;
        int[] regions;
    }

    private static final class AreaFile {
        List<AreaEntry> areas;
    }

    private final Map<Integer, Area> byRegion;

    @Inject
    public RegionNameRegistry(Gson gson) {
        this.byRegion = load(gson);
    }

    private static Map<Integer, Area> load(Gson gson) {
        try (InputStream is = RegionNameRegistry.class.getResourceAsStream(RESOURCE_PATH);
             Reader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(is), StandardCharsets.UTF_8))) {
            AreaFile file = gson.fromJson(reader, new TypeToken<AreaFile>() {}.getType());
            Objects.requireNonNull(file);
            Objects.requireNonNull(file.areas);

            Map<Integer, Area> map = new HashMap<>();
            for (AreaEntry entry : file.areas) {
                if (entry == null || entry.name == null || entry.regions == null) {
                    continue;
                }
                Area area = new Area(entry.name, entry.type);
                for (int region : entry.regions) {
                    map.put(region, area);
                }
            }
            return Collections.unmodifiableMap(map);
        } catch (Exception e) {
            // A missing/corrupt resource must not break death tracking — callers
            // degrade to reporting the bare region id.
            log.error("Failed to load region names from {}", RESOURCE_PATH, e);
            return Collections.emptyMap();
        }
    }

    /**
     * @param regionId an instance-corrected map region id
     * @return the area covering that region, or null when unmapped
     */
    @Nullable
    public Area lookup(int regionId) {
        return byRegion.get(regionId);
    }

    /**
     * @param regionId an instance-corrected map region id
     * @return the human-readable area name, or null when unmapped
     */
    @Nullable
    public String nameOf(int regionId) {
        Area area = byRegion.get(regionId);
        return area != null ? area.getName() : null;
    }

    /**
     * @param regionId an instance-corrected map region id
     * @return the coarse area type, or null when unmapped
     */
    @Nullable
    public String typeOf(int regionId) {
        Area area = byRegion.get(regionId);
        return area != null ? area.getType() : null;
    }

    /** Number of distinct region ids the registry can name. */
    public int size() {
        return byRegion.size();
    }
}
