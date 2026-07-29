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

    /**
     * Mad Angel (Wyrmscraig, 2026-07-29) awards loot server-side. Until its ids
     * were listed here, {@code DropTrackerPlugin.onServerNpcLoot} returned early
     * and every drop was discarded — kills still registered (PBs landed) so the
     * gap was invisible from the outside. Every in-game variant must be covered,
     * or the quest kill (or the repeatable one) silently stops tracking loot.
     */
    @Test
    public void madAngelVariantsAreServerLootSources() {
        assertTrue(NpcUtilities.SERVER_LOOT_NPC_IDS.contains(NpcUtilities.MAD_ANGEL_QUEST_A));
        assertTrue(NpcUtilities.SERVER_LOOT_NPC_IDS.contains(NpcUtilities.MAD_ANGEL_QUEST_B));
        assertTrue(NpcUtilities.SERVER_LOOT_NPC_IDS.contains(NpcUtilities.MAD_ANGEL_POST_QUEST_A));
        assertTrue(NpcUtilities.SERVER_LOOT_NPC_IDS.contains(NpcUtilities.MAD_ANGEL_POST_QUEST_B));
    }

    /**
     * Mad Angel loot arrives on exactly one path (ServerNpcLoot), so it must not
     * be treated as multi-path: that dedup key is item-signature based, and a
     * boss with a 100% common drop would have back-to-back identical kills
     * suppressed as "duplicates".
     */
    @Test
    public void madAngelIsNotMultiPath() {
        assertFalse(NpcUtilities.isMultiPathLootSource("Mad Angel"));
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
