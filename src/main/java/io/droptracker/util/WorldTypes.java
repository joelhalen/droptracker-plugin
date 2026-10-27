package io.droptracker.util;

import java.util.Set;
import net.runelite.api.WorldType;

/**
 * Which "world" a piece of data belongs to, as the server files it.
 *
 * <p>Main-game data and data from worlds with their own characters (Leagues,
 * Deadman, Hardcore Worlds, ...) must never be mixed: a fresh character's
 * drops, XP or collection log would otherwise land on the main-game account.
 */
public final class WorldTypes {

    public static final String MAIN = "MAIN";

    private WorldTypes() {
    }

    /**
     * "MAIN" for regular game worlds, or the specific WorldType name for
     * temporary/special worlds (e.g. "BETA_WORLD", "DEADMAN", "SEASONAL").
     * LAST_MAN_STANDING worlds are intentionally treated as MAIN because LMS is
     * played on rotating main-game worlds; PVP worlds are MAIN because they
     * play the main-game character.
     */
    public static String submissionName(Set<WorldType> worldType) {
        if (worldType.contains(WorldType.BETA_WORLD))         return "BETA_WORLD";
        // Hardcore Worlds (Nov 2026): fresh one-life characters with their own
        // economy, kept apart from main-game data like seasonal.
        if (worldType.contains(WorldType.HARDCORE_MAIN))      return "HARDCORE_MAIN";
        if (worldType.contains(WorldType.DEADMAN))            return "DEADMAN";
        if (worldType.contains(WorldType.FRESH_START_WORLD))  return "FRESH_START_WORLD";
        if (worldType.contains(WorldType.NOSAVE_MODE))        return "NOSAVE_MODE";
        if (worldType.contains(WorldType.PVP_ARENA))          return "PVP_ARENA";
        if (worldType.contains(WorldType.QUEST_SPEEDRUNNING)) return "QUEST_SPEEDRUNNING";
        if (worldType.contains(WorldType.SEASONAL))           return "SEASONAL";
        if (worldType.contains(WorldType.TOURNAMENT_WORLD))   return "TOURNAMENT_WORLD";
        return MAIN;
    }

    /** Whether data from this world belongs to the main-game character. */
    public static boolean isMainWorld(Set<WorldType> worldType) {
        return MAIN.equals(submissionName(worldType));
    }
}
