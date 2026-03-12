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


/**
 * Handles Combat Achievement (CA) completions.
 *
 * <p>Listens for the game message that fires when a CA task is completed, e.g.:
 * <pre>"Congratulations, you've completed an Easy combat task: Break the Wall."</pre>
 *
 * <p>The tier name from the message is resolved to a {@link CombatAchievement} enum value
 * (which carries the point value), and the varbit {@link #TOTAL_POINTS_ID} is read at
 * tick-end to ensure the point total reflects the just-completed task.</p>
 *
 * <p>Enabled/disabled via {@link io.droptracker.DropTrackerConfig#caEmbeds()}.</p>
 */
@Slf4j
public class CaHandler extends BaseEventHandler {

    /**
     * Regex that matches the combat achievement completion game message.
     * Named groups: {@code tier} (e.g. "Easy", "Grandmaster") and {@code task} (full task name).
     */
    private static final Pattern ACHIEVEMENT_PATTERN = Pattern.compile("Congratulations, you've completed an? (?<tier>\\w+) combat task: (?<task>.+)\\.");

    /**
     * Regex used to strip the optional point-count suffix from a task name, e.g.
     * {@code "Break the Wall (5 points)"} → {@code "Break the Wall"}.
     */
    private static final Pattern TASK_POINTS = Pattern.compile("\\s+\\(\\d+ points?\\)$");

    /** Varbit ID: controls whether the repeat-completion popup is shown for already-completed tasks. */
    @Varbit
    public static final int COMBAT_TASK_REPEAT_POPUP = 12456;

    /** Varbit ID: total combat achievement points accumulated by the player. Read after task completion. */
    @Varbit
    public static final int TOTAL_POINTS_ID = 14815;

    /** Varbit ID: grandmaster-tier total points (separate from the overall total). Currently unused. */
    @Varbit
    public static final int GRANDMASTER_TOTAL_POINTS_ID = 14814;

    @Override
    public boolean isEnabled() {
        return config.caEmbeds();
    }

    /**
     * Entry point called by {@link io.droptracker.DropTrackerPlugin#onChatMessage} for
     * {@code GAMEMESSAGE} type messages. Parses the message and, if it matches a CA completion,
     * schedules a notification at tick-end.
     *
     * @param message the sanitized game chat message text
     */
    public void onGameMessage(String message) {
        if (!isEnabled()) return;
        parseCombatAchievement(message).ifPresent(pair -> processCombatAchievement(pair.getLeft(), pair.getRight()));
    }

    /**
     * Builds and sends the CA webhook embed. Scheduled via {@code invokeAtTickEnd} so that the
     * {@link #TOTAL_POINTS_ID} varbit has been updated before we read it.
     *
     * @param tier the {@link CombatAchievement} tier (determines the point value)
     * @param task the task name with any point-count suffix already stripped
     */
    private void processCombatAchievement(CombatAchievement tier, String task) {
        // Defer to tick-end so varbits are updated before we read total_points
        clientThread.invokeAtTickEnd(() -> {
            int taskPoints = tier.getPoints();
            int totalPoints = client.getVarbitValue(TOTAL_POINTS_ID);

            String player = getPlayerName();
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

    /**
     * Parses a game message into a tier/task pair if it matches a CA completion message.
     * The point-count suffix (e.g. {@code " (5 points)"}) is stripped from the task name.
     *
     * @param message the raw game message text
     * @return an {@link Optional} containing the tier and cleaned task name, or empty if no match
     */
    @VisibleForTesting
    static Optional<Pair<CombatAchievement, String>> parseCombatAchievement(String message) {
        Matcher matcher = ACHIEVEMENT_PATTERN.matcher(message);
        if (!matcher.find()) return Optional.empty();
        return Optional.of(matcher.group("tier"))
                .map(CombatAchievement.TIER_BY_LOWER_NAME::get)
                .map(tier -> Pair.of(
                        tier,
                        TASK_POINTS.matcher(matcher.group("task")).replaceFirst("") // strip "(N points)" suffix
                ));
    }
}
