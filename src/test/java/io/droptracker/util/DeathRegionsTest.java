package io.droptracker.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The PvP minigames killer attribution treats as player-vs-player. The
 * safe/dangerous policy lives on the server (tests/unit/test_death_facts.py).
 */
public class DeathRegionsTest {

    @Test
    public void pvpMinigamesAreRecognised() {
        assertTrue(DeathRegions.isPvpMinigame(13915)); // LMS
        assertTrue(DeathRegions.isPvpMinigame(9520));  // Castle Wars
        assertTrue(DeathRegions.isPvpMinigame(8493));  // Soul Wars
        assertTrue(DeathRegions.isPvpMinigame(13362)); // Emir's Arena
        assertTrue(DeathRegions.isPvpMinigame(12621)); // Clan Wars
        assertFalse(DeathRegions.isPvpMinigame(10588)); // PK tutor: NPC opponents
        assertFalse(DeathRegions.isPvpMinigame(12889));
    }
}
