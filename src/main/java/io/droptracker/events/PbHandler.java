package io.droptracker.events;

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

/**
 * Handles personal best (PB) kill-time tracking for boss encounters.
 *
 * <p><b>Multi-message accumulation:</b> A complete kill notification spans multiple game messages
 * (kill count, duration, team size) that may arrive in different ticks. This handler merges them
 * into a {@link KillData} object; once the object passes {@link KillData#isValid()}, it is
 * processed on the next game tick.</p>
 *
 * <p><b>Tick safety:</b> If {@link #MAX_BAD_TICKS} ticks elapse without a valid record, the
 * accumulated state is discarded to prevent stale data from being sent on a future kill.</p>
 *
 * <p><b>Duplicate prevention:</b> A composite {@code "boss-killcount"} key and wall-clock timestamp
 * are stored; a second occurrence within {@link #DUPLICATE_THRESHOLD} ms is suppressed.</p>
 *
 * <p><b>Team size:</b> Extracted from the message where an explicit "Team size: N players" phrase
 * is present, with varbit fallbacks for ToB ({@code THEATRE_OF_BLOOD_ORB*}), ToA
 * ({@code TOA_MEMBER_*_HEALTH}), and Royal Titans (nearby player count).</p>
 *
 * <p>Enabled/disabled via {@link io.droptracker.DropTrackerConfig#pbEmbeds()}.</p>
 */
@Slf4j
public class PbHandler extends BaseEventHandler {

    /**
     * Maximum ticks without a valid {@link KillData} before resetting. Guards against partial
     * state left over from disconnects or unexpected message orderings.
     */
    @VisibleForTesting
    static final int MAX_BAD_TICKS = 10;

    /**
     * Milliseconds threshold for duplicate kill suppression. Two kills with the same
     * boss+count identifier within this window are treated as a duplicate fire.
     */
    private static final long DUPLICATE_THRESHOLD = 5000;

    /**
     * Matches primary boss kill/completion count messages, e.g.:
     * {@code "Your Vorkath kill count is: 42"}
     * Named groups: {@code key} (boss name), {@code type}, {@code value} (count).
     */
    private static final Pattern BOSS_COUNT_PATTERN = Pattern.compile(
        "Your (?<key>[\\w\\s:'-]+) (?<type>kill|chest|completion|success) count is:? (?<value>[\\d,]+)",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Matches secondary count formats used by Wintertodt and multi-phase raids, e.g.:
     * {@code "Your completed Tombs of Amascut count is: 10"}
     */
    private static final Pattern SECONDARY_BOSS_PATTERN = Pattern.compile(
        "Your (?<type>completed|subdued) (?<key>[\\w\\s:]+) count is: (?<value>[\\d,]+)",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Matches a kill duration and optional PB time, e.g.:
     * {@code "Fight duration: 1:23.40 (new personal best)"}
     * Named groups: {@code duration}, {@code pbtime} (optional), {@code pb_indicator}.
     */
    private static final Pattern TIME_WITH_PB_PATTERN = Pattern.compile(
        "(?<prefix>.*?)(?<duration>\\d*:?\\d+:\\d+(?:\\.\\d+)?)\\.?\\s+(?:Personal best: (?<pbtime>\\d*:?\\d+:\\d+(?:\\.\\d+)?)\\.?)?(?<pb_indicator>\\(new personal best\\))?",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Matches an explicit team-size declaration in a message, e.g. {@code "Team size: 3 players"}.
     * Used as the primary source; varbit fallback is used when absent.
     */
    private static final Pattern TEAM_SIZE_PATTERN = Pattern.compile(
        "Team size: (?<size>\\d+) players?",
        Pattern.CASE_INSENSITIVE
    );

    /** Ticks elapsed without a valid {@link KillData}; triggers reset at {@link #MAX_BAD_TICKS}. */
    private final AtomicInteger badTicks = new AtomicInteger();

    /** Accumulates partial kill-data across messages; atomically updated to avoid race conditions. */
    private final AtomicReference<KillData> killData = new AtomicReference<>();

    /** Boss+count key of the last processed kill, used for duplicate suppression. */
    private String lastProcessedKill = null;

    /** Wall-clock time of the last processed kill, used for duplicate suppression. */
    private long lastProcessedTime = 0;

    /**
     * Immutable value object holding the accumulated state for a single boss kill.
     * Fields are nullable until populated by their respective game messages.
     */
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

    /**
     * Primary game-message entry point. Attempts to parse the message as either a boss kill
     * count or a kill-time message, and merges the result into {@link #killData}.
     *
     * @param message the sanitized game chat message text
     */
    public void onGameMessage(String message) {
        if (!isEnabled()) return;
        parseMessage(message).ifPresent(this::updateKillData);
    }

    /**
     * Friends-chat notification entry point. Handles the raid completion message
     * ({@code "Congratulations - your raid is complete!"}) which arrives via the friends chat
     * channel rather than the standard game message channel.
     *
     * @param message the friends-chat message text
     */
    public void onFriendsChatNotification(String message) {
        if (message.startsWith("Congratulations - your raid is complete!")) {
            onGameMessage(message);
        }
    }

    /**
     * Per-tick update. If {@link #killData} is valid and the handler is enabled, processes the
     * kill and resets state. If {@link #MAX_BAD_TICKS} ticks elapse without a valid record
     * (e.g. only a count message with no time), the partial data is discarded.
     */
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
     * Attempts to parse a game message into a partial {@link KillData}.
     * First tries to match a boss kill-count message; if that fails, tries a time/PB message.
     * Messages starting with "Preparation" are from minigame countdowns and are ignored.
     *
     * @param message the sanitized game message
     * @return a partial {@link KillData}, or empty if the message is not relevant
     */
    private Optional<KillData> parseMessage(String message) {
        if (message.startsWith("Preparation")) {
            return Optional.empty();
        }

        // Boss count messages take priority; they arrive before or alongside time messages
        Optional<Pair<String, Integer>> bossCount = parseBossCount(message);
        if (bossCount.isPresent()) {
            Pair<String, Integer> pair = bossCount.get();
            return Optional.of(new KillData(pair.getLeft(), pair.getRight(),
                Duration.ZERO, Duration.ZERO, false, null, message));
        }

        // Fall back to parsing a kill-time message
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
            boolean isPersonalBest = matcher.group("pb_indicator") != null;
            
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
     * Infers the boss name from time-message context when a boss-count message is unavailable.
     * Each raid/boss has a unique prefix in its duration message that allows identification.
     * Falls back to "Grotesque Guardians" as that boss's time message has no unique prefix.
     *
     * @param message the kill-time game message
     * @return the inferred boss name
     */
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
            // Doom of Mokhaiotl includes the delve level in its time message
            return extractDelveBoss(message);
        }
        // Grotesque Guardians has no unique prefix in its time message
        return "Grotesque Guardians";
    }

    /**
     * Extracts the delve level from a Doom of Mokhaiotl time message and formats it as the
     * boss name with the level appended, e.g. {@code "Doom of Mokhaiotl (Level:5)"}.
     *
     * @param message the kill-time game message containing "Delve level: N"
     * @return the formatted boss name with level
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
     * Extracts the team size from a kill message. Checks for an explicit "Team size: N players"
     * phrase first, then falls back to reading player-health varbits for raids that do not
     * include this phrase in their completion messages.
     *
     * @param message the kill-time or completion game message
     * @return the team size string (e.g. "Solo", "3") or "Solo" if undetermined
     */
    private String extractTeamSize(String message) {
        Matcher teamMatcher = TEAM_SIZE_PATTERN.matcher(message);
        if (teamMatcher.find()) {
            return teamMatcher.group("size");
        }

        // Fall back to varbit-based team size for raids without an explicit phrase
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

    /**
     * Merges {@code newData} into the current {@link #killData} reference atomically.
     * Non-null fields in {@code newData} override the corresponding fields in the existing record;
     * null fields are retained from the existing record. This allows boss count and time
     * messages to be received in any order and still form a complete kill record.
     *
     * @param newData the partial kill data parsed from the latest game message
     */
    private void updateKillData(KillData newData) {
        killData.getAndUpdate(old -> {
            if (old == null) {
                return newData;
            }

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

    // =========================================================================
    // Kill processing
    // =========================================================================

    /**
     * Validates and dispatches a completed {@link KillData} for webhook submission.
     * Applies duplicate suppression and schedules the actual notification on the client thread.
     *
     * @param data the validated kill data to process
     */
    private void processKill(KillData data) {
        if (data == null || !data.isValid()) {
            log.debug("Invalid kill data, skipping processing");
            return;
        }

        // Build a composite key for duplicate suppression: "bossName-killCount"
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

    // =========================================================================
    // Boss name / count parsing helpers
    // =========================================================================

    /**
     * Maps a primary count-message boss key and type to a canonical boss name.
     * Returns {@code null} for unrecognised type/boss combinations so they are ignored.
     *
     * @param boss the raw boss name from the {@code key} capture group
     * @param type the message type: "kill", "chest", "completion", or "success"
     * @return the canonical boss name, or {@code null} if not applicable
     */
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

    /**
     * Maps a secondary count-message boss key to a canonical boss name.
     * Only Wintertodt and the three main raids are handled by the secondary pattern.
     *
     * @param boss the raw boss name from the {@code key} capture group
     * @return the canonical boss name, or {@code null} if not applicable
     */
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

    // =========================================================================
    // Team size helpers (varbit fallbacks)
    // =========================================================================

    /**
     * Determines Theatre of Blood team size by counting non-zero player-health orb varbits.
     * Each orb varbit is 0 when the slot is empty, or a health value when occupied.
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

    /**
     * Determines Tombs of Amascut team size by counting non-zero member-health varbits.
     * ToA supports up to 8 players; each slot is 0 when unoccupied.
     */
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

    /**
     * Determines Royal Titans team size from the number of players currently visible on screen.
     * The fight takes place in an instanced arena, so visible players are the raid party.
     */
    @SuppressWarnings("deprecation")
    private String getRoyalTitansTeamSize() {
        int size = client.getPlayers().size();
        return size == 1 ? "Solo" : String.valueOf(size);
    }
}
