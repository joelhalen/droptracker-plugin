package io.droptracker.events;

import com.google.gson.Gson;
import com.google.inject.Inject;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import io.droptracker.service.*;
import io.droptracker.util.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.gameval.VarbitID;

import java.util.*;
import java.util.regex.*;

/**
 * Kill times and personal bests. The plugin does not read these messages
 * itself: it forwards the candidate chat lines of one kill, with the game
 * facts only the client can see (raid party sizes, precise timing, loadout),
 * and the server parses them exactly as this class used to
 * (data/submissions/pb_lines.py).
 *
 * <p>A kill is sent on the tick its lines hold both a count line and a time,
 * or a timed delve level (a delve level is a whole kill on its own) — the
 * tick the parser here used to finish it on. Lines that never complete a
 * kill are dropped after ten seconds, as partial kills always were.
 */
@Slf4j
public class PbHandler extends BaseEventHandler {

    /** A message the server's parser could use: a time, a count or a delve level. */
    private static final Pattern CANDIDATE = Pattern.compile("\\d:\\d\\d| count is|Delve level");
    private static final Pattern TIME = Pattern.compile("\\d:\\d\\d");
    /** The wall-clock total, never a kill time (except the Fractured Archive's). */
    private static final Pattern TOTAL = Pattern.compile(
        "(?<!Fractured Archive: )total completion time", Pattern.CASE_INSENSITIVE);
    /* Discord caps an embed field at 1024 characters and an embed at 6000. */
    private static final int MAX_LINES = 8;
    private static final int MAX_LINE_CHARS = 900;
    private static final long EXPIRY_MS = 10_000;

    @Inject
    protected NearbyPlayerTracker nearbyPlayerTracker;

    /* Gear and inventory at the moment of the kill; see LoadoutCapture. */
    @Inject
    protected LoadoutCapture loadoutCapture;

    /* Which uploaded character model the kill was made in; see PlayerModelService. */
    @Inject
    protected PlayerModelService playerModelService;

    @Inject
    protected Gson gson;

    private final List<Map<String, Object>> lines = new ArrayList<>();
    private long lastLineAt;

    @Override
    public boolean isEnabled() {
        return config.pbEmbeds();
    }

    public synchronized void onGameMessage(String message) {
        if (!isEnabled() || !CANDIDATE.matcher(message).find()
                || message.startsWith("Preparation") || message.contains("target time")) {
            return;
        }
        if (lines.size() >= MAX_LINES) {
            lines.remove(0);
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("m", message.length() > MAX_LINE_CHARS ? message.substring(0, MAX_LINE_CHARS) : message);
        line.put("t", client.getTickCount());
        line.put("p", party());
        lines.add(line);
        lastLineAt = System.currentTimeMillis();
    }

    public void onFriendsChatNotification(String message) {
        if (message.startsWith("Congratulations - your raid is complete!")) {
            onGameMessage(message);
        }
    }

    public synchronized void onTick() {
        if (lines.isEmpty()) {
            return;
        }
        boolean count = false;
        boolean time = false;
        boolean delve = false;
        for (Map<String, Object> line : lines) {
            String message = (String) line.get("m");
            boolean timed = has(message, null);
            count |= message.contains(" count is");
            time |= timed;
            delve |= timed && message.contains("Delve level");
        }
        if ((count && time) || delve) {
            send(new ArrayList<>(lines));
            reset();
        } else if (System.currentTimeMillis() - lastLineAt > EXPIRY_MS) {
            reset();
        }
    }

    private void send(List<Map<String, Object>> batch) {
        String player = getPlayerName();
        if (player == null || !isEnabled()) {
            return;
        }
        DebugLogger.log("[PbHandler][send] " + batch);
        Map<String, Object> fieldData = new LinkedHashMap<>();
        boolean pb = false;
        for (int i = 0; i < batch.size(); i++) {
            String message = (String) batch.get(i).get("m");
            pb |= has(message, "(new personal best)");
            if (message.contains(" count is")) {
                // Names the boss for the raid roster and the screenshot delay.
                fieldData.put("source", message);
            }
            fieldData.put("chat_line_" + (i + 1), gson.toJson(batch.get(i)));
        }
        // With precise timing off the game prints whole seconds; the server
        // credits those as approximate, so it needs to know which it got.
        fieldData.put("precise_timing", client.getVarbitValue(11866) > 0);
        // Decides the screenshot here; the server reads the real flag.
        fieldData.put("is_pb", pb);

        // Loadout at the moment of the kill. The outfit fingerprint pairs this
        // time with the character model filed under the same key, so the
        // leaderboard shows the model as worn for the kill. Same consent.
        if (loadoutCapture != null && loadoutCapture.isEnabled()) {
            fieldData.put("equipment", loadoutCapture.captureEquipment());
            fieldData.put("inventory", loadoutCapture.captureInventory());
            fieldData.put("model_fingerprint",
                playerModelService != null ? playerModelService.currentFingerprint() : null);
            fieldData.values().removeIf(Objects::isNull);
        }

        CustomWebhookBody webhook = createWebhookBody(player + " has killed a boss:");
        CustomWebhookBody.Embed embed = createEmbed(player + " has killed a boss:", "npc_kill");
        addFields(embed, fieldData);
        webhook.getEmbeds().add(embed);
        sendData(webhook, SubmissionType.KILL_TIME);
    }

    /** Whether a line of the message, other than a wall-clock total, holds
     *  {@code text} (or a time, when null). */
    private static boolean has(String message, String text) {
        for (String line : message.split("\n")) {
            if (!TOTAL.matcher(line).find() && (text == null ? TIME.matcher(line).find() : line.contains(text))) {
                return true;
            }
        }
        return false;
    }

    public synchronized void reset() {
        lines.clear();
    }

    /**
     * Raid party sizes the server reads team size from when a message does not
     * state it: [ToB, ToA, TFA, Royal Titans]. The raids prefer the
     * accumulated roster over the health-orb varbits, which clear at
     * completion; Royal Titans counts the players in view.
     */
    @SuppressWarnings("deprecation")
    private int[] party() {
        return new int[] {
            raidSize(NearbyPlayerTracker.RAID_TOB, Varbits.THEATRE_OF_BLOOD_ORB1, Varbits.THEATRE_OF_BLOOD_ORB2,
                Varbits.THEATRE_OF_BLOOD_ORB3, Varbits.THEATRE_OF_BLOOD_ORB4, Varbits.THEATRE_OF_BLOOD_ORB5),
            raidSize(NearbyPlayerTracker.RAID_TOA, Varbits.TOA_MEMBER_0_HEALTH, Varbits.TOA_MEMBER_1_HEALTH,
                Varbits.TOA_MEMBER_2_HEALTH, Varbits.TOA_MEMBER_3_HEALTH, Varbits.TOA_MEMBER_4_HEALTH,
                Varbits.TOA_MEMBER_5_HEALTH, Varbits.TOA_MEMBER_6_HEALTH, Varbits.TOA_MEMBER_7_HEALTH),
            raidSize(NearbyPlayerTracker.RAID_TFA, VarbitID.TFA_CLIENT_P0, VarbitID.TFA_CLIENT_P1,
                VarbitID.TFA_CLIENT_P2, VarbitID.TFA_CLIENT_P3, VarbitID.TFA_CLIENT_P4,
                VarbitID.TFA_CLIENT_P5, VarbitID.TFA_CLIENT_P6, VarbitID.TFA_CLIENT_P7),
            client.getPlayers().size()
        };
    }

    private int raidSize(String raid, int... varbits) {
        int filled = 0;
        for (int varbit : varbits) {
            filled += Math.min(client.getVarbitValue(varbit), 1);
        }
        return Math.max(nearbyPlayerTracker != null ? nearbyPlayerTracker.raidRosterSize(raid) : 0, filled);
    }
}
