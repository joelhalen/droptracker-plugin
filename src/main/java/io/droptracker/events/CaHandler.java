package io.droptracker.events;

import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.CombatAchievement;
import io.droptracker.models.submissions.SubmissionType;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.annotations.Varbit;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.VisibleForTesting;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Slf4j
public class CaHandler extends BaseEventHandler {
    private static final Pattern ACHIEVEMENT_PATTERN = Pattern.compile("Congratulations, you've completed an? (?<tier>\\w+) combat task: (?<task>.+)\\.");
    private static final Pattern TASK_POINTS = Pattern.compile("\\s+\\(\\d+ points?\\)$");

    /**
     * Jagex's {@code @token@} chat markup. Since the 2026-08-26 update the
     * completion message wraps the task name in {@code @ach_comp@} (the token
     * that makes it click-through in game), so the raw capture reads
     * "@ach_comp@Smite Fight". The client consumes these tokens rather than
     * rendering them — the player never sees them — so they must not travel
     * with the name: the panel would show the token, and the server keys a
     * completion on the name, so a marked-up one reads as a task nobody has
     * ever done.
     * <p>
     * Bounded length so a stray pair of {@code @} cannot swallow the name. No
     * real task name contains {@code @}, which is what makes a generic pattern
     * safe here.
     */
    private static final Pattern CHAT_TOKEN = Pattern.compile("@[A-Za-z][A-Za-z0-9_]{0,15}@");
    @Varbit
    public static final int COMBAT_TASK_REPEAT_POPUP = 12456;

    @Varbit
    public static final int TOTAL_POINTS_ID = 14815;
    @Varbit
    public static final int GRANDMASTER_TOTAL_POINTS_ID = 14814;

    public void onGameMessage(String message) {
        if (!isEnabled()) return;
        parseCombatAchievement(message).ifPresent(pair -> processCombatAchievement(pair.getLeft(), pair.getRight()));
    }

    private void processCombatAchievement(CombatAchievement tier, String task) {
        // delay notification for varbits to be updated
        clientThread.invokeAtTickEnd(() -> {
            int taskPoints = tier.getPoints();
            int totalPoints = client.getVarbitValue(TOTAL_POINTS_ID);

            String player = getPlayerName();
            if (player == null) {
                log.debug("Skipping combat achievement submission: no resolvable player name");
                return;
            }
            CustomWebhookBody combatWebhook = createWebhookBody(player + " has completed a new combat task:");
            CustomWebhookBody.Embed combatAchievementEmbed = createEmbed(null, "combat_achievement");
            
            Map<String, Object> fieldData = new HashMap<>();
            fieldData.put("tier", tier.toString());
            fieldData.put("task", task);
            fieldData.put("points", taskPoints);
            fieldData.put("total_points", totalPoints);
            
            addFields(combatAchievementEmbed, fieldData);
            
            combatWebhook.getEmbeds().add(combatAchievementEmbed);
            sendData(combatWebhook, SubmissionType.COMBAT_ACHIEVEMENT);
        });
    }

    @VisibleForTesting
    static Optional<Pair<CombatAchievement, String>> parseCombatAchievement(String message) {
        Matcher matcher = ACHIEVEMENT_PATTERN.matcher(message);
        if (!matcher.find()) return Optional.empty();
        String task = cleanTaskName(matcher.group("task"));
        // Nothing but markup matched: there is no task here to submit, and an
        // empty name would reach the server as a completion of "".
        if (task.isEmpty()) return Optional.empty();
        return Optional.of(matcher.group("tier"))
                .map(CombatAchievement.TIER_BY_LOWER_NAME::get)
                .map(tier -> Pair.of(tier, task));
    }

    /** Task name with the points suffix and any chat markup removed. */
    @VisibleForTesting
    static String cleanTaskName(String task) {
        if (task == null) return "";
        // Markup first: the points suffix anchors on end-of-string, so a
        // trailing token would hide it.
        String cleaned = CHAT_TOKEN.matcher(task).replaceAll("");
        cleaned = TASK_POINTS.matcher(cleaned).replaceFirst("");
        return cleaned.trim();
    }
}
