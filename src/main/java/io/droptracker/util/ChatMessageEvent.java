package io.droptracker.util;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import io.droptracker.DropTrackerConfig;
import io.droptracker.models.BossNotification;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.models.CombatAchievement;
import io.droptracker.models.CustomWebhookBody;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.annotations.Varbit;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.chatcommands.ChatCommandsPlugin;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.Temporal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private ClientThread clientThread;


    @Inject
    public ChatMessageEvent(DropTrackerPlugin plugin, DropTrackerConfig config) {
        this.plugin = plugin;
        this.config = config;
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

    private static final Pattern PRIMARY_REGEX = Pattern.compile("Your (?<key>.+)\\s(?<type>kill|chest|completion)\\s?count is: (?<value>\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECONDARY_REGEX = Pattern.compile("Your (?:completed|subdued) (?<key>.+) count is: (?<value>\\d+)\\b");
    private static final Pattern TIME_REGEX = Pattern.compile("(?:Duration|time|Subdued in):? (?<time>[\\d:]+(.\\d+)?)\\.?", Pattern.CASE_INSENSITIVE);

    private static final String BA_BOSS_NAME = "Penance Queen";

    public static final String GAUNTLET_NAME = "Gauntlet", GAUNTLET_BOSS = "Crystalline Hunllef";
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

    @Varbit
    public static final int KILL_COUNT_SPAM_FILTER = 4930;

    public boolean isEnabled() {
        return true;
        // Todo: add config option for enabling
        //  return config.notifyKillCount(); // Add the appropriate condition for enabling
    }

    public void onGameMessage(String message) {
        if (isEnabled())
            parseBossKill(message).ifPresent(this::updateData);
            parseCombatAchievement(message).ifPresent(pair -> processCombatAchievement(pair.getLeft(), pair.getRight()));
    }

    public void onFriendsChatNotification(String message) {
        /* Chambers of Xeric completions are sent in the Friends chat channel */
        if (message.startsWith("Congratulations - your raid is complete!"))
            this.onGameMessage(message);
    }

    public void onWidget(WidgetLoaded event) {
        if (!isEnabled())
            return;
        /* Handle BA events */
        if (event.getGroupId() == InterfaceID.BA_REWARD) {
            Widget widget = client.getWidget(ComponentID.BA_REWARD_REWARD_TEXT);
            if (widget != null && widget.getText().contains("80 ") && widget.getText().contains("5 ")) {
                int gambleCount = client.getVarbitValue(Varbits.BA_GC);
                BossNotification notification = new BossNotification(BA_BOSS_NAME, gambleCount, "The Queen is dead!", null, null);
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
        if (cumulativeUnlockPoints.size() < CUM_POINTS_VARBIT_BY_TIER.size())
            initThresholds();
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
            combatWebhook.setEmbeds((List<CustomWebhookBody.Embed>) combatAchievementEmbed);
            plugin.sendDropTrackerWebhook(combatWebhook, "combat_achievement");
        });
    }


    private void processKill(BossNotification data) {
        if (data.getBoss() == null || data.getCount() == null)
            return;
        boolean ba = data.getBoss().equals(BA_BOSS_NAME);
        boolean isPb = data.isPersonalBest() == Boolean.TRUE;
        String player = plugin.getLocalPlayerName();
        String time = formatTime(data.getTime(), isPreciseTiming(client));
        CustomWebhookBody.Embed killEmbed = null;
        CustomWebhookBody killWebhook = new CustomWebhookBody();
        killEmbed = new CustomWebhookBody.Embed();
        Integer npcId = null;

        if (ba) {
            npcId = NpcID.PENANCE_QUEEN;
            killEmbed.setImage(ItemUtilities.getNpcImageUrl(npcId));
        } else {
            Optional<NPC> npcOptional = Arrays.stream(client.getCachedNPCs())
                    .filter(Objects::nonNull)
                    .filter(npc -> data.getBoss().equalsIgnoreCase(npc.getName()))
                    .findAny();

            if (npcOptional.isPresent()) {
                NPC npc = npcOptional.get();
                npcId = npc.getId();
                killEmbed.setImage(ItemUtilities.getNpcImageUrl(npcId));
            }
        }
        killEmbed.setTitle(player + " has killed a boss:");
        killEmbed.addField("type", "npc_kill", true);
        killEmbed.addField("boss_name", data.getBoss(), true);
        killEmbed.addField("npc_id", npcId.toString(), true);
        killEmbed.addField("kill_time", time, true);
        killEmbed.addField("is_pb", String.valueOf(isPb), true);

        killWebhook.setEmbeds(Collections.singletonList(killEmbed));
        plugin.sendDropTrackerWebhook(killWebhook, "1");
        // Call webhook or whatever method to send the notification
    }

    private void updateData(BossNotification updated) {
        bossData.getAndUpdate(old -> {
            if (old == null) {
                return updated;
            } else {
                return new BossNotification(
                        defaultIfNull(updated.getBoss(), old.getBoss()),
                        defaultIfNull(updated.getCount(), old.getCount()),
                        defaultIfNull(updated.getGameMessage(), old.getGameMessage()),
                        defaultIfNull(updated.getTime(), old.getTime()),
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

    private static Optional<BossNotification> parseBossKill(String message) {
        System.out.println("Parsing message" + message);
        Optional<Pair<String, Integer>> boss = parseBoss(message);
        if (boss.isPresent()) {
            System.out.println("Boss returned as " + boss);
        }
        if (boss.isPresent())
            return boss.map(pair -> new BossNotification(pair.getLeft(), pair.getRight(), message, null, null));
        return parseKillTime(message).map(t -> new BossNotification(null, null, message, t.getLeft(), t.getRight()));
    }

    private static Optional<Pair<String, Integer>> parseBoss(String message) {
        Matcher primary = PRIMARY_REGEX.matcher(message);
        Matcher secondary;
        if (primary.find()) {
            String boss = parsePrimaryBoss(primary.group("key"), primary.group("type"));
            String count = primary.group("value");
            return result(boss, count);
        } else if ((secondary = SECONDARY_REGEX.matcher(message)).find()) {
            String key = parseSecondary(secondary.group("key"));
            String value = secondary.group("value");
            return result(key, value);
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

    private static Optional<Pair<Duration, Boolean>> parseKillTime(String message) {
        Matcher matcher = TIME_REGEX.matcher(message);
        if (matcher.find()) {
            Duration duration = parseTime(matcher.group("time"));
            boolean pb = message.toLowerCase().contains("(new personal best)");
            return Optional.of(Pair.of(duration, pb));
        }
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
        // noinspection UnstableApiUsage (builderWithExpectedSize is no longer @Beta in snapshot guava)
        CUM_POINTS_VARBIT_BY_TIER = ImmutableMap.<CombatAchievement, Integer>builderWithExpectedSize(6)
                .put(CombatAchievement.EASY, 4132) // 33 = 33 * 1
                .put(CombatAchievement.MEDIUM, 10660) // 115 = 33 + 41 * 2
                .put(CombatAchievement.HARD, 10661) // 304 = 115 + 63 * 3
                .put(CombatAchievement.ELITE, 14812) // 820 = 304 + 129 * 4
                .put(CombatAchievement.MASTER, 14813) // 1465 = 820 + 129 * 5
                .put(CombatAchievement.GRANDMASTER, GRANDMASTER_TOTAL_POINTS_ID) // 2005 = 1465 + 90 * 6
                .build();
    }
}
