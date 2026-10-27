package io.droptracker.util;

import java.util.EnumSet;
import net.runelite.api.WorldType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The world_type tag the server routes submissions by. Anything not MAIN is
 * kept away from main-game data (api/routes/webhook.py).
 */
public class WorldTypesTest {

    @Test
    public void regularWorldsAreMain() {
        assertEquals("MAIN", WorldTypes.submissionName(EnumSet.noneOf(WorldType.class)));
        assertEquals("MAIN", WorldTypes.submissionName(EnumSet.of(WorldType.MEMBERS)));
        assertTrue(WorldTypes.isMainWorld(EnumSet.of(WorldType.MEMBERS, WorldType.SKILL_TOTAL)));
    }

    @Test
    public void pvpAndLmsWorldsPlayTheMainCharacter() {
        assertTrue(WorldTypes.isMainWorld(EnumSet.of(WorldType.MEMBERS, WorldType.PVP)));
        assertTrue(WorldTypes.isMainWorld(EnumSet.of(WorldType.MEMBERS, WorldType.PVP, WorldType.HIGH_RISK)));
        assertTrue(WorldTypes.isMainWorld(EnumSet.of(WorldType.LAST_MAN_STANDING)));
    }

    @Test
    public void hardcoreWorldsAreKeptApart() {
        assertEquals("HARDCORE_MAIN", WorldTypes.submissionName(EnumSet.of(WorldType.MEMBERS, WorldType.HARDCORE_MAIN)));
        assertFalse(WorldTypes.isMainWorld(EnumSet.of(WorldType.HARDCORE_MAIN)));
    }

    @Test
    public void betaWinsOverEverythingElse() {
        assertEquals("BETA_WORLD", WorldTypes.submissionName(EnumSet.of(WorldType.BETA_WORLD, WorldType.HARDCORE_MAIN)));
        assertEquals("BETA_WORLD", WorldTypes.submissionName(EnumSet.of(WorldType.BETA_WORLD, WorldType.SEASONAL)));
    }

    @Test
    public void temporaryWorldsAreNotMain() {
        assertEquals("SEASONAL", WorldTypes.submissionName(EnumSet.of(WorldType.MEMBERS, WorldType.SEASONAL)));
        assertEquals("DEADMAN", WorldTypes.submissionName(EnumSet.of(WorldType.DEADMAN, WorldType.SEASONAL)));
        assertFalse(WorldTypes.isMainWorld(EnumSet.of(WorldType.FRESH_START_WORLD)));
        assertFalse(WorldTypes.isMainWorld(EnumSet.of(WorldType.TOURNAMENT_WORLD)));
    }
}
