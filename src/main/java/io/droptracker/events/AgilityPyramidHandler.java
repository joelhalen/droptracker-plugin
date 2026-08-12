package io.droptracker.events;

import java.util.Collections;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.game.ItemStack;

/**
 * Tracks pyramid tops obtained at the Agility Pyramid. Grabbing the top fires
 * no chat message (only a jingle, which RuneLite does not expose) and no loot
 * event, so detection is: a click on the summit pyramid-top object arms a
 * short window, and an inventory gain of the Pyramid top item (6970) inside
 * that window — while still in the pyramid region — is the grab.
 *
 * Bank withdrawals and turn-ins to Simon Templeton only move the baseline
 * count; they can never emit (wrong region, no armed click, or a decrease).
 *
 * Invoked manually from DropTrackerPlugin's event subscriptions (handlers in
 * this package are not registered on the RuneLite event bus).
 */
@Slf4j
public class AgilityPyramidHandler extends BaseEventHandler {

    public static final String SOURCE_NAME = "Agility Pyramid";

    /* Agility Pyramid map region (core AgilityPlugin's Courses.PYRAMID) */
    @VisibleForTesting
    static final int PYRAMID_REGION_ID = 13356;

    /* Ticks a summit click stays armed; grabbing is a one-tick action but the
     * container update can land a tick or two later. */
    @VisibleForTesting
    static final int ARM_WINDOW_TICKS = 5;

    private final DropHandler dropHandler;

    private int tickCounter = 0;
    private int grabArmedTick = Integer.MIN_VALUE;
    /* -1 = baseline unknown (fresh login/hop); first INV change just seeds it */
    private int lastPyramidTopCount = -1;

    @Inject
    public AgilityPyramidHandler(DropHandler dropHandler) {
        this.dropHandler = dropHandler;
    }

    @Override
    public boolean isEnabled() {
        return config.trackAgilityPyramid();
    }

    public void onTick() {
        tickCounter++;
    }

    public void onMenuOptionClicked(MenuOptionClicked event) {
        int id = event.getId();
        if (id != ObjectID.AGILITY_PYRAMID_TOP_MULTILOC && id != ObjectID.AGILITY_PYRAMID_TOP) {
            return;
        }
        if (getCurrentRegionId() != PYRAMID_REGION_ID) {
            /* Object-id collisions from other menu sources (items, NPCs)
             * can't happen where the summit object doesn't exist. */
            return;
        }
        grabArmedTick = tickCounter;
    }

    public void onItemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() != InventoryID.INV) {
            return;
        }
        ItemContainer inventory = event.getItemContainer();
        if (inventory == null) {
            return;
        }
        int count = inventory.count(ItemID.AGILITY_PYRAMID_GOLD_PYRAMID);
        int previous = lastPyramidTopCount;
        lastPyramidTopCount = count;

        if (!shouldEmit(previous, count, tickCounter - grabArmedTick, getCurrentRegionId())) {
            return;
        }
        grabArmedTick = Integer.MIN_VALUE;
        dropHandler.onActivityLoot(SOURCE_NAME,
            Collections.singletonList(new ItemStack(ItemID.AGILITY_PYRAMID_GOLD_PYRAMID, count - previous)));
    }

    /**
     * A grab is: known baseline, count increased, a summit click within the
     * arm window, and the player still inside the pyramid region.
     */
    @VisibleForTesting
    static boolean shouldEmit(int previousCount, int newCount, int ticksSinceArmed, int regionId) {
        return previousCount >= 0
            && newCount > previousCount
            && ticksSinceArmed >= 0
            && ticksSinceArmed <= ARM_WINDOW_TICKS
            && regionId == PYRAMID_REGION_ID;
    }

    public void reset() {
        tickCounter = 0;
        grabArmedTick = Integer.MIN_VALUE;
        lastPyramidTopCount = -1;
    }

    private int getCurrentRegionId() {
        Player local = client.getLocalPlayer();
        if (local == null || local.getWorldLocation() == null) {
            return -1;
        }
        return local.getWorldLocation().getRegionID();
    }
}
