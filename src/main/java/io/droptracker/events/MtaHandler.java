package io.droptracker.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EnumComposition;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemStack;

/**
 * Tracks Mage Training Arena reward-shop purchases. Buying a reward fires no
 * chat message and no loot event, so detection is a state machine over the
 * shop interface (group 197):
 *
 *  - shop opens  -> snapshot the four pizazz-point varps and the inventory
 *  - points drop -> arm a short purchase window
 *  - inventory gains inside the window -> those items were the purchase
 *
 * The pizazz varps (261-264, VarPlayerID.IF1-IF4 = Telekinetic / Alchemist /
 * Enchantment / Graveyard) are shared interface-transmit scratch varps — they
 * are only meaningful while the shop is open and are ignored otherwise.
 *
 * Bones to Peaches grants no item; its unlock is varbit 1505 flipping 0->1,
 * submitted as the unlock's own item (6926, "Bones to peaches").
 *
 * Invoked manually from DropTrackerPlugin's event subscriptions (handlers in
 * this package are not registered on the RuneLite event bus).
 */
@Slf4j
public class MtaHandler extends BaseEventHandler {

    public static final String SOURCE_NAME = "Mage Training Arena";

    /* Pizazz-point scratch varps while the shop is open (IF1..IF4) */
    private static final int[] POINT_VARPS = {
        VarPlayerID.IF1, VarPlayerID.IF2, VarPlayerID.IF3, VarPlayerID.IF4
    };

    /* enum 2753 maps the shop's selected-reward index (varbit 10059) to an
     * item id; index 63 means nothing is selected. */
    private static final int REWARD_ENUM_ID = 2753;
    private static final int REWARD_NONE_SELECTED = 63;

    /* Ticks a points-drop stays armed waiting for the inventory update */
    @VisibleForTesting
    static final int PURCHASE_WINDOW_TICKS = 4;

    private final DropHandler dropHandler;

    private boolean shopOpen = false;
    private int tickCounter = 0;
    private int purchaseArmedTick = Integer.MIN_VALUE;
    private int[] pointSnapshot = null;
    private Map<Integer, Integer> invSnapshot = null;
    private int b2pAtOpen = -1;
    private boolean b2pEmitted = false;

    @Inject
    public MtaHandler(DropHandler dropHandler) {
        this.dropHandler = dropHandler;
    }

    @Override
    public boolean isEnabled() {
        return config.trackActivities() && config.trackMta();
    }

    public void onTick() {
        tickCounter++;
    }

    public void onWidgetLoaded(WidgetLoaded event) {
        if (event.getGroupId() != InterfaceID.MAGICTRAINING_SHOP) {
            return;
        }
        shopOpen = true;
        purchaseArmedTick = Integer.MIN_VALUE;
        /* Varps/containers are settled by the time invokeLater runs */
        clientThread.invokeLater(() -> {
            pointSnapshot = readPoints();
            invSnapshot = readInventory();
            if (b2pAtOpen == -1) {
                b2pAtOpen = client.getVarbitValue(VarbitID.MAGICTRAINING_BONESPEACHES);
            }
        });
    }

    public void onWidgetClosed(WidgetClosed event) {
        if (event.getGroupId() != InterfaceID.MAGICTRAINING_SHOP) {
            return;
        }
        shopOpen = false;
        purchaseArmedTick = Integer.MIN_VALUE;
        pointSnapshot = null;
        invSnapshot = null;
    }

    public void onVarbitChanged(VarbitChanged event) {
        if (!shopOpen) {
            return;
        }

        if (event.getVarbitId() == VarbitID.MAGICTRAINING_BONESPEACHES) {
            /* 0 -> 1 while the shop is open is the purchase; anything else
             * (login sync, already unlocked at open) is not. */
            if (b2pAtOpen == 0 && event.getValue() == 1 && !b2pEmitted) {
                b2pEmitted = true;
                /* The unlock also spends points; disarm so the purchase
                 * window can't re-emit it via the fallback path. */
                purchaseArmedTick = Integer.MIN_VALUE;
                log.debug("MTA: Bones to Peaches unlocked");
                dropHandler.onActivityLoot(SOURCE_NAME,
                    Collections.singletonList(new ItemStack(ItemID.MAGICTRAINING_PEACHSPELL, 1)));
            }
            return;
        }

        if (!isPointVarp(event.getVarpId()) || pointSnapshot == null) {
            return;
        }
        int[] current = readPoints();
        if (anyDecrease(pointSnapshot, current)) {
            purchaseArmedTick = tickCounter;
            log.debug("MTA: pizazz points dropped ({} -> {}), purchase window armed",
                java.util.Arrays.toString(pointSnapshot), java.util.Arrays.toString(current));
        }
        /* Gains (earning points with the shop open) just move the baseline */
        pointSnapshot = current;
    }

    public void onItemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() != InventoryID.INV || !shopOpen || invSnapshot == null) {
            return;
        }
        Map<Integer, Integer> current = readInventory();
        boolean armed = tickCounter - purchaseArmedTick >= 0
            && tickCounter - purchaseArmedTick <= PURCHASE_WINDOW_TICKS;
        if (!armed) {
            /* Unrelated inventory activity with the shop open — keep the
             * baseline current so a later purchase diffs cleanly. */
            invSnapshot = current;
            return;
        }

        List<ItemStack> gained = diffGains(invSnapshot, current);
        invSnapshot = current;
        purchaseArmedTick = Integer.MIN_VALUE;

        if (gained.isEmpty()) {
            /* Points dropped but nothing landed in the inventory (e.g. the
             * item went elsewhere). Fall back to the shop's selected reward. */
            ItemStack fallback = selectedRewardFallback();
            if (fallback == null) {
                log.debug("MTA: purchase window fired with no inventory gain and no selected reward");
                return;
            }
            log.debug("MTA: no inventory gain, using selected-reward fallback (item {})", fallback.getId());
            gained = Collections.singletonList(fallback);
        }

        dropHandler.onActivityLoot(SOURCE_NAME, gained);
    }

    public void reset() {
        shopOpen = false;
        tickCounter = 0;
        purchaseArmedTick = Integer.MIN_VALUE;
        pointSnapshot = null;
        invSnapshot = null;
        b2pAtOpen = -1;
        b2pEmitted = false;
    }

    /** Items whose count increased from before to after, as ItemStacks. */
    @VisibleForTesting
    static List<ItemStack> diffGains(Map<Integer, Integer> before, Map<Integer, Integer> after) {
        List<ItemStack> gains = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : after.entrySet()) {
            int delta = entry.getValue() - before.getOrDefault(entry.getKey(), 0);
            if (delta > 0) {
                gains.add(new ItemStack(entry.getKey(), delta));
            }
        }
        return gains;
    }

    @VisibleForTesting
    static boolean anyDecrease(int[] before, int[] after) {
        for (int i = 0; i < before.length && i < after.length; i++) {
            if (after[i] < before[i]) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPointVarp(int varpId) {
        for (int varp : POINT_VARPS) {
            if (varp == varpId) {
                return true;
            }
        }
        return false;
    }

    private int[] readPoints() {
        int[] points = new int[POINT_VARPS.length];
        for (int i = 0; i < POINT_VARPS.length; i++) {
            points[i] = client.getVarpValue(POINT_VARPS[i]);
        }
        return points;
    }

    private Map<Integer, Integer> readInventory() {
        Map<Integer, Integer> counts = new HashMap<>();
        ItemContainer inventory = client.getItemContainer(InventoryID.INV);
        if (inventory == null) {
            return counts;
        }
        for (Item item : inventory.getItems()) {
            if (item.getId() > 0) {
                counts.merge(item.getId(), item.getQuantity(), Integer::sum);
            }
        }
        return counts;
    }

    /** The shop's currently selected reward and quantity, or null. */
    private ItemStack selectedRewardFallback() {
        int selected = client.getVarbitValue(VarbitID.MAGICTRAINING_SHOP);
        if (selected == REWARD_NONE_SELECTED) {
            return null;
        }
        EnumComposition rewardEnum = client.getEnum(REWARD_ENUM_ID);
        if (rewardEnum == null) {
            return null;
        }
        int itemId = rewardEnum.getIntValue(selected);
        if (itemId <= 0 || itemId == ItemID.MAGICTRAINING_PEACHSPELL) {
            /* B2P is handled exclusively by the varbit path */
            return null;
        }
        int quantity = Math.max(1, client.getVarpValue(VarPlayerID.MAKEXCRAFTING));
        return new ItemStack(itemId, quantity);
    }
}
