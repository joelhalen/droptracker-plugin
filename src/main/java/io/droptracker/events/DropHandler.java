package io.droptracker.events;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.Drop;
import io.droptracker.service.EventNotificationService;
import io.droptracker.service.KCService;
import io.droptracker.util.ItemStacks;
import io.droptracker.util.NpcUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
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
    private KCService kcService;

    @Inject
    private ItemManager itemManager;

    @Inject
    private EventNotificationService eventNotificationService;

    /** One in-flight untradeable-list reload at a time: a busy player killing
     *  through an outage would otherwise queue a fetch per drop. */
    private final AtomicBoolean untradeableReloadInFlight = new AtomicBoolean(false);

    /**
     * Short-lived record of boss loot already submitted, keyed by
     * accountHash|canonicalSource|itemSignature. RuneLite fires more than one
     * loot event for multi-part / server-loot bosses (e.g. a granular
     * NpcLootReceived AND a generic LootReceived for Grotesque Guardians), so
     * the same kill reaches processDropEvent twice with identical items. We
     * submit the first and drop the rest within the window. The window is far
     * shorter than the minimum time to re-kill any of these bosses, and this is
     * scoped to boss sources only (isMultiPathLootSource), so ordinary NPCs that
     * are legitimately multi-killed in one tick are never de-duplicated.
     */
    private static final Cache<String, Boolean> recentBossLoot = CacheBuilder.newBuilder()
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .maximumSize(256L)
            .build();

    /*
     * NOTE: These handlers are NOT registered on the RuneLite event bus — they are
     * invoked manually by DropTrackerPlugin's own @Subscribe methods, which also
     * dispatch KCService. Do not call KCService from here, or kill counts get
     * incremented twice per event.
     */
	public void onNpcLootReceived(NpcLootReceived event) {
		if (!plugin.isTracking) {
			return;
		}
		NPC npc = event.getNpc();
		Collection<ItemStack> items = event.getItems();
		processDropEvent(npc.getName(), "npc", LootRecordType.NPC, items);
	}

	public void onPlayerLootReceived(PlayerLootReceived playerLootReceived) {
		if (!plugin.isTracking) {
			return;
		}
		Collection<ItemStack> items = playerLootReceived.getItems();
		processDropEvent(playerLootReceived.getPlayer().getName(), "pvp", LootRecordType.PLAYER, items);
	}

	public void onServerNpcLoot(ServerNpcLoot event) {
		if (!plugin.isTracking) {
			return;
		}
		var comp = event.getComposition();
		log.debug("onServerNpcLoot: processing {} (npcId={})", comp.getName(), comp.getId());
		processDropEvent(comp.getName(), "npc", LootRecordType.NPC, event.getItems());
	}

	public void onLootReceived(LootReceived lootReceived) {
		if (!plugin.isTracking) {
			return;
		}
		/* A select few npc loot sources will arrive here, instead of npclootreceived events */
		String npcName = NpcUtilities.getStandardizedSource(lootReceived, plugin);
		if (lootReceived.getType() == LootRecordType.NPC && NpcUtilities.SPECIAL_NPC_NAMES.contains(npcName)) {
			// Remap sub-NPC names to the canonical encounter name. processDropEvent
			// also canonicalises, but doing it here keeps the pre-canonical name
			// out of downstream logic and mirrors the historical behaviour.
			npcName = NpcUtilities.canonicalizeSpecialSource(npcName);

			processDropEvent(npcName, "npc", LootRecordType.NPC, lootReceived.getItems());
			return;
		}
		if (lootReceived.getType() != LootRecordType.EVENT && lootReceived.getType() != LootRecordType.PICKPOCKET) {
			return;
		}
		processDropEvent(npcName, "other", lootReceived.getType(), lootReceived.getItems());
	}

    private void processDropEvent(String rawNpcName, String sourceType, LootRecordType lootRecordType, Collection<ItemStack> items) {
		final Collection<ItemStack> finalItems = new ArrayList<>(items);
		if (!plugin.isTracking) {
			return;
		}
		// Collapse every loot path to one canonical boss name (e.g. "Dusk" ->
		// "Grotesque Guardians") so the record, the KC lookup and the dedup key
		// all agree regardless of which RuneLite event delivered the kill.
		final String npcName = NpcUtilities.canonicalizeSpecialSource(rawNpcName);
		// Cross-handler de-duplication: a multi-part / server-loot boss fires
		// more than one loot event per kill, so the same items reach here twice.
		// Submit the first, drop identical repeats within the window. Scoped to
		// boss sources only, so normal per-death AoE multi-kills are unaffected.
		if (NpcUtilities.isMultiPathLootSource(npcName)) {
			String dedupKey = getAccountHash() + "|" + npcName + "|" + ItemStacks.signature(finalItems);
			if (recentBossLoot.getIfPresent(dedupKey) != null) {
				log.debug("Suppressing duplicate loot submission for {} (already submitted this kill)", npcName);
				return;
			}
			recentBossLoot.put(dedupKey, Boolean.TRUE);
		}
		if (NpcUtilities.LONG_TICK_NPC_NAMES.contains(npcName)){
			plugin.ticksSinceNpcDataUpdate -= 30;
		}
        plugin.lastDrop = new Drop(npcName, lootRecordType, finalItems);
		/* These lists are normally loaded off-thread at startup. When that
		   failed, this used to fetch them INLINE — but processDropEvent runs
		   on the game client thread (straight off @Subscribe), and the fetch is
		   a synchronous OkHttp call with a 5s connect / 10s read timeout. So
		   the one situation where the list is missing (the server was
		   unreachable at startup, and probably still is) froze the client for
		   up to 15 seconds on EVERY drop — and since a failed fetch returns
		   null, the guard never cleared and it happened again on the next one.
		   Kick a reload off-thread instead; the consumer loop below already
		   null-guards both lists, so this drop simply goes un-flagged. */
		if ((plugin.valuedItemIds == null || plugin.untradeableItemIds == null)
				&& executor != null && untradeableReloadInFlight.compareAndSet(false, true)) {
			executor.submit(() -> {
				try {
					if (plugin.valuedItemIds == null) {
						plugin.valuedItemIds = api.getValuedUntradeables();
					}
					if (plugin.untradeableItemIds == null) {
						plugin.untradeableItemIds = api.getNotableUntradeables();
					}
				} catch (Exception e) {
					log.debug("Untradeable list reload failed: {}", e.getMessage());
				} finally {
					untradeableReloadInFlight.set(false);
				}
			});
		}
		/* Items required by one of the player's active events (from the last
		/event_state snapshot) are always screenshotted for proof — this
		overrides the "Screenshot untradeables" toggle. API users only:
		webhook-only clients never poll event state, so their coverage is
		whatever the toggle + notable-untradeables list provide. */
		final Set<Integer> eventItemIds = config.useApi()
			? eventNotificationService.getEventScreenshotItemIds()
			: Collections.emptySet();
		final AtomicReference<Boolean> untradeableScreenshot = new AtomicReference<>(false);
		clientThread.invokeLater(() -> {
			// Gather all game state info needed
			List<ItemStack> stackedItems = new ArrayList<>(ItemStacks.stack(finalItems));
			String localPlayerName = getPlayerName();
			AtomicInteger totalValue = new AtomicInteger(0);
			List<CustomWebhookBody.Embed> embeds = new ArrayList<>();
			AtomicInteger singleValue = new AtomicInteger(0);
			
			for (ItemStack item : stackedItems) {
				int itemId = item.getId();
				/* Check if the itemId exists in the valued list we obtained */
				if (plugin.valuedItemIds != null && plugin.valuedItemIds.contains(itemId)) {
					untradeableScreenshot.set(true);
				} else if (config.screenshotUntradeables() && plugin.untradeableItemIds != null
						&& plugin.untradeableItemIds.contains(itemId)) {
					/* Notable untradeable (0gp) drop — toggle-gated screenshot */
					untradeableScreenshot.set(true);
				} else if (eventItemIds.contains(itemId)) {
					/* Event-required item — forced for proof, regardless of toggles */
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
					Integer killCount = kcService.getKillCountWithStorage(lootRecordType, npcName);
					fieldData.put("killcount", killCount != null ? killCount : 0);
				}
				
				addFields(itemEmbed, fieldData);
				embeds.add(itemEmbed);
			}

			// Now do the heavy work off the client thread
			executor.submit(() -> {
				try {
					CustomWebhookBody customWebhookBody = createWebhookBody(localPlayerName + " received some drops:");
					customWebhookBody.getEmbeds().addAll(embeds);

					if (!customWebhookBody.getEmbeds().isEmpty()) {
						int valueToSend = totalValue.get();
						Boolean valueModified = untradeableScreenshot.get();
						sendData(customWebhookBody, valueToSend, singleValue.get(), valueModified);
					}
				} catch (Exception e) {
					log.error("Error processing drop event", e);
				}
			});
		});
	}

}
