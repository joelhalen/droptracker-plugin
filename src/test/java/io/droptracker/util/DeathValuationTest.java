package io.droptracker.util;

import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Covers the items-kept-on-death arithmetic. The rest of
 * {@link DeathValuation#value} needs a live client (account type, skull, active
 * prayers, item prices) and is not exercised here.
 *
 * <p>Rows are {@code {itemId, quantity, unitPrice}}; {@link DeathValuation#priced}
 * sorts them the way the real collection does, so the tests can list items in
 * whatever order reads best.
 */
public class DeathValuationTest {

    /** An item id with no special handling, so the split treats it normally. */
    private static final int PLAIN = 4151;

    private static DeathValuation.Valuation split(int keepCount, int[]... rows) {
        List<DeathValuation.PricedItem> items = DeathValuation.priced(rows);
        return DeathValuation.split(items, keepCount);
    }

    @Test
    public void protectsTheThreeMostValuableItems() {
        // 3 keep slots go to the 3 most expensive singles; the 10k one drops.
        DeathValuation.Valuation v = split(3,
                new int[] { PLAIN, 1, 1_000_000 },
                new int[] { PLAIN, 1, 500_000 },
                new int[] { PLAIN, 1, 100_000 },
                new int[] { PLAIN, 1, 10_000 });

        assertEquals(10_000L, v.getLostValue());
        assertEquals(1_600_000L, v.getKeptValue());
        assertEquals(1, v.getLostItemCount());
    }

    @Test
    public void keepSlotsAreConsumedPerItemNotPerStack() {
        // One slot left and 100 sharks: the player keeps a single shark and
        // drops the other 99. A per-stack rule would protect all 100.
        DeathValuation.Valuation v = split(1,
                new int[] { PLAIN, 100, 1_000 });

        assertEquals(99_000L, v.getLostValue());
        assertEquals(1_000L, v.getKeptValue());
        assertEquals(1, v.getLostItemCount());
    }

    @Test
    public void skulledPlayerKeepsNothing() {
        DeathValuation.Valuation v = split(0,
                new int[] { PLAIN, 1, 1_000_000 },
                new int[] { PLAIN, 5, 2_000 });

        assertEquals(1_010_000L, v.getLostValue());
        assertEquals(0L, v.getKeptValue());
        assertEquals(2, v.getLostItemCount());
    }

    @Test
    public void bondIsKeptWithoutConsumingASlot() {
        // The bond is the most valuable thing carried, so without the special
        // case it would soak up a protect slot and the twisted bow would drop.
        DeathValuation.Valuation v = split(1,
                new int[] { ItemID.OSRS_BOND, 1, 8_000_000 },
                new int[] { PLAIN, 1, 1_000_000 },
                new int[] { PLAIN, 1, 5_000 });

        assertEquals(5_000L, v.getLostValue());
        assertEquals(9_000_000L, v.getKeptValue());
        assertEquals(1, v.getLostItemCount());
    }

    @Test
    public void allBondVariantsAreRecognised() {
        for (int bond : new int[] { ItemID.OSRS_BOND, ItemID.BOUGHT_OSRS_BOND, ItemID.OSRS_BOND_UNTRADEABLE }) {
            DeathValuation.Valuation v = split(0, new int[] { bond, 1, 8_000_000 });
            assertEquals("bond variant " + bond + " should never be lost", 0L, v.getLostValue());
            assertEquals(8_000_000L, v.getKeptValue());
        }
    }

    @Test
    public void moreKeepSlotsThanItemsLosesNothing() {
        DeathValuation.Valuation v = split(4,
                new int[] { PLAIN, 1, 1_000 },
                new int[] { PLAIN, 1, 500 });

        assertEquals(0L, v.getLostValue());
        assertEquals(1_500L, v.getKeptValue());
        assertEquals(0, v.getLostItemCount());
    }

    @Test
    public void emptyInventoryIsWorthNothing() {
        DeathValuation.Valuation v = split(3);

        assertEquals(0L, v.getLostValue());
        assertEquals(0L, v.getKeptValue());
        assertEquals(0, v.getLostItemCount());
    }

    @Test
    public void worthlessItemsDoNotWasteProtectSlots() {
        // Zero-value items sort last, so the 4 keep slots land on the gear
        // rather than on a pile of bones.
        DeathValuation.Valuation v = split(4,
                new int[] { PLAIN, 20, 0 },
                new int[] { PLAIN, 1, 2_000_000 },
                new int[] { PLAIN, 1, 1_000_000 },
                new int[] { PLAIN, 1, 750_000 },
                new int[] { PLAIN, 1, 500_000 },
                new int[] { PLAIN, 1, 250_000 });

        assertEquals(250_000L, v.getLostValue());
        assertEquals(4_250_000L, v.getKeptValue());
        // The 250k item and the bones stack both dropped.
        assertEquals(2, v.getLostItemCount());
    }

    @Test
    public void partiallyProtectedStackCountsAsOneLostStack() {
        DeathValuation.Valuation v = split(2,
                new int[] { PLAIN, 3, 100_000 });

        assertEquals(100_000L, v.getLostValue());
        assertEquals(200_000L, v.getKeptValue());
        assertEquals(1, v.getLostItemCount());
    }

    @Test
    public void valuesDoNotOverflowOnAMaxedStack() {
        // 2.1B coins at 1gp each overflows an int; the sums are long for this.
        DeathValuation.Valuation v = split(0,
                new int[] { PLAIN, Integer.MAX_VALUE, 1 });

        assertEquals((long) Integer.MAX_VALUE, v.getLostValue());
    }
}
