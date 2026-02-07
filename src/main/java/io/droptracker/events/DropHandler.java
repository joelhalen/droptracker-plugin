package io.droptracker.events;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.Drop;
import io.droptracker.service.KCService;
import io.droptracker.util.ChatMessageUtil;
import io.droptracker.util.NpcUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;

@Slf4j
public class DropHandler extends BaseEventHandler {

    @Inject
    private ChatMessageUtil chatMessageUtil;

    @Inject
    private KCService kcService;

    @Inject
    private ItemManager itemManager;

    @Subscribe
	public void onNpcLootReceived(NpcLootReceived event) {
		chatMessageUtil.checkForMessage();
		if (!plugin.isTracking) {
			return;
		}
		NPC npc = event.getNpc();
		Collection<ItemStack> items = event.getItems();
		processDropEvent(npc.getName(), "npc", LootRecordType.NPC, items);
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived playerLootReceived) {
		chatMessageUtil.checkForMessage();
		if (!plugin.isTracking) {
			return;
		}
		Collection<ItemStack> items = playerLootReceived.getItems();
		processDropEvent(playerLootReceived.getPlayer().getName(), "pvp", LootRecordType.PLAYER, items);
		kcService.onPlayerKill(playerLootReceived);
	}

	@Subscribe(priority=1)
	public void onServerNpcLoot(ServerNpcLoot event) {
		chatMessageUtil.checkForMessage();
		if (!plugin.isTracking) {
			return;
		}
		var comp = event.getComposition();
		log.debug("onServerNpcLoot: processing {} (npcId={})", comp.getName(), comp.getId());
		processDropEvent(comp.getName(), "npc", LootRecordType.NPC, event.getItems());
	}

	@Subscribe
	public void onLootReceived(LootReceived lootReceived) {
		chatMessageUtil.checkForMessage();
		if (!plugin.isTracking) {
			System.out.println("[DT-DEBUG] DropHandler.onLootReceived SKIPPED: isTracking=false");
			return;
		}
		/* A select few npc loot sources will arrive here, instead of npclootreceived events */
		String npcName = NpcUtilities.getStandardizedSource(lootReceived, plugin);
		System.out.println("[DT-DEBUG] DropHandler.onLootReceived: rawName=" + lootReceived.getName() + " standardized=" + npcName + " type=" + lootReceived.getType() + " items=" + lootReceived.getItems().size() + " lastDrop=" + (plugin.lastDrop != null ? plugin.lastDrop.getSource() : "null"));
		if (lootReceived.getType() == LootRecordType.NPC && NpcUtilities.SPECIAL_NPC_NAMES.contains(npcName)) {
			System.out.println("[DT-DEBUG]   -> routing as SPECIAL NPC: " + npcName);
			if(npcName.equals("Branda the Fire Queen")|| npcName.equals("Eldric the Ice King")) {
				npcName = "Royal Titans";
			}
			if(npcName.equals("Dusk")){
				npcName = "Grotesque Guardians";
			}
			if(npcName.equals("Corrupted Hunllef")) {
				npcName = "The Corrupted Gauntlet";
			}
			if(npcName.equals("Crystalline Hunllef")) {
				npcName = "The Gauntlet";
			}

			processDropEvent(npcName, "npc", LootRecordType.NPC, lootReceived.getItems());
			return;
		}
		if (lootReceived.getType() != LootRecordType.EVENT && lootReceived.getType() != LootRecordType.PICKPOCKET) {
			System.out.println("[DT-DEBUG]   -> SKIPPED: type=" + lootReceived.getType() + " not EVENT/PICKPOCKET");
			return;
		}
		System.out.println("[DT-DEBUG]   -> processing EVENT/PICKPOCKET: source=" + npcName);
		processDropEvent(npcName, "other", lootReceived.getType(), lootReceived.getItems());
		kcService.onLoot(lootReceived);
	}

    private void processDropEvent(String npcName, String sourceType, LootRecordType lootRecordType, Collection<ItemStack> items) {
		System.out.println("[DT-DEBUG] processDropEvent: npc=" + npcName + " sourceType=" + sourceType + " recordType=" + lootRecordType + " itemCount=" + items.size());
		chatMessageUtil.checkForMessage();
		final Collection<ItemStack> finalItems = new ArrayList<>(items);
		if (!plugin.isTracking) {
			System.out.println("[DT-DEBUG]   processDropEvent SKIPPED: isTracking=false");
			return;
		} 
		if (NpcUtilities.LONG_TICK_NPC_NAMES.contains(npcName)){
			plugin.ticksSinceNpcDataUpdate -= 30;
		}
        plugin.lastDrop = new Drop(npcName, lootRecordType, finalItems);
		if (plugin.valuedItemIds == null) {
			/* Load target 'valued item ids' if they are not present
			To help properly screenshot un-tradeables that are later given values
			 */
			plugin.valuedItemIds = api.getValuedUntradeables();
		}
		final AtomicReference<Boolean> untradeableScreenshot = new AtomicReference<>(false);
		clientThread.invokeLater(() -> {
			// Gather all game state info needed
			List<ItemStack> stackedItems = new ArrayList<>(stack(finalItems));
			String localPlayerName = getPlayerName();
			AtomicInteger totalValue = new AtomicInteger(0);
			List<CustomWebhookBody.Embed> embeds = new ArrayList<>();
			AtomicInteger singleValue = new AtomicInteger(0);
			
			for (ItemStack item : stackedItems) {
				int itemId = item.getId();
				/* Check if the itemId exists in the valued list we obtained */
				if (plugin.valuedItemIds != null && plugin.valuedItemIds.contains(itemId)) {
					untradeableScreenshot.set(true);
				}
				int qty = item.getQuantity();
				int price = itemManager.getItemPrice(itemId);
				ItemComposition itemComposition = itemManager.getItemComposition(itemId);
				totalValue.addAndGet(qty * price);
				singleValue.addAndGet(price);
				CustomWebhookBody.Embed itemEmbed = createEmbed(localPlayerName + " received some drops:", "drop");
				itemEmbed.setImage(plugin.itemImageUrl(itemId));
				Map<String, Object> fieldData = new HashMap<>();
				fieldData.put("source_type", sourceType);
				fieldData.put("item", itemComposition.getName());
				fieldData.put("id", itemComposition.getId());
				fieldData.put("quantity", qty);
				fieldData.put("value", price);
				fieldData.put("source", npcName);
				
				if (npcName != null) {
					Integer killCount = getKillCount(npcName);
					fieldData.put("killcount", killCount);
				}
				
				addFields(itemEmbed, fieldData);
				embeds.add(itemEmbed);
			}

			System.out.println("[DT-DEBUG]   clientThread callback: player=" + localPlayerName + " stackedItems=" + stackedItems.size() + " embeds=" + embeds.size() + " totalValue=" + totalValue.get());
			// Now do the heavy work off the client thread
			executor.submit(() -> {
				try {
					CustomWebhookBody customWebhookBody = createWebhookBody(localPlayerName + " received some drops:");
					customWebhookBody.getEmbeds().addAll(embeds);

					if (!customWebhookBody.getEmbeds().isEmpty()) {
						int valueToSend = totalValue.get();
						Boolean valueModified = untradeableScreenshot.get();
						System.out.println("[DT-DEBUG]   SENDING: embeds=" + customWebhookBody.getEmbeds().size() + " value=" + valueToSend + " valueModified=" + valueModified);
						sendData(customWebhookBody, valueToSend, singleValue.get(), valueModified);
					} else {
						System.out.println("[DT-DEBUG]   NOT SENDING: embeds empty");
					}
				} catch (Exception e) {
					System.out.println("[DT-DEBUG]   ERROR in executor: " + e.getMessage());
					log.error("Error processing drop event", e);
				}
			});
		});
	}

    @SuppressWarnings("deprecation")
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
