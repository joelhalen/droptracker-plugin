package io.droptracker.events;

import com.google.inject.Inject;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.Drop;
import io.droptracker.util.ItemIDSearch;
import io.droptracker.util.KCService;
import io.droptracker.util.Rarity;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.annotations.Varp;
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
    @Inject
    private Rarity rarity;

    @Inject
    private KCService kcService;

    private ItemIDSearch itemIDFinder;
    private final AtomicBoolean popupStarted = new AtomicBoolean(false);
    public static final @Varp int COMPLETED_VARP = 2943, TOTAL_VARP = 2944;

    private static final Duration RECENT_DROP = Duration.ofSeconds(30L);
    
    @Inject
    public ClogHandler(ItemIDSearch itemIDFinder, Rarity rarity, KCService kcService) {
        this.itemIDFinder = itemIDFinder;
        this.rarity = rarity;
        this.kcService = kcService;
    }


    static final Pattern COLLECTION_LOG_REGEX = Pattern.compile("New item added to your collection log: (?<itemName>(.*))");
    private static final int POPUP_PREFIX_LENGTH = "New item:".length();
    public static final String CG_NAME = "Corrupted Gauntlet", CG_BOSS = "Corrupted Hunllef";
    // private static final String TOA = "Tombs of Amascut";
    // private static final String TOB = "Theatre of Blood";
    // private static final String COX = "Chambers of Xeric";

    @Override
    public void process(Object... args) {
        /* does not need an override */
    }


    @SuppressWarnings("deprecation")
    public void onChatMessage(String chatMessage) {
        if (client.getVarbitValue(Varbits.COLLECTION_LOG_NOTIFICATION) != 1) {
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
            if (popupStarted.getAndSet(false) && "Collection log".equalsIgnoreCase(topText) && isEnabled()) {
                String bottomText = submissionManager.sanitize(client.getVarcStrValue(VarClientStr.NOTIFICATION_BOTTOM_TEXT));
                processCollection(bottomText.substring(POPUP_PREFIX_LENGTH).trim());
            }
        }
    }




    private void processCollection(String itemName) {
        int completed = client.getVarpValue(COMPLETED_VARP);
        int total = client.getVarpValue(TOTAL_VARP);

        boolean varpValid = total > 0 && completed > 0;
        if (!varpValid) {
            // This occurs if the player doesn't have the character summary tab selected
            log.debug("Collection log progress varps were invalid ({} / {})", completed, total);
        }
        
        Integer itemId = itemIDFinder.findItemId(itemName);
        Drop loot = itemId != null ? getLootSource(itemId) : null;
        Integer killCount = loot != null ? kcService.getKillCountWithStorage(loot.getCategory(), loot.getSource()) : null;
        OptionalDouble itemRarity = ((loot != null) && (loot.getCategory() == LootRecordType.NPC) && (itemId != null)) ?
                rarity.getRarity(loot.getSource(), itemId, 1) : OptionalDouble.empty();
                
        String player = getPlayerName();
        CustomWebhookBody collectionLogBody = createWebhookBody(player + " received a collection log:");
        CustomWebhookBody.Embed collEmbed = createEmbed(player + " received a collection log:", "collection_log");
        
        Map<String, Object> fieldData = new HashMap<>();
        fieldData.put("source", loot != null ? loot.getSource() : "unknown");
        fieldData.put("item", itemName);
        fieldData.put("kc", killCount);
        fieldData.put("rarity", itemRarity);
        fieldData.put("item_id", itemId);
        fieldData.put("slots", completed + "/" + total);
        
        addFields(collEmbed, fieldData);
        
        collectionLogBody.getEmbeds().add(collEmbed);
        sendData(collectionLogBody, "2");
    }

    @Nullable
    private Drop getLootSource(int itemId) {
        Drop drop = plugin.lastDrop;
        if (drop == null) return null;
        if (Duration.between(drop.getTime(), Instant.now()).compareTo(RECENT_DROP) > 0) return null;
        for (ItemStack item : drop.getItems()) {
            if (item.getId() == itemId) {
                return drop;
            }
        }
        return null;
    }

}


