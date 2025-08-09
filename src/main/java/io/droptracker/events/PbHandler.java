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
import org.apache.commons.lang3.tuple.Triple;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.Temporal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

import static java.time.temporal.ChronoField.*;
import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;

@Slf4j
public class PbHandler extends BaseEventHandler {

    @VisibleForTesting
    static final int MAX_BAD_TICKS = 10;

    private final AtomicInteger badTicks = new AtomicInteger();
    private final AtomicReference<BossNotification> bossData = new AtomicReference<>();

    private static Pair<String, Integer> mostRecentNpcData = null;

    private static final long MESSAGE_LOOT_WINDOW = 15000; // 15 seconds
    private final Map<String, BossNotification> pendingNotifications = new HashMap<>();

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
            //Delve Pattern
            Pattern.compile("Delve level: .+? duration: (\\d*:*\\d+:\\d+\\.?\\d*)\\. Personal best: (\\d*:*\\d+:\\d+\\.?\\d*)\\.*"),
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
            //Delve Pattern
            Pattern.compile("Delve level: (\\S+) duration: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\)\\.*"),
            // Generic boss pattern
            Pattern.compile("Duration: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\)\\.*"),
            Pattern.compile("Fight duration: (\\d*:*\\d+:\\d+\\.?\\d*) \\(new personal best\\)\\.*"),
    };
    private final Map<String, PbHandler.TimeData> pendingTimeData = new HashMap<>();
    private String lastProcessedKill = null;
    private long lastProcessedTime = 0;
    private static final long DUPLICATE_THRESHOLD = 5000;
    private String teamSize = null;

    @Override
    public void process(Object... args) { /* does not need an override */ }

    public void onGameMessage(String message) {
        if (!isEnabled()) return;
        checkPB(message);
        checkTime(message);
        parseBossKill(message).ifPresent(this::updateData);
    }



    public void onFriendsChatNotification(String message) {
        /* Chambers of Xeric completions are sent in the Friends chat channel */
        if (message.startsWith("Congratulations - your raid is complete!"))
            this.onGameMessage(message);
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



    @SuppressWarnings("unlikely-arg-type")
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
        boolean isPb = data.isPersonalBest() == Boolean.TRUE;
        String player = getPlayerName();
        final String[] timeRef = {null};
        final String[] bestTimeRef = {null};
        clientThread.invokeLater(() -> {
            timeRef[0] = formatTime(data.getTime(), isPreciseTiming(client));
            bestTimeRef[0] = formatTime(data.getBestTime(), isPreciseTiming(client));
            
            String bossName = data.getBoss();
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
            CustomWebhookBody killWebhook = createWebhookBody(player + " has killed a boss:");
            CustomWebhookBody.Embed killEmbed = createEmbed(player + " has killed a boss:", "npc_kill");
            
            Map<String, Object> fieldData = new HashMap<>();
            fieldData.put("boss_name", bossName);
            fieldData.put("kill_time", timeRef[0]);
            fieldData.put("best_time", bestTimeRef[0]);
            fieldData.put("is_pb", isPb);
            fieldData.put("team_size", teamSize);
            
            if (bossName != null) {
                killCount = getKillCount(bossName);
                fieldData.put("killcount", killCount);
            }
            
            addFields(killEmbed, fieldData);
            
            killWebhook.getEmbeds().add(killEmbed);
            sendData(killWebhook, SubmissionType.KILL_TIME);
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
        Matcher primary = NpcUtilities.PRIMARY_REGEX.matcher(message);
        Matcher secondary = NpcUtilities.SECONDARY_REGEX.matcher(message);


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
    

    @SuppressWarnings("deprecation")
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
            @SuppressWarnings("deprecation")
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
    //Storing boss Time to either access at a later point or to move through sending the time
    private void noKcPB(String message, Duration time, Duration bestTime, boolean isPb){
        PbHandler.TimeData timeData = new PbHandler.TimeData(time,bestTime,isPb);
        BossNotification withTime = null;

        if (message.contains("Delve")) {
            String bossName = "Doom of Mokhaiotl";
            pendingTimeData.put(bossName,timeData);
            Pattern teamSizePattern = Pattern.compile("Delve level: (\\S+) duration.*");
            Matcher teamMatch = teamSizePattern.matcher(message);
            if (teamMatch.find())
                bossName = "Doom of Mokhaiotl (Level: " + teamMatch.group(1) + " )";
            withTime = new BossNotification(
                    bossName,
                    0,
                    message,
                    time,
                    bestTime,
                    isPb
            );
            bossData.set(withTime);
            processKill(withTime);
        }


    }


}
