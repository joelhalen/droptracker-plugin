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
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
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
public class PbHandler {
    @Inject
    private final DropTrackerPlugin plugin;
    private final DropTrackerConfig config;

    @Inject
    protected Client client;

    @Inject
    private Rarity rarity;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ClientThread clientThread;

    private ItemIDSearch itemIDFinder;


    private static final Duration RECENT_DROP = Duration.ofSeconds(30L);
    @Inject
    public PbHandler(DropTrackerPlugin plugin, DropTrackerConfig config, ItemIDSearch itemIDFinder,
                            ScheduledExecutorService executor, ConfigManager configManager) {
        this.plugin = plugin;
        this.config = config;
        this.itemIDFinder = itemIDFinder;
        this.executor = executor;
        this.configManager = configManager;
    }


    private static final Pattern PRIMARY_REGEX = Pattern.compile(
            "Your (?<key>[\\w\\s:'-]+) (?<type>kill|chest|completion|success) count is:? (?<value>[\\d,]+)"
    );
    private static final Pattern SECONDARY_REGEX = Pattern.compile("Your (?<type>kill|chest|completed) (?<key>[\\w\\s:]+) count is:? (?<value>[\\d,]+)");


    private static final String BA_BOSS_NAME = "Penance Queen";
    public static final String GAUNTLET_NAME = "Gauntlet", GAUNTLET_BOSS = "Crystalline Hunllef";

    public static final String CG_NAME = "Corrupted Gauntlet", CG_BOSS = "Corrupted Hunllef";
    private static final String TOA = "Tombs of Amascut";
    private static final String TOB = "Theatre of Blood";
    private static final String COX = "Chambers of Xeric";

    @VisibleForTesting
    static final int MAX_BAD_TICKS = 10;

    private final AtomicInteger badTicks = new AtomicInteger();
    private final AtomicReference<BossNotification> bossData = new AtomicReference<>();

    private static Pair<String, Integer> mostRecentNpcData = null;

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
    private final Map<String, PbHandler.TimeData> pendingTimeData = new HashMap<>();
    private String lastProcessedKill = null;
    private long lastProcessedTime = 0;
    private static final long DUPLICATE_THRESHOLD = 5000;
    private String teamSize = null;

    public boolean isEnabled() {
        return true;
    }

    public void onGameMessage(String message) {
        if (!isEnabled()) return;
        checkPB(message);
        checkTime(message);
        parseBossKill(message).ifPresent(this::updateData);
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
                    return;
                }
            } else if (badTicks.incrementAndGet() > MAX_BAD_TICKS) {
                reset();
            }
        }
        else if (mostRecentNpcData != null) {
            if (bossData.get() != null) {
                processKill(bossData.get());
            }
            plugin.ticksSinceNpcDataUpdate += 1;
        } else {
            if (plugin.ticksSinceNpcDataUpdate > 1)  {
                plugin.ticksSinceNpcDataUpdate = 0;
            }
        }
        if (plugin.ticksSinceNpcDataUpdate >= 5 && mostRecentNpcData != null) {
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

    private int getKc(String boss)
    {
        Integer killCount = configManager.getRSProfileConfiguration("killcount", boss.toLowerCase(), int.class);
        return killCount == null ? 0 : killCount;
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
            return;
        }
        lastProcessedKill = killIdentifier;
        lastProcessedTime = currentTime;
        boolean ba = data.getBoss().equals(BA_BOSS_NAME);
        boolean isPb = data.isPersonalBest() == Boolean.TRUE;
        String player = plugin.getLocalPlayerName();
        String time = null;
        final String[] timeRef = {null};
        final String[] bestTimeRef = {null};
        clientThread.invokeLater(() -> {
            timeRef[0] = formatTime(data.getTime(), isPreciseTiming(client));
            bestTimeRef[0] = formatTime(data.getBestTime(), isPreciseTiming(client));
            String accountHash = String.valueOf(client.getAccountHash());
            CustomWebhookBody.Embed killEmbed = null;
            CustomWebhookBody killWebhook = new CustomWebhookBody();
            killEmbed = new CustomWebhookBody.Embed();
            String bossName = "";
            if (teamSize == null || teamSize.equals("")) {
                teamSize = "Solo";
            }
            if (mostRecentNpcData != null) {
                bossName = mostRecentNpcData.getLeft();
            } else if (!pendingNotifications.isEmpty()) {
                if (pendingNotifications.size() > 1) {
                } else {
                    bossName = pendingNotifications.get(0).getBoss();
                }
            }
            if (bossName == null || bossName.equalsIgnoreCase("")){
                return;
            }
            Integer killCount = 0;
            killEmbed.setTitle(player + " has killed a boss:");
            killEmbed.addField("type", "npc_kill", true);
            killEmbed.addField("boss_name", bossName, true);
            killEmbed.addField("player_name", plugin.getLocalPlayerName(), true);
            killEmbed.addField("kill_time", timeRef[0], true);
            killEmbed.addField("best_time", bestTimeRef[0], true);
            killEmbed.addField("is_pb", String.valueOf(isPb), true);
            killEmbed.addField("team_size", teamSize,true);
            killEmbed.addField("acc_hash", accountHash, true);
            killEmbed.addField("p_v",plugin.pluginVersion,true);
            if (bossName != null) {
                killCount = getKc(bossName);
                killEmbed.addField("killcount", String.valueOf(killCount), true);
            }
            killWebhook.getEmbeds().add(killEmbed);
            plugin.sendDataToDropTracker(killWebhook, "1");
            mostRecentNpcData = null;
            pendingNotifications.clear();
            bossData.set(null);
            teamSize = null;
        });
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
        teamSize = null;
    }


    private Optional<BossNotification> parseBossKill(String message) {
        Optional<Pair<String, Integer>> boss = parseBoss(message);
        return boss.flatMap(pair -> {
            String bossName = pair.getLeft();
            // retrieve the stored timeData for this bossName, if any is stored...
            // for cases where a time message may appear before the boss name/kc message appears
            PbHandler.TimeData timeData = pendingTimeData.get(bossName);

            //Search for stored TimeData
            if(timeData != null){


                BossNotification newBossData  = new BossNotification(
                        bossName,
                        pair.getRight(),
                        message,
                        timeData.time,
                        timeData.bestTime,
                        timeData.isPb
                );
                bossData.set(newBossData);
                return Optional.of(newBossData);
            } else {
                BossNotification currentData = bossData.get();
                if (currentData != null) {
                    BossNotification newBossData = new BossNotification(
                            bossName,
                            pair.getRight(),
                            "",
                            currentData.getTime(),
                            currentData.getBestTime(),
                            currentData.isPersonalBest()
                    );
                    bossData.set(newBossData);
                    return Optional.of(newBossData);
                }
                return Optional.empty();
            }
        });
    }
    private Optional<Triple<Duration, Duration, Boolean>> parseKillTime(String message, String bossName) {

        Pattern[] groupedPatterns;

        List<Pair<Pattern, Boolean>> allPatterns = new ArrayList<>();
        for (Pattern pattern : PB_PATTERNS) {
            allPatterns.add(Pair.of(pattern, true));
        }
        for (Pattern pattern : TIME_PATTERNS) {
            allPatterns.add(Pair.of(pattern, false));
        }
        for (Pair<Pattern, Boolean> patternPair : allPatterns) {
            Pattern pattern = patternPair.getLeft();
            boolean isPb = patternPair.getRight();

            Matcher timeMatcher = pattern.matcher(message);
            if (timeMatcher.find()) {
                String timeStr = "";
                String bestTimeStr = "";

                try {
                    timeStr = timeMatcher.group(1);
                    bestTimeStr = isPb ? timeMatcher.group(1) : timeMatcher.group(2);
                } catch (Exception e) {
                    // Handle exception
                }

                Duration time = parseTime(timeStr);
                Duration bestTime = parseTime(bestTimeStr);
                setTeamSize(bossName, message);
                storeBossTime(bossName, time, bestTime, isPb);

                return Optional.of(Triple.of(time, bestTime, isPb));
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
            e.printStackTrace();
            return null;
        }
    }

    public Optional<Pair<String, Integer>> parseBoss(String message) {
        Matcher primary = PRIMARY_REGEX.matcher(message);
        Matcher secondary = SECONDARY_REGEX.matcher(message);


        if (primary.find()) {
            String boss = parsePrimaryBoss(primary.group("key"), primary.group("type"));
            String count = primary.group("value");
            if (boss != null) {
                try {
                    int killCount = Integer.parseInt(count.replace(",", ""));
                    mostRecentNpcData = Pair.of(boss, killCount);
                    plugin.ticksSinceNpcDataUpdate = 0;
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
                    plugin.ticksSinceNpcDataUpdate = 0;
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

            case "success":
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
            if (pending.getBoss().equals("")) {
                String npcName = source;
                BossNotification trueBossNoti = new BossNotification(npcName, pending.getCount(), pending.getGameMessage(), pending.getTime(), pending.getBestTime(), pending.isPersonalBest());
                bossData.set(trueBossNoti);
            }
            processKill(pending);
        } else {
            // Store the loot event info for later matching with chat message
            lastDrop = new Drop(source, event.getType(), event.getItems());
        }
    }

    //Storing boss Time to either access at a later point or to move through sending the time
    private void storeBossTime(String bossName, Duration time, Duration bestTime, boolean isPb){
        PbHandler.TimeData timeData = new PbHandler.TimeData(time,bestTime,isPb);
        pendingTimeData.put(bossName,timeData);
        BossNotification withTime = null;
        if (mostRecentNpcData != null) {
            withTime = new BossNotification(
                    bossName,
                    mostRecentNpcData.getRight(),
                    "",
                    time,
                    bestTime,
                    isPb
            );

        } else {
            withTime = new BossNotification(
                    "",
                    0,
                    "",
                    time,
                    bestTime,
                    isPb
            );
        }
        bossData.set(withTime);
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
                } else {
                    storeBossTime("Grotesque Guardians",time,bestTime,isPb);
                    teamSize = "Solo";
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

                String bossName = mostRecentNpcData != null ? mostRecentNpcData.getLeft() : null;
                if (bossName != null) {
                    setTeamSize(bossName, message);
                    storeBossTime(bossName, time, bestTime, isPb);
                    return;
                } else {
                    storeBossTime("Grotesque Guardians",time,bestTime,isPb);
                    teamSize = "Solo";
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

}
