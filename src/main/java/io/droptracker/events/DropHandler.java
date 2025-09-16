package io.droptracker.events;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
		//sendChatReminder();
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
		//sendChatReminder();
	}

	@Subscribe
	public void onLootReceived(LootReceived lootReceived) {
		chatMessageUtil.checkForMessage();
		if (!plugin.isTracking) {
			return;
		}
		/* A select few npc loot sources will arrive here, instead of npclootreceived events */
		String npcName = NpcUtilities.getStandardizedSource(lootReceived, plugin);
		System.out.println("NpcName: {}" + npcName);
		System.out.println("LootRecordType: {}" + lootReceived.getType());

		if (lootReceived.getType() == LootRecordType.NPC && NpcUtilities.SPECIAL_NPC_NAMES.contains(npcName)) {
			System.out.println("Loot is NPC and special names contains the npc name....");
			if(npcName.equals("Branda the Fire Queen")|| npcName.equals("Eldric the Ice King")) {
				npcName = "Royal Titans";
			}
			if(npcName.equals("Dusk")){
				npcName = "Grotesque Guardians";
			}
			System.out.println("Processing drop event for NPC: {}" + npcName);
			processDropEvent(npcName, "npc", LootRecordType.NPC, lootReceived.getItems());
			return;
		}
		if (lootReceived.getType() != LootRecordType.EVENT && lootReceived.getType() != LootRecordType.PICKPOCKET) {
			return;
		}
		processDropEvent(npcName, "other", lootReceived.getType(), lootReceived.getItems());
		kcService.onLoot(lootReceived);
		//sendChatReminder();
	}

    private void processDropEvent(String npcName, String sourceType, LootRecordType lootRecordType, Collection<ItemStack> items) {
		
		chatMessageUtil.checkForMessage();
		if (!plugin.isTracking) {
			return;
		}
		if (NpcUtilities.LONG_TICK_NPC_NAMES.contains(npcName)){
			plugin.ticksSinceNpcDataUpdate -= 30;
		}
        plugin.lastDrop = new Drop(npcName, lootRecordType, items);
		clientThread.invokeLater(() -> {
			// Gather all game state info needed
			List<ItemStack> stackedItems = new ArrayList<>(stack(items));
			String localPlayerName = getPlayerName();
			AtomicInteger totalValue = new AtomicInteger(0);
			List<CustomWebhookBody.Embed> embeds = new ArrayList<>();
			AtomicInteger singleValue = new AtomicInteger(0);

			for (ItemStack item : stackedItems) {
				int itemId = item.getId();
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

			// Now do the heavy work off the client thread
			int valueToSend = totalValue.get();
			executor.submit(() -> {
				try {
					CustomWebhookBody customWebhookBody = createWebhookBody(localPlayerName + " received some drops:");
					customWebhookBody.getEmbeds().addAll(embeds);
					if (!customWebhookBody.getEmbeds().isEmpty()) {
						// ValidSubmission creation is now handled by SubmissionManager.sendDataToDropTracker()
						sendData(customWebhookBody, valueToSend, singleValue.get());
					}
				} catch (Exception e) {
					log.error("Error processing drop event", e);
					// Optionally, add debug logging
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
