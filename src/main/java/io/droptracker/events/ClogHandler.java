package io.droptracker.events;

import com.google.inject.Inject;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.Drop;
import io.droptracker.util.ItemIDSearch;
import io.droptracker.util.KCService;
import io.droptracker.util.Rarity;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.annotations.Varp;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemStack;
import net.runelite.http.api.loottracker.LootRecordType;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ScheduledExecutorService;


@Slf4j
public class ClogHandler {
    @Inject
    private final DropTrackerPlugin plugin;

    @Inject
    protected Client client;

    @Inject
    private Rarity rarity;

    @Inject
    private ClientThread clientThread;

    @Inject
    private KCService kcService;

    private ItemIDSearch itemIDFinder;
    private final AtomicBoolean popupStarted = new AtomicBoolean(false);
    public static final @Varp int COMPLETED_VARP = 2943, TOTAL_VARP = 2944;

    private static final Duration RECENT_DROP = Duration.ofSeconds(30L);
    @Inject
    public ClogHandler(DropTrackerPlugin plugin, ItemIDSearch itemIDFinder,
                            ScheduledExecutorService executor) {
        this.plugin = plugin;
        this.itemIDFinder = itemIDFinder;
    }


    static final Pattern COLLECTION_LOG_REGEX = Pattern.compile("New item added to your collection log: (?<itemName>(.*))");
    private static final int POPUP_PREFIX_LENGTH = "New item:".length();
    public static final String CG_NAME = "Corrupted Gauntlet", CG_BOSS = "Corrupted Hunllef";
    // private static final String TOA = "Tombs of Amascut";
    // private static final String TOB = "Theatre of Blood";
    // private static final String COX = "Chambers of Xeric";




    public boolean isEnabled() {
        return true;
    }


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
                String bottomText = plugin.sanitize(client.getVarcStrValue(VarClientStr.NOTIFICATION_BOTTOM_TEXT));
                processCollection(bottomText.substring(POPUP_PREFIX_LENGTH).trim());
            }
        }
    }

    // private boolean isCorruptedGauntlet(LootReceived event) {
    //     return event.getType() == LootRecordType.EVENT && lastDrop != null && "The Gauntlet".equals(event.getName())
    //             && (CG_NAME.equals(lastDrop.getSource()) || CG_BOSS.equals(lastDrop.getSource()));
    // }


    // private boolean shouldUseChatName(LootReceived event) {
    //     assert lastDrop != null;
    //     String lastSource = lastDrop.getSource();
    //     Predicate<String> coincides = source -> source.equals(event.getName()) && lastSource.startsWith(source);
    //     return coincides.test(TOA) || coincides.test(TOB) || coincides.test(COX);
    // }



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
        OptionalDouble itemRarity = ((loot != null) && (loot.getCategory() == LootRecordType.NPC)) ?
                rarity.getRarity(loot.getSource(), itemId, 1) : OptionalDouble.empty();
        CustomWebhookBody collectionLogBody = new CustomWebhookBody();
        CustomWebhookBody.Embed collEmbed = new CustomWebhookBody.Embed();
        collEmbed.addField("type", "collection_log",true);
        if (loot != null) {
            if (loot.getSource() != null) {
                Drop drop = getLootSource(itemId);
            }
        }
        collEmbed.addField("source", loot != null ? loot.getSource() : "unknown", true);
        collEmbed.addField("item", itemName, true);
        collEmbed.addField("kc", String.valueOf(killCount),true);
        collEmbed.addField("rarity", String.valueOf(itemRarity),true);
        collEmbed.addField("item_id", String.valueOf(itemId),true);
        collEmbed.addField("player", client.getLocalPlayer().getName(), true);
        collEmbed.addField("slots", completed + "/" + total, true);
        collEmbed.addField("p_v",plugin.pluginVersion,true);
        String accountHash = String.valueOf(client.getAccountHash());
        collEmbed.addField("acc_hash", accountHash, true);
        collectionLogBody.getEmbeds().add(collEmbed);
        plugin.sendDataToDropTracker(collectionLogBody, "2");
    }

    @Nullable
    private Drop getLootSource(int itemId) {
        Drop drop = KCService.getLastDrop();
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


