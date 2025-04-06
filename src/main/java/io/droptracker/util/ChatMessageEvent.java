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
import net.runelite.api.coords.WorldPoint;
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

    private static final Pattern[] TIME_PATTERNS = {
            // Team patterns
            Pattern.compile("Team size: .+? Duration: (\\d*:*\\d+:\\d+\\.?\\d*) Personal best: (\\d*:*\\d+:\\d+\\.?\\d*)\\.*"),
            Pattern.compile("Team size: .+? Fight duration: (\\d*:*\\d+:\\d+\\.?\\d*) Personal best: (\\d*:*\\d+:\\d+\\.?\\d*)\\.*"),
            // ToA patterns
            Pattern.compile("Tombs of Amascut: Expert Mode total completion time: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.*\\d*)\\.*"),
            Pattern.compile("Tombs of Amascut total completion time: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.?\\d*)\\.*"),
            // ToB pattern
            Pattern.compile("Theatre of Blood completion time: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.?\\d*)\\.*"),
            // Gauntlet pattern - fixed spacing and case
            Pattern.compile("Challenge duration: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.?\\d*)\\.*"),
            Pattern.compile("Corrupted challenge duration: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.?\\d*)\\.*"),
            //Colosseum Pattern
            Pattern.compile("Colosseum duration: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.?\\d*)\\.*"),
            // Generic boss pattern
            Pattern.compile("Duration: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.?\\d*)\\.*"),
            Pattern.compile("Fight duration: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.?\\d*)\\.*"),
            Pattern.compile("Fight duration: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.?\\d*)\\.*")
    };
    private static final Pattern[] PB_PATTERNS = {
            // Team patterns
            Pattern.compile("Team size: .+? Duration: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\)\\.*"),
            Pattern.compile("Team size: .+? Fight duration: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\)\\.*"),
            // ToA patterns
            Pattern.compile("Tombs of Amascut: Expert Mode total completion time: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\)\\.*"),
            Pattern.compile("Tombs of Amascut total completion time: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\)\\.*"),
            // ToB pattern
            Pattern.compile("Theatre of Blood completion time: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\)\\.*"),
            // Gauntlet pattern - fixed spacing and case
            Pattern.compile("Challenge duration: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\)\\.*"),
            Pattern.compile("Corrupted challenge duration: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\)\\.*"),
            // Colosseum Pattern
            Pattern.compile("Colosseum duration: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\)\\.*"),
            // Generic boss pattern
            Pattern.compile("Duration: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\)\\.*"),
            Pattern.compile("Fight duration: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\)\\.*"),
    };
    private final Map<String,TimeData> pendingTimeData = new HashMap<>();
    private final Map<String,TimeData> recentTimeData = new HashMap<>();
    private String lastProcessedKill = null;
    private long lastProcessedTime = 0;
    private static final long DUPLICATE_THRESHOLD = 5000;
    private String teamSize = null;
    private int lastNpcTypeTarget;

    public boolean isEnabled() {
        return true;
    }

    public void onGameMessage(String message) {
        if (!isEnabled()) return;
        System.out.println(message);
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
            System.out.println("onTick got data:" + data.getBoss() + " time: " + data.getTime());
        }
        if (data != null) {
            if (data.getBoss() != null) {
                if (isEnabled()) {
                    processKill(data);
                } else {
                    System.out.println("disabled?");
                }
                System.out.println("onTick: reset");
                reset();
            } else if (badTicks.incrementAndGet() > MAX_BAD_TICKS) {
                System.out.println("onTick: badTicks > MAX_BAD_TICKS");
                reset();
            }
        }
        if (mostRecentNpcData != null) {
            System.out.println("ticksSinceNpcDataUpdate incremented.");
            if (bossData.get() != null) {
                processKill(bossData.get());
                System.out.println("name: " + mostRecentNpcData.getLeft() + "time: " + mostRecentNpcData.getRight());
            }
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
        collEmbed.addField("p_v",plugin.pluginVersion,true);
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
            String accountHash = String.valueOf(client.getAccountHash());
            CustomWebhookBody combatWebhook = new CustomWebhookBody();
            combatWebhook.setContent(player + " has completed a new combat task:");
            CustomWebhookBody.Embed combatAchievementEmbed = new CustomWebhookBody.Embed();
            combatAchievementEmbed.addField("type", "combat_achievement",true);
            combatAchievementEmbed.addField("tier", tier.toString(),true);
            combatAchievementEmbed.addField("task", task,true);
            combatAchievementEmbed.addField("player_name", plugin.getLocalPlayerName(), true);
            combatAchievementEmbed.addField("acc_hash", accountHash, true);
            combatAchievementEmbed.addField("points", String.valueOf(taskPoints),true);
            combatAchievementEmbed.addField("total_points", String.valueOf(totalPoints),true);
            combatAchievementEmbed.addField("completed", completedTierName,true);
            combatAchievementEmbed.addField("p_v", plugin.pluginVersion,true);
            combatWebhook.getEmbeds().add(combatAchievementEmbed);
            plugin.sendDropTrackerWebhook(combatWebhook, "3");
        });
    }


    private void processKill(BossNotification data) {
        if (data == null) {
            return;
        }
        if (data.getBoss() == null || data.getCount() == null)
            return;

        String killIdentifier = data.getBoss() + "-" + data.getCount();
        long currentTime = System.currentTimeMillis();

        if(killIdentifier.equals(lastProcessedKill) && (currentTime - lastProcessedTime) < DUPLICATE_THRESHOLD){
            System.out.println("Duplicate message trying to send");
            return;
        }
        lastProcessedKill = killIdentifier;
        lastProcessedTime = currentTime;
        boolean ba = data.getBoss().equals(BA_BOSS_NAME);
        boolean isPb = data.isPersonalBest() == Boolean.TRUE;
        String player = plugin.getLocalPlayerName();
        System.out.println("Player: " + player);
        System.out.println(data + " " + data.getClass());
        String time = null;
        final String[] timeRef = {null};
        final String[] bestTimeRef = {null};
        System.out.println("calling invokeLater...");
        clientThread.invokeLater(() -> {
            System.out.println("Attempting to generate timeRef and bestTimeRef");
            timeRef[0] = formatTime(data.getTime(), isPreciseTiming(client));
            bestTimeRef[0] = formatTime(data.getBestTime(), isPreciseTiming(client));
            System.out.println("bestTime: " + bestTimeRef[0]);
            System.out.println("Time: " + timeRef[0]); 
            String accountHash = String.valueOf(client.getAccountHash());
            System.out.println("AccountHash: " + accountHash);
            CustomWebhookBody.Embed killEmbed = null;
            CustomWebhookBody killWebhook = new CustomWebhookBody();
            killEmbed = new CustomWebhookBody.Embed();
            killEmbed.setTitle(player + " has killed a boss:");
            killEmbed.addField("type", "npc_kill", true);
            killEmbed.addField("boss_name", data.getBoss(), true);
            killEmbed.addField("player_name", plugin.getLocalPlayerName(), true);
            killEmbed.addField("kill_time", timeRef[0], true);
            killEmbed.addField("best_time", bestTimeRef[0], true);
            killEmbed.addField("is_pb", String.valueOf(isPb), true);
            killEmbed.addField("Team_Size", teamSize,true);
            killEmbed.addField("acc_hash", accountHash, true);
            killEmbed.addField("p_v",plugin.pluginVersion,true);
            killWebhook.getEmbeds().add(killEmbed);
            System.out.println("Sending Boss: " + data.getBoss() + " with kill time: " + timeRef[0]);
            plugin.sendDropTrackerWebhook(killWebhook, "1");
        });
        mostRecentNpcData = null;
        teamSize = null;
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
                        System.out.println("Processing after loot event");
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
        System.out.println("Called parseBossKill with " + message);
        return boss.map(pair -> {
            String bossName = pair.getLeft();
            // retrieve the stored timeData for this bossName, if any is stored...
            // for cases where a time message may appear before the boss name/kc message appears
            TimeData timeData = pendingTimeData.get(bossName);
            //Search for stored TimeData
            System.out.println("Received bossName from pair:" + bossName);
            if(timeData != null){
                return new BossNotification(
                        bossName,
                        pair.getRight(),
                        message,
                        timeData.time,
                        timeData.bestTime,
                        timeData.isPb
                );
            } else {
                System.out.println("Time data is null...");
            }
            System.out.println("No recent time, checking for time in this message...");
            BossNotification pbData = parseKillTime(message, bossName)
                    .map(t -> new BossNotification(
                            bossName,
                            pair.getRight(),
                            message,
                            t.getLeft(),
                            t.getMiddle(),
                            t.getRight()
                    ))
                    .orElse(null);
            // No recent time message, check for time in current message
            System.out.println("Got pbData:" + pbData);
            return pbData;
        });
    }
    private Optional<Triple<Duration, Duration, Boolean>> parseKillTime(String message, String bossName) {

        //check for Pb
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
                }
                Duration time = parseTime(timeStr);
                Duration bestTime = parseTime(bestTimeStr);
                setTeamSize(bossName,message);
                storeBossTime(bossName,time,bestTime,isPb);
                return Optional.of(Triple.of(time, bestTime, isPb));
            }
        }
        //System.out.println("No PB found");
        //check for time
        for(Pattern pattern: TIME_PATTERNS) {
            boolean isPb = false;
            Matcher timeMatcher = pattern.matcher(message);

            if (timeMatcher.find()) {
                System.out.println("Time Pattern Found (after KC)");
                String timeStr = "";
                String bestTimeStr = "";
                try {
                    timeStr = timeMatcher.group(1);
                    bestTimeStr = timeMatcher.group(2);
                } catch (Exception e) {
                }
                Duration time = parseTime(timeStr);
                Duration bestTime = parseTime(bestTimeStr);
                setTeamSize(bossName,message);
                storeBossTime(bossName,time,bestTime,isPb);

                return Optional.of(Triple.of(time, bestTime, isPb));
            }

            //System.out.println("No time message Found");
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
            System.out.println("Loot was received now processing kill");
            processKill(pending);
        } else {
            // Store the loot event info for later matching with chat message
            lastDrop = new Drop(source, event.getType(), event.getItems());
        }
    }

    //Storing boss Time to either access at a later point or to move through sending the time
    private void storeBossTime(String bossName, Duration time, Duration bestTime, boolean isPb){
        TimeData timeData = new TimeData(time,bestTime,isPb);
        pendingTimeData.put(bossName,timeData);
        System.out.println("Put pendingTimeData: " + bossName + " with time" + time + " / " + bestTime);
        BossNotification withTime = new BossNotification(
                bossName,
                mostRecentNpcData.getRight(),
                "",
                time,
                bestTime,
                isPb
        );
        bossData.set(withTime);
        System.out.println("Time: " + time + " Processing Kill next");
        processKill(withTime);
    }
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

    //Checking current message for time Message containing PB
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
                }
                Duration time = parseTime(timeStr);
                Duration bestTime = parseTime(bestTimeStr);
                String bossName = mostRecentNpcData != null ? mostRecentNpcData.getLeft() : null;
                if (bossName != null) {
                    setTeamSize(bossName,message);
                    storeBossTime(bossName, time, bestTime, isPb);
                }

                if (message.contains("Team Size:")) {
                    setTeamSize("Chambers of Xeric",message);
                    storeBossTime("Chambers of Xeric", time, bestTime, isPb);
                    storeBossTime("Chambers of Xeric Challenge Mode", time, bestTime, isPb);
                    storeBossTime("The Nightmare", time, bestTime, isPb);
                    storeBossTime("Phosani's Nightmare",time,bestTime,isPb);
                } else if (message.contains("Tombs of Amascut")) {
                    setTeamSize("Tombs of Amascut",message);
                    storeBossTime("Tombs of Amascut: Entry Mode", time,bestTime,isPb);
                    storeBossTime("Tombs of Amascut", time, bestTime, isPb);
                    storeBossTime("Tombs of Amascut: Expert Mode", time, bestTime, isPb);
                }else if(message.contains("Theatre of Blood")){
                    setTeamSize("Theatre of Blood",message);
                    storeBossTime("Theatre of Blood: Entry Mode", time,bestTime,isPb);
                    storeBossTime("Theatre of Blood",time,bestTime,isPb);
                    storeBossTime("Theatre of Blood: Hard Mode",time,bestTime,isPb);
                }else if (message.contains("Corrupted challenge")) {
                    setTeamSize("Corrupted Hunllef",message);
                    storeBossTime("Corrupted Hunllef", time, bestTime, isPb);

                } else if (message.contains("Challenge duration")) {
                    setTeamSize("Crystalline Hunllef",message);
                    storeBossTime("Crystalline Hunllef", time, bestTime, isPb);
                } else if (message.contains("Colosseum duration")){
                    setTeamSize("Sol Heredit",message);
                    storeBossTime("Sol Heredit",time,bestTime,isPb);
                }



            }

        }
    }
    //Checking current message for time Message (not containing PB)
    private void checkTime(String message){
        //check for time
        for(Pattern pattern: TIME_PATTERNS) {

            Matcher timeMatcher = pattern.matcher(message);
            if (timeMatcher.find()) {
                System.out.println("Time message Found");
                String timeStr = "";
                String bestTimeStr = "";
                boolean isPb = false;
                try {
                    timeStr = timeMatcher.group(1);
                    bestTimeStr = timeMatcher.group(2);
                }catch (Exception e){
                }

                Duration time = parseTime(timeStr);
                Duration bestTime = parseTime(bestTimeStr);
                System.out.println("Time:" + time + " Best: " + bestTime);
                System.out.println("Raw: " + timeStr + " " + bestTimeStr);

                String bossName = mostRecentNpcData != null ? mostRecentNpcData.getLeft() : null;
                if (bossName != null) {
                    System.out.println("Boss Name not Null: " + bossName);
                    setTeamSize(bossName, message);
                    storeBossTime(bossName, time, bestTime, isPb);
                }
                // removed else if here, as if we only entered the below clauses
                // where bossname is null, it may not work as expected...
                if (message.contains("Team size:")) {
                    setTeamSize("Chambers of Xeric",message);
                    storeBossTime("Chambers of Xeric", time, bestTime, isPb);
                    storeBossTime("Chambers of Xeric Challenge Mode", time, bestTime, isPb);
                    storeBossTime("The Nightmare", time, bestTime, isPb);
                    storeBossTime("Phosani's Nightmare",time,bestTime,isPb);
                } else if (message.contains("Tombs of Amascut")) {
                    setTeamSize("Tombs of Amascut",message);
                    storeBossTime("Tombs of Amascut", time, bestTime, isPb);
                    storeBossTime("Tombs of Amascut: Expert Mode", time, bestTime, isPb);
                }else if(message.contains("Theatre of Blood")){
                    setTeamSize("Theatre of Blood",message);
                    storeBossTime("Theatre of Blood",time,bestTime,isPb);
                    storeBossTime("Theatre of Blood: Hard Mode",time,bestTime,isPb);
                }else if (message.contains("Corrupted challenge")) {
                    setTeamSize("Corrupted Hunllef",message);
                    storeBossTime("Corrupted Hunllef", time, bestTime, isPb);
                } else if (message.contains("Challenge duration")) {
                    setTeamSize("Crystalline Hunllef",message);
                    storeBossTime("Crystalline Hunllef", time, bestTime, isPb);
                }

            }
        }
    }

    /*
        We can obtain the group size for TOB/TOA using the player orb varbits
    */
    private String tobTeamSize() {

        Integer teamSize = Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB1), 1) +
                Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB2), 1) +
                Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB3), 1) +
                Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB4), 1) +
                Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB5), 1);
        if(teamSize == 1){
            return "Solo";
        }
        return teamSize.toString();
    }

    private String toaTeamSize() {
        Integer teamSize = Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_0_HEALTH), 1 +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_1_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_2_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_3_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_4_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_5_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_6_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_7_HEALTH), 1));
        if(teamSize==1){
            return "Solo";
        }
        return teamSize.toString();
    }
    private void setTeamSize (String bossName, String message){

        if(bossName.contains("Theatre of Blood")){
            teamSize = tobTeamSize();
        }else if (bossName.contains("Tombs of Amascut")){
            teamSize = toaTeamSize();
        }else if (message.contains("Team size")){
            Pattern teamSizePattern = Pattern.compile("Team size: (\\S+) players.*");
            Matcher teamMatch = teamSizePattern.matcher(message);
            if(teamMatch.find())
                teamSize = teamMatch.group(1);

        }else if(bossName.contains("Royal Titans")){
            int size = client.getPlayers().size();
            if(size == 1){
                teamSize = "Solo";
            }else{
                teamSize = String.valueOf(size);
            }

        } else if(message.contains("ersonal best")){
            teamSize = "Solo";
        }

    }

    @VisibleForTesting
    public void generateTestMessage() {
        //Chambers of Xeric Challenge Mode Test
        /*
          onGameMessage("Congratulations - Your raid is complete!");
          // onGameMessage("Team size: 3 players Duration: 32:32 Personal best: 28:26 Olm Duration: 4:11");
          // onGameMessage("Team size: 3 players Duration: 27:32 (new personal best) Olm Duration: 4:11");
          onGameMessage("Your completed Chambers of Xeric Challenge Mode count is 61.");
        */

        //Chambers of Xeric Test
        /*
          onGameMessage("Congratulations - Your raid is complete!");
          // onGameMessage("Team size: 3 players Duration: 27:32 Personal best: 22:26 Olm Duration: 4:11");
          // onGameMessage("Team size: 3 players Duration: 27:32 (new personal best) Olm Duration: 4:11");
          onGameMessage("Your completed Chambers of Xeric count is 52.");
        */

        //Tombs of Amascut: Entry Mode Test
        /*
        onGameMessage("Challenge complete: The Wardens. Duration: 3:02");
        onGameMessage("Tombs of Amascut: Entry Mode challenge completion time: 14:40. Personal best: 12:16");
        // onGameMessage("Tombs of Amascut: Entry Mode total completion time: 16:37.4. Personal best: 14:37.4");
        // onGameMessage("Tombs of Amascut: Entry Mode total completion time: 14:36.4 (new personal best)");
        onGameMessage("Your completed Tombs of Amascut: Entry Mode count is 15.");
        */

        //Tombs of Amascut Test
        /*
        onGameMessage("Challenge complete: The Wardens. Duration: 3:02");
        onGameMessage("Tombs of Amascut challenge completion time: 14:40. Personal best: 12:16");
        // onGameMessage("Tombs of Amascut total completion time: 16:37.4. Personal best: 14:37.4");
        // onGameMessage("Tombs of Amascut total completion time: 14:36.4 (new personal best)");
        onGameMessage("Your completed Tombs of Amascut count is 15.");
        /*

        //Tombs of Amascut Expert Mode Test
        /*
          onGameMessage("Challenge complete: The Wardens. Duration: 3:02");
          onGameMessage("Tombs of Amascut: Expert Mode challenge completion time: 20:40. Personal best: 18:16");
          // onGameMessage("Tombs of Amascut: Expert Mode total completion time: 1:23:38.2. Personal best: 1:20:38.4");
          // onGameMessage("Tombs of Amascut: Expert Mode total completion time: 18:37.4 (new personal best)");
          onGameMessage("Your completed Tombs of Amascut: Expert Mode count is 20.");
        */

        //Theatre of Blood: Entry Mode Test
        /*
          // onGameMessage("Theatre of Blood completion time: 18:12. Personal best: 17:09");
          // onGameMessage("Theatre of Blood completion time: 25:40 (new personal best)");
          onGameMessage("Theatre of Blood total completion time: 23:01. Personal best: 21:41");
          onGameMessage("Your completed Theatre of Blood: Entry Mode count is: 1.");
        */

        //Theatre of Blood Test
        /*
          // onGameMessage("Theatre of Blood completion time: 18:12. Personal best: 17:09");
          // onGameMessage("Theatre of Blood completion time: 25:40 (new personal best)");
          onGameMessage("Theatre of Blood total completion time: 23:01. Personal best: 21:41");
          onGameMessage("Your completed Theatre of Blood count is 11.");
        */

        //Theatre of Blood Hard Mode Test
        /*
          // onGameMessage("Theatre of Blood completion time: 25:12. Personal best: 23:09");
          // onGameMessage("Theatre of Blood completion time: 24:40 (new personal best)");
          onGameMessage("Theatre of Blood total completion time: 28:01. Personal best: 25:41");
          onGameMessage("Your completed Theatre of Blood: Hard Mode count is 11.");

        */

        //Gauntlet Test
        /*
          // onGameMessage("Challenge duration: 3:06. Personal best: 1:47.");
          // onGameMessage("Challenge duration: 6:22 (new personal best).");
          onGameMessage("Preparation time: 2:06. Hunllef kill time: 1:00.");
          onGameMessage("Your Gauntlet completion count is 40.");
        */


        //Corrupted Gauntlet Test
        /*
          // onGameMessage("Corrupted challenge duration: 3:06. Personal best: 1:47.");
          // onGameMessage("Corrupted challenge duration: 7:55 (new personal best).");
          onGameMessage("Preparation time: 2:06. Hunllef kill time: 1:00.");
          onGameMessage("Your Corrupted Gauntlet completion count is 40.");
        */

        //Nightmare Test
        /*
          onGameMessage("Your nightmare kill count is 31.");
          // onGameMessage("Team size: 6+ players Fight duration: 1:46. Personal best: 1:46");
          // onGameMessage("Team size: 6+ players Fight duration: 3:57 (new personal best)");
        */

        //Phosani Nightmare Test
        /*
          onGameMessage("Your Phosani's Nightmare kill count is: 100.");
          // onGameMessage("Team size: Solo Fight Duration: 8:58. Personal best: 8:30");
          // onGameMessage("Team size: Solo Fight Duration: 1:05:30 (new personal best)");
        */

        //Zulrah Test
        /*
          onGameMessage("Congratulations - Your Zulrah kill count is: 559.");
          // onGameMessage("Fight duration: 1:02. Personal best: 0:59");
          // onGameMessage("Fight duration: 0:58 (new personal best)");
        */

        //Alchemical Hydra Test

//        onGameMessage("Your Alchemical Hydra kill count is: 150.");
//        onGameMessage("Fight duration: 1:49. Personal best: 1:28.");
        // onGameMessage("Fight duration: 1:20 (new personal best).");


        //Amoxialtl Test
        /*
          onGameMessage("Your Amoxliatl kill count is: 42.");
          // onGameMessage("Fight duration: 1:05.40. Personal best: 0:29.40");
          // onGameMessage("Fight duration: 0:29.40 (new personal best)");
        */

        //Araxxor Test
        /*
          onGameMessage("Your Araxxor kill count is 75.");
          // onGameMessage("Fight duration: 1:19.20. Personal best: 1:00.00");
          // onGameMessage("Fight duration: 1:15.60 (new personal best)");
        */

        //Duke Succelus Test
        /*
          onGameMessage("Your Duke Sucellus kill count is: 150.");
          // onGameMessage("Fight duration: 2:52.20. Personal best: 1:37.80");
          // onGameMessage("Fight duration: 1:34.20 (new personal best)");
        */

        //Fight Caves Test
        /*
          onGameMessage("Your TzTok-Jad kill count is 5.");
          // onGameMessage("Duration: 59:20. Personal best: 46:16");
          // onGameMessage("Duration: 1:47:28.20 (new personal best)");
        */

        //Fortis Colosseum Test
        /*
          onGameMessage("Your Sol Heredit kill count is: 10.");
          onGameMessage("Wave 12 completed! Wave duration: 37:51.60");
          // onGameMessage("Colosseum duration: 37:51.60. Personal best: 30:12.00");
          // onGameMessage("Colosseum duration: 26:13.20 (new personal best)");
        */

        //Fragment of Seren Test
        /*
          onGameMessage("Your Fragment of Seren kill count is: 2.");
          // onGameMessage("Fight duration: 4:25.20. Personal best: 3:25.20.");
          // onGameMessage("Fight duration: 3:29 (new personal best).");
        */

        //Galvek Test
        /*
          onGameMessage("Your Galvek kill count is: 2.");
          // onGameMessage("Fight duration: 3:48.60. Personal best: 2:58.80");
          // onGameMessage("Fight duration: 2:19 (new personal best)");
        */

        //Grotesque Guardians Test

          onGameMessage("Fight duration: 2:12. Personal best: 1:18");
          //onGameMessage("Fight duration: 1:55 (new personal best)");
          onGameMessage("Your Grotesque Guardians kill count is 413.");


        //Hespori Test
        /*
          onGameMessage("Your Hespori kill count is: 134.");
          // onGameMessage("Fight duration: 1:16. Personal best: 0:44");
          // onGameMessage("Fight duration: 0:35 (new personal best)");
        */

        //Colossal Wyrm Agility (Basic) Test
        /*
          onGameMessage("Your Colossal Wyrm Agility Course (Basic) lap count is: 3.");
          // onGameMessage("Lap duration: 2:52.80. Personal best: 1:22.20");
          // onGameMessage("Lap duration: 2:52.80 (new personal best)");
        */

        //Colossal Wyrm Agility (Advanced) Test
        /*
          onGameMessage("Your Colossal Wyrm Agility Course (Advanced) lap count is: 217.");
          // onGameMessage("Lap duration: 1:01.80. Personal best: 0:59.40");
          // onGameMessage("Lap duration: 1:01.80 (new personal best)");
        */

        //Priff Agility Test
        /*
          onGameMessage("Your Prifddinas Agility Course lap count is: 92.");
          // onGameMessage("Lap duration: 1:15.00. Personal best: 1:04.80");
          // onGameMessage("Lap duration: 1:15.00 (new personal best)");
        */

        //Hallowed Sepluchre Floor Test
        /*
          onGameMessage("You have completed Floor 4 of the Hallowed Sepulchre! Total completions: 125.");
          // onGameMessage("Floor 4 time: 1:46.80. Personal best: 1:36.60");
          // onGameMessage("Floor 4 time: 1:46.80 (new personal best)");
        */

        //Hallowed Sepluchre Total Test
        /*
          onGameMessage("You have completed Floor 5 of the Hallowed Sepulchre! Total completions: 95.");
          // onGameMessage("Floor 5 time: 2:49.80. Personal best: 2:38.40");
          // onGameMessage("Overall time: 6:48.60. Personal best: 6:18.00");
          // onGameMessage("Overall time: 6:48.60 (new personal best)");
        */

        //Inferno Test
        /*
          onGameMessage("Your TzKal-Zuk kill count is 1.");
          // onGameMessage("Duration: 2:23:41. Personal best: 1:09:04");
          // onGameMessage("Duration: 2:21:41 (new personal best)");
        */

        //Phantom Muspah Test
        /*
          onGameMessage("Your Phantom Muspah kill count is: 12.");
          // onGameMessage("Fight duration: 3:11. Personal best: 2:17");
          // onGameMessage("Fight duration: 2:02 (new personal best)");
        */

        //Six Jads Test
        /*
          onGameMessage("Your completion count for Tzhaar-Ket-Rak's Sixth Challenge is 1.");
          // onGameMessage("Challenge duration: 6:02. Personal best: 5:31");
          // onGameMessage("Challenge duration: 6:31.80 (new personal best");
        */

        //Hueycoatl Test
        /*
          onGameMessage("Your Hueycoatl kill count is 3.");
          // onGameMessage("Fight duration: 3:09.60. Personal best: 0:58.20");
          // onGameMessage("Fight duration: 3:04 (new personal best)");
        */

        //Leviathan Test
        /*
          onGameMessage("Your Leviathan kill count is 2.");
          // onGameMessage("Fight duration: 3:19. Personal best: 2:50");
          // onGameMessage("Fight duration: 2:16.80 (new personal best)");
        */

        //Royal Titans Test
        /*
          onGameMessage("Your Royal Titans kill count is: 9.");
          // onGameMessage("Fight Duration: 2:33. Personal best: 0:53");
          // onGameMessage("Fight duration: 2:50 (new personal best)");
        */

        //Whisperer Test
        /*
          onGameMessage("Your whisperer kill count is 4.");
          // onGameMessage("Fight duration: 3:06.00. Personal best: 2:29.40");
          // onGameMessage("Fight duration: 2:18.60 (new personal best)");
        */

        //Vardorvis Test
        /*
          onGameMessage("Your Vardorvis kill count is: 18.");
          // onGameMessage("Fight duration: 4:04. Personal best: 1:13");
          // onGameMessage("Fight duration: 2:39 (new personal best)");
        */

        //Vorkath Test
        /*
          onGameMessage("Your Vorkath kill count is: 168.");
          // onGameMessage("Fight duration: 4:04. Personal best: 1:13");
          // onGameMessage("Fight duration: 1:53 (new personal best)");
        */

    }
}

