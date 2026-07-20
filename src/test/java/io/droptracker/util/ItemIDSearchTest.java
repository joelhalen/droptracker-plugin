package io.droptracker.util;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Exercises the name to id inversion in {@link ItemIDSearch#populate}, the
 * pure core of the (network-backed) item cache. populate() is
 * {@code @VisibleForTesting} and does not touch the injected OkHttpClient/Gson,
 * so it can be driven directly without a live client.
 */
public class ItemIDSearchTest {

    @Test
    public void invertsNameToIdMapping() {
        ItemIDSearch search = new ItemIDSearch();
        Map<Integer, String> namesById = new HashMap<>();
        namesById.put(4151, "Abyssal whip");
        namesById.put(11802, "Armadyl godsword");

        search.populate(namesById, new HashSet<>());

        assertEquals(Integer.valueOf(4151), search.findItemId("Abyssal whip"));
        assertEquals(Integer.valueOf(11802), search.findItemId("Armadyl godsword"));
    }

    @Test
    public void skipsNotedItemIds() {
        ItemIDSearch search = new ItemIDSearch();
        Map<Integer, String> namesById = new HashMap<>();
        namesById.put(4151, "Abyssal whip");
        namesById.put(4152, "Abyssal whip"); // noted variant, same name

        Set<Integer> notedIds = new HashSet<>();
        notedIds.add(4152);

        search.populate(namesById, notedIds);

        assertEquals(Integer.valueOf(4151), search.findItemId("Abyssal whip"));
    }

    @Test
    public void keepsEarliestIdWhenNamesCollide() {
        ItemIDSearch search = new ItemIDSearch();
        // Insertion order preserved so the "earliest id wins" contract is deterministic.
        Map<Integer, String> namesById = new LinkedHashMap<>();
        namesById.put(1, "Coins");
        namesById.put(2, "Coins");
        namesById.put(3, "Coins");

        search.populate(namesById, new HashSet<>());

        assertEquals(Integer.valueOf(1), search.findItemId("Coins"));
    }

    @Test
    public void returnsNullForUnknownItem() {
        ItemIDSearch search = new ItemIDSearch();
        search.populate(new HashMap<>(), new HashSet<>());
        assertNull(search.findItemId("Twisted bow"));
    }
}
