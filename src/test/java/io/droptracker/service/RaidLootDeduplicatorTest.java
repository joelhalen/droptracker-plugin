package io.droptracker.service;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests the raid chest re-loot suppression: a player who opens the reward
 * chest in the loot room and later claims the same (unclaimed) loot from the
 * collection chest at the bank fires two identical loot events for one
 * completion — the second must be suppressed, but a NEW completion of the same
 * raid must always be accepted, even with an identical loot signature.
 */
public class RaidLootDeduplicatorTest {

    private static final long ACC = 4062539364958246995L;
    private static final String TOB_LOOT = "22446:1,565:500";

    private RaidLootDeduplicator dedup;

    @Before
    public void setUp() {
        // Client is only touched by the public wrappers; the testable core
        // takes the account hash explicitly.
        dedup = new RaidLootDeduplicator(null);
    }

    @Test
    public void firstChestOpenIsNotADuplicate() {
        assertFalse(dedup.isDuplicate(ACC, "Theatre of Blood", TOB_LOOT));
    }

    @Test
    public void reLootingTheSameBundleIsSuppressed() {
        assertFalse(dedup.isDuplicate(ACC, "Theatre of Blood", TOB_LOOT));
        assertTrue(dedup.isDuplicate(ACC, "Theatre of Blood", TOB_LOOT));
        // and it stays suppressed until the next completion
        assertTrue(dedup.isDuplicate(ACC, "Theatre of Blood", TOB_LOOT));
    }

    @Test
    public void nextCompletionReArmsEvenWithIdenticalLoot() {
        assertFalse(dedup.isDuplicate(ACC, "Theatre of Blood", TOB_LOOT));
        dedup.invalidate(ACC, "Theatre of Blood");
        assertFalse(dedup.isDuplicate(ACC, "Theatre of Blood", TOB_LOOT));
    }

    @Test
    public void completionMessageModeVariantReArmsTheBaseSource() {
        // Loot events carry the base raid name; the completion chat message
        // may carry a mode suffix. Both must fold to one dedup scope.
        assertFalse(dedup.isDuplicate(ACC, "Theatre of Blood", TOB_LOOT));
        dedup.invalidate(ACC, "Theatre of Blood: Hard Mode");
        assertFalse(dedup.isDuplicate(ACC, "Theatre of Blood", TOB_LOOT));
    }

    @Test
    public void differentBundleReplacesTheStoredSignature() {
        assertFalse(dedup.isDuplicate(ACC, "Theatre of Blood", TOB_LOOT));
        assertFalse(dedup.isDuplicate(ACC, "Theatre of Blood", "995:1000000"));
        // the older signature is no longer tracked
        assertFalse(dedup.isDuplicate(ACC, "Theatre of Blood", TOB_LOOT));
    }

    @Test
    public void raidsDoNotShareDedupScope() {
        assertFalse(dedup.isDuplicate(ACC, "Theatre of Blood", TOB_LOOT));
        assertFalse(dedup.isDuplicate(ACC, "Tombs of Amascut", TOB_LOOT));
        assertFalse(dedup.isDuplicate(ACC, "Chambers of Xeric", TOB_LOOT));
    }

    @Test
    public void accountsDoNotShareDedupScope() {
        assertFalse(dedup.isDuplicate(ACC, "Theatre of Blood", TOB_LOOT));
        assertFalse(dedup.isDuplicate(ACC + 1, "Theatre of Blood", TOB_LOOT));
    }

    @Test
    public void nonRaidSourcesAreNeverSuppressed() {
        assertFalse(dedup.isDuplicate(ACC, "Zulrah", TOB_LOOT));
        assertFalse(dedup.isDuplicate(ACC, "Zulrah", TOB_LOOT));
        assertFalse(dedup.isDuplicate(ACC, "Barrows", TOB_LOOT));
        assertFalse(dedup.isDuplicate(ACC, null, TOB_LOOT));
    }

    @Test
    public void raidFamilyFoldsModeVariants() {
        assertEquals("Theatre of Blood", RaidLootDeduplicator.raidFamily("Theatre of Blood"));
        assertEquals("Theatre of Blood", RaidLootDeduplicator.raidFamily("Theatre of Blood: Entry Mode"));
        assertEquals("Tombs of Amascut", RaidLootDeduplicator.raidFamily("Tombs of Amascut: Expert Mode"));
        assertEquals("Chambers of Xeric", RaidLootDeduplicator.raidFamily("Chambers of Xeric Challenge Mode"));
        assertNull(RaidLootDeduplicator.raidFamily("Zulrah"));
        assertNull(RaidLootDeduplicator.raidFamily(null));
    }
}
