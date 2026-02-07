package io.droptracker.service;
/* Author: https://github.com/pajlads/DinkPlugin */

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import io.droptracker.DropTrackerPlugin;
import io.droptracker.models.SerializedDrop;
import io.droptracker.models.submissions.Drop;
import io.droptracker.util.NpcUtilities;
import io.droptracker.util.Rarity;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.api.NpcID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.chatcommands.ChatCommandsPlugin;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.loottracker.LootTrackerConfig;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;
import net.runelite.http.api.loottracker.LootRecordType;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Singleton
public class KCService {


    private static final String RL_CHAT_CMD_PLUGIN_NAME = ChatCommandsPlugin.class.getSimpleName().toLowerCase();
    private static final String RL_LOOT_PLUGIN_NAME = LootTrackerPlugin.class.getSimpleName().toLowerCase();
    private static final Pattern CLUE_SCROLL_REGEX = Pattern.compile("You have completed (?<scrollCount>\\d+) (?<scrollType>\\w+) Treasure Trails\\.");

    private ConfigManager configManager;


    private Gson gson;

    @Inject
    private ScheduledExecutorService executor;

    private Rarity rarityService;

    @Inject
    private DropTrackerPlugin plugin;

    private List<Integer> whispererIds = Arrays.asList(12204, 12205, 12206, 12207);


    private static final Cache<String, Integer> killCounts = CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .maximumSize(64L)
            .build();


    @Inject
    public KCService(ConfigManager configManager, Gson gson,
                     ScheduledExecutorService executor,
                     Rarity rarityService, DropTrackerPlugin plugin) {
        this.configManager = configManager;
        this.gson = gson;
        this.rarityService = rarityService;
        this.executor = executor;
        this.plugin = plugin;
    }

    public void reset() {
        plugin.lastDrop = null;
        KCService.killCounts.invalidateAll();
    }

    @SuppressWarnings("deprecation")
    public void onNpcKill(NpcLootReceived event) {
        NPC npc = event.getNpc();
        int id = npc.getId();
        if (whispererIds.contains(id)) {
            return;
        }

        String name = npc.getName();
        if (NpcUtilities.GAUNTLET_BOSS.equals(name) || NpcUtilities.CG_BOSS.equals(name)) {
            // already handled by onGameMessage
            return;
        }
        if (name != null) {
            this.incrementKills(LootRecordType.NPC, name, event.getItems());
        }
    }

    public void onPlayerKill(PlayerLootReceived event) {
        String name = event.getPlayer().getName();
        if (name != null) {
            this.incrementKills(LootRecordType.PLAYER, name, event.getItems());
        }
    }

    public void onLoot(LootReceived event) {
        boolean increment;
        switch (event.getType()) {
            case NPC:
                // Special case: upstream fires LootReceived for the whisperer, but not NpcLootReceived
                increment = "The Whisperer".equalsIgnoreCase(event.getName());
                break;
            case PLAYER:
                increment = false; // handled by PlayerLootReceived
                break;
            default:
                increment = true;
                break;
        }

        if (increment) {
            this.incrementKills(event.getType(), NpcUtilities.getStandardizedSource(event, plugin), event.getItems());
        }
    }
    public void onServerNpcLoot(ServerNpcLoot event) {
        /* Currently only called for Yama events */
        this.incrementKills(LootRecordType.NPC, event.getComposition().getName(), event.getItems());
    }

    public void onGameMessage(String message) {
        // update cached clue casket count
        Map.Entry<String, Integer> clue = parseClue(message);
        if (clue != null) {
            String tier = ucFirst(clue.getKey());
            int count = clue.getValue() - 1; // decremented since onLoot will increment
            killCounts.put("Clue Scroll (" + tier + ")", count);
            return;
        }

        NpcUtilities.parseBoss(message, plugin).ifPresent(pair -> {
            String boss = pair.getKey();
            Integer kc = pair.getValue();

            // Update cache. We store kc - 1 since onNpcLootReceived will increment; kc - 1 + 1 == kc
            String cacheKey = getCacheKey(LootRecordType.UNKNOWN, boss);
            killCounts.asMap().merge(cacheKey, kc - 1, Math::max);

            if (boss.equals(NpcUtilities.GAUNTLET_BOSS) || boss.equals(NpcUtilities.CG_BOSS) || boss.startsWith(NpcUtilities.TOA) || boss.startsWith(NpcUtilities.TOB) || boss.startsWith(NpcUtilities.COX)) {
                // populate lastDrop to workaround loot tracker quirks
                // (RuneLite fires LootReceived with "The Gauntlet" for both regular and corrupted)
                plugin.lastDrop = new Drop(boss, LootRecordType.EVENT, Collections.emptyList());

                if (!isPluginDisabled(RL_LOOT_PLUGIN_NAME)) {   
                    // onLoot will already increment kc, no need to schedule task below.
                    // this early return also simplifies our test code
                    return;
                }
            }

            // However: we don't know if boss message appeared before/after the loot event.
            // If after, we should store kc. If before, we should store kc - 1.
            // Given this uncertainty, we wait so that the loot event has passed, and then we can store latest kc.

            
            /* -- We are using the executor here -- */
            executor.schedule(() -> {
                killCounts.asMap().merge(cacheKey, kc, Math::max);
            }, 15, TimeUnit.SECONDS);
        });
    }

    @Nullable
    public Integer getKillCount(LootRecordType type, String sourceName) {
        if (sourceName == null) return null;
        // This static method is deprecated - use instance method instead
        return killCounts.getIfPresent(getCacheKey(type, sourceName));
    }

    @Nullable
    public Integer getKillCountWithStorage(LootRecordType type, String sourceName) {
        if (sourceName == null) return null;
        Integer stored = getStoredKillCount(type, sourceName);
        if (stored != null) {
            return killCounts.asMap().merge(getCacheKey(type, sourceName), stored, Math::max);
        }
        return killCounts.getIfPresent(getCacheKey(type, sourceName));
    }

    private void incrementKills(@NotNull LootRecordType type, @NotNull String sourceName, @NotNull Collection<ItemStack> items) {
        String cacheKey = getCacheKey(type, sourceName);
        killCounts.asMap().compute(cacheKey, (key, cachedKc) -> {
            if (cachedKc != null) {
                // increment kill count
                return cachedKc + 1;
            } else {
                // pull kc from loot tracker or chat commands plugin
                Integer kc = getStoredKillCount(type, sourceName);
                // increment if found
                return kc != null ? kc + 1 : null;
            }
        });
    }

    /**
     * @param type       {@link LootReceived#getType()}
     * @param sourceName {@link NPC#getName()} or {@link LootReceived#getName()}
     * @return the kill count stored by base runelite plugins
     */
    @Nullable
    private Integer getStoredKillCount(@NotNull LootRecordType type, @NotNull String sourceName) {
        // get kc from base runelite chat commands plugin (if enabled)
        if (!isPluginDisabled(RL_CHAT_CMD_PLUGIN_NAME)) {
            Integer kc = configManager.getRSProfileConfiguration("killcount", cleanBossName(sourceName), int.class);
            if (kc != null) {
                return kc - 1; // decremented since chat event typically occurs before loot event
            }
        }

        if (isPluginDisabled(RL_LOOT_PLUGIN_NAME)) {
            // assume stored kc is useless if loot tracker plugin is disabled
            return null;
        }
        String json = configManager.getConfiguration(LootTrackerConfig.GROUP,
                configManager.getRSProfileKey(),
                "drops_" + type + "_" + sourceName
        );
        if (json == null) {
            // no kc stored implies first kill
            return 0;
        }
        try {
            int kc = gson.fromJson(json, SerializedDrop.class).getKills();

            // loot tracker doesn't count kill if no loot - https://github.com/runelite/runelite/issues/5077
            OptionalDouble nothingProbability = rarityService.getRarity(sourceName, -1, 0);
            if (nothingProbability.isPresent() && nothingProbability.getAsDouble() < 1.0) {
                // estimate the actual kc (including kills with no loot)
                kc = (int) Math.round(kc / (1 - nothingProbability.getAsDouble()));
            }

            return kc;
        } catch (JsonSyntaxException e) {
            // should not occur unless loot tracker changes stored loot POJO structure
            log.warn("Failed to read kills from loot tracker config", e);
            return null;
        }
    }

    /**
     * @param boss {@link LootReceived#getName()}
     * @return lowercase boss name that {@link ChatCommandsPlugin} uses during serialization
     */
    private static String cleanBossName(String boss) {
        if ("The Gauntlet".equalsIgnoreCase(boss)) return "gauntlet";
        if ("The Leviathan".equalsIgnoreCase(boss)) return "leviathan";
        if ("The Whisperer".equalsIgnoreCase(boss)) return "whisperer";
        if (boss.startsWith("Barrows")) return "barrows chests";
        if (boss.endsWith("Hallowed Sepulchre)")) return "hallowed sepulchre";
        if (boss.endsWith("Tempoross)")) return "tempoross";
        if (boss.endsWith("Wintertodt)")) return "wintertodt";
        return StringUtils.remove(boss.toLowerCase(), ':');
    }

    private static String getCacheKey(@NotNull LootRecordType type, @NotNull String sourceName) {
        switch (type) {
            case PICKPOCKET:
                return "pickpocket_" + sourceName;
            case PLAYER:
                return "player_" + sourceName;
            default:
                if ("The Gauntlet".equals(sourceName)) return NpcUtilities.GAUNTLET_BOSS;
                if (NpcUtilities.CG_NAME.equals(sourceName)) return NpcUtilities.CG_BOSS;
                return sourceName;
        }
    }
    @Nullable
    public static Map.Entry<String, Integer> parseClue(String gameMessage) {
        Matcher clueMatcher = CLUE_SCROLL_REGEX.matcher(gameMessage);
        if (!clueMatcher.find()) return null;
        String tier = clueMatcher.group("scrollType");
        String count = clueMatcher.group("scrollCount");
        return Map.entry(tier, Integer.parseInt(count));
    }

    public String ucFirst(@NotNull String text) {
        if (text.length() < 2) return text.toUpperCase();
        return Character.toUpperCase(text.charAt(0)) + text.substring(1).toLowerCase();
    }

    protected boolean isPluginDisabled(String simpleLowerClassName) {
        return "false".equals(configManager.getConfiguration(RuneLiteConfig.GROUP_NAME, simpleLowerClassName));
    }
}
