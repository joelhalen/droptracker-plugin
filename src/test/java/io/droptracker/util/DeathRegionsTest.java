package io.droptracker.util;

import io.droptracker.testing.RuneLiteStubs;
import net.runelite.api.Client;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the safe/dangerous split: the region policy directly, and
 * {@link DeathRegions#classify} with a stub client for the account type.
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
        assertTrue(DeathRegions.isSafeRegion(10588)); // PK tutor
        assertTrue(DeathRegions.isSafeRegion(11100)); // PK tutor
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

    private static Client account(int accountType) {
        Client client = RuneLiteStubs.stub(Client.class, "client");
        RuneLiteStubs.state(client).put("getVarbitValue", accountType);
        return client;
    }

    @Test
    public void hardcoreMinigameDeathsAreSafe() {
        // Neither items nor hardcore status are lost in a minigame.
        for (int hardcore : new int[] { 3, 5 }) {
            Client client = account(hardcore);
            assertEquals(DeathRegions.Danger.SAFE, DeathRegions.classify(client, 13658)); // LMS
            assertEquals(DeathRegions.Danger.SAFE, DeathRegions.classify(client, 10588)); // PK tutor
            assertEquals(DeathRegions.Danger.SAFE, DeathRegions.classify(client, 9520));  // Castle Wars
            assertEquals(DeathRegions.Danger.SAFE, DeathRegions.classify(client, 7257));  // POH
        }
    }

    @Test
    public void hardcoreRaidAndGauntletDeathsAreDangerous() {
        // The status is what a hardcore account loses here.
        Client client = account(3);
        assertEquals(DeathRegions.Danger.DANGEROUS, DeathRegions.classify(client, 12889)); // CoX
        assertEquals(DeathRegions.Danger.DANGEROUS, DeathRegions.classify(client, 7512));  // Gauntlet
        assertEquals(DeathRegions.Danger.DANGEROUS, DeathRegions.classify(client, 9023));  // Vorkath
    }

    @Test
    public void otherAccountsFollowTheRegion() {
        Client client = account(1);
        assertEquals(DeathRegions.Danger.SAFE, DeathRegions.classify(client, 12889));
        assertEquals(DeathRegions.Danger.SAFE, DeathRegions.classify(client, 13658));
        assertEquals(DeathRegions.Danger.DANGEROUS, DeathRegions.classify(client, 9023));
    }
}
