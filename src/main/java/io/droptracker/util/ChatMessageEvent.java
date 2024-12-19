package io.droptracker.util;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import io.droptracker.DropTrackerConfig;
import io.droptracker.models.BossNotification;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.models.CombatAchievement;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.Drop;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.annotations.Varbit;
import net.runelite.api.annotations.Varp;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.chatcommands.ChatCommandsPlugin;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;
import net.runelite.http.api.loottracker.LootRecordType;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.Temporal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.droptracker.util.KCService.lastDrop;
import static java.time.temporal.ChronoField.*;
import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;

@Slf4j
public class ChatMessageEvent {
    @Inject
    private final DropTrackerPlugin plugin;
    private final DropTrackerConfig config;

    @Inject
    protected Client client;

    @Inject
    private Rarity rarity;

    @Inject
    private ClientThread clientThread;

    private ItemIDSearch itemIDFinder;
    private final AtomicInteger completed = new AtomicInteger(-1);
    private final AtomicBoolean popupStarted = new AtomicBoolean(false);
    public static final @Varp int COMPLETED_VARP = 2943, TOTAL_VARP = 2944;
    private static final Duration TIME_MESSAGE_WINDOW = Duration.ofSeconds(10);
    private final Map<String, TimeData> recentTimeData = new HashMap<>();

    private static final Duration RECENT_DROP = Duration.ofSeconds(30L);
    @Inject
    public ChatMessageEvent(DropTrackerPlugin plugin, DropTrackerConfig config, ItemIDSearch itemIDFinder, 
                           ScheduledExecutorService executor) {
        this.plugin = plugin;
        this.config = config;
        this.itemIDFinder = itemIDFinder;
        this.executor = executor;
    }
    private static final Pattern ACHIEVEMENT_PATTERN = Pattern.compile("Congratulations, you've completed an? (?<tier>\\w+) combat task: (?<task>.+)\\.");
    private static final Pattern TASK_POINTS = Pattern.compile("\\s+\\(\\d+ points?\\)$");
    @Varbit
    public static final int COMBAT_TASK_REPEAT_POPUP = 12456;
    /**
     * @see <a href="https://github.com/Joshua-F/cs2-scripts/blob/master/scripts/%5Bproc,ca_tasks_progress_bar%5D.cs2#L6-L11">CS2 Reference</a>
     */
    @VisibleForTesting
    public static final Map<CombatAchievement, Integer> CUM_POINTS_VARBIT_BY_TIER;

    /**
     * The cumulative points needed to unlock rewards for each tier, in a Red-Black tree.
     * <p>
     * This is populated by {@link #initThresholds()} based on {@link #CUM_POINTS_VARBIT_BY_TIER}.
     *
     * @see <a href="https://gachi.gay/01CAv">Rewards Thresholds at the launch of the points-based system</a>
     */
    private final NavigableMap<Integer, CombatAchievement> cumulativeUnlockPoints = new TreeMap<>();

    private static final Pattern PRIMARY_REGEX = Pattern.compile("Your (?<key>.+)\\s(?<type>kill|chest|completion)\\s?count is: (?<value>[\\d,]+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECONDARY_REGEX = Pattern.compile(
        "Your (?:completed|subdued) (?<key>Tombs of Amascut(?:: Expert Mode)?|Theatre of Blood(?:: Hard Mode)?|Chambers of Xeric(?: Challenge Mode)?)(?:\\s+count\\s+is:?|\\s+completion\\s+count\\s+is:?)\\s*(?<value>[\\d,]+)\\.?",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TIME_REGEX = Pattern.compile(
            "(?:(?:Challenge )?Duration|Challenge duration|time|Subdued in):? (?<time>[\\d:]+(?:\\.\\d+)?)(?:\\. Personal best: (?<bestTime>[\\d:]+(?:\\.\\d+)?))?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GAUNTLET_TIME_REGEX = Pattern.compile(
            "Challenge duration: (?<time>[\\d:]+)\\. Personal best: (?<bestTime>[\\d:]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final String BA_BOSS_NAME = "Penance Queen";
    public static final String GAUNTLET_NAME = "Gauntlet", GAUNTLET_BOSS = "Crystalline Hunllef";

    static final Pattern COLLECTION_LOG_REGEX = Pattern.compile("New item added to your collection log: (?<itemName>(.*))");
    public static final String ADDITION_WARNING = "Collection notifier will not fire unless you enable the game setting: Collection log - New addition notification";
    private static final int POPUP_PREFIX_LENGTH = "New item:".length();
    public static final String CG_NAME = "Corrupted Gauntlet", CG_BOSS = "Corrupted Hunllef";
    private static final String TOA = "Tombs of Amascut";
    private static final String TOB = "Theatre of Blood";
    private static final String COX = "Chambers of Xeric";
    private static final String RL_CHAT_CMD_PLUGIN_NAME = ChatCommandsPlugin.class.getSimpleName().toLowerCase();
    private static final String RL_LOOT_PLUGIN_NAME = LootTrackerPlugin.class.getSimpleName().toLowerCase();
    @Varbit
    public static final int TOTAL_POINTS_ID = 14815;
    @Varbit
    public static final int GRANDMASTER_TOTAL_POINTS_ID = 14814;
    @VisibleForTesting
    static final int MAX_BAD_TICKS = 10;

    private final AtomicInteger badTicks = new AtomicInteger();
    private final AtomicReference<BossNotification> bossData = new AtomicReference<>();

    private static Pair<String, Integer> mostRecentNpcData = null;

    private static Integer ticksSinceNpcDataUpdate = 0;

    @Varbit
    public static final int KILL_COUNT_SPAM_FILTER = 4930;

    private static final long MESSAGE_LOOT_WINDOW = 15000; // 15 seconds
    private final Map<String, BossNotification> pendingNotifications = new HashMap<>();
    private final ScheduledExecutorService executor;


    private static final Pattern BOSS_TIME_PATTERN = Pattern.compile("Fight duration: (\\d+:\\d+)\\. Personal best: (\\d+:\\d+)");
    private static final Duration KILL_MESSAGE_WINDOW = Duration.ofSeconds(2);
    private static final long TIME_MESSAGE_DELAY = 1000;
    private final Map<String,TimeData> pendingTimeData = new HashMap<>();
    private final Set<String> processedKills = new HashSet<>();


    public boolean isEnabled() {
        return true;
    }

    public void onGameMessage(String message) {
        if (!isEnabled()) return;

        System.out.println("Game message received: '" + message + "'");

        // Check for time messages first
        for (Pattern pattern : TIME_PATTERNS) {
            Matcher timeMatcher = pattern.matcher(message);
            if (timeMatcher.find()) {
                System.out.println("Found time message matching pattern: " + pattern.pattern());
                try {
                    // Debug the full match
                    System.out.println("Full match: " + timeMatcher.group(0));
                    String timeStr = timeMatcher.group(1);
                    String bestTimeStr = timeMatcher.group(2);
                    
                    System.out.println("Group 1 (Duration): " + timeStr);
                    System.out.println("Group 2 (Personal Best): " + bestTimeStr);
                    
                    // Validate time format before parsing
                    if (!timeStr.matches("\\d+:\\d+")) {
                        System.out.println("Invalid duration format: " + timeStr);
                        continue;
                    }
                    
                    Duration time = parseTime(timeStr);
                    Duration bestTime = parseTime(bestTimeStr);
                    boolean isPb = message.contains("new personal best") || message.contains("(Personal best)");

                    // Determine boss type and store time data
                    if (message.contains("Team Size:")) {
                        // Handle CoX time data for both regular and CM
                        storeBossTimeData("Chambers of Xeric", time, bestTime, isPb);
                        storeBossTimeData("Chambers of Xeric Challenge Mode", time, bestTime, isPb);
                    } else if (message.contains("Tombs of Amascut: Expert Mode")) {
                        storeBossTimeData("Tombs of Amascut: Expert Mode", time, bestTime, isPb);
                    } else if (message.contains("Tombs of Amascut")) {
                        storeBossTimeData("Tombs of Amascut", time, bestTime, isPb);
                    } else if (message.contains("Corrupted challenge")) {
                        storeBossTimeData("Corrupted Hunllef", time, bestTime, isPb);
                    } else if (message.contains("Challenge duration")) {
                        storeBossTimeData("Crystalline Hunllef", time, bestTime, isPb);
                    } else {
                        // Handle regular boss time data
                        String bossName = mostRecentNpcData != null ? mostRecentNpcData.getLeft() : null;
                        if (bossName != null) {
                            storeBossTimeData(bossName, time, bestTime, isPb);
                        }
                    }
                    
                    System.out.println("Successfully processed time data for message: " + message);
                } catch (Exception e) {
                    System.out.println("Error parsing time message: " + message + " - " + e.getMessage());
                    e.printStackTrace();
                }
                return;
            }
        }

        // Kill count message handling
        parseBossKill(message).ifPresent(notification -> {
            String bossName = notification.getBoss();
            System.out.println("Found kill count message for " + bossName);

            // Check for pending time data
            TimeData timeData = pendingTimeData.get(bossName);
            if (timeData != null) {
                BossNotification withTime = new BossNotification(
                        bossName,
                        notification.getCount(),
                        notification.getGameMessage(),
                        timeData.time,
                        timeData.bestTime,
                        timeData.isPb
                );
                System.out.println("Created notification with pending time data: " + withTime);
                bossData.set(withTime);
                processKill(withTime);
            } else {
                // Store notification and wait for time data
                System.out.println("Storing notification without time data for: " + bossName);
                bossData.set(notification);

                // Only schedule time check for non-raid bosses or if we haven't processed this kill yet
                if (!RAID_STYLE_BOSSES.contains(bossName)) {
                    scheduleTimeDataCheck(notification);
                } else {
                    // For raid bosses, process immediately as time should have been received
                    System.out.println("Processing raid boss kill without time data: " + bossName);
                    processKill(notification);
                }
            }
        });
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

    private boolean isCorruptedGauntlet(LootReceived event) {
        return event.getType() == LootRecordType.EVENT && lastDrop != null && "The Gauntlet".equals(event.getName())
                && (CG_NAME.equals(lastDrop.getSource()) || CG_BOSS.equals(lastDrop.getSource()));
    }

    public void onFriendsChatNotification(String message) {
        /* Chambers of Xeric completions are sent in the Friends chat channel */
        if (message.startsWith("Congratulations - your raid is complete!"))
            this.onGameMessage(message);
    }

    public String getStandardizedSource(LootReceived event) {
        if (isCorruptedGauntlet(event)) {
            return CG_NAME;
        } else if (lastDrop != null && shouldUseChatName(event)) {
            return lastDrop.getSource(); // distinguish entry/expert/challenge modes
        }
        return event.getName();
    }

    private boolean shouldUseChatName(LootReceived event) {
        assert lastDrop != null;
        String lastSource = lastDrop.getSource();
        Predicate<String> coincides = source -> source.equals(event.getName()) && lastSource.startsWith(source);
        return coincides.test(TOA) || coincides.test(TOB) || coincides.test(COX);
    }

    public void onWidget(WidgetLoaded event) {
        if (!isEnabled())
            return;
        /* Handle BA events */
        if (event.getGroupId() == InterfaceID.BA_REWARD) {
            Widget widget = client.getWidget(ComponentID.BA_REWARD_REWARD_TEXT);
            if (widget != null && widget.getText().contains("80 ") && widget.getText().contains("5 ")) {
                int gambleCount = client.getVarbitValue(Varbits.BA_GC);
                BossNotification notification = new BossNotification(BA_BOSS_NAME, gambleCount, "The Queen is dead!", null, null,null);
                bossData.set(notification);
            }
        }
    }
    public void onTick() {
        BossNotification data = this.bossData.get();
        if (data != null) {
            if (data.getBoss() != null) {
                if (isEnabled()) {
                    processKill(data);
                }
                reset();
            } else if (badTicks.incrementAndGet() > MAX_BAD_TICKS) {
                reset();
            }
        }
        if (mostRecentNpcData != null) {
            ticksSinceNpcDataUpdate += 1;
        } else {
            if (ticksSinceNpcDataUpdate > 1)  {
                ticksSinceNpcDataUpdate = 0;
            }
        }
        if (ticksSinceNpcDataUpdate >= 5 && mostRecentNpcData != null) {
            mostRecentNpcData = null;
        }

        // Clean up old time messages
        if (processedKills.size() > 100) { // Prevent memory leak
            processedKills.clear();
            System.out.println("Cleared processed kills cache");
        }
    }
    private void processCollection(String itemName) {
        int completed = this.completed.updateAndGet(i -> i >= 0 ? i + 1 : i);
        int total = client.getVarpValue(TOTAL_VARP);
        boolean varpValid = total > 0 && completed > 0;
        if (!varpValid) {
            // This occurs if the player doesn't have the character summary tab selected
            log.debug("Collection log progress varps were invalid ({} / {})", completed, total);
        }
        Integer itemId = itemIDFinder.findItemId(itemName);
        Drop loot = itemId != null ? getLootSource(itemId) : null;
        Integer killCount = loot != null ? KCService.getKillCount(loot.getCategory(), loot.getSource()) : null;
        OptionalDouble itemRarity = ((loot != null) && (loot.getCategory() == LootRecordType.NPC)) ?
                rarity.getRarity(loot.getSource(), itemId, 1) : OptionalDouble.empty();
        CustomWebhookBody collectionLogBody = new CustomWebhookBody();
        CustomWebhookBody.Embed collEmbed = new CustomWebhookBody.Embed();
        collEmbed.addField("type", "collection_log",true);
        collEmbed.addField("source", loot != null ? loot.getSource() : "unknown", true);
        collEmbed.addField("item", itemName, true);
        collEmbed.addField("kc", String.valueOf(killCount),true);
        collEmbed.addField("rarity", String.valueOf(itemRarity),true);
        collEmbed.addField("item_id", String.valueOf(itemId),true);
        collEmbed.addField("player", client.getLocalPlayer().getName(), true);
        collEmbed.addField("slots", total + "/" + completed, true);
        collEmbed.addField("auth_key", config.token(), true);
        String accountHash = String.valueOf(client.getAccountHash());
        collEmbed.addField("acc_hash", accountHash, true);
        collectionLogBody.getEmbeds().add(collEmbed);

        plugin.sendDropTrackerWebhook(collectionLogBody, "2");
    }

    private void processCombatAchievement(CombatAchievement tier, String task) {
        // delay notification for varbits to be updated
        clientThread.invokeAtTickEnd(() -> {
            int taskPoints = tier.getPoints();
            int totalPoints = client.getVarbitValue(TOTAL_POINTS_ID);

            Integer nextUnlockPointsThreshold = cumulativeUnlockPoints.ceilingKey(totalPoints + 1);
            Map.Entry<Integer, CombatAchievement> prev = cumulativeUnlockPoints.floorEntry(totalPoints);
            int prevThreshold = prev != null ? prev.getKey() : 0;

            Integer tierProgress, tierTotalPoints;
            if (nextUnlockPointsThreshold != null) {
                tierProgress = totalPoints - prevThreshold;
                tierTotalPoints = nextUnlockPointsThreshold - prevThreshold;
            } else {
                tierProgress = tierTotalPoints = null;
            }

            boolean crossedThreshold = prevThreshold > 0 && totalPoints - taskPoints < prevThreshold;
            CombatAchievement completedTier = crossedThreshold ? prev.getValue() : null;
            String completedTierName = completedTier != null ? completedTier.getDisplayName() : "N/A";

            String player = client.getLocalPlayer().getName();
            CustomWebhookBody combatWebhook = new CustomWebhookBody();
            combatWebhook.setContent(player + " has completed a new combat task:");
            CustomWebhookBody.Embed combatAchievementEmbed = new CustomWebhookBody.Embed();
            combatAchievementEmbed.addField("type", "combat_achievement",true);
            combatAchievementEmbed.addField("tier", tier.toString(),true);
            combatAchievementEmbed.addField("task", task,true);
            combatAchievementEmbed.addField("points", String.valueOf(taskPoints),true);
            combatAchievementEmbed.addField("total_points", String.valueOf(totalPoints),true);
            combatAchievementEmbed.addField("completed", completedTierName,true);
            combatAchievementEmbed.addField("auth_key", config.token(), true);
            String accountHash = String.valueOf(client.getAccountHash());
            combatAchievementEmbed.addField("acc_hash", accountHash, true);
            combatWebhook.getEmbeds().add(combatAchievementEmbed);
            plugin.sendDropTrackerWebhook(combatWebhook, "3");
        });
    }


    private void processKill(BossNotification data) {
        System.out.println("About to process kill Notification: " + data);
        if (data.getBoss() == null || data.getCount() == null) {
            System.out.println("Missing Boss or Count Data");
            return;
        }
        String killKey = data.getBoss() + "-" + data.getCount();

        // Check if we've already processed this kill
        if (processedKills.contains(killKey)) {
            System.out.println("Kill already processed, skipping: " + killKey);
            return;
        }

        // Add to processed kills before sending webhook
        processedKills.add(killKey);

        clientThread.invoke(() -> {
            String player = plugin.getLocalPlayerName();
            CustomWebhookBody.Embed killEmbed = new CustomWebhookBody.Embed();
            killEmbed.setTitle(player + " has killed a boss:");
            killEmbed.addField("type", "npc_kill", true);
            killEmbed.addField("boss_name", data.getBoss(), true);
            killEmbed.addField("player_name", player, true);

            // Add time data if available
            if (data.getTime() != null) {
                killEmbed.addField("kill_time", formatTime(data.getTime(), isPreciseTiming(client)), true);
            }
            if (data.getBestTime() != null) {
                killEmbed.addField("best_time", formatTime(data.getBestTime(), isPreciseTiming(client)), true);
            }
            killEmbed.addField("is_pb", String.valueOf(data.isPersonalBest()), true);

            killEmbed.addField("auth_key", config.token(), true);
            String accountHash = String.valueOf(client.getAccountHash());
            killEmbed.addField("acc_hash", accountHash, true);

            CustomWebhookBody killWebhook = new CustomWebhookBody();
            killWebhook.getEmbeds().add(killEmbed);
            plugin.sendDropTrackerWebhook(killWebhook, "1");
        });
    }

    private void updateData(BossNotification notification) {
        final String bossName = notification.getBoss();

        bossData.getAndUpdate(old -> {
            if (old == null) {
                // Schedule a check for time data
                executor.schedule(() -> {
                    BossNotification current = bossData.get();
                    if (current != null && current.getBoss().equals(bossName) && current.getTime() == null) {
                        TimeData timeData = recentTimeData.get(bossName);
                        if (timeData != null && timeData.isRecent()) {
                            BossNotification withTime = new BossNotification(
                                    current.getBoss(),
                                    current.getCount(),
                                    current.getGameMessage(),
                                    timeData.time,
                                    timeData.bestTime,
                                    timeData.isPb
                            );
                            bossData.set(withTime);
                            System.out.println("Updated notification with delayed time data: "+ withTime);
                            processKill(withTime);
                        } else {
                            System.out.println("Processing kill without time data");
                            processKill(current);
                        }
                    }
                }, KILL_MESSAGE_WINDOW.toMillis(), TimeUnit.MILLISECONDS);

                return notification;
            } else {
                return old; // Keep existing data
            }
        });
    }

    public void reset() {
        bossData.set(null);
        badTicks.set(0);
    }
    @VisibleForTesting
    static Optional<Pair<CombatAchievement, String>> parseCombatAchievement(String message) {
        Matcher matcher = ACHIEVEMENT_PATTERN.matcher(message);
        if (!matcher.find()) return Optional.empty();
        return Optional.of(matcher.group("tier"))
                .map(CombatAchievement.TIER_BY_LOWER_NAME::get)
                .map(tier -> Pair.of(
                        tier,
                        TASK_POINTS.matcher(
                                matcher.group("task")
                        ).replaceFirst("") // remove points suffix
                ));
    }

    private Optional<BossNotification> parseBossKill(String message) {
        System.out.println("Attempting to parse boss kill from message: " + message);
        return parseBoss(message).map(pair -> {
            String bossName = pair.getLeft();
            Integer killCount = pair.getRight();
            System.out.println("Successfully parsed boss kill - Name: " + bossName + ", KC: " + killCount);

            // Look for recent time data for this boss
            TimeData timeData = recentTimeData.get(bossName);
            if (timeData != null && timeData.isRecent()) {
                System.out.println("Found recent time data for " + bossName + ": " + timeData);
                return new BossNotification(
                    bossName,
                    killCount,
                    message,
                    timeData.time,
                    timeData.bestTime,
                    timeData.isPb
                );
            }
            
            System.out.println("No recent time data found for " + bossName);
            return new BossNotification(bossName, killCount, message, null, null, null);
        });
    }
    private Optional<Triple<Duration, Duration, Boolean>> parseKillTime(String message) {
        System.out.println("Attempting to parse kill time from message: " + message);

        for (Pattern pattern : TIME_PATTERNS) {
            Matcher matcher = pattern.matcher(message);
            if (matcher.find()) {
                System.out.println("Matched pattern: " + pattern.pattern());

                try {
                    Duration time = parseTime(matcher.group(1));
                    Duration bestTime = parseTime(matcher.group(2));
                    boolean isPb = message.toLowerCase().contains("new personal best");

                    System.out.println(String.format("Parsed time: %s, best time: %s, isPB: %s", 
                        time, bestTime, isPb));
                    return Optional.of(Triple.of(time, bestTime, isPb));
                } catch (IllegalArgumentException e) {
                    System.out.println("Error parsing time values: " + e.getMessage());
                }
            }
        }

        System.out.println("No time pattern matched");
        return Optional.empty();
    }
    @NotNull
    public static Duration parseTime(@NotNull String in) {
        Pattern TIME_PATTERN = Pattern.compile("\\b(?:(?<hours>\\d+):)?(?<minutes>\\d+):(?<seconds>\\d{2})(?:\\.(?<fractional>\\d{2}))?\\b");
        Matcher m = TIME_PATTERN.matcher(in);
        if (!m.find()) return Duration.ZERO;

        int minutes = Integer.parseInt(m.group("minutes"));
        int seconds = Integer.parseInt(m.group("seconds"));

        Duration d = Duration.ofMinutes(minutes).plusSeconds(seconds);

        String hours = m.group("hours");
        if (hours != null) {
            d = d.plusHours(Integer.parseInt(hours));
        }

        String fractional = m.group("fractional");
        if (fractional != null) {
            String f = fractional.length() < 3 ? StringUtils.rightPad(fractional, 3, '0') : fractional.substring(0, 3);
            d = d.plusMillis(Integer.parseInt(f));
        }

        return d;
    }

    static Optional<Pair<String, Integer>> parseBoss(String message) {
        Matcher primary = PRIMARY_REGEX.matcher(message);
        Matcher secondary = SECONDARY_REGEX.matcher(message);
        
        if (primary.find()) {
            String boss = parsePrimaryBoss(primary.group("key"), primary.group("type"));
            String count = primary.group("value");
            if (boss != null) {
                try {
                    int killCount = Integer.parseInt(count.replace(",", ""));
                    mostRecentNpcData = Pair.of(boss, killCount);
                    ticksSinceNpcDataUpdate = 0;
                    return Optional.of(mostRecentNpcData);
                } catch (NumberFormatException e) {
                    System.out.println("Failed to parse kill count " + count + " for boss " + boss);
                }
            }
        } else if (secondary.find()) {
            String key = secondary.group("key");
            String value = secondary.group("value");
            System.out.println("Found secondary match - key: " + key + ", value: " + value);
            
            // Handle Tombs of Amascut and other raid-style bosses
            if (key != null && (key.contains("Tombs of Amascut") || 
                              key.contains("Theatre of Blood") || 
                              key.contains("Chambers of Xeric"))) {
                try {
                    int killCount = Integer.parseInt(value.replace(",", ""));
                    mostRecentNpcData = Pair.of(key, killCount);
                    ticksSinceNpcDataUpdate = 0;
                    return Optional.of(mostRecentNpcData);
                } catch (NumberFormatException e) {
                    System.out.println("Failed to parse kill count " + value + " for boss " + key);
                }
            }
        }
        return Optional.empty();
    }


    private static Optional<Pair<String, Integer>> result(String boss, String count) {
        try {
            return Optional.ofNullable(boss).map(k -> Pair.of(boss, Integer.parseInt(count)));
        } catch (NumberFormatException e) {
            System.out.println("Failed to parse kill count " + count + "for boss" + boss);
            return Optional.empty();
        }
    }

    @Nullable
    private static String parsePrimaryBoss(String boss, String type) {
        switch (type.toLowerCase()) {
            case "chest":
                if ("Barrows".equalsIgnoreCase(boss))
                    return boss;
                if ("Lunar".equals(boss))
                    return boss + " " + type;
                return null;

            case "completion":
                if (GAUNTLET_NAME.equalsIgnoreCase(boss))
                    return GAUNTLET_BOSS;
                if (CG_NAME.equalsIgnoreCase(boss))
                    return CG_BOSS;
                return null;

            case "kill":
                return boss;

            default:
                return null;
        }
    }

    private static String parseSecondary(String boss) {
        if (boss == null || "Wintertodt".equalsIgnoreCase(boss)) {
            return boss;
        }

        // Handle raid names with their modes
        if (boss.contains("Tombs of Amascut") || 
            boss.contains("Theatre of Blood") || 
            boss.contains("Chambers of Xeric")) {
            return boss;  // Return the full name including mode
        }

        return null;
    }



    @NotNull
    public String formatTime(@Nullable Duration duration, boolean precise) {
        Temporal time = ObjectUtils.defaultIfNull(duration, Duration.ZERO).addTo(LocalTime.of(0, 0));
        StringBuilder sb = new StringBuilder();

        int h = time.get(HOUR_OF_DAY);
        if (h > 0)
            sb.append(String.format("%02d", h)).append(':');

        sb.append(String.format("%02d", time.get(MINUTE_OF_HOUR))).append(':');
        sb.append(String.format("%02d", time.get(SECOND_OF_MINUTE)));

        if (precise)
            sb.append('.').append(String.format("%02d", time.get(MILLI_OF_SECOND) / 10));

        return sb.toString();
    }

    public boolean isPreciseTiming(@NotNull Client client) {
        @Varbit int ENABLE_PRECISE_TIMING = 11866;
        return client.getVarbitValue(ENABLE_PRECISE_TIMING) > 0;
    }

    private void initThresholds() {
        CUM_POINTS_VARBIT_BY_TIER.forEach((tier, varbitId) -> {
            int cumulativePoints = client.getVarbitValue(varbitId);
            if (cumulativePoints > 0)
                cumulativeUnlockPoints.put(cumulativePoints, tier);
        });
    }
    static {
        CUM_POINTS_VARBIT_BY_TIER = ImmutableMap.<CombatAchievement, Integer>builderWithExpectedSize(6)
                .put(CombatAchievement.EASY, 4132) // 33 = 33 * 1
                .put(CombatAchievement.MEDIUM, 10660) // 115 = 33 + 41 * 2
                .put(CombatAchievement.HARD, 10661) // 304 = 115 + 63 * 3
                .put(CombatAchievement.ELITE, 14812) // 820 = 304 + 129 * 4
                .put(CombatAchievement.MASTER, 14813) // 1465 = 820 + 129 * 5
                .put(CombatAchievement.GRANDMASTER, GRANDMASTER_TOTAL_POINTS_ID) // 2005 = 1465 + 90 * 6
                .build();
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

    public void onLootReceived(LootReceived event) {
        String source = getStandardizedSource(event);
        BossNotification pending = pendingNotifications.remove(source);
        
        if (pending != null) {
            // We found a pending notification for this boss, process it now
            processKill(pending);
        } else {
            // Store the loot event info for later matching with chat message
            lastDrop = new Drop(source, event.getType(), event.getItems());
        }
    }
    // Create a class to hold time-related data
    private static class TimeData {
        final Duration time;
        final Duration bestTime;
        final boolean isPb;
        final Instant timestamp;

        TimeData(Duration time, Duration bestTime, boolean isPb) {
            this.time = time;
            this.bestTime = bestTime;
            this.isPb = isPb;
            this.timestamp = Instant.now();
        }

        boolean isRecent() {
            return Duration.between(timestamp, Instant.now()).compareTo(Duration.ofSeconds(2)) <= 0;
        }
        @Override
        public String toString(){
            return String.format("TimeData(time=%s, bestTime=%s, isPb%s)",time,bestTime,isPb);
        }
    }
    private static final String TEAM_SIZES = "(?<teamsize>\\d+(?:\\+|-\\d+)? players?|Solo)";
    
    private static final Pattern[] TIME_PATTERNS = {
        // CoX pattern
        Pattern.compile("Team Size: .+? Duration: (\\d+:\\d+) Personal Best: (\\d+:\\d+)"),
        // ToA patterns
        Pattern.compile("Tombs of Amascut total completion time: (\\d+:\\d+)\\. Personal Best: (\\d+:\\d+)"),
        Pattern.compile("Tombs of Amascut: Expert Mode total completion time: (\\d+:\\d+)\\. Personal Best: (\\d+:\\d+)"),
        // Gauntlet patterns
        Pattern.compile("Corrupted challenge duration: (\\d+:\\d+)\\. Personal best: (\\d+:\\d+)"),
        Pattern.compile("Challenge duration: (\\d+:\\d+)\\. Personal best: (\\d+:\\d+)"),
        // Standard boss pattern
        Pattern.compile("Fight duration: (\\d+:\\d+)\\. Personal best: (\\d+:\\d+)"),
    };
    private String determineBossFromMessage(String message) {
        // Theatre of Blood variants
        if (message.contains("Theatre of Blood total completion time:")) {
            return "Theatre of Blood: Hard Mode";
        }
        if (message.contains("Theatre of Blood completion time:")) {
            return "Theatre of Blood";
        }

        // Other raid-style bosses
        if (message.contains("Corrupted challenge")) return "Corrupted Hunllef";
        if (message.contains("Challenge duration")) return "Crystalline Hunllef";
        if (message.contains("Expert Mode")) return "Tombs of Amascut: Expert Mode";
        if (message.contains("Tombs of Amascut")) return "Tombs of Amascut";
        if (message.contains("Challenge Mode")) return "Chambers of Xeric Challenge Mode";
        if (message.contains("Raid complete") || message.contains("Challenge complete")) return "Chambers of Xeric";

        // For standard boss fights (like Zulrah), use the most recent NPC data
        return mostRecentNpcData != null ? mostRecentNpcData.getLeft() : null;
    }
    private static final Set<String> RAID_STYLE_BOSSES = Set.of(
            "Crystalline Hunllef",      // Regular Gauntlet
            "Corrupted Hunllef",        // Corrupted Gauntlet
            "Tombs of Amascut",
            "Tombs of Amascut: Expert Mode",
            "Theatre of Blood",
            "Theatre of Blood: Hard Mode",
            "Chambers of Xeric",
            "Chambers of Xeric Challenge Mode"
    );
    private void updateExistingNotification(String bossName, TimeData timeData) {
        BossNotification current = bossData.get();
        if (current != null && current.getBoss().equals(bossName)) {
            BossNotification withTime = new BossNotification(
                    current.getBoss(),
                    current.getCount(),
                    current.getGameMessage(),
                    timeData.time,
                    timeData.bestTime,
                    timeData.isPb
            );
            bossData.set(withTime);
            System.out.println("Updated existing notification with time data: " + withTime);
            processKill(withTime);
        }
    }
    private void scheduleTimeDataCheck(BossNotification notification) {
        executor.schedule(() -> {
            BossNotification current = bossData.get();
            if (current != null && current.getBoss().equals(notification.getBoss())) {
                TimeData timeData = pendingTimeData.get(notification.getBoss());
                if (timeData != null) {
                    BossNotification withTime = new BossNotification(
                            current.getBoss(),
                            current.getCount(),
                            current.getGameMessage(),
                            timeData.time,
                            timeData.bestTime,
                            timeData.isPb
                    );
                    bossData.set(withTime);
                    System.out.println("Processing kill with delayed time data: " + withTime);
                    processKill(withTime);
                } else {
                    System.out.println("No time data found after delay, processing without time: " + current);
                    processKill(current);
                }
            }
        }, TIME_MESSAGE_DELAY, TimeUnit.MILLISECONDS);
    }
    
    private void storeBossTimeData(String bossName, Duration time, Duration bestTime, boolean isPb) {
        TimeData timeData = new TimeData(time, bestTime, isPb);
        pendingTimeData.put(bossName, timeData);
        recentTimeData.put(bossName, timeData);
        System.out.println("Stored time data for " + bossName + " - Time: " + time + ", BestTime: " + bestTime);

        // Update existing notification if we have one
        BossNotification current = bossData.get();
        if (current != null && current.getBoss().equals(bossName)) {
            BossNotification withTime = new BossNotification(
                current.getBoss(),
                current.getCount(),
                current.getGameMessage(),
                time,
                bestTime,
                isPb
            );
            bossData.set(withTime);
            System.out.println("Updated existing notification with time data: " + withTime);
            processKill(withTime);
        }
    }

    @VisibleForTesting
    public void generateTestMessage() {
        //Chambers of Xeric Challenge Mode Test
        /* 
          onGameMessage("Congratulations - Your raid is complete!");        
          onGameMessage("Team Size: 3 players Duration: 32:32 Personal Best: 28:26 Olm Duration: 4:11");
          onGameMessage("Your completed Chambers of Xeric Challenge Mode count is 61.");
          
        */
        //Chambers of Xeric Test
         /* 
          onGameMessage("Congratulations - Your raid is complete!");        
          onGameMessage("Team Size: 3 players Duration: 27:32 Personal Best: 22:26 Olm Duration: 4:11");
          onGameMessage("Your completed Chambers of Xeric count is 52.");
         */
        
       //Chambers of Xeric Challenge Mode PB Test
        /* 
         * onGameMessage("Congratulations - Your raid is complete!");        
         * onGameMessage("Team Size: 3 players Duration: 27:32 Personal Best: 22:26 Olm Duration: 4:11");
         * onGameMessage("Your completed Chambers of Xeric Challenge Mode count is 52.");
         * 
        */
        //Tombs of Amascut Test
        /*  
          onGameMessage("Challenge complete: The Wardens. Duration: 3:02");        
          onGameMessage("Tombs of Amascut challenge completion time: 14:40. Personal Best: 12:16");
          onGameMessage("Tombs of Amascut total completion time: 16:37. Personal Best: 14:37");
          onGameMessage("Your completed Tombs of Amascut count is 15.");
        */  
        
        //Tombs of Amascut Expert Mode Test
        /*  
          onGameMessage("Challenge complete: The Wardens. Duration: 3:02");        
          onGameMessage("Tombs of Amascut: Expert Mode challenge completion time: 20:40. Personal Best: 18:16");
          onGameMessage("Tombs of Amascut: Expert Mode total completion time: 23:37. Personal Best: 20:37");
          onGameMessage("Your completed Tombs of Amascut: Expert Mode count is 20.");
        */  
        
       //Tombs of Amascut PB Test  
        /* 
          onGameMessage("Challenge complete: The Wardens. Duration: 3:02");         
          onGameMessage("Tombs of Amascut challenge completion time: 12:16 (new personal best)");
          onGameMessage("Tombs of Amascut total completion time: 14:37 (new personal best)");
          onGameMessage("Your completed Tombs of Amascut count is 10.");
          
        */
        //Theatre of Blood Test
        /* 
          onGameMessage("Theatre of Blood completion time: 18:12. Personal best: 17:09");
          onGameMessage("Theatre of Blood total completion time: 23:01. Personal best: 21:41");        
          onGameMessage("Your completed Theatre of Blood count is 11.");
          
        */
        //Theatre of Blood Hard Mode Test
        /* 
          onGameMessage("Theatre of Blood completion time: 25:12. Personal best: 23:09");
          onGameMessage("Theatre of Blood total completion time: 28:01. Personal best: 25:41");        
          onGameMessage("Your completed Theatre of Blood: Hard Mode count is 11.");
          
        */
        //Theatre of Blood Hard Mode PB Test
        /* 
          onGameMessage("Congratulations - Your raid is complete!");        
          onGameMessage("Team Size: 3 players Duration: 32:32 Personal Best: 28:26 Olm Duration: 4:11");
          onGameMessage("Your completed Chambers of Xeric Challenge Mode count is 61.");
          
        */
        //Gauntlet Test
        /* 
          onGameMessage("Challenge duration: 3:06. Personal best: 1:47.");
          onGameMessage("Preparation time: 2:06. Hunllef kill time: 1:00.");
          onGameMessage("Your Gauntlet completion count is 40.");
         * 
        */
        //Corrupted Gauntlet Test
        /* 
          onGameMessage("Corrupted challenge duration: 3:06. Personal best: 1:47.");
          onGameMessage("Preparation time: 2:06. Hunllef kill time: 1:00.");
          onGameMessage("Your Corrupted Gauntlet completion count is 40.");
          
        */
        //Corrupted Gauntlet PB Test
        /* 
          onGameMessage("Congratulations - Your raid is complete!");        
          onGameMessage("Team Size: 3 players Duration: 32:32 Personal Best: 28:26 Olm Duration: 4:11");
          onGameMessage("Your completed Chambers of Xeric Challenge Mode count is 61.");
          
        */
        //Nightmare Test
        /* 
          onGameMessage("Congratulations - Your raid is complete!");        
          onGameMessage("Team Size: 3 players Duration: 32:32 Personal Best: 28:26 Olm Duration: 4:11");
          onGameMessage("Your completed Chambers of Xeric Challenge Mode count is 61.");
          
        */  
        //Phosani Nightmare Test
        /* 
          onGameMessage("Your Phosani's Nightmare kill count is: 58.");        
          onGameMessage("Team Size: Solo Fight Duration: 8:58. Personal best: 8:30");
          
        /* 
        //Phosani Nightmare PB Test
        /* 
          onGameMessage("Your Phosani Nightmare kill count is: 100.");        
          onGameMessage("Team Size: Solo Fight Duration: 5:30 (new personal best)");
          
        */  
        //Zulrah Test
        /* 
          onGameMessage("Congratulations - Your Zulrah kill count is: 559.");        
          onGameMessage("Fight duration: 1:02. Personal best: 0:59");
          
        */        
              
        //Zulrah PB Test
        /* 
          onGameMessage("Congratulations - Your Zulrah kill count is: 559.");        
          onGameMessage("Fight duration: 1:02 (new personal best)");

          
        */        



    }



}





