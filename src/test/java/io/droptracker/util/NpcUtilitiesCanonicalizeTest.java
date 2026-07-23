package io.droptracker.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Locks the boss-name canonicalisation and multi-path detection that drive
 * {@code DropHandler}'s cross-handler duplicate-loot suppression. A regression
 * here would either re-introduce the Grotesque Guardians / Maggot King double
 * submissions or (worse) start de-duplicating ordinary AoE multi-kills.
 */
public class NpcUtilitiesCanonicalizeTest {

    @Test
    public void remapsSubNpcsToEncounterName() {
        assertEquals("Grotesque Guardians", NpcUtilities.canonicalizeSpecialSource("Dusk"));
        assertEquals("Royal Titans", NpcUtilities.canonicalizeSpecialSource("Branda the Fire Queen"));
        assertEquals("Royal Titans", NpcUtilities.canonicalizeSpecialSource("Eldric the Ice King"));
        assertEquals("The Corrupted Gauntlet", NpcUtilities.canonicalizeSpecialSource("Corrupted Hunllef"));
        assertEquals("The Gauntlet", NpcUtilities.canonicalizeSpecialSource("Crystalline Hunllef"));
    }

    @Test
    public void leavesOtherNamesUnchangedAndIsIdempotent() {
        assertEquals("Maggot King", NpcUtilities.canonicalizeSpecialSource("Maggot King"));
        assertEquals("Zulrah", NpcUtilities.canonicalizeSpecialSource("Zulrah"));
        // Applying it to an already-canonical name must be a no-op (both loot
        // paths canonicalise, so double application happens in practice).
        assertEquals("Grotesque Guardians", NpcUtilities.canonicalizeSpecialSource("Grotesque Guardians"));
        assertNull(NpcUtilities.canonicalizeSpecialSource(null));
    }

    @Test
    public void multiPathSourcesAreExactlyTheDoublingBosses() {
        // Canonical encounter names that can arrive via >1 loot event -> dedup on.
        assertTrue(NpcUtilities.isMultiPathLootSource("Grotesque Guardians"));
        assertTrue(NpcUtilities.isMultiPathLootSource("Royal Titans"));
        assertTrue(NpcUtilities.isMultiPathLootSource("The Gauntlet"));
        assertTrue(NpcUtilities.isMultiPathLootSource("The Corrupted Gauntlet"));
        assertTrue(NpcUtilities.isMultiPathLootSource("Maggot King"));
        assertTrue(NpcUtilities.isMultiPathLootSource("Araxxor"));
        assertTrue(NpcUtilities.isMultiPathLootSource("The Whisperer"));
    }

    @Test
    public void ordinaryNpcsAreNotMultiPath() {
        // These fire one NpcLootReceived per death and are legitimately
        // multi-killed in a tick with identical loot; must NOT be de-duplicated.
        assertFalse(NpcUtilities.isMultiPathLootSource("Maniacal monkey"));
        assertFalse(NpcUtilities.isMultiPathLootSource("Hill Giant"));
        assertFalse(NpcUtilities.isMultiPathLootSource("Zulrah"));
        assertFalse(NpcUtilities.isMultiPathLootSource(null));
    }
}
