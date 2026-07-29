package io.droptracker.util;

import java.util.Arrays;
import java.util.Collections;

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

    /**
     * The published list layers over the compiled-in one; it never replaces it.
     * A backend that drops an id (or serves an older list than the build) must
     * not be able to switch off loot tracking for a boss this build already
     * knows about.
     */
    @Test
    public void publishedListAddsToCompiledInList() {
        NpcUtilities.setRemoteServerLootNpcIds(Arrays.asList(999001, 999002));
        assertTrue(NpcUtilities.isServerLootNpc(999001));
        assertTrue(NpcUtilities.isServerLootNpc(999002));
        // ...and everything compiled in still qualifies.
        assertTrue(NpcUtilities.isServerLootNpc(NpcUtilities.MAD_ANGEL_QUEST_B));
        assertFalse(NpcUtilities.isServerLootNpc(999003));
    }

    /**
     * A failed fetch returns null and an empty/garbage file parses to an empty
     * list. Either must leave the previous state untouched rather than wiping
     * it — otherwise an unreachable GitHub Pages would silently stop loot
     * tracking for every server-loot boss, which is the exact outage this
     * mechanism exists to prevent.
     */
    @Test
    public void failedOrEmptyFetchLeavesCompiledInListIntact() {
        NpcUtilities.setRemoteServerLootNpcIds(Collections.singletonList(999004));
        NpcUtilities.setRemoteServerLootNpcIds(null);
        assertTrue("null fetch must not clear the overlay", NpcUtilities.isServerLootNpc(999004));
        NpcUtilities.setRemoteServerLootNpcIds(Collections.emptyList());
        assertTrue("empty fetch must not clear the overlay", NpcUtilities.isServerLootNpc(999004));
        // The compiled-in ids survive regardless of what the backend serves.
        assertTrue(NpcUtilities.isServerLootNpc(NpcUtilities.MAD_ANGEL_POST_QUEST_A));
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
