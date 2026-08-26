package io.droptracker.service;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/**
 * Tests {@link NearbyPlayerTracker#boundedRoster}, which keeps an accumulated
 * raid roster inside the party the raid can actually hold.
 *
 * <p>Plugin 6.0 started bracketing raid PBs by this roster, and the roster
 * accumulates across whatever the clear-on-entry lifecycle misses — ToB's state
 * varbit covers spectating as well as raiding — so Theatre of Blood times were
 * submitted as 6-, 7-, 8- and 9-player raids and the Hall of Fame grew boards
 * for team sizes the game cannot produce (suggestion #140). The same roster
 * decides who gets split credit, so the names matter as much as the count.
 */
public class NearbyPlayerTrackerRosterBoundTest {

    private static Set<String> setOf(String... names) {
        return new LinkedHashSet<>(Arrays.asList(names));
    }

    @Test
    public void capacityMatchesTheGamesOwnSlotCounts() {
        assertEquals(5, NearbyPlayerTracker.rosterCapacity(NearbyPlayerTracker.RAID_TOB));
        assertEquals(8, NearbyPlayerTracker.rosterCapacity(NearbyPlayerTracker.RAID_TOA));
        // Chambers of Xeric has no small ceiling — mass raids are legal.
        assertEquals(0, NearbyPlayerTracker.rosterCapacity(NearbyPlayerTracker.RAID_COX));
        assertEquals(0, NearbyPlayerTracker.rosterCapacity(null));
    }

    /** The regression: two runs' worth of names can no longer stack up. */
    @Test
    public void staleNamesNeverPushAToBRosterPastFive() {
        Set<String> thisRaid = setOf("Alpha", "Bravo", "Charlie");
        Set<String> lastRaid = setOf("Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India");

        Set<String> bounded = NearbyPlayerTracker.boundedRoster(thisRaid, lastRaid, 5);

        assertEquals(5, bounded.size());
        assertEquals(setOf("Alpha", "Bravo", "Charlie", "Delta", "Echo"), bounded);
    }

    @Test
    public void liveReadWinsOverHistory() {
        // Every name the game is publishing right now survives; history only
        // fills what is left, so a contaminated roster self-heals as soon as
        // the next raid's party appears.
        Set<String> live = setOf("Alpha", "Bravo", "Charlie", "Delta", "Echo");
        Set<String> history = setOf("Stale1", "Stale2", "Stale3");

        assertEquals(live, NearbyPlayerTracker.boundedRoster(live, history, 5));
    }

    /**
     * Why history is kept at all (issue #43): the authoritative sources go
     * quiet before the loot chest opens, so a chest-time read is empty.
     */
    @Test
    public void historyStillCarriesTheRosterWhenTheLiveReadIsEmpty() {
        Set<String> history = setOf("Alpha", "Bravo", "Charlie", "Delta");

        Set<String> bounded = NearbyPlayerTracker.boundedRoster(
            Collections.<String>emptySet(), history, 5);

        assertEquals(history, bounded);
    }

    @Test
    public void toaKeepsItsEightSlots() {
        Set<String> live = setOf("A", "B", "C", "D", "E", "F", "G", "H");
        Set<String> history = setOf("Stale");

        Set<String> bounded = NearbyPlayerTracker.boundedRoster(live, history, 8);

        assertEquals(8, bounded.size());
        assertEquals(live, bounded);
    }

    @Test
    public void unboundedRaidsAreLeftAlone() {
        Set<String> live = setOf("A", "B", "C");
        Set<String> history = setOf("D", "E", "F", "G", "H", "I", "J", "K", "L");

        Set<String> bounded = NearbyPlayerTracker.boundedRoster(live, history, 0);

        assertEquals(12, bounded.size());
    }

    @Test
    public void duplicateNamesAcrossSourcesCountOnce() {
        Set<String> live = setOf("Alpha", "Bravo");
        Set<String> history = setOf("Bravo", "Charlie");

        assertEquals(setOf("Alpha", "Bravo", "Charlie"),
            NearbyPlayerTracker.boundedRoster(live, history, 5));
    }

    @Test
    public void emptySourcesGiveAnEmptyRoster() {
        assertEquals(Collections.<String>emptySet(),
            NearbyPlayerTracker.boundedRoster(
                Collections.<String>emptySet(), Collections.<String>emptySet(), 5));
    }
}
