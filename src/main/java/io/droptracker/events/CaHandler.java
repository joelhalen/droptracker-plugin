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
    @Varbit
    public static final int COMBAT_TASK_REPEAT_POPUP = 12456;

    @Varbit
    public static final int TOTAL_POINTS_ID = 14815;
    @Varbit
    public static final int GRANDMASTER_TOTAL_POINTS_ID = 14814;

    @Override
    public void process(Object... args) {
        /* does not need an override */
    }

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
        return Optional.of(matcher.group("tier"))
                .map(CombatAchievement.TIER_BY_LOWER_NAME::get)
                .map(tier -> Pair.of(
                        tier,
                        TASK_POINTS.matcher(
                                matcher.group("task")
                        ).replaceFirst("") // remove points suffix
                ));
    }
}
