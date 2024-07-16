package io.droptracker.util;

import com.google.common.collect.ImmutableSet;
import io.droptracker.models.CustomWebhookBody;
import lombok.experimental.UtilityClass;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.util.QuantityFormatter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.runelite.api.ItemID.*;

@UtilityClass
public class ItemUtilities {

    final String ITEM_CACHE_BASE_URL = "https://static.runelite.net/cache/item/";

    public final Collection<Integer> COIN_VARIATIONS = new HashSet<>(ItemVariationMapping.getVariations(ItemID.COINS));

    private final Set<Integer> NEVER_KEPT_ITEMS = ImmutableSet.of(
            CLUE_BOX, LOOTING_BAG, FLAMTAER_BAG, JAR_GENERATOR,
            AMULET_OF_THE_DAMNED, RING_OF_CHAROS, RING_OF_CHAROSA,
            BRACELET_OF_ETHEREUM, BRACELET_OF_ETHEREUM_UNCHARGED,
            AVAS_ACCUMULATOR, AVAS_ATTRACTOR, MAGIC_SECATEURS, MAGIC_BUTTERFLY_NET,
            COOKING_GAUNTLETS, GOLDSMITH_GAUNTLETS, CHAOS_GAUNTLETS, STEEL_GAUNTLETS,
            SILLY_JESTER_HAT, SILLY_JESTER_TOP, SILLY_JESTER_TIGHTS, SILLY_JESTER_BOOTS,
            LUNAR_HELM, LUNAR_TORSO, LUNAR_LEGS, LUNAR_GLOVES, LUNAR_BOOTS,
            LUNAR_CAPE, LUNAR_AMULET, LUNAR_RING, LUNAR_STAFF,
            SHATTERED_RELICS_ADAMANT_TROPHY, SHATTERED_RELICS_BRONZE_TROPHY, SHATTERED_RELICS_DRAGON_TROPHY,
            SHATTERED_RELICS_IRON_TROPHY, SHATTERED_RELICS_MITHRIL_TROPHY, SHATTERED_RELICS_RUNE_TROPHY, SHATTERED_RELICS_STEEL_TROPHY,
            TRAILBLAZER_ADAMANT_TROPHY, TRAILBLAZER_BRONZE_TROPHY, TRAILBLAZER_DRAGON_TROPHY, TRAILBLAZER_IRON_TROPHY,
            TRAILBLAZER_MITHRIL_TROPHY, TRAILBLAZER_RUNE_TROPHY, TRAILBLAZER_STEEL_TROPHY,
            TWISTED_ADAMANT_TROPHY, TWISTED_BRONZE_TROPHY, TWISTED_DRAGON_TROPHY, TWISTED_IRON_TROPHY,
            TWISTED_MITHRIL_TROPHY, TWISTED_RUNE_TROPHY, TWISTED_STEEL_TROPHY
    );

    private final BinaryOperator<Item> SUM_ITEM_QUANTITIES = (a, b) -> new Item(a.getId(), a.getQuantity() + b.getQuantity());
    private final BinaryOperator<ItemStack> SUM_ITEM_STACK_QUANTITIES = (a, b) -> new ItemStack(a.getId(), a.getQuantity() + b.getQuantity());

    public boolean isItemNeverKeptOnDeath(int itemId) {
        return NEVER_KEPT_ITEMS.contains(itemId);
    }


    public Collection<Item> getItems(Client client) {
        return Stream.of(InventoryID.INVENTORY, InventoryID.EQUIPMENT)
                .map(client::getItemContainer)
                .filter(Objects::nonNull)
                .map(ItemContainer::getItems)
                .flatMap(Arrays::stream)
                .filter(Objects::nonNull)
                .filter(item -> item.getId() >= 0) // -1 implies empty slot
                .collect(Collectors.toList());
    }

    public <K, V> Map<K, V> reduce(@NotNull Iterable<V> items, Function<V, K> deriveKey, BinaryOperator<V> aggregate) {
        final Map<K, V> map = new LinkedHashMap<>();
        items.forEach(v -> map.merge(deriveKey.apply(v), v, aggregate));
        return map;
    }

    public Map<Integer, Item> reduceItems(@NotNull Iterable<Item> items) {
        return reduce(items, Item::getId, SUM_ITEM_QUANTITIES);
    }

    @NotNull
    public Collection<ItemStack> reduceItemStack(@NotNull Iterable<ItemStack> items) {
        return reduce(items, ItemStack::getId, SUM_ITEM_STACK_QUANTITIES).values();
    }

    public String getItemImageUrl(int itemId) {
        return ITEM_CACHE_BASE_URL + "icon/" + itemId + ".png";
    }

    public String getNpcImageUrl(int npcId) {
        return String.format("https://chisel.weirdgloop.org/static/img/osrs-npc/%d_128.png", npcId);
    }

    private static Collection<ItemStack> stack(Collection<ItemStack> items) {
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

}
