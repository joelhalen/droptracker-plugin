package io.droptracker.events;

import net.runelite.client.game.ItemStack;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the MTA purchase-detection helpers exposed via
 * {@code @VisibleForTesting}: the inventory diff that identifies purchased
 * items and the pizazz-point decrease check that arms the purchase window.
 */
public class MtaHandlerDiffTest {

    private static final int MAGES_BOOK = 6889;
    private static final int COINS = 995;
    private static final int RUNE_POUCH = 12791;

    private static Map<Integer, Integer> inv(int... idCountPairs) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i < idCountPairs.length; i += 2) {
            counts.put(idCountPairs[i], idCountPairs[i + 1]);
        }
        return counts;
    }

    @Test
    public void detectsSinglePurchase() {
        List<ItemStack> gains = MtaHandler.diffGains(
            inv(COINS, 1000),
            inv(COINS, 1000, MAGES_BOOK, 1));
        assertEquals(1, gains.size());
        assertEquals(MAGES_BOOK, gains.get(0).getId());
        assertEquals(1, gains.get(0).getQuantity());
    }

    @Test
    public void detectsMultiBuyQuantity() {
        // Make-5 on a stackable reward arrives as one container change
        List<ItemStack> gains = MtaHandler.diffGains(
            inv(RUNE_POUCH, 1),
            inv(RUNE_POUCH, 6));
        assertEquals(1, gains.size());
        assertEquals(5, gains.get(0).getQuantity());
    }

    @Test
    public void noChangeYieldsNothing() {
        assertTrue(MtaHandler.diffGains(inv(COINS, 500), inv(COINS, 500)).isEmpty());
    }

    @Test
    public void decreasesAreNotGains() {
        // Dropping/consuming items with the shop open must not look like a purchase
        assertTrue(MtaHandler.diffGains(inv(COINS, 500), inv(COINS, 100)).isEmpty());
        assertTrue(MtaHandler.diffGains(inv(MAGES_BOOK, 1), inv()).isEmpty());
    }

    @Test
    public void newItemAndStackIncreaseBothDetected() {
        List<ItemStack> gains = MtaHandler.diffGains(
            inv(COINS, 100),
            inv(COINS, 150, MAGES_BOOK, 1));
        assertEquals(2, gains.size());
    }

    @Test
    public void pointDecreaseArms() {
        assertTrue(MtaHandler.anyDecrease(new int[]{500, 550, 6000, 500}, new int[]{0, 0, 0, 0}));
        assertTrue(MtaHandler.anyDecrease(new int[]{100, 100, 100, 100}, new int[]{100, 99, 100, 100}));
    }

    @Test
    public void pointGainOrNoChangeDoesNotArm() {
        // Earning points with the shop open only refreshes the baseline
        assertFalse(MtaHandler.anyDecrease(new int[]{100, 100, 100, 100}, new int[]{100, 100, 100, 100}));
        assertFalse(MtaHandler.anyDecrease(new int[]{100, 100, 100, 100}, new int[]{120, 100, 130, 100}));
    }
}
