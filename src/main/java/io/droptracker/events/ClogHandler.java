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


@Slf4j
public class ClogHandler extends BaseEventHandler {
    private final Rarity rarity;
    private final KCService kcService;
    private final ItemIDSearch itemIDFinder;

    private final AtomicBoolean popupStarted = new AtomicBoolean(false);

    private static final Duration RECENT_DROP = Duration.ofSeconds(30L);
    
    @Inject
    public ClogHandler(ItemIDSearch itemIDFinder, Rarity rarity, KCService kcService) {
        this.itemIDFinder = itemIDFinder;
        this.rarity = rarity;
        this.kcService = kcService;
    }


    static final Pattern COLLECTION_LOG_REGEX = Pattern.compile("New item added to your collection log: (?<itemName>(.*))");
    private static final int POPUP_PREFIX_LENGTH = "New item:".length();



    @Override
    public boolean isEnabled() {
        return config.clogEmbeds();
    }


    public void onChatMessage(String chatMessage) {
        if (client.getVarbitValue(VarbitID.OPTION_COLLECTION_NEW_ITEM) != 1 || !this.isEnabled()) {
            // require notifier enabled without popup mode to use chat event
            return;
        }
        Matcher collectionMatcher = COLLECTION_LOG_REGEX.matcher(chatMessage);
        if (collectionMatcher.find()) {
            String itemName = collectionMatcher.group("itemName");
            clientThread.invokeLater(() -> processCollection(itemName));
        }
    }

    public void onScript(int scriptId) {
        if (scriptId == ScriptID.NOTIFICATION_START) {
            popupStarted.set(true);
        } else if (scriptId == ScriptID.NOTIFICATION_DELAY) {
            String topText = client.getVarcStrValue(VarClientStr.NOTIFICATION_TOP_TEXT);
            if (popupStarted.getAndSet(false) && "Collection log".equalsIgnoreCase(topText) && this.isEnabled()) {
                String bottomText = submissionManager.sanitize(client.getVarcStrValue(VarClientStr.NOTIFICATION_BOTTOM_TEXT));
                processCollection(bottomText.substring(POPUP_PREFIX_LENGTH).trim());
            }
        }
    }




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
                // This occurs if the player doesn't have the character summary tab selected
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


