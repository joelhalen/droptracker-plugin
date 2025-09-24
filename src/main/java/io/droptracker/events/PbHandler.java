package io.droptracker.events;

import io.droptracker.models.BossNotification;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import io.droptracker.util.NpcUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.annotations.Varbit;
import org.apache.commons.lang3.ObjectUtils;
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
public class PbHandler extends BaseEventHandler {

    @VisibleForTesting
    static final int MAX_BAD_TICKS = 10;
    private static final long DUPLICATE_THRESHOLD = 5000;
    private static final long MESSAGE_LOOT_WINDOW = 15000; // 15 seconds

    /* Four regex filters to rule them all :) */
    private static final Pattern BOSS_COUNT_PATTERN = Pattern.compile(
        "Your (?<key>[\\w\\s:'-]+) (?<type>kill|chest|completion|success) count is:? (?<value>[\\d,]+)", 
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SECONDARY_BOSS_PATTERN = Pattern.compile(
        "Your (?<type>completed|subdued) (?<key>[\\w\\s:]+) count is: (?<value>[\\d,]+)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TIME_WITH_PB_PATTERN = Pattern.compile(
        "(?<prefix>.*?)(?<duration>\\d*:?\\d+:\\d+(?:\\.\\d+)?)\\.?\\s+(?:Personal best: (?<pbtime>\\d*:?\\d+:\\d+(?:\\.\\d+)?)\\.?)?(?<pb_indicator>\\(new personal best\\))?",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TEAM_SIZE_PATTERN = Pattern.compile(
        "Team size: (?<size>\\d+) players?",
        Pattern.CASE_INSENSITIVE
    );
    
    // State management
    private final AtomicInteger badTicks = new AtomicInteger();
    private final AtomicReference<KillData> killData = new AtomicReference<>();
    
    // Duplicate prevention
    private String lastProcessedKill = null;
    private long lastProcessedTime = 0;
    
    private static class KillData {
        final String boss;
        final Integer count;
        final Duration time;
        final Duration bestTime;
        final boolean isPersonalBest;
        final String teamSize;
        final String gameMessage;

        KillData(String boss, Integer count, Duration time, Duration bestTime, 
                boolean isPersonalBest, String teamSize, String gameMessage) {
            this.boss = boss;
            this.count = count;
            this.time = time;
            this.bestTime = bestTime;
            this.isPersonalBest = isPersonalBest;
            this.teamSize = teamSize;
            this.gameMessage = gameMessage;
        }

        KillData withTime(Duration time, Duration bestTime, boolean isPersonalBest) {
            return new KillData(boss, count, time, bestTime, isPersonalBest, teamSize, gameMessage);
        }

        KillData withBossCount(String boss, Integer count) {
            return new KillData(boss, count, time, bestTime, isPersonalBest, teamSize, gameMessage);
        }

        KillData withTeamSize(String teamSize) {
            return new KillData(boss, count, time, bestTime, isPersonalBest, teamSize, gameMessage);
        }

        boolean isValid() {
            return boss != null && count != null && time != null && !time.isZero();
        }
    }

    @Override
    public boolean isEnabled() {
        return config.pbEmbeds();
    }

    public void onGameMessage(String message) {
        if (!isEnabled()) return;
        
        // Parse and update data in a clean pipeline
        parseMessage(message).ifPresent(this::updateKillData);
    }

    public void onFriendsChatNotification(String message) {
        // Chambers of Xeric completions are sent in the Friends chat channel
        if (message.startsWith("Congratulations - your raid is complete!")) {
            onGameMessage(message);
        }
    }

    public void onTick() {
        KillData data = killData.get();
        
        if (data == null) {
            if (badTicks.incrementAndGet() > MAX_BAD_TICKS) {
                reset();
            }
            return;
        }

        if (data.isValid() && isEnabled()) {
            processKill(data);
            reset();
        } else if (badTicks.incrementAndGet() > MAX_BAD_TICKS) {
            reset();
        }
    }

    /**
     * Parses game messages to extract kill data.
     * 
     */
    private Optional<KillData> parseMessage(String message) {
        // Skip preparation messages
        if (message.startsWith("Preparation")) {
            return Optional.empty();
        }

        // Try to parse boss count first
        Optional<Pair<String, Integer>> bossCount = parseBossCount(message);
        if (bossCount.isPresent()) {
            Pair<String, Integer> pair = bossCount.get();
            return Optional.of(new KillData(pair.getLeft(), pair.getRight(), 
                Duration.ZERO, Duration.ZERO, false, null, message));
        }

        // Try to parse time data
        return parseTimeData(message);
    }

    /**
     * Parses boss count information from messages.
     */
    private Optional<Pair<String, Integer>> parseBossCount(String message) {
        // Primary pattern
        Matcher primary = BOSS_COUNT_PATTERN.matcher(message);
        if (primary.find()) {
            String boss = parsePrimaryBoss(primary.group("key"), primary.group("type"));
            if (boss != null) {
                try {
                    int count = Integer.parseInt(primary.group("value").replace(",", ""));
                    return Optional.of(Pair.of(boss, count));
                } catch (NumberFormatException e) {
                    log.debug("Failed to parse kill count: {}", primary.group("value"));
                }
            }
        }

        // Secondary pattern
        Matcher secondary = SECONDARY_BOSS_PATTERN.matcher(message);
        if (secondary.find()) {
            String boss = parseSecondaryBoss(secondary.group("key"));
            if (boss != null) {
                try {
                    int count = Integer.parseInt(secondary.group("value").replace(",", ""));
                    return Optional.of(Pair.of(boss, count));
                } catch (NumberFormatException e) {
                    log.debug("Failed to parse kill count: {}", secondary.group("value"));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Parses time data from messages.
     */
    private Optional<KillData> parseTimeData(String message) {
        Matcher matcher = TIME_WITH_PB_PATTERN.matcher(message);
        if (!matcher.find()) {
            return Optional.empty();
        }

        try {
            Duration time = parseTime(matcher.group("duration"));
            String pbTimeStr = matcher.group("pbtime");
            Duration bestTime = pbTimeStr != null ? parseTime(pbTimeStr) : Duration.ZERO;
            boolean isPersonalBest = matcher.group("pb_indicator") != null;
            
            // Determine boss name from context
            String bossName = determineBossFromContext(message);
            String teamSize = extractTeamSize(message);
            
            return Optional.of(new KillData(bossName, null, time, bestTime, 
                isPersonalBest, teamSize, message));
                
        } catch (Exception e) {
            log.error("Error parsing time data: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Determines boss name from message context when not explicitly provided.
     */
    private String determineBossFromContext(String message) {
        // Check for specific raid patterns
        if (message.contains("Tombs of Amascut")) {
            return message.contains("Expert Mode") ? "Tombs of Amascut: Expert Mode" : "Tombs of Amascut";
        }
        if (message.contains("Theatre of Blood")) {
            return "Theatre of Blood";
        }
        if (message.contains("Chambers of Xeric")) {
            return "Chambers of Xeric";
        }
        if (message.contains("Corrupted challenge")) {
            return "Corrupted Hunllef";
        }
        if (message.contains("Challenge duration")) {
            return "Crystalline Hunllef";
        }
        if (message.contains("Colosseum duration")) {
            return "Sol Heredit";
        }
        if (message.contains("Delve level")) {
            return extractDelveBoss(message);
        }
        
        // Default fallback
        return "Grotesque Guardians";
    }

    /**
     * Extracts delve boss information with level.
     */
    private String extractDelveBoss(String message) {
        Pattern delvePattern = Pattern.compile("Delve level: (\\S+)");
        Matcher matcher = delvePattern.matcher(message);
        if (matcher.find()) {
            return "Doom of Mokhaiotl (Level:" + matcher.group(1).trim() + ")";
        }
        return "Doom of Mokhaiotl";
    }

    /**
     * Extracts team size from message or determines from varbits.
     */
    private String extractTeamSize(String message) {
        // Check message for explicit team size
        Matcher teamMatcher = TEAM_SIZE_PATTERN.matcher(message);
        if (teamMatcher.find()) {
            return teamMatcher.group("size");
        }

        // Check for raid-specific team size patterns
        if (message.contains("Tombs of Amascut")) {
            return getToaTeamSize();
        }
        if (message.contains("Theatre of Blood")) {
            return getTobTeamSize();
        }
        if (message.contains("Royal Titans")) {
            return getRoyalTitansTeamSize();
        }

        // Default to solo
        return "Solo";
    }

    /**
     * Updates kill data with new information, merging intelligently.
     */
    private void updateKillData(KillData newData) {
        killData.getAndUpdate(old -> {
            if (old == null) {
                return newData;
            }

            // Merge data intelligently
            String boss = defaultIfNull(newData.boss, old.boss);
            Integer count = defaultIfNull(newData.count, old.count);
            Duration time = newData.time != null && !newData.time.isZero() ? newData.time : old.time;
            Duration bestTime = newData.bestTime != null && !newData.bestTime.isZero() ? newData.bestTime : old.bestTime;
            boolean isPersonalBest = newData.isPersonalBest || old.isPersonalBest;
            String teamSize = defaultIfNull(newData.teamSize, old.teamSize);
            String gameMessage = defaultIfNull(newData.gameMessage, old.gameMessage);

            return new KillData(boss, count, time, bestTime, isPersonalBest, teamSize, gameMessage);
        });
    }

    /**
     * Processes a complete kill notification.
     */
    private void processKill(KillData data) {
        if (data == null || !data.isValid()) {
            log.debug("Invalid kill data, skipping processing");
            return;
        }

        // Check for duplicates
        String killIdentifier = data.boss + "-" + data.count;
        long currentTime = System.currentTimeMillis();
        
        if (killIdentifier.equals(lastProcessedKill) && 
            (currentTime - lastProcessedTime) < DUPLICATE_THRESHOLD) {
            if (!data.boss.contains("1-8")) {
                log.debug("Duplicate kill detected, skipping: {}", killIdentifier);
                return;
            }
        }
        
        lastProcessedKill = killIdentifier;
        lastProcessedTime = currentTime;

        // Process on client thread
        if (clientThread == null) {
            log.error("ClientThread is null, cannot process kill");
            return;
        }

        clientThread.invokeLater(() -> {
            try {
                sendKillNotification(data);
            } catch (Exception e) {
                log.error("Error processing kill notification: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * Sends the kill notification webhook.
     */
    private void sendKillNotification(KillData data) {
        String player = getPlayerName();
        String formattedTime = formatTime(data.time, isPreciseTiming(client));
        String formattedBestTime = formatTime(data.bestTime, isPreciseTiming(client));
        
        // Create webhook
        CustomWebhookBody webhook = createWebhookBody(player + " has killed a boss:");
        CustomWebhookBody.Embed embed = createEmbed(player + " has killed a boss:", "npc_kill");
        
        // Add fields
        Map<String, Object> fieldData = new HashMap<>();
        fieldData.put("boss_name", data.boss);
        fieldData.put("kill_time", formattedTime != null ? formattedTime : "N/A");
        fieldData.put("best_time", formattedBestTime != null ? formattedBestTime : "N/A");
        fieldData.put("is_pb", data.isPersonalBest);
        fieldData.put("team_size", data.teamSize != null ? data.teamSize : "Solo");
        fieldData.put("killcount", data.count);
        
        addFields(embed, fieldData);
        webhook.getEmbeds().add(embed);
        
        // Send data
        sendData(webhook, SubmissionType.KILL_TIME);
    }

    /**
     * Resets handler state.
     */
    public void reset() {
        killData.set(null);
        badTicks.set(0);
    }

    // ========== UTILITY METHODS ==========

    /**
     * Parses time string into Duration.
     */
    private Duration parseTime(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return Duration.ZERO;
        }

        try {
            String timePart = timeStr.contains(".") ? timeStr.substring(0, timeStr.indexOf('.')) : timeStr;
            String[] timeParts = timePart.split(":");

            long hours = 0;
            long minutes = 0;
            long seconds = 0;
            long millis = 0;

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
                while (millisStr.length() < 3) {
                    millisStr += "0";
                }
                millis = Long.parseLong(millisStr);
            }

            return Duration.ofHours(hours)
                    .plusMinutes(minutes)
                    .plusSeconds(seconds)
                    .plusMillis(millis);

        } catch (Exception e) {
            log.error("Error parsing time: {}", e.getMessage());
            return Duration.ZERO;
        }
    }

    /**
     * Formats duration for display.
     */
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

    /**
     * Checks if precise timing is enabled.
     */
    public boolean isPreciseTiming(@NotNull Client client) {
        @Varbit int ENABLE_PRECISE_TIMING = 11866;
        return client.getVarbitValue(ENABLE_PRECISE_TIMING) > 0;
    }

    // ========== BOSS PARSING METHODS ==========

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
                if (NpcUtilities.GAUNTLET_NAME.equalsIgnoreCase(boss))
                    return NpcUtilities.GAUNTLET_BOSS;
                if (NpcUtilities.CG_NAME.equalsIgnoreCase(boss))
                    return NpcUtilities.CG_BOSS;
                return null;

            case "kill":
            case "success":
                return boss;

            default:
                return null;
        }
    }

    private static String parseSecondaryBoss(String boss) {
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



    //Storing boss Time to either access at a later point or to move through sending the time
    private void storeBossTime(String bossName, Duration time, Duration bestTime, boolean isPb){
        if (bossName == null || bossName.trim().isEmpty()) {
            log.debug("Cannot store boss time with null/empty boss name");
            return;
        }
        if (time == null) {
            log.debug("Cannot store boss time with null duration");
            return;
        }
        
        try {
            PbHandler.TimeData timeData = new PbHandler.TimeData(time, bestTime != null ? bestTime : Duration.ZERO, isPb);
            pendingTimeData.put(bossName, timeData);
            
            BossNotification withTime;
            if (mostRecentNpcData != null && mostRecentNpcData.getRight() != null) {
                withTime = new BossNotification(
                        bossName,
                        mostRecentNpcData.getRight(),
                        "",
                        time,
                        bestTime != null ? bestTime : Duration.ZERO,
                        isPb
                );
            } else {
                withTime = new BossNotification(
                        bossName,
                        0,
                        "",
                        time,
                        bestTime != null ? bestTime : Duration.ZERO,
                        isPb
                );
            }
            bossData.set(withTime);
            processKill(withTime);
        } catch (Exception e) {
            log.error("Error storing boss time for {}: {}", bossName, e.getMessage(), e);
        }
    }
    private static class TimeData {
        final Duration time;
        final Duration bestTime;
        final boolean isPb;

        TimeData(Duration time, Duration bestTime, boolean isPb) {
            this.time = time;
            this.bestTime = bestTime;
            this.isPb = isPb;
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
                    if(message.contains("Delve level") && message.contains("best")){
                        noKcPB(message,time,bestTime,isPb);
                        teamSize="Solo";
                    }
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
                } else if(message.contains("Theatre of Blood")){
                    setTeamSize("Theatre of Blood",message);
                    storeBossTime("Theatre of Blood: Entry Mode", time,bestTime,isPb);
                    storeBossTime("Theatre of Blood",time,bestTime,isPb);
                    storeBossTime("Theatre of Blood: Hard Mode",time,bestTime,isPb);
                } else if (message.contains("Corrupted challenge")) {
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
                    if(message.contains("Delve level") && message.contains("best")){
                        noKcPB(message,time,bestTime,isPb);
                        teamSize="Solo";
                    }
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
    @SuppressWarnings("deprecation")
    private String getTobTeamSize() {
        Integer teamSize = Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB1), 1) +
                Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB2), 1) +
                Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB3), 1) +
                Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB4), 1) +
                Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB5), 1);
        return teamSize == 1 ? "Solo" : teamSize.toString();
    }

    @SuppressWarnings("deprecation")
    private String getToaTeamSize() {
        Integer teamSize = Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_0_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_1_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_2_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_3_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_4_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_5_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_6_HEALTH), 1) +
                Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_7_HEALTH), 1);
        return teamSize == 1 ? "Solo" : teamSize.toString();
    }

    @SuppressWarnings("deprecation")
    private String getRoyalTitansTeamSize() {
        int size = client.getPlayers().size();
        return size == 1 ? "Solo" : String.valueOf(size);
    }
}
