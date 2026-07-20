package io.droptracker.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Tests {@link NearbyPlayerTracker#raidTypeForSource}, which decides whether a
 * submission gets the authoritative raid roster attached or a plain proximity
 * scan. A false positive attaches a raid roster to unrelated content; a false
 * negative silently downgrades a raid submission to proximity guessing.
 */
public class NearbyPlayerTrackerRaidTypeTest {

    @Test
    public void tobVariantsMapToTob() {
        assertEquals("tob", NearbyPlayerTracker.raidTypeForSource("Theatre of Blood"));
        assertEquals("tob", NearbyPlayerTracker.raidTypeForSource("Theatre of Blood: Entry Mode"));
        assertEquals("tob", NearbyPlayerTracker.raidTypeForSource("Theatre of Blood: Hard Mode"));
    }

    @Test
    public void toaVariantsMapToToa() {
        assertEquals("toa", NearbyPlayerTracker.raidTypeForSource("Tombs of Amascut"));
        assertEquals("toa", NearbyPlayerTracker.raidTypeForSource("Tombs of Amascut: Entry Mode"));
        assertEquals("toa", NearbyPlayerTracker.raidTypeForSource("Tombs of Amascut: Expert Mode"));
    }

    @Test
    public void coxVariantsMapToCox() {
        assertEquals("cox", NearbyPlayerTracker.raidTypeForSource("Chambers of Xeric"));
        assertEquals("cox", NearbyPlayerTracker.raidTypeForSource("Chambers of Xeric Challenge Mode"));
        assertEquals("cox", NearbyPlayerTracker.raidTypeForSource("Chambers of Xeric: Challenge Mode"));
    }

    @Test
    public void matchingIsCaseInsensitive() {
        assertEquals("tob", NearbyPlayerTracker.raidTypeForSource("THEATRE OF BLOOD"));
        assertEquals("cox", NearbyPlayerTracker.raidTypeForSource("chambers of xeric"));
    }

    @Test
    public void nonRaidSourcesMapToNull() {
        assertNull(NearbyPlayerTracker.raidTypeForSource(null));
        assertNull(NearbyPlayerTracker.raidTypeForSource(""));
        assertNull(NearbyPlayerTracker.raidTypeForSource("Vorkath"));
        assertNull(NearbyPlayerTracker.raidTypeForSource("Zulrah"));
        assertNull(NearbyPlayerTracker.raidTypeForSource("Barrows Chests"));
        // Raid bosses reported as NPCs are not chest/completion submissions
        assertNull(NearbyPlayerTracker.raidTypeForSource("Verzik Vitur"));
        assertNull(NearbyPlayerTracker.raidTypeForSource("Great Olm"));
    }
}
