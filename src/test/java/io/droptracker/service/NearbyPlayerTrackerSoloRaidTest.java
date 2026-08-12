package io.droptracker.service;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link NearbyPlayerTracker#isSoloRaid}, which decides what an empty
 * authoritative roster means. A solo raid empties the roster exactly the way a
 * broken capture does, and the old code read both as breakage and guessed from
 * proximity + the RuneLite party — so a solo raider's submission was credited
 * to whoever was still in their party from an earlier session.
 *
 * <p>A false positive here drops real participants from a genuine team raid; a
 * false negative re-opens the guessing that credited non-participants.
 */
public class NearbyPlayerTrackerSoloRaidTest {

    /** The regression: solo raid, party size 1 sampled throughout. */
    @Test
    public void soloRaidWithSampledPartySizeIsSolo() {
        assertTrue(NearbyPlayerTracker.isSoloRaid(0, true, 1, true, true));
        // Capture never worked, but the game still said the party was 1.
        assertTrue(NearbyPlayerTracker.isSoloRaid(0, true, 1, false, false));
    }

    @Test
    public void teamSizeSeenDuringTheRaidRulesOutSolo() {
        // Varbits have reset by loot-chest time; only the high-water mark knows.
        assertFalse(NearbyPlayerTracker.isSoloRaid(0, true, 3, false, false));
        assertFalse(NearbyPlayerTracker.isSoloRaid(0, true, 2, true, true));
    }

    @Test
    public void liveTeamSizeRulesOutSolo() {
        assertFalse(NearbyPlayerTracker.isSoloRaid(2, true, 0, true, true));
        assertFalse(NearbyPlayerTracker.isSoloRaid(5, false, 0, false, true));
    }

    /**
     * Team size never sampled (plugin enabled mid-raid): a live source that
     * returned the local player proves the capture works, so an empty roster
     * really is empty.
     */
    @Test
    public void workingCaptureWithNobodyElseIsSolo() {
        assertTrue(NearbyPlayerTracker.isSoloRaid(0, true, 0, true, false));
        assertTrue(NearbyPlayerTracker.isSoloRaid(0, true, 0, false, true));
        assertTrue(NearbyPlayerTracker.isSoloRaid(0, false, 0, false, true));
    }

    /** No evidence either way: keep guessing rather than drop real teammates. */
    @Test
    public void noEvidenceIsNotSolo() {
        assertFalse(NearbyPlayerTracker.isSoloRaid(0, true, 0, false, false));
        assertFalse(NearbyPlayerTracker.isSoloRaid(0, false, 0, false, false));
    }

    /** Retained state from a *different* raid must not settle this one. */
    @Test
    public void staleRosterFromAnotherRaidIsIgnored() {
        // teamSizeMax=1 and captureWorked belong to the previous raid.
        assertFalse(NearbyPlayerTracker.isSoloRaid(0, false, 1, true, false));
    }
}
