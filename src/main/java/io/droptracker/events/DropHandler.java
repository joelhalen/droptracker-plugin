package io.droptracker.events;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import com.google.common.eventbus.Subscribe;

import io.droptracker.DebugLogger;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.Drop;
import io.droptracker.util.ChatMessageUtil;
import io.droptracker.util.KCService;
import io.droptracker.util.NpcUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;

@Slf4j
public class DropHandler {

    @Inject
    private ChatMessageUtil chatMessageUtil;

    @Inject
    private DropTrackerPlugin plugin;

    @Inject
    private KCService kcService;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private Client client;

    
    @Subscribe
	public void onNpcLootReceived(NpcLootReceived npcLootReceived) {
		chatMessageUtil.checkForMessage();
		if (!plugin.isTracking) {
			return;
		}
		NPC npc = npcLootReceived.getNpc();
		Collection<ItemStack> items = npcLootReceived.getItems();
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

		if (lootReceived.getType() == LootRecordType.NPC && NpcUtilities.SPECIAL_NPC_NAMES.contains(npcName)) {

			if(npcName.equals("Branda the Fire Queen")|| npcName.equals("Eldric the Ice King")) {
				npcName = "Royal Titans";
			}
			if(npcName.equals("Dusk")){
				npcName = "Grotesque Guardians";
			}
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
		DebugLogger.logSubmission("processDropEvent called for " + npcName);
		
		chatMessageUtil.checkForMessage();
		if (!plugin.isTracking) {
			DebugLogger.logSubmission("Not tracking - skipping drop event");
			return;
		}
		if (NpcUtilities.LONG_TICK_NPC_NAMES.contains(npcName)){
			plugin.ticksSinceNpcDataUpdate -= 30;
		}
        plugin.lastDrop = new Drop(npcName, lootRecordType, items);
		clientThread.invokeLater(() -> {
			// Gather all game state info needed
			List<ItemStack> stackedItems = new ArrayList<>(stack(items));
			String localPlayerName = plugin.getLocalPlayerName();
			String accountHash = String.valueOf(client.getAccountHash());
			AtomicInteger totalValue = new AtomicInteger(0);
			List<CustomWebhookBody.Embed> embeds = new ArrayList<>();

			for (ItemStack item : stackedItems) {
				int itemId = item.getId();
				int qty = item.getQuantity();
				int price = itemManager.getItemPrice(itemId);
				ItemComposition itemComposition = itemManager.getItemComposition(itemId);
				totalValue.addAndGet(qty * price);
				CustomWebhookBody.Embed itemEmbed = new CustomWebhookBody.Embed();
				itemEmbed.setImage(plugin.itemImageUrl(itemId));
				itemEmbed.addField("type", "drop", true);
				itemEmbed.addField("source_type", sourceType, true);
				itemEmbed.addField("acc_hash", accountHash, true);
				itemEmbed.addField("item", itemComposition.getName(), true);
				itemEmbed.addField("player", localPlayerName, true);
				itemEmbed.addField("id", String.valueOf(itemComposition.getId()), true);
				itemEmbed.addField("quantity", String.valueOf(qty), true);
				itemEmbed.addField("value", String.valueOf(price), true);
				itemEmbed.addField("source", npcName, true);
				itemEmbed.addField("type", sourceType, true);
				itemEmbed.addField("p_v", plugin.pluginVersion, true);
				if (npcName != null) {
					Integer killCount = configManager.getRSProfileConfiguration("killcount", npcName.toLowerCase(), int.class);
					itemEmbed.addField("killcount", String.valueOf(killCount), true);
				}
				itemEmbed.title = localPlayerName + " received some drops:";
				embeds.add(itemEmbed);
			}


			// Now do the heavy work off the client thread
			int valueToSend = totalValue.get();
			executor.submit(() -> {
				try {
					CustomWebhookBody customWebhookBody = new CustomWebhookBody();
					customWebhookBody.getEmbeds().addAll(embeds);
					customWebhookBody.setContent(localPlayerName + " received some drops:");
					if (!customWebhookBody.getEmbeds().isEmpty()) {
						plugin.sendDataToDropTracker(customWebhookBody, valueToSend);
					}
				} catch (Exception e) {
					log.error("Error processing drop event", e);
					// Optionally, add debug logging
					DebugLogger.logSubmission("Drop processing failed: " + e.getMessage());
				}
			});
		});
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
