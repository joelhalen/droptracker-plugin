package io.droptracker.events;

import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import io.droptracker.util.NpcUtilities;
import io.droptracker.util.DebugLogger;
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

    private static final Pattern BOSS_COUNT_PATTERN = Pattern.compile(
        "Your (?<key>[\\w\\s:'-]+) (?<type>kill|chest|completion|success) count is:? (?<value>[\\d,]+)", 
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern SECONDARY_BOSS_PATTERN = Pattern.compile(
        "Your (?<type>completed|subdued) (?<key>[\\w\\s:]+) count is:?[ \\t]*(?<value>[\\d,]+)",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern TIME_WITH_PB_PATTERN = Pattern.compile(
        "(?<prefix>.*?)(?<duration>\\d*:?\\d+:\\d+(?:\\.\\d+)?)\\.?\\s*(?:Personal best: (?<pbtime>\\d*:?\\d+:\\d+(?:\\.\\d+)?)\\.?\\s*)?(?<pbIndicator>\\(new personal best\\))?",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern TEAM_SIZE_PATTERN = Pattern.compile(
        "Team size:\\s*(?<size>\\d+|Solo|\\d\\+)\\s*(?:players?)?",
        Pattern.CASE_INSENSITIVE
    );

    @VisibleForTesting
    static Pattern bossCountPattern() {
        return BOSS_COUNT_PATTERN;
    }

    @VisibleForTesting
    static Pattern secondaryBossPattern() {
        return SECONDARY_BOSS_PATTERN;
    }

    @VisibleForTesting
    static Pattern timeWithPbPattern() {
        return TIME_WITH_PB_PATTERN;
    }

    @VisibleForTesting
    static Pattern teamSizePattern() {
        return TEAM_SIZE_PATTERN;
    }

    private final AtomicInteger badTicks = new AtomicInteger();
    private final AtomicReference<KillData> killData = new AtomicReference<>();
    private String lastProcessedKill = null;
    private long lastProcessedTime = 0;
    private long lastKillDataUpdate = 0L;

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

        boolean isValid() {
            if (boss != null) {
                if (boss.contains("Doom")) {
                    // Doom levels do not include count; so we do not need a null check here
                    return time != null && !time.isZero();
                }
            }
            return boss != null && count != null && time != null && !time.isZero();
        }
    }

    @Override
    public boolean isEnabled() {
        return config.pbEmbeds();
    }

    public void onGameMessage(String message) {
        if (!isEnabled()) return;
        parseMessage(message).ifPresent(this::updateKillData);
    }

    public void onFriendsChatNotification(String message) {
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
            if (data.count == null) {
                /* Doom does not have KCs available for individual floors, so we need to handle the data anyways for this boss */
                /* Otherwise, we should handle other data with no count included consistently with before */
                if (data.boss.contains("Doom")) {
                    processKill(data);
                    reset();
                }
            } else {
                processKill(data);
                reset();
            }
        } else {
            // Allow partial data (e.g., time before count or vice versa) to coalesce for up to 10 seconds
            long now = System.currentTimeMillis();
            if (now - lastKillDataUpdate > 10_000) {
                reset();
            }
        }
    }

    private Optional<KillData> parseMessage(String message) {
        if (message.startsWith("Preparation")) {
            return Optional.empty();
        }
        // Ignore raid target-time announcements (e.g. "Your party failed to beat the overall target time of 40:00.")
        // These contain a time string but are unrelated to the player's personal best.
        if (message.contains("target time")) {
            return Optional.empty();
        }

        // Try boss count first
        Optional<Pair<String, Integer>> bossCount = parseBossCount(message);
        if (bossCount.isPresent()) {
            Pair<String, Integer> pair = bossCount.get();
            return Optional.of(new KillData(pair.getLeft(), pair.getRight(), 
                Duration.ZERO, Duration.ZERO, false, null, message));
        }

        // Try time data
        return parseTimeData(message);
    }

    private Optional<Pair<String, Integer>> parseBossCount(String message) {
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

    private Optional<KillData> parseTimeData(String message) {
        Matcher matcher = TIME_WITH_PB_PATTERN.matcher(message);
        if (!matcher.find()) {
            return Optional.empty();
        }

        try {
            Duration time = parseTime(matcher.group("duration"));
            String pbTimeStr = matcher.group("pbtime");
            Duration bestTime = pbTimeStr != null ? parseTime(pbTimeStr) : Duration.ZERO;
            boolean isPersonalBest = matcher.group("pbIndicator") != null;
            
            String bossName = determineBossFromContext(message);
            String teamSize = extractTeamSize(message, bossName);
            
            // For CoX, PB lines often include Olm split but team size isn't always on KC line. If team size missing, try to extract now.
            if (teamSize == null && message.contains("Team size:")) {
                teamSize = extractTeamSize(message, bossName);
            }
            return Optional.of(new KillData(bossName, null, time, bestTime, 
                isPersonalBest, teamSize, message));
                
        } catch (Exception e) {
            log.error("Error parsing time data: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String determineBossFromContext(String message) {
        if (message.contains("Tombs of Amascut")) {
            return message.contains("Expert Mode") ? "Tombs of Amascut: Expert Mode" : "Tombs of Amascut";
        }
        if (message.contains("Theatre of Blood")) {
            return "Theatre of Blood";
        }
        if (message.contains("Chambers of Xeric")) {
            return "Chambers of Xeric";
        }
        // Chambers of Xeric time lines often omit the raid name but include the Olm split
        if (message.contains("Olm Duration")) {
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
        // With an unknown context, don't guess--defer to subsequent kill-count parsing
        return null;
    }

    private String extractDelveBoss(String message) {
        Pattern delvePattern = Pattern.compile("Delve level: (\\S+)");
        Matcher matcher = delvePattern.matcher(message);
        if (matcher.find()) {
            return "Doom of Mokhaiotl (Level:" + matcher.group(1).trim() + ")";
        }
        return "Doom of Mokhaiotl";
    }

    private String extractTeamSize(String message, String bossName) {
        Matcher teamMatcher = TEAM_SIZE_PATTERN.matcher(message);
        if (teamMatcher.find()) {
            String raw = teamMatcher.group("size");
            if (raw == null) {
                return "Solo";
            }
            if (raw.equalsIgnoreCase("solo")) {
                return "Solo";
            }
            return raw;
        }

        boolean mentionsToa = message.contains("Tombs of Amascut") || (bossName != null && bossName.contains("Tombs of Amascut"));
        boolean mentionsTob = message.contains("Theatre of Blood") || (bossName != null && bossName.contains("Theatre of Blood"));
        boolean mentionsRoyalTitans = message.contains("Royal Titans") || (bossName != null && bossName.contains("Royal Titans"));

        if (mentionsToa) {
            return getToaTeamSize();
        }
        if (mentionsTob) {
            return getTobTeamSize();
        }
        if (mentionsRoyalTitans) {
            return getRoyalTitansTeamSize();
        }

        return "Solo";
    }

    private void updateKillData(KillData newData) {
        DebugLogger.log("[PbHandler][update] updateKillData called; newData=" + newData);
        killData.getAndUpdate(old -> {
            if (old == null) {
                lastKillDataUpdate = System.currentTimeMillis();
                return newData;
            }

            String boss = defaultIfNull(newData.boss, old.boss);
            DebugLogger.log("[PbHandler][update] boss merge; newBoss=" + newData.boss + ", oldBoss=" + old.boss);
            Integer count = defaultIfNull(newData.count, old.count);
            Duration time = newData.time != null && !newData.time.isZero() ? newData.time : old.time;
            Duration bestTime = newData.bestTime != null && !newData.bestTime.isZero() ? newData.bestTime : old.bestTime;
            boolean isPersonalBest = newData.isPersonalBest || old.isPersonalBest;
            String teamSize = defaultIfNull(newData.teamSize, old.teamSize);
            String gameMessage = defaultIfNull(newData.gameMessage, old.gameMessage);
            DebugLogger.log("[PbHandler][update] merged killData=" + new KillData(boss, count, time, bestTime, isPersonalBest, teamSize, gameMessage));
            lastKillDataUpdate = System.currentTimeMillis();
            return new KillData(boss, count, time, bestTime, isPersonalBest, teamSize, gameMessage);
        });
        DebugLogger.log("[PbHandler][update] updateKillData completed; boss=" + newData.boss + ", time=" + newData.time + ", count=" + newData.count);
    }

    // === KILL PROCESSING ===
    private void processKill(KillData data) {
        DebugLogger.log("[PbHandler][process] processKill invoked; data=" + data);
        if (data == null || !data.isValid()) {
            DebugLogger.log("[PbHandler][process] skipping invalid kill data");
            log.debug("Invalid kill data, skipping processing");
            return;
        }

        // Duplicate prevention
        String killIdentifier = data.boss + "-" + data.count;
        long currentTime = System.currentTimeMillis();
        
        if (killIdentifier.equals(lastProcessedKill) && 
            (currentTime - lastProcessedTime) < DUPLICATE_THRESHOLD) {
            if (!data.boss.contains("1-8")) {
                
                DebugLogger.log("[PbHandler][process] duplicate kill detected; killIdentifier=" + killIdentifier);
                log.debug("Duplicate kill detected, skipping: {}", killIdentifier);
                return;
            }
        }
        
        lastProcessedKill = killIdentifier;
        lastProcessedTime = currentTime;

        if (clientThread == null) {
            DebugLogger.log("[PbHandler][process] cannot process kill; clientThread=null");
            log.error("ClientThread is null, cannot process kill");
            return;
        }

        clientThread.invokeLater(() -> {
            try {
                sendKillNotification(data);
            } catch (Exception e) {
                DebugLogger.log("[PbHandler][process] sendKillNotification failed; reason=" + e.getMessage());
                log.error("Error processing kill notification: {}", e.getMessage(), e);
            }
        });
    }

    private void sendKillNotification(KillData data) {
        String player = getPlayerName();
        String formattedTime = formatTime(data.time, isPreciseTiming(client));
        String formattedBestTime = formatTime(data.bestTime, isPreciseTiming(client));
        
        CustomWebhookBody webhook = createWebhookBody(player + " has killed a boss:");
        CustomWebhookBody.Embed embed = createEmbed(player + " has killed a boss:", "npc_kill");
        
        Map<String, Object> fieldData = new HashMap<>();
        fieldData.put("boss_name", data.boss);
        fieldData.put("kill_time", formattedTime != null ? formattedTime : "N/A");
        fieldData.put("best_time", formattedBestTime != null ? formattedBestTime : "N/A");
        fieldData.put("is_pb", data.isPersonalBest);
        fieldData.put("team_size", data.teamSize != null ? data.teamSize : "Solo");
        fieldData.put("killcount", data.count);
        
        addFields(embed, fieldData);
        webhook.getEmbeds().add(embed);
        
        sendData(webhook, SubmissionType.KILL_TIME);
    }

    public void reset() {
        killData.set(null);
        badTicks.set(0);
    }

    private Duration parseTime(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return Duration.ZERO;
        }

        try {
            String timePart = timeStr.contains(".") ? timeStr.substring(0, timeStr.indexOf('.')) : timeStr;
            String[] timeParts = timePart.split(":");

            long hours = 0, minutes = 0, seconds = 0, millis = 0;

            if (timeParts.length == 3) {  // h:m:s
                hours = Long.parseLong(timeParts[0]);
                minutes = Long.parseLong(timeParts[1]);
                seconds = Long.parseLong(timeParts[2]);
            } else if (timeParts.length == 2) {  // m:s
                minutes = Long.parseLong(timeParts[0]);
                seconds = Long.parseLong(timeParts[1]);
            }

            if (timeStr.contains(".")) {
                String millisStr = timeStr.substring(timeStr.indexOf('.') + 1);
                while (millisStr.length() < 3) {
                    millisStr += "0";
                }
                millis = Long.parseLong(millisStr);
            }

            return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds).plusMillis(millis);

        } catch (Exception e) {
            log.error("Error parsing time: {}", e.getMessage());
            return Duration.ZERO;
        }
    }

    @NotNull
    public String formatTime(@Nullable Duration duration, boolean precise) {
        Temporal time = ObjectUtils.defaultIfNull(duration, Duration.ZERO).addTo(LocalTime.of(0, 0));
        StringBuilder sb = new StringBuilder();

        int h = time.get(HOUR_OF_DAY);
        if (h > 0) sb.append(String.format("%02d", h)).append(':');

        sb.append(String.format("%02d", time.get(MINUTE_OF_HOUR))).append(':');
        sb.append(String.format("%02d", time.get(SECOND_OF_MINUTE)));

        if (precise) sb.append('.').append(String.format("%02d", time.get(MILLI_OF_SECOND) / 10));

        return sb.toString();
    }

    public boolean isPreciseTiming(@NotNull Client client) {
        @Varbit int ENABLE_PRECISE_TIMING = 11866;
        return client.getVarbitValue(ENABLE_PRECISE_TIMING) > 0;
    }

    // === BOSS PARSING METHODS ===
    @Nullable
    private static String parsePrimaryBoss(String boss, String type) {
        switch (type.toLowerCase()) {
            case "chest":
                if ("Barrows".equalsIgnoreCase(boss)) return boss;
                if ("Lunar".equals(boss)) return boss + " " + type;
                return null;
            case "completion":
                if (NpcUtilities.GAUNTLET_NAME.equalsIgnoreCase(boss)) return NpcUtilities.GAUNTLET_BOSS;
                if (NpcUtilities.CG_NAME.equalsIgnoreCase(boss)) return NpcUtilities.CG_BOSS;
                return null;
            case "kill":
            case "success":
                return boss;
            default:
                return null;
        }
    }

    private static String parseSecondaryBoss(String boss) {
        if (boss == null || "Wintertodt".equalsIgnoreCase(boss)) return boss;

        int modeSeparator = boss.lastIndexOf(':');
        String raid = modeSeparator > 0 ? boss.substring(0, modeSeparator) : boss;
        if (raid.equalsIgnoreCase("Theatre of Blood") || raid.equalsIgnoreCase("Tombs of Amascut") ||
            raid.equalsIgnoreCase("Chambers of Xeric") || raid.equalsIgnoreCase("Chambers of Xeric Challenge Mode")) {
            return boss;
        }
        return null;
    }

    // === TEAM SIZE METHODS ===
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
