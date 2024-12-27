package io.droptracker.util;

import io.droptracker.DropTrackerPlugin;
import net.runelite.api.*;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class ContainerManager {
    /**
     * Helps to determine whether or not the player's newly "received" item
     * Actually came from a loot-related event, or if it was just a result of swapping gear/looting pre-existing ground items
     * on the same game tick that a loot-related event occurred.
     */

    private final DropTrackerPlugin plugin;
    private final Client client;

    private Item[] lastInventoryState;
    private Item[] lastEquipmentState;

    @Inject
    public ContainerManager(DropTrackerPlugin plugin, Client client) {
        this.plugin = plugin;
        this.client = client;
    }

    public void onTick() {
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        ItemContainer worn = client.getItemContainer(InventoryID.EQUIPMENT);

        if (inventory != null) {
            lastInventoryState = inventory.getItems().clone();
        }
        if (worn != null) {
            lastEquipmentState = worn.getItems().clone();
        }
    }

    public boolean isRealDrop(Item receivedItem) {
        /** Determine if the drop is 'real' by checking the last stored inventory/worn equipment
         * against the item. If the item was added to their containers on the same tick as a kill,
         * we can assume it was not a real drop...?
         * The only place I would see this potentially not working is with row(i) for coins(?)
         * */
        int previousQuantity = 0;

        if (lastInventoryState != null) {
            for (Item item : lastInventoryState) {
                if (item != null && item.getId() == receivedItem.getId()) {
                    previousQuantity += item.getQuantity();
                }
            }
        }

        if (lastEquipmentState != null) {
            for (Item item : lastEquipmentState) {
                if (item != null && item.getId() == receivedItem.getId()) {
                    previousQuantity += item.getQuantity();
                }
            }
        }

        // Get current quantity from both containers
        int currentQuantity = 0;
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);

        if (inventory != null) {
            for (Item item : inventory.getItems()) {
                if (item != null && item.getId() == receivedItem.getId()) {
                    currentQuantity += item.getQuantity();
                }
            }
        }

        if (equipment != null) {
            for (Item item : equipment.getItems()) {
                if (item != null && item.getId() == receivedItem.getId()) {
                    currentQuantity += item.getQuantity();
                }
            }
        }
        currentQuantity += receivedItem.getQuantity();
        // If current quantity is greater than previous quantity, it's a real drop
        if (currentQuantity > previousQuantity) {
            return true;
        }

        return false;
    }
}
