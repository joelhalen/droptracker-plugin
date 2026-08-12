package io.droptracker.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Tests {@link NearbyPlayerTracker#computeRaidPartySize}, the party size a
 * raid submission reports as evidence alongside its participant list. The
 * server splits credit against this number and treats 1 as proof of a solo
 * raid, so under-reporting drops real teammates' shares and over-reporting
 * dilutes everyone's cut.
 */
public class NearbyPlayerTrackerPartySizeTest {

    /** The incident shape: solo raid, no evidence of company anywhere. */
    @Test
    public void soloRaidReportsOne() {
        assertEquals(1, NearbyPlayerTracker.computeRaidPartySize(0, 0, 0));
        assertEquals(1, NearbyPlayerTracker.computeRaidPartySize(1, 1, 0));
    }

    /**
     * The live varbits reset at raid completion, so by loot-chest time only
     * the max accumulated during the raid still knows the team size.
     */
    @Test
    public void accumulatedMaxSurvivesTheVarbitReset() {
        assertEquals(3, NearbyPlayerTracker.computeRaidPartySize(0, 3, 2));
        assertEquals(5, NearbyPlayerTracker.computeRaidPartySize(0, 5, 4));
    }

    @Test
    public void liveReadWorksWhenAccumulationNeverRan() {
        // Plugin enabled mid-raid: nothing accumulated, varbits still live.
        assertEquals(4, NearbyPlayerTracker.computeRaidPartySize(4, 0, 3));
    }

    /**
     * A payload naming k others asserts a party of at least k+1; the reported
     * size can never contradict the roster it travels with. This mirrors the
     * server's split_size consistency rule.
     */
    @Test
    public void participantCountFloorsThePartySize() {
        assertEquals(3, NearbyPlayerTracker.computeRaidPartySize(0, 0, 2));
        // Roster accumulated a leaver the live count no longer shows.
        assertEquals(4, NearbyPlayerTracker.computeRaidPartySize(2, 3, 3));
    }

    @Test
    public void disagreeingSignalsTakeTheMax() {
        assertEquals(5, NearbyPlayerTracker.computeRaidPartySize(5, 3, 1));
        assertEquals(5, NearbyPlayerTracker.computeRaidPartySize(3, 5, 1));
        assertEquals(5, NearbyPlayerTracker.computeRaidPartySize(3, 1, 4));
    }
}
