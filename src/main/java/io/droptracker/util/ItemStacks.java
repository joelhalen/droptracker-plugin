package io.droptracker.util;

import net.runelite.client.game.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Helpers for working with loot bundles ({@link ItemStack} collections).
 * Shared by {@link io.droptracker.events.DropHandler} (embed building and
 * cross-handler dedup) and {@link io.droptracker.service.RaidLootDeduplicator}
 * (raid chest re-loot suppression), so every consumer derives the same
 * signature for the same bundle.
 */
public final class ItemStacks {

    private ItemStacks() {
    }

    @SuppressWarnings("deprecation")
    public static Collection<ItemStack> stack(Collection<ItemStack> items) {
        final List<ItemStack> list = new ArrayList<>();

        for (final ItemStack item : items) {
            int quantity = 0;
            for (final ItemStack i : list) {
                if (i.getId() == item.getId()) {
                    quantity = i.getQuantity();
                    list.remove(i);
                    break;
                }
            }
            if (quantity > 0) {
                list.add(new ItemStack(item.getId(), item.getQuantity() + quantity, item.getLocation()));
            } else {
                list.add(item);
            }
        }

        return list;
    }

    /**
     * Order-independent signature of a loot bundle (consolidated id:qty pairs),
     * used to recognise the same loot arriving via two different loot events.
     */
    public static String signature(Collection<ItemStack> items) {
        return stack(items).stream()
                .map(i -> i.getId() + ":" + i.getQuantity())
                .sorted()
                .collect(Collectors.joining(","));
    }
}
