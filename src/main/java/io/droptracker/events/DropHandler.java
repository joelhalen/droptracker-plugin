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
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Handles all loot-drop events: NPC kills, PvP kills, server-authoritative loot (Yama),
 * and miscellaneous loot events (events, pickpocketing).
 *
 * <p><b>Processing pipeline:</b>
 * <ol>
 *   <li>An incoming RuneLite loot event is received by one of the {@code on*} subscribers.</li>
 *   <li>Special NPC name normalisations are applied (e.g. Royal Titans sub-bosses are merged
 *       into a single source name).</li>
 *   <li>{@link #processDropEvent} is called, which stacks identical items, prices each stack
 *       via the {@link net.runelite.client.game.ItemManager}, and builds one embed per item.</li>
 *   <li>The assembled {@link io.droptracker.models.CustomWebhookBody} is passed to
 *       {@link io.droptracker.service.SubmissionManager#sendDataToDropTracker(
 *       io.droptracker.models.CustomWebhookBody, int, int)} for group-config filtering and
 *       delivery.</li>
 * </ol>
 *
 * <p><b>Thread model:</b> Game-state reads (item prices, kill counts, item composition) are
 * performed on the client thread via {@code clientThread.invokeLater()}. The subsequent HTTP
 * submission is then dispatched to the shared executor to avoid blocking the client thread.</p>
 */
@Slf4j
public class DropHandler extends BaseEventHandler {

    @Inject
    private ChatMessageUtil chatMessageUtil;

    @Inject
    private KCService kcService;

    @Inject
    private ItemManager itemManager;

    /** Processes a standard NPC kill drop. */
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

    /** Processes loot from a PvP kill. The source type is set to {@code "pvp"}. */
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

    /**
     * Processes Yama's server-authoritative loot event.
     * Only called when the NPC composition ID matches {@code NpcID.YAMA}
     * (routing is enforced in {@link io.droptracker.DropTrackerPlugin}).
     */
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event) {
		chatMessageUtil.checkForMessage();
		if (!plugin.isTracking) {
			return;
		}
		var comp = event.getComposition();
		processDropEvent(comp.getName(), "npc", LootRecordType.NPC, event.getItems());
	}

    /**
     * Handles loot that arrives via the LootTracker plugin's {@code LootReceived} event rather
     * than the standard {@code NpcLootReceived}. This includes:
     * <ul>
     *   <li>Special NPCs listed in {@link NpcUtilities#SPECIAL_NPC_NAMES} (e.g. The Whisperer,
     *       Araxxor, Royal Titans sub-bosses, Grotesque Guardians)</li>
     *   <li>Skilling events ({@link LootRecordType#EVENT}) such as Tempoross, Wintertodt</li>
     *   <li>Pickpocketing ({@link LootRecordType#PICKPOCKET})</li>
     * </ul>
     * Royal Titans (Branda / Eldric) and Grotesque Guardians (Dusk) have their sub-boss names
     * normalised to a single shared source name before submission.
     */
	@Subscribe
	public void onLootReceived(LootReceived lootReceived) {
		chatMessageUtil.checkForMessage();
		if (!plugin.isTracking) {
			return;
		}
		// Resolve the canonical source name, accounting for raid mode variants and special NPCs
		String npcName = NpcUtilities.getStandardizedSource(lootReceived, plugin);

		if (lootReceived.getType() == LootRecordType.NPC && NpcUtilities.SPECIAL_NPC_NAMES.contains(npcName)) {
			log.debug("Special NPC loot received: {}", npcName);
			// Merge Royal Titans sub-bosses into a single source
			if(npcName.equals("Branda the Fire Queen")|| npcName.equals("Eldric the Ice King")) {
				npcName = "Royal Titans";
			}
			// Grotesque Guardians only fires loot on the "Dusk" phase; normalise to the encounter name
			if(npcName.equals("Dusk")){
				npcName = "Grotesque Guardians";
			}

			processDropEvent(npcName, "npc", LootRecordType.NPC, lootReceived.getItems());
			return;
		}
		// Only process event-type and pickpocket loot beyond this point
		if (lootReceived.getType() != LootRecordType.EVENT && lootReceived.getType() != LootRecordType.PICKPOCKET) {
			return;
		}
		log.debug("Other NPC loot received: {}", npcName);
		processDropEvent(npcName, "other", lootReceived.getType(), lootReceived.getItems());
		kcService.onLoot(lootReceived);
	}

    /**
     * Core drop processing method shared by all loot event subscribers.
     *
     * <p>Stacks identical item IDs, prices each stacked item via the item manager, builds one
     * {@link io.droptracker.models.CustomWebhookBody.Embed} per distinct item, and submits the
     * assembled webhook body to {@link io.droptracker.service.SubmissionManager}.</p>
     *
     * <p>Note that some NPCs (Grotesque Guardians, Yama) have a larger-than-normal gap between
     * the kill-count chat message and the loot event. The {@code ticksSinceNpcDataUpdate} counter
     * is adjusted to account for this so that kill-count correlation remains accurate.</p>
     *
     * @param npcName       canonical source name (NPC name, player name, event name, etc.)
     * @param sourceType    human-readable category: {@code "npc"}, {@code "pvp"}, {@code "other"}
     * @param lootRecordType the RuneLite loot record type used for kill-count cache keying
     * @param items          the raw (possibly duplicate) item stacks from the loot event
     */
    private void processDropEvent(String npcName, String sourceType, LootRecordType lootRecordType, Collection<ItemStack> items) {
		chatMessageUtil.checkForMessage();
		final Collection<ItemStack> finalItems = new ArrayList<>(items);
		if (!plugin.isTracking) {
			return;
		}
		// Some NPCs have an unusually large tick gap between KC message and loot event;
		// pulling this counter back keeps KC lookups correct for those sources.
		if (NpcUtilities.LONG_TICK_NPC_NAMES.contains(npcName)){
			plugin.ticksSinceNpcDataUpdate -= 30;
		}
		// Expose this drop to ClogHandler so it can correlate a collection-log unlock
        plugin.lastDrop = new Drop(npcName, lootRecordType, finalItems);
		clientThread.invokeLater(() -> {
			// All game-state access (item prices, compositions) must happen on the client thread
			List<ItemStack> stackedItems = new ArrayList<>(stack(finalItems));
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
				fieldData.put("value", price);  // single-item price (used for screenshot threshold)
				fieldData.put("source", npcName);

				if (npcName != null) {
					Integer killCount = getKillCount(npcName);
					fieldData.put("killcount", killCount);
				}

				addFields(itemEmbed, fieldData);
				embeds.add(itemEmbed);
			}

			// Capture values before leaving the client thread
			int valueToSend = totalValue.get();

			// Dispatch HTTP work off the client thread to avoid frame stutter
			executor.submit(() -> {
				try {
					CustomWebhookBody customWebhookBody = createWebhookBody(localPlayerName + " received some drops:");
					customWebhookBody.getEmbeds().addAll(embeds);
					if (!customWebhookBody.getEmbeds().isEmpty()) {
						// totalValue is used for screenshot threshold; singleValue is used to detect stacked items
						sendData(customWebhookBody, valueToSend, singleValue.get());
					}
				} catch (Exception e) {
					log.error("Error processing drop event", e);
				}
			});
		});
	}

    /**
     * Merges duplicate item IDs in a loot collection by summing their quantities.
     * Some loot events (particularly raids) may deliver the same item in multiple
     * {@link ItemStack} entries; stacking them prevents duplicate embed entries.
     *
     * @param items the raw item stacks from a loot event
     * @return a new collection with at most one entry per item ID
     */
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
