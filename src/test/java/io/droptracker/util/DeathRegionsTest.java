package io.droptracker.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the account-independent half of the safe/dangerous split. The rest of
 * {@link DeathRegions#classify} needs a live client (account type, Pest Control
 * status overlay) and is not exercised here.
 */
public class DeathRegionsTest {

    @Test
    public void minigameDeathsAreSafe() {
        assertTrue(DeathRegions.isSafeRegion(9033));  // Nightmare Zone
        assertTrue(DeathRegions.isSafeRegion(10536)); // Pest Control lander
        assertTrue(DeathRegions.isSafeRegion(9520));  // Castle Wars
        assertTrue(DeathRegions.isSafeRegion(7508));  // Barbarian Assault
        assertTrue(DeathRegions.isSafeRegion(8493));  // Soul Wars
        assertTrue(DeathRegions.isSafeRegion(9552));  // TzHaar Fight Pit
        assertTrue(DeathRegions.isSafeRegion(13462)); // MTA creature graveyard
        assertTrue(DeathRegions.isSafeRegion(6997));  // Clan hall
        assertTrue(DeathRegions.isSafeRegion(13658)); // Last Man Standing
    }

    @Test
    public void playerOwnedHouseDeathsAreSafe() {
        assertTrue(DeathRegions.isSafeRegion(7257));
        assertTrue(DeathRegions.isSafeRegion(8303));
    }

    @Test
    public void raidDeathsAreSafe() {
        // Nothing is carried in or out, so a wipe costs the run, not the bank.
        assertTrue(DeathRegions.isSafeRegion(12889)); // Chambers of Xeric
        assertTrue(DeathRegions.isSafeRegion(12867)); // Theatre of Blood
        assertTrue(DeathRegions.isSafeRegion(14160)); // Tombs of Amascut
        assertTrue(DeathRegions.isSafeRegion(7512));  // The Gauntlet
        assertTrue(DeathRegions.isSafeRegion(7768));  // Corrupted Gauntlet
    }

    @Test
    public void infernoAndFightCaveAreNotTreatedAsSafe() {
        // Technically safe, but losing the run is the whole reason to announce
        // it — these are deliberately excluded from the safe sets.
        assertFalse(DeathRegions.isSafeRegion(DeathRegions.INFERNO));
        assertFalse(DeathRegions.isSafeRegion(DeathRegions.TZHAAR_FIGHT_CAVE));
    }

    @Test
    public void ordinaryOverworldDeathsAreDangerous() {
        assertFalse(DeathRegions.isSafeRegion(12850)); // Lumbridge
        assertFalse(DeathRegions.isSafeRegion(12598)); // Grand Exchange
        assertFalse(DeathRegions.isSafeRegion(9023));  // Vorkath
        assertFalse(DeathRegions.isSafeRegion(6557));  // Catacombs of Kourend
        assertFalse(DeathRegions.isSafeRegion(0));
    }
}
