package io.droptracker.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Tests the rule that decides which item id a collection log unlock is recorded
 * under. The cache walk itself needs a running client; the judgement does not,
 * and it is the judgement that has been getting this wrong.
 *
 * <p>An unlock arrives as a chat message carrying only the item's *name*, which
 * {@link io.droptracker.util.ItemIDSearch} resolves to the earliest item sharing
 * it. For a duplicated name that is the wrong item — the collection log's Coal
 * bag is 25627, the item cache answers 764 — and nothing errors: the slot simply
 * stays empty on the player's profile while a non-slot id sits in their item
 * map.
 */
public class CollectionLogSlotsTest {

    /** Coal bag, Pet snakeling, and both Graceful hoods. */
    private static final Set<Integer> SLOTS = new HashSet<>();
    private static final Map<String, Integer> BY_NAME = new HashMap<>();

    static {
        SLOTS.add(25627);
        SLOTS.add(12921);
        SLOTS.add(11850);
        SLOTS.add(21061);
        BY_NAME.put("coal bag", 25627);
        BY_NAME.put("pet snakeling", 12921);
        // Two slots, one name: unanswerable from a name alone.
        BY_NAME.put("graceful hood", null);
    }

    private static Integer resolve(Integer candidate, String name) {
        return CollectionLogSlots.resolve(candidate, name, SLOTS, BY_NAME);
    }

    @Test
    public void anIdThatIsAlreadyASlotIsKept() {
        assertEquals(Integer.valueOf(12921), resolve(12921, "Pet snakeling"));
    }

    @Test
    public void aSameNamedImpostorIsCorrectedToTheSlot() {
        // The whole point: 764 is a Coal bag, but not the log's Coal bag.
        assertEquals(Integer.valueOf(25627), resolve(764, "Coal bag"));
    }

    @Test
    public void anAmbiguousNameIsRefused() {
        // Rooftop Agility and the Hallowed Sepulchre both have a Graceful hood
        // slot, and the chat message does not say which was unlocked. Recording
        // either would be a coin flip; the next full read knows the answer.
        assertNull(resolve(30045, "Graceful hood"));
    }

    @Test
    public void anUnknownNameIsRefused() {
        // A game update adds slots before the cache read knows them. Better a
        // slot that fills on the next full read than a wrong id recorded now.
        assertNull(resolve(30805, "Dossier"));
    }

    @Test
    public void aNullCandidateStillResolvesByName() {
        // ItemIDSearch has not finished loading, or has no row for the name.
        assertEquals(Integer.valueOf(25627), resolve(null, "Coal bag"));
    }

    @Test
    public void nameMatchingIgnoresCaseAndPadding() {
        assertEquals(Integer.valueOf(25627), resolve(764, "  coal BAG "));
    }

    @Test
    public void anEmptyNameWithAnUnknownIdIsRefused() {
        assertNull(resolve(764, ""));
        assertNull(resolve(764, null));
    }

    @Test
    public void duplicateNamesArePoisonedRatherThanOverwritten() {
        Map<Integer, String> names = new HashMap<>();
        names.put(11850, "Graceful hood");
        names.put(21061, "Graceful hood");
        names.put(30045, "Graceful hood");
        names.put(25627, "Coal bag");

        Map<String, Integer> index = CollectionLogSlots.indexByName(names);
        assertEquals(Integer.valueOf(25627), index.get("coal bag"));
        // Present, but deliberately unanswerable — and a third sighting must
        // not resurrect it.
        assertEquals(true, index.containsKey("graceful hood"));
        assertNull(index.get("graceful hood"));
    }

    @Test
    public void unnamedSlotsAreSkipped() {
        // A cache miss names an item "null"; indexing that would let any unlock
        // the client could not name resolve to a random slot.
        Map<Integer, String> names = new HashMap<>();
        names.put(1, null);
        names.put(2, "");
        names.put(3, "null");
        assertEquals(0, CollectionLogSlots.indexByName(names).size());
    }
}
