package io.droptracker.events;

import com.google.inject.Inject;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.Drop;
import io.droptracker.models.submissions.SubmissionType;
import io.droptracker.service.KCService;
import io.droptracker.util.ItemIDSearch;
import io.droptracker.util.Rarity;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemStack;
import net.runelite.http.api.loottracker.LootRecordType;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Handles collection-log slot unlock notifications.
 *
 * <p>Two detection paths are supported, selectable via the in-game setting
 * <em>Collection log - New addition notification</em>:</p>
 * <ol>
 *   <li><b>Chat message mode</b> ({@code VarbitID.OPTION_COLLECTION_NEW_ITEM == 1}): listens for
 *       the game message {@code "New item added to your collection log: <name>"} via
 *       {@link #onChatMessage}.</li>
 *   <li><b>Popup mode</b>: intercepts {@link ScriptID#NOTIFICATION_START} and
 *       {@link ScriptID#NOTIFICATION_DELAY} scripts that drive the overlay popup
 *       via {@link #onScript}.</li>
 * </ol>
 *
 * <p>Both paths resolve to {@link #processCollection(String)}, which correlates the unlocked
 * item with the most recent drop (within 30 s) to determine source NPC, kill count, and rarity.</p>
 *
 * <p>Enabled/disabled via {@link io.droptracker.DropTrackerConfig#clogEmbeds()}.</p>
 */
@Slf4j
public class ClogHandler extends BaseEventHandler {
    private final Rarity rarity;
    private final KCService kcService;
    private final ItemIDSearch itemIDFinder;

    /** Guards against processing a popup that was not initiated by the collection-log script. */
    private final AtomicBoolean popupStarted = new AtomicBoolean(false);

    /**
     * Maximum age of {@link io.droptracker.DropTrackerPlugin#lastDrop} to be considered the source
     * of this unlock. 30 seconds covers most lag scenarios without risking cross-kill correlation.
     */
    private static final Duration RECENT_DROP = Duration.ofSeconds(30L);

    @Inject
    public ClogHandler(ItemIDSearch itemIDFinder, Rarity rarity, KCService kcService) {
        this.itemIDFinder = itemIDFinder;
        this.rarity = rarity;
        this.kcService = kcService;
    }


    /** Regex to extract the item name from the collection-log chat notification message. */
    static final Pattern COLLECTION_LOG_REGEX = Pattern.compile("New item added to your collection log: (?<itemName>(.*))");

    /**
     * Byte length of the {@code "New item:"} prefix in the popup bottom-text varc.
     * This prefix is stripped before the item name is passed to {@link #processCollection}.
     */
    private static final int POPUP_PREFIX_LENGTH = "New item:".length();

    @Override
    public boolean isEnabled() {
        return config.clogEmbeds();
    }

    /**
     * Handles the collection-log chat-message path. Only active when the game is configured
     * to send a chat notification ({@code VarbitID.OPTION_COLLECTION_NEW_ITEM == 1}).
     *
     * @param chatMessage sanitized game chat message
     */
    public void onChatMessage(String chatMessage) {
        if (client.getVarbitValue(VarbitID.OPTION_COLLECTION_NEW_ITEM) != 1 || !this.isEnabled()) {
            // Chat-message path only works in non-popup mode; popup path handled by onScript
            return;
        }
        Matcher collectionMatcher = COLLECTION_LOG_REGEX.matcher(chatMessage);
        if (collectionMatcher.find()) {
            String itemName = collectionMatcher.group("itemName");
            clientThread.invokeLater(() -> processCollection(itemName));
        }
    }

    /**
     * Handles RuneLite script events for popup-mode collection-log detection.
     * {@code NOTIFICATION_START} marks the start of an overlay notification;
     * {@code NOTIFICATION_DELAY} fires shortly after with the title and body text.
     *
     * @param scriptId the RuneLite script ID pre-fired
     */
    public void onScript(int scriptId) {
        if (scriptId == ScriptID.NOTIFICATION_START) {
            popupStarted.set(true);
        } else if (scriptId == ScriptID.NOTIFICATION_DELAY) {
            String topText = client.getVarcStrValue(VarClientStr.NOTIFICATION_TOP_TEXT);
            if (popupStarted.getAndSet(false) && "Collection log".equalsIgnoreCase(topText) && this.isEnabled()) {
                // Bottom text is "New item: <item name>" — strip the fixed prefix
                String bottomText = submissionManager.sanitize(client.getVarcStrValue(VarClientStr.NOTIFICATION_BOTTOM_TEXT));
                processCollection(bottomText.substring(POPUP_PREFIX_LENGTH).trim());
            }
        }
    }

    /**
     * Core processing method. Reads collection-log progress varps, resolves the item ID,
     * correlates with the most recent drop to determine the source NPC, looks up kill count
     * and rarity, then builds and sends the webhook embed.
     *
     * @param itemName the collection-log item name as it appears in the in-game notification
     */
    private void processCollection(String itemName) {
        if (!this.isEnabled()) {
            log.debug("Collection log processing disabled");
            return;
        }

        if (itemName == null || itemName.trim().isEmpty()) {
            log.debug("Cannot process collection log with null/empty item name");
            return;
        }

        try {
            int completed = 0;
            int total = 0;
            
            if (client != null) {
                completed = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
                total = client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX);
            }

            boolean varpValid = total > 0 && completed > 0;
            if (!varpValid) {
                // Varps are only populated when the Character Summary tab is open in-game
                log.debug("Collection log progress varps were invalid ({} / {})", completed, total);
            }
            
            Integer itemId = null;
            if (itemIDFinder != null) {
                try {
                    itemId = itemIDFinder.findItemId(itemName);
                } catch (Exception e) {
                    log.debug("Error finding item ID for {}: {}", itemName, e.getMessage());
                }
            }
            
            Drop loot = itemId != null ? getLootSource(itemId) : null;
            Integer killCount = 0;
            if (loot != null && kcService != null) {
                try {
                    killCount = kcService.getKillCountWithStorage(loot.getCategory(), loot.getSource());
                } catch (Exception e) {
                    log.debug("Error getting kill count: {}", e.getMessage());
                }
            }
            
            OptionalDouble itemRarity = OptionalDouble.empty();
            if (loot != null && loot.getCategory() == LootRecordType.NPC && itemId != null && rarity != null) {
                try {
                    itemRarity = rarity.getRarity(loot.getSource(), itemId, 1);
                } catch (Exception e) {
                    log.debug("Error calculating rarity: {}", e.getMessage());
                }
            }
                    
            String player = getPlayerName();
            CustomWebhookBody collectionLogBody = createWebhookBody(player + " received a collection log:");
            CustomWebhookBody.Embed collEmbed = createEmbed(player + " received a collection log:", "collection_log");
            
            Map<String, Object> fieldData = new HashMap<>();
            fieldData.put("source", loot != null && loot.getSource() != null ? loot.getSource() : "unknown");
            fieldData.put("item", itemName);
            fieldData.put("kc", killCount);
            fieldData.put("rarity", itemRarity.isPresent() ? itemRarity.getAsDouble() : "unknown");
            fieldData.put("item_id", itemId);
            fieldData.put("slots", varpValid ? completed + "/" + total : "unknown");
            
            addFields(collEmbed, fieldData);
            
            if (collectionLogBody != null && collEmbed != null) {
                collectionLogBody.getEmbeds().add(collEmbed);
                sendData(collectionLogBody, SubmissionType.COLLECTION_LOG);
            } else {
                log.warn("Failed to create webhook or embed for collection log");
            }
        } catch (Exception e) {
            log.error("Error processing collection log for item {}: {}", itemName, e.getMessage(), e);
        }
    }

    /**
     * Tries to match a collection-log item ID against the most recently received drop.
     * Returns the drop if it occurred within {@link #RECENT_DROP} and contained the item;
     * otherwise returns {@code null} (source will be reported as "unknown").
     *
     * @param itemId the OSRS item ID of the unlocked collection-log entry
     * @return the matching {@link Drop}, or {@code null}
     */
    @Nullable
    private Drop getLootSource(int itemId) {
        if (plugin == null) {
            return null;
        }

        Drop drop = plugin.lastDrop;
        if (drop == null || drop.getTime() == null) {
            return null;
        }

        try {
            // Discard stale drops to prevent cross-kill false correlations
            if (Duration.between(drop.getTime(), Instant.now()).compareTo(RECENT_DROP) > 0) {
                return null;
            }

            if (drop.getItems() == null) {
                return null;
            }

            for (ItemStack item : drop.getItems()) {
                if (item != null && item.getId() == itemId) {
                    return drop;
                }
            }
        } catch (Exception e) {
            log.debug("Error checking loot source for item {}: {}", itemId, e.getMessage());
        }

        return null;
    }

}


