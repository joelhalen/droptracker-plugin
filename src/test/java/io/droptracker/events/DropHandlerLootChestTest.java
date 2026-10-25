package io.droptracker.events;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.game.ItemStack;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Opened PvP loot keys, read straight off the Wilderness loot chest (6.1.16).
 * The chest holds one item container per key, 558-562. Each key is sent once,
 * however often the chest is reopened before the loot is taken.
 */
public class DropHandlerLootChestTest {

    private final Map<Integer, Item[]> containers = new HashMap<>();
    private DropHandler handler;

    @Before
    public void setUp() {
        handler = new DropHandler();
        handler.client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
            new Class<?>[]{Client.class}, (p, m, a) -> {
                if (m.getName().equals("getItemContainer")) {
                    Item[] items = containers.get((Integer) a[0]);
                    return items == null ? null : container(items);
                }
                Class<?> r = m.getReturnType();
                return r == boolean.class ? false : r == int.class ? 0 : r == long.class ? 0L : null;
            });
    }

    private static ItemContainer container(Item[] items) {
        return (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
            new Class<?>[]{ItemContainer.class},
            (p, m, a) -> m.getName().equals("getItems") ? items : null);
    }

    @Test
    public void eachHeldKeyIsOneSubmission() {
        containers.put(558, new Item[]{new Item(4151, 1), new Item(385, 10)});
        containers.put(560, new Item[]{new Item(995, 250_000)});
        List<List<ItemStack>> keys = handler.unsentLootKeys();
        assertEquals(2, keys.size());
        assertEquals(2, keys.get(0).size());
        assertEquals(995, keys.get(1).get(0).getId());
    }

    @Test
    public void reopeningTheChestSendsNothingTwice() {
        containers.put(558, new Item[]{new Item(4151, 1)});
        assertEquals(1, handler.unsentLootKeys().size());
        assertTrue(handler.unsentLootKeys().isEmpty());
        // A key won later shows up beside the unclaimed one.
        containers.put(559, new Item[]{new Item(11802, 1)});
        assertEquals(1, handler.unsentLootKeys().size());
    }

    @Test
    public void emptySlotsAndPlaceholdersAreSkipped() {
        containers.put(558, new Item[]{new Item(-1, 0), new Item(0, 0)});
        assertTrue(handler.unsentLootKeys().isEmpty());
    }

    @Test
    public void theChestIsNotAnNpcLootSource() {
        assertEquals("Loot Chest", DropHandler.LOOT_CHEST);
    }
}
