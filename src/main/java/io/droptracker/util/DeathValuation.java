package io.droptracker.util;

import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Prayer;
import net.runelite.api.SkullIcon;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;
import org.jetbrains.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Values what a death actually cost: the GE value of the items the player
 * failed to protect.
 *
 * <p>This exists so a group can filter on it server-side — "don't announce
 * deaths under 1M" is the single most requested death filter, and it is the one
 * thing the server could not previously answer because the plugin never sent a
 * value. The algorithm mirrors Dink's {@code DeathNotifier#splitItemsByKept},
 * which is the most complete public treatment of items-kept-on-death.
 *
 * <h2>What is counted</h2>
 * Inventory and worn equipment only, exactly as Dink counts them. Containers
 * carried inside the inventory (rune pouch, looting bag, seed box) are not
 * opened; their contents are lost on death but the game does not expose them
 * as carried items, and guessing would be worse than the documented omission.
 *
 * <h2>What is approximated</h2>
 * The real items-kept-on-death rules have per-item exceptions ("never kept"
 * items, quest items, deposited items) that Dink models with a hand-maintained
 * id list. That list is not ported here: almost every entry on it has no GE
 * price, and since keep slots are handed out most-expensive-first, a zero-value
 * item sorts last and never takes a slot from something that matters. The one
 * exception that does move the number is the bond, which is special-cased
 * below. Treat the result as a good estimate, not an audit.
 */
public final class DeathValuation {

    /** Items kept on an unskulled, non-UIM death. */
    private static final int UNSKULLED_KEEP_COUNT = 3;

    /** Account-type varbit value for an Ultimate Ironman, who keeps nothing. */
    private static final int ACCOUNT_TYPE_ULTIMATE_IRONMAN = 2;

    private DeathValuation() {
    }

    /** What a death cost, in GP. */
    public static final class Valuation {

        private final long lostValue;
        private final long keptValue;
        private final int lostItemCount;

        @VisibleForTesting
        Valuation(long lostValue, long keptValue, int lostItemCount) {
            this.lostValue = lostValue;
            this.keptValue = keptValue;
            this.lostItemCount = lostItemCount;
        }

        /** Total GE value of everything that dropped. Zero on a safe death. */
        public long getLostValue() {
            return lostValue;
        }

        /** Total GE value of everything that came back with the player. */
        public long getKeptValue() {
            return keptValue;
        }

        /** Number of item stacks lost, for the "and 12 other items" case. */
        public int getLostItemCount() {
            return lostItemCount;
        }
    }

    /** A carried stack reduced to the three things the split cares about. */
    @VisibleForTesting
    static final class PricedItem {

        private final int id;
        private final int quantity;
        private final long unitPrice;

        @VisibleForTesting
        PricedItem(int id, int quantity, long unitPrice) {
            this.id = id;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
        }
    }

    /**
     * Values the local player's death.
     *
     * <p>Must be called on the client thread, and while the inventory still
     * reflects the death — the containers are emptied shortly afterwards, so a
     * deferred call values an empty inventory at zero.
     *
     * @param safeDeath whether this location costs no items (see
     *                  {@link DeathRegions}); a safe death loses nothing, so
     *                  the whole split is skipped
     * @return the valuation, or {@code null} when it cannot be established
     *         (no client, no item manager) — callers must omit the field
     *         rather than send a zero that reads as "died with nothing"
     */
    public static Valuation value(Client client, ItemManager itemManager, boolean safeDeath) {
        if (client == null || itemManager == null) {
            return null;
        }

        List<PricedItem> carried = pricedItems(client, itemManager);

        // Nothing is at risk in a safe area, so everything carried is kept.
        // Short-circuited rather than run through the split because the keep
        // count is meaningless here: a skulled player in Castle Wars still
        // walks out with their whole inventory.
        if (safeDeath) {
            long kept = 0;
            for (PricedItem item : carried) {
                kept += item.unitPrice * item.quantity;
            }
            return new Valuation(0L, kept, 0);
        }

        return split(carried, keepCount(client));
    }

    /**
     * Every item the player is carrying or wearing, priced and sorted most
     * valuable first — the order the game hands out protect slots in.
     */
    private static List<PricedItem> pricedItems(Client client, ItemManager itemManager) {
        List<PricedItem> items = new ArrayList<>();
        for (int containerId : new int[] { InventoryID.INV, InventoryID.WORN }) {
            ItemContainer container = client.getItemContainer(containerId);
            if (container == null) {
                continue;
            }
            Item[] contents = container.getItems();
            if (contents == null) {
                continue;
            }
            for (Item item : contents) {
                // id -1 is an empty slot, not an item worth 0gp.
                if (item == null || item.getId() < 0 || item.getQuantity() <= 0) {
                    continue;
                }
                items.add(new PricedItem(item.getId(), item.getQuantity(),
                        unitPrice(itemManager, item.getId())));
            }
        }
        items.sort(Comparator.comparingLong((PricedItem item) -> item.unitPrice).reversed());
        return items;
    }

    /**
     * One item's value.
     *
     * <p>{@code getItemPrice} already resolves noted and worn variants back to
     * the tradeable id, so nothing needs canonicalizing first. Untradeables
     * come back as 0 and fall through to the store price, which is what the
     * game itself ranks them by when deciding what to protect.
     */
    private static long unitPrice(ItemManager itemManager, int itemId) {
        try {
            int price = itemManager.getItemPrice(itemId);
            if (price > 0) {
                return price;
            }
            return Math.max(0, itemManager.getItemComposition(itemId).getPrice());
        } catch (RuntimeException e) {
            // An unknown id must not cost us the whole submission.
            return 0L;
        }
    }

    /**
     * How many items this player would keep on an unsafe death.
     *
     * <p>Must be called on the client thread.
     */
    @VisibleForTesting
    static int keepCount(Client client) {
        if (client.getVarbitValue(VarbitID.IRONMAN) == ACCOUNT_TYPE_ULTIMATE_IRONMAN) {
            return 0;
        }

        boolean skulled = client.getLocalPlayer() != null
                && client.getLocalPlayer().getSkullIcon() != SkullIcon.NONE;
        int keepCount = skulled ? 0 : UNSKULLED_KEEP_COUNT;

        // Ruinous Powers is a separate prayer book with its own varbit, so the
        // standard Protect Item alone does not answer the question.
        if (client.isPrayerActive(Prayer.PROTECT_ITEM) || client.isPrayerActive(Prayer.RP_PROTECT_ITEM)) {
            keepCount++;
        }
        return keepCount;
    }

    /**
     * Splits priced items into kept and lost, handing the {@code keepCount}
     * protect slots to the most valuable items first.
     *
     * <p>Slots are per *item*, not per stack: a player carrying 100 sharks with
     * one slot left keeps one shark and drops 99. Kept separate from the client
     * reads so the arithmetic can be tested without a running game.
     *
     * @param itemsByPrice carried items, most valuable unit price first
     */
    @VisibleForTesting
    static Valuation split(List<PricedItem> itemsByPrice, int keepCount) {
        long lost = 0;
        long kept = 0;
        int lostStacks = 0;
        int slotsUsed = 0;

        for (PricedItem item : itemsByPrice) {
            // A bond always comes back and never occupies a protect slot. This
            // is the one never-kept-style exception worth modelling: it is
            // valuable enough to otherwise soak up a slot that belongs to real
            // gear, which would overstate the loss by millions.
            if (isBond(item.id)) {
                kept += item.unitPrice * item.quantity;
                continue;
            }

            int protectedUnits = Math.min(item.quantity, Math.max(0, keepCount - slotsUsed));
            slotsUsed += protectedUnits;
            kept += item.unitPrice * protectedUnits;

            int droppedUnits = item.quantity - protectedUnits;
            if (droppedUnits > 0) {
                lost += item.unitPrice * droppedUnits;
                lostStacks++;
            }
        }

        return new Valuation(lost, kept, lostStacks);
    }

    private static boolean isBond(int itemId) {
        return itemId == ItemID.OSRS_BOND
                || itemId == ItemID.BOUGHT_OSRS_BOND
                || itemId == ItemID.OSRS_BOND_UNTRADEABLE;
    }

    /**
     * Test seam: priced items in the most-valuable-first order {@link #split}
     * expects, from {@code {id, quantity, unitPrice}} rows.
     */
    @VisibleForTesting
    static List<PricedItem> priced(int[]... idQuantityPrice) {
        List<PricedItem> items = new ArrayList<>();
        for (int[] row : idQuantityPrice) {
            items.add(new PricedItem(row[0], row[1], row[2]));
        }
        items.sort(Comparator.comparingLong((PricedItem item) -> item.unitPrice).reversed());
        return items;
    }
}
