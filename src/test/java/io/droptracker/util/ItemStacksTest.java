package io.droptracker.util;

import net.runelite.client.game.ItemStack;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Tests {@link ItemStacks#signature}: the raid chest re-loot dedup compares
 * the loot-room bundle against the bank collection chest bundle, so the
 * signature must be identical for the same items regardless of event order or
 * stack fragmentation, and must differ whenever ids or quantities differ.
 */
public class ItemStacksTest {

    @Test
    public void signatureIsOrderIndependent() {
        List<ItemStack> a = Arrays.asList(new ItemStack(565, 500), new ItemStack(22446, 1));
        List<ItemStack> b = Arrays.asList(new ItemStack(22446, 1), new ItemStack(565, 500));
        assertEquals(ItemStacks.signature(a), ItemStacks.signature(b));
    }

    @Test
    public void signatureConsolidatesSplitStacks() {
        List<ItemStack> split = Arrays.asList(new ItemStack(565, 300), new ItemStack(565, 200));
        List<ItemStack> whole = Collections.singletonList(new ItemStack(565, 500));
        assertEquals(ItemStacks.signature(whole), ItemStacks.signature(split));
    }

    @Test
    public void signatureDistinguishesQuantities() {
        String x = ItemStacks.signature(Collections.singletonList(new ItemStack(565, 500)));
        String y = ItemStacks.signature(Collections.singletonList(new ItemStack(565, 501)));
        assertEquals(false, x.equals(y));
    }

    @Test
    public void emptyBundleHasEmptySignature() {
        assertEquals("", ItemStacks.signature(Collections.emptyList()));
    }
}
