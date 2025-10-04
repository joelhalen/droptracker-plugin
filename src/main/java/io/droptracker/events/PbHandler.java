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
        "Your (?<type>completed|subdued) (?<key>[\\w\\s:]+) count is: (?<value>[\\d,]+)",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern TIME_WITH_PB_PATTERN = Pattern.compile(
        "(?<prefix>.*?)(?<duration>\\d*:?\\d+:\\d+(?:\\.\\d+)?)\\.?\\s*(?:Personal best: (?<pbtime>\\d*:?\\d+:\\d+(?:\\.\\d+)?)\\.?\\s*)?(?<pbIndicator>\\(new personal best\\))?",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern TEAM_SIZE_PATTERN = Pattern.compile(
        "Team size: (?<size>\\d+) players?",
        Pattern.CASE_INSENSITIVE
    );

    private final AtomicInteger badTicks = new AtomicInteger();
    private final AtomicReference<KillData> killData = new AtomicReference<>();
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
        DebugLogger.log("[PbHandler.java:92] onGameMessage: " + message);
        parseMessage(message).ifPresent(this::updateKillData);
    }

    public void onFriendsChatNotification(String message) {
        DebugLogger.log("[PbHandler.java:97] onFriendsChatNotification: " + message);
        if (message.startsWith("Congratulations - your raid is complete!")) {
            onGameMessage(message);
        }
    }

    public void onTick() {
        KillData data = killData.get();
        DebugLogger.log("[PbHandler.java:105] onTick: " + data);
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

    private Optional<KillData> parseMessage(String message) {
        if (message.startsWith("Preparation")) {
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
            String teamSize = extractTeamSize(message);
            
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

    private String extractTeamSize(String message) {
        Matcher teamMatcher = TEAM_SIZE_PATTERN.matcher(message);
        if (teamMatcher.find()) {
            return teamMatcher.group("size");
        }

        if (message.contains("Tombs of Amascut")) {
            return getToaTeamSize();
        }
        if (message.contains("Theatre of Blood")) {
            return getTobTeamSize();
        }
        if (message.contains("Royal Titans")) {
            return getRoyalTitansTeamSize();
        }

        return "Solo";
    }

    private void updateKillData(KillData newData) {
        DebugLogger.log("[PbHandler.java:247] updateKillData called with newData: " + newData);
        killData.getAndUpdate(old -> {
            if (old == null) {
                return newData;
            }

            String boss = defaultIfNull(newData.boss, old.boss);
            DebugLogger.log("[PbHandler.java:254] set boss to new data: " + newData.boss + " from old: " + old.boss);
            Integer count = defaultIfNull(newData.count, old.count);
            Duration time = newData.time != null && !newData.time.isZero() ? newData.time : old.time;
            Duration bestTime = newData.bestTime != null && !newData.bestTime.isZero() ? newData.bestTime : old.bestTime;
            boolean isPersonalBest = newData.isPersonalBest || old.isPersonalBest;
            String teamSize = defaultIfNull(newData.teamSize, old.teamSize);
            String gameMessage = defaultIfNull(newData.gameMessage, old.gameMessage);
            DebugLogger.log("[PbHandler.java:260] updateKillData completed -- returning: " + new KillData(boss, count, time, bestTime, isPersonalBest, teamSize, gameMessage).toString());
            return new KillData(boss, count, time, bestTime, isPersonalBest, teamSize, gameMessage);
        });
        DebugLogger.log("[PbHandler.java:263] updateKillData completed. boss / time / count:" + newData.boss + "/" + newData.time + "/" + newData.count);
    }

    // === KILL PROCESSING ===
    private void processKill(KillData data) {
        DebugLogger.log("[PbHandler.java:109] processKill: " + data);
        if (data == null || !data.isValid()) {
            DebugLogger.log("[PbHandler.java:268] Invalid kill data, skipping processing");
            log.debug("Invalid kill data, skipping processing");
            return;
        }

        // Duplicate prevention
        String killIdentifier = data.boss + "-" + data.count;
        long currentTime = System.currentTimeMillis();
        
        if (killIdentifier.equals(lastProcessedKill) && 
            (currentTime - lastProcessedTime) < DUPLICATE_THRESHOLD) {
            if (!data.boss.contains("1-8")) {
                
                DebugLogger.log("[PbHandler.java:281] Duplicate kill detected, skipping: " + killIdentifier);
                log.debug("Duplicate kill detected, skipping: {}", killIdentifier);
                return;
            }
        }
        
        lastProcessedKill = killIdentifier;
        lastProcessedTime = currentTime;

        if (clientThread == null) {
            DebugLogger.log("[PbHandler.java:291] ClientThread is null, cannot process kill");
            log.error("ClientThread is null, cannot process kill");
            return;
        }

        clientThread.invokeLater(() -> {
            try {
                sendKillNotification(data);
            } catch (Exception e) {
                DebugLogger.log("[PbHandler.java:300] Error processing kill notification: " + e.getMessage());
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
    @VisibleForTesting
    public void generateTestMessage() {
        //Chambers of Xeric Challenge Mode Test
        /*
          onGameMessage("Congratulations - Your raid is complete!");
           onGameMessage("Team size: 3 players Duration: 32:32 Personal best: 28:26 Olm Duration: 4:11");
          // onGameMessage("Team size: 3 players Duration: 27:32 (new personal best) Olm Duration: 4:11");
          onGameMessage("Your completed Chambers of Xeric Challenge Mode count is 61.");
        /*

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
        /*
        onGameMessage("Your Alchemical Hydra kill count is: 150.");
        // onGameMessage("Fight duration: 1:49. Personal best: 1:28.");
        // onGameMessage("Fight duration: 1:20 (new personal best).");

         */

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
/*
          onGameMessage("Fight duration: 2:12. Personal best: 1:18");
          //onGameMessage("Fight duration: 1:55 (new personal best)");
          onGameMessage("Your Grotesque Guardians kill count is 413.");
*/

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

        //Yama Test
        /*
        onGameMessage("Your Yama success count is 20.");
        // onGameMessage("Fight duration: 4:04. Personal best: 3:50");
        // onGameMessage("Fight duration: 3:22 (new personal best)");\
         */

        //Doom Mokhaiotl Level 4 Test
        /*
        // onGameMessage("Delve level: 4 duration: 2:34. Personal best: 1:54");
        // onGameMessage("Delve level: 4 (new personal best)");
        onGameMessage("Total duration: 6:29");
         */


        //Doom Mokhaiotl 1-8 Test
        /*
        onGameMessage("Delve level: 8 duration: 1:30. Personal best: 1:11");
        // onGameMessage("Delve level 1 - 8 duration: 9:40. Personal best: 9:33");
        // onGameMessage("Delve level 1 - 8 duration: 9:33 (new personal best)");
        onGameMessage("Deep delves completed: 2");
         */
    }
}
