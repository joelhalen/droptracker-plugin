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

import javax.management.Notification;
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

    private static final Pattern PRIMARY_REGEX = Pattern.compile(
            "Your (?<key>[\\w\\s:']+) (?<type>kill|chest|completion) count is:? (?<value>[\\d,]+)"
    );
    private static final Pattern SECONDARY_REGEX = Pattern.compile("Your (?<type>kill|chest|completed) (?<key>[\\w\\s:]+) count is:? (?<value>[\\d,]+)");


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

    private static final Duration PB_MESSAGE_WINDOW = Duration.ofSeconds(5); // Adjust window as needed
    private final Map<String, Triple<Duration, Duration, Boolean>> recentTimeMessages = new HashMap<>();
    private final Map<String, Instant> timeMessageTimestamps = new HashMap<>();

    private static final ArrayList<String> specialRaidBosses = new ArrayList<>(Arrays.asList("Theatre of Blood", "Tombs of Amascut"));

    private static final Pattern[] TIME_PATTERNS = {
            // Team patterns - updated to capture team size including + notation
            Pattern.compile("Team size: (?<teamsize>\\d+(?:-\\d+|\\+)?) players? Duration: (\\d*:*\\d+:\\d+\\.?\\d*) Personal best: (\\d*:*\\d+:\\d+\\.?\\d*).*"),
            Pattern.compile("Team size: (?<teamsize>\\d+(?:-\\d+|\\+)?) players? Fight duration: (\\d*:*\\d+:\\d+\\.?\\d*) Personal best: (\\d*:*\\d+:\\d+\\.?\\d*)"),
            // ToA patterns
            Pattern.compile("Tombs of Amascut: Expert Mode total completion time: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.*\\d*).*"),
            Pattern.compile("Tombs of Amascut total completion time: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.?\\d*).*"),
            // ToB pattern
            Pattern.compile("Theatre of Blood completion time: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.?\\d*).*"),
            // Gauntlet pattern - fixed spacing and case
            Pattern.compile("Challenge duration: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.?\\d*).*"),
            Pattern.compile("Corrupted challenge duration: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.?\\d*).*"),
            // Generic boss pattern
            Pattern.compile("Duration: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.?\\d*).*"),
            Pattern.compile("Fight duration: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.?\\d*).*"),
    };
    private static final Pattern[] PB_PATTERNS = {
            // Team patterns - updated to capture team size including + notation
            Pattern.compile("Team size: (?<teamsize>\\d+(?:-\\d+|\\+)?) players? Duration: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\).*"),
            Pattern.compile("Team size: (?<teamsize>\\d+(?:-\\d+|\\+)?) players? Fight duration: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\).*"),
            // ToA patterns
            Pattern.compile("Tombs of Amascut: Expert Mode total completion time: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\).*"),
            Pattern.compile("Tombs of Amascut total completion time: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\).*"),
            // ToB pattern
            Pattern.compile("Theatre of Blood completion time: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\).*"),
            // Gauntlet pattern - fixed spacing and case
            Pattern.compile("Challenge duration: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\).*"),
            Pattern.compile("Corrupted challenge duration: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\).*"),
            // Generic boss pattern
            Pattern.compile("Duration: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\).*"),
            Pattern.compile("Fight duration: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\).*"),
    };
    private final Map<String,TimeData> pendingTimeData = new HashMap<>();
    private final Map<String,TimeData> recentTimeData = new HashMap<>();
    private String lastProcessedKill = null;
    private long lastProcessedTime = 0;
    private static final long DUPLICATE_THRESHOLD = 5000;

    public boolean isEnabled() {
        return true;
    }

    public void onGameMessage(String message) {
        if (!isEnabled()) return;
        checkPB(message);
        checkTime(message);


        parseBossKill(message).ifPresent(this::updateData);

        parseCombatAchievement(message).ifPresent(pair -> processCombatAchievement(pair.getLeft(), pair.getRight()));
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
        Instant cutoff = Instant.now().minus(PB_MESSAGE_WINDOW);
        timeMessageTimestamps.entrySet().removeIf(entry ->
                entry.getValue().isBefore(cutoff)
        );
        recentTimeMessages.keySet().removeIf(boss ->
                !timeMessageTimestamps.containsKey(boss)
        );
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
        collEmbed.addField("p_v", plugin.pluginVersion, true);
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
            combatAchievementEmbed.addField("p_v", plugin.pluginVersion, true);
            String accountHash = String.valueOf(client.getAccountHash());
            combatAchievementEmbed.addField("acc_hash", accountHash, true);
            combatWebhook.getEmbeds().add(combatAchievementEmbed);
            plugin.sendDropTrackerWebhook(combatWebhook, "3");
        });
    }


    private void processKill(BossNotification data) {
        if (data.getBoss() == null || data.getCount() == null)
            return;

        String killIdentifier = data.getBoss() + "-" + data.getCount();
        long currentTime = System.currentTimeMillis();

        if(killIdentifier.equals(lastProcessedKill) && (currentTime - lastProcessedTime) < DUPLICATE_THRESHOLD){
            System.out.println("Kill already processed, skipping: " + killIdentifier);
            return;
        }
        lastProcessedKill = killIdentifier;
        lastProcessedTime = currentTime;

        boolean ba = data.getBoss().equals(BA_BOSS_NAME);
        boolean isPb = data.isPersonalBest() == Boolean.TRUE;
        String player = plugin.getLocalPlayerName();
        String time = formatTime(data.getTime(), isPreciseTiming(client));
        String bestTime = formatTime(data.getBestTime(), isPreciseTiming(client));
        CustomWebhookBody.Embed killEmbed = null;
        CustomWebhookBody killWebhook = new CustomWebhookBody();
        killEmbed = new CustomWebhookBody.Embed();
        killEmbed.setTitle(player + " has killed a boss:");
        String teamSize = "1";
        if (data.getBoss().contains("Theatre of Blood")) {
            teamSize = tobTeamSize();
        }else if (data.getBoss().contains("Tombs of Amascut")) {
            teamSize = toaTeamSize();
        }
        killEmbed.addField("type", "npc_kill", true);
        killEmbed.addField("boss_name", data.getBoss(), true);
        killEmbed.addField("player_name", plugin.getLocalPlayerName(), true);
        killEmbed.addField("kill_time", time, true);
        killEmbed.addField("best_time", bestTime, true);
        killEmbed.addField("auth_key", config.token(), true);
        killEmbed.addField("is_pb", String.valueOf(isPb), true);
        killEmbed.addField("team_size", String.valueOf(teamSize), true);
        String accountHash = String.valueOf(client.getAccountHash());
        killEmbed.addField("acc_hash", accountHash, true);
        killEmbed.addField("p_v", plugin.pluginVersion, true);

        killWebhook.getEmbeds().add(killEmbed);

        plugin.sendDropTrackerWebhook(killWebhook, "1");
        mostRecentNpcData = null;
    }

    private void updateData(BossNotification updated) {
        bossData.getAndUpdate(old -> {
            if (old == null) {
                // Store pending notification for later processing
                pendingNotifications.put(updated.getBoss(), updated);

                // Schedule cleanup task
                executor.schedule(() -> {
                    BossNotification pending = pendingNotifications.remove(updated.getBoss());
                    if (pending != null) {
                        // If notification wasn't processed by loot event, process it now
                        processKill(pending);
                    }
                }, MESSAGE_LOOT_WINDOW, TimeUnit.MILLISECONDS);

                return updated;
            } else {
                return new BossNotification(
                        defaultIfNull(updated.getBoss(), old.getBoss()),
                        defaultIfNull(updated.getCount(), old.getCount()),
                        defaultIfNull(updated.getGameMessage(), old.getGameMessage()),
                        defaultIfNull(updated.getTime(), old.getTime()),
                        defaultIfNull(updated.getBestTime(), old.getBestTime()),
                        defaultIfNull(updated.isPersonalBest(), old.isPersonalBest())
                );
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
        Optional<Pair<String, Integer>> boss = parseBoss(message);

        return boss.map(pair -> {
            String bossName = pair.getLeft();
            TimeData timeData = pendingTimeData.get(bossName);
            //Search for stored TimeData
            if(timeData != null){
                return new BossNotification(
                        bossName,
                        pair.getRight(),
                        message,
                        timeData.time,
                        timeData.bestTime,
                        timeData.isPb
                );
            }


            // No recent time message, check for time in current message
            return parseKillTime(message, bossName)
                    .map(t -> new BossNotification(
                            bossName,
                            pair.getRight(),
                            message,
                            t.getLeft(),
                            t.getMiddle(),
                            t.getRight()
                    ))
                    .orElse(new BossNotification(bossName, pair.getRight(), message, null, null, null));
        });
    }
    private Optional<Triple<Duration, Duration, Boolean>> parseKillTime(String message, String bossName) {
        // Check for PB first
        for (Pattern pattern : PB_PATTERNS) {
            Matcher timeMatcher = pattern.matcher(message);
            if (timeMatcher.find()) {
                String timeStr = timeMatcher.group(1);
                // Extract team size if present
                String teamSize = timeMatcher.groupCount() >= 2 ? timeMatcher.group("teamsize") : null;
                if (teamSize != null) {
                    // Store team size for later use
                    pendingTimeData.get(bossName).setTeamSize(teamSize);
                }
                Duration time = parseTime(timeStr);
                Duration bestTime = time; // For PB, current time is best time
                storeBossTime(bossName, time, bestTime, true);
                return Optional.of(Triple.of(time, bestTime, true));
            }
        }

        // Check for regular time
        for (Pattern pattern : TIME_PATTERNS) {
            Matcher timeMatcher = pattern.matcher(message);
            if (timeMatcher.find()) {
                String timeStr = timeMatcher.group(1);
                String bestTimeStr = timeMatcher.group(2);
                // Extract team size if present
                String teamSize = timeMatcher.groupCount() >= 3 ? timeMatcher.group("teamsize") : null;
                if (teamSize != null) {
                    // Store team size for later use
                    pendingTimeData.get(bossName).setTeamSize(teamSize);
                }
                Duration time = parseTime(timeStr);
                Duration bestTime = parseTime(bestTimeStr);
                storeBossTime(bossName, time, bestTime, false);
                return Optional.of(Triple.of(time, bestTime, false));
            }
        }
        return Optional.empty();
    }

    @NotNull
    private Duration parseTime(String timeStr) {

        try {
            // Split into parts based on : and .
            String timePart = timeStr.contains(".") ? timeStr.substring(0, timeStr.indexOf('.')) : timeStr;
            String[] timeParts = timePart.split(":");

            long hours = 0;
            long minutes = 0;
            long seconds = 0;
            long millis = 0;

            // Parse hours:minutes:seconds or minutes:seconds
            if (timeParts.length == 3) {  // h:m:s
                hours = Long.parseLong(timeParts[0]);
                minutes = Long.parseLong(timeParts[1]);
                seconds = Long.parseLong(timeParts[2]);
            } else if (timeParts.length == 2) {  // m:s
                minutes = Long.parseLong(timeParts[0]);
                seconds = Long.parseLong(timeParts[1]);
            }

            // Parse milliseconds if present
            if (timeStr.contains(".")) {
                String millisStr = timeStr.substring(timeStr.indexOf('.') + 1);
                // Pad with zeros if needed
                while (millisStr.length() < 3) {
                    millisStr += "0";
                }
                millis = Long.parseLong(millisStr);
            }

            Duration duration = Duration.ofHours(hours)
                    .plusMinutes(minutes)
                    .plusSeconds(seconds)
                    .plusMillis(millis);
            return duration;

        } catch (Exception e) {
            System.out.println("Error parsing time: " + timeStr);
            e.printStackTrace();
            return null;
        }
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
        } else
        if (secondary.find()){

            String key = parseSecondary(secondary.group("key"));
            String value = secondary.group("value");
            if (key != null) {
                try {
                    int killCount = Integer.parseInt(value.replace(",", ""));
                    mostRecentNpcData = Pair.of(key, killCount);
                    ticksSinceNpcDataUpdate = 0;
                    return Optional.of(mostRecentNpcData);
                } catch (NumberFormatException e) {
                    System.out.println("Failed to parse kill count " +value+ " for boss " + key);
                }
            }
        }
        return Optional.empty();
    }


    private static Optional<Pair<String, Integer>> result(String boss, String count) {
        try {
            return Optional.ofNullable(boss).map(k -> Pair.of(boss, Integer.parseInt(count)));
        } catch (NumberFormatException e) {
            log.debug("Failed to parse kill count [{}] for boss [{}]", count, boss);
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
        if (boss == null || "Wintertodt".equalsIgnoreCase(boss))
            return boss;

        int modeSeparator = boss.lastIndexOf(':');
        String raid = modeSeparator > 0 ? boss.substring(0, modeSeparator) : boss;
        if (raid.equalsIgnoreCase("Theatre of Blood")
                || raid.equalsIgnoreCase("Tombs of Amascut")
                || raid.equalsIgnoreCase("Chambers of Xeric")
                || raid.equalsIgnoreCase("Chambers of Xeric Challenge Mode"))
            return boss;

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

    private void storeBossTime(String bossName, Duration time, Duration bestTime, boolean isPb){
        TimeData timeData = new TimeData(time,bestTime,isPb);
        pendingTimeData.put(bossName,timeData);
        recentTimeData.put(bossName,timeData);

        BossNotification current = bossData.get();
        if(current != null && current.getBoss().equals(bossName)){

            BossNotification withTime = new BossNotification(
                    current.getBoss(),
                    current.getCount(),
                    current.getGameMessage(),
                    time,
                    bestTime,
                    isPb
            );
            bossData.set(withTime);
            processKill(withTime);
        }
    }

    private static class TimeData {
        final Duration time;
        final Duration bestTime;
        final boolean isPb;
        final Instant timestamp;
        private String teamSize;

        TimeData(Duration time, Duration bestTime, boolean isPb) {
            this.time = time;
            this.bestTime = bestTime;
            this.isPb = isPb;
            this.timestamp = Instant.now();
        }

        boolean isRecent() {
            return Duration.between(timestamp, Instant.now()).compareTo(Duration.ofSeconds(2)) <= 0;
        }

        public void setTeamSize(String teamSize) {
            this.teamSize = teamSize;
        }

        public String getTeamSize() {
            return teamSize;
        }

        @Override
        public String toString(){
            return String.format("TimeData(time=%s, bestTime=%s, isPb%s)",time,bestTime,isPb);
        }
    }
    private void checkPB(String message){
        //Check for PB time
        for(Pattern pattern: PB_PATTERNS){
            Matcher timeMatcher = pattern.matcher(message);

            if (timeMatcher.find()) {
                String timeStr = "";
                String bestTimeStr = "";
                boolean isPb = true;
                try {
                    timeStr = timeMatcher.group(1);
                    bestTimeStr = timeMatcher.group(1);
                }catch (Exception e){
                    System.out.println("Error parsing time message: " + message + " - " + e.getMessage());
                }

                Duration time = parseTime(timeStr);
                Duration bestTime = parseTime(bestTimeStr);
                if (message.contains("Team Size:")) {
                    storeBossTime("Chambers of Xeric", time, bestTime, isPb);
                    storeBossTime("Chambers of Xeric Challenge Mode", time, bestTime, isPb);
                    storeBossTime("The Nightmare", time, bestTime, isPb);
                    storeBossTime("Phosani's Nightmare",time,bestTime,isPb);
                } else if (message.contains("Tombs of Amascut")) {
                    storeBossTime("Tombs of Amascut", time, bestTime, isPb);
                    storeBossTime("Tombs of Amascut: Expert Mode", time, bestTime, isPb);
                }else if(message.contains("Theatre of Blood")){
                    storeBossTime("Theatre of Blood",time,bestTime,isPb);
                    storeBossTime("Theatre of Blood: Hard Mode",time,bestTime,isPb);
                }else if (message.contains("Corrupted challenge")) {
                    storeBossTime("Corrupted Hunllef", time, bestTime, isPb);
                } else if (message.contains("Challenge duration")) {
                    storeBossTime("Crystalline Hunllef", time, bestTime, isPb);
                } else {
                    String bossName = mostRecentNpcData != null ? mostRecentNpcData.getLeft() : null;
                    if (bossName != null) {
                        storeBossTime(bossName, time, bestTime, isPb);
                    }
                }

            }

        }
    }
    private void checkTime(String message){
        //check for time
        for(Pattern pattern: TIME_PATTERNS) {

            Matcher timeMatcher = pattern.matcher(message);
            if (timeMatcher.find()) {
                String timeStr = "";
                String bestTimeStr = "";
                boolean isPb = false;
                try {
                    timeStr = timeMatcher.group(1);
                    bestTimeStr = timeMatcher.group(2);
                }catch (Exception e){
                    System.out.println("Error parsing time message: " + message + " - " + e.getMessage());
                }

                Duration time = parseTime(timeStr);
                Duration bestTime = parseTime(bestTimeStr);

                if (message.contains("Team Size:")) {
                    storeBossTime("Chambers of Xeric", time, bestTime, isPb);
                    storeBossTime("Chambers of Xeric Challenge Mode", time, bestTime, isPb);
                    storeBossTime("The Nightmare", time, bestTime, isPb);
                    storeBossTime("Phosani's Nightmare",time,bestTime,isPb);
                } else if (message.contains("Tombs of Amascut")) {
                    storeBossTime("Tombs of Amascut", time, bestTime, isPb);
                    storeBossTime("Tombs of Amascut: Expert Mode", time, bestTime, isPb);
                }else if(message.contains("Theatre of Blood")){
                    storeBossTime("Theatre of Blood",time,bestTime,isPb);
                    storeBossTime("Theatre of Blood: Hard Mode",time,bestTime,isPb);
                }else if (message.contains("Corrupted challenge")) {
                    storeBossTime("Corrupted Hunllef", time, bestTime, isPb);
                } else if (message.contains("Challenge duration")) {
                    storeBossTime("Crystalline Hunllef", time, bestTime, isPb);
                } else {
                    String bossName = mostRecentNpcData != null ? mostRecentNpcData.getLeft() : null;
                    if (bossName != null) {
                        storeBossTime(bossName, time, bestTime, isPb);
                    }
                }

            }
        }
    }

    private String tobTeamSize()
	{
		Integer teamSize = Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB1), 1) +
			Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB2), 1) +
			Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB3), 1) +
			Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB4), 1) +
			Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB5), 1);
		return teamSize.toString();
	}

	private String toaTeamSize()
	{
		Integer teamSize = Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_0_HEALTH), 1 +
			Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_1_HEALTH), 1) +
			Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_2_HEALTH), 1) +
			Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_3_HEALTH), 1) +
			Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_4_HEALTH), 1) +
			Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_5_HEALTH), 1) +
			Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_6_HEALTH), 1) +
			Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_7_HEALTH), 1));
		return teamSize.toString();
	}

    @VisibleForTesting
    public void generateTestMessage() {
        //Chambers of Xeric Challenge Mode Test
        /*
          onGameMessage("Congratulations - Your raid is complete!");
          onGameMessage("Team size: 3 players Duration: 32:32 Personal best: 28:26 Olm Duration: 4:11");
          onGameMessage("Your completed Chambers of Xeric Challenge Mode count is 61.");
        */

        //Chambers of Xeric Test
        /*
          onGameMessage("Congratulations - Your raid is complete!");
          onGameMessage("Team size: 3 players Duration: 27:32 Personal best: 22:26 Olm Duration: 4:11");
          onGameMessage("Your completed Chambers of Xeric count is 52.");
        */

        //Chambers of Xeric Challenge Mode PB Test
        /*
          onGameMessage("Congratulations - Your raid is complete!");
          onGameMessage("Team size: 3 players Duration: 27:32 (new personal best) Olm Duration: 4:11");
          onGameMessage("Your completed Chambers of Xeric Challenge Mode count is 47.");
        */

        //Tombs of Amascut Test
        /*
        onGameMessage("Challenge complete: The Wardens. Duration: 3:02");
        onGameMessage("Tombs of Amascut challenge completion time: 14:40. Personal best: 12:16");
        onGameMessage("Tombs of Amascut total completion time: 16:37.4. Personal best: 14:37.4");
        onGameMessage("Your completed Tombs of Amascut count is 15.");
        /*

        //Tombs of Amascut Expert Mode Test
        /*
          onGameMessage("Challenge complete: The Wardens. Duration: 3:02");
          onGameMessage("Tombs of Amascut: Expert Mode challenge completion time: 20:40. Personal best: 18:16");
          onGameMessage("Tombs of Amascut: Expert Mode total completion time: 1:23:38.2. Personal best: 1:20:38.4");
          onGameMessage("Your completed Tombs of Amascut: Expert Mode count is 20.");
        */

        //Tombs of Amascut PB Test
        /*
          onGameMessage("Challenge complete: The Wardens. Duration: 3:02");
          onGameMessage("Tombs of Amascut challenge completion time: 12:16 (new personal best)");
          onGameMessage("Tombs of Amascut total completion time: 14:37.4 (new personal best)");
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

          onGameMessage("Theatre of Blood completion time: 51:42 (new personal best)");
          onGameMessage("Your completed Theatre of Blood count is 754.");
        */

        //Gauntlet Test
        /*
          onGameMessage("Challenge duration: 3:06. Personal best: 1:47.");
          onGameMessage("Preparation time: 2:06. Hunllef kill time: 1:00.");
          onGameMessage("Your Gauntlet completion count is 40.");

        */
        //Corrupted Gauntlet Test
        /*
          onGameMessage("Corrupted challenge duration: 3:06. Personal best: 1:47.");
          onGameMessage("Preparation time: 2:06. Hunllef kill time: 1:00.");
          onGameMessage("Your Corrupted Gauntlet completion count is 40.");
        */

        //Corrupted Gauntlet PB Test
        /*
          onGameMessage("Corrupted challenge duration: 1:57 (new personal best)");
          onGameMessage("Your corrupted Gauntlet completion count is 25.");

        */
        //Nightmare Test
        /*
          onGameMessage("Your nightmare kill count is 31.");
          onGameMessage("Team size: 6+ players Fight duration: 1:46. Personal best: 1:46");
        */
        //Phosani Nightmare Test
        /*
          onGameMessage("Your Phosani's Nightmare kill count is: 58.");
          onGameMessage("Team size: Solo Fight Duration: 8:58. Personal best: 8:30");
        */

        //Phosani Nightmare PB Test
        /*
          onGameMessage("Your Phosani's Nightmare kill count is: 100.");
          onGameMessage("Team size: Solo Fight Duration: 1:05:30 (new personal best)");
        */
        //Zulrah Test
        /*
          onGameMessage("Your Zulrah kill count is: 559.");
          onGameMessage("Fight duration: 1:02. Personal best: 0:59");
        */
        //Zulrah PB Test
        /*
          onGameMessage("Congratulations - Your Zulrah kill count is: 559.");
          onGameMessage("Fight duration: 0:58 (new personal best)");
        */





    }

}
