package io.droptracker.events;
import com.google.common.collect.ImmutableMap;

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
    /**
     * @see <a href="https://github.com/Joshua-F/cs2-scripts/blob/master/scripts/%5Bproc,ca_tasks_progress_bar%5D.cs2#L6-L11">CS2 Reference</a>
     */
    @VisibleForTesting
    public static final Map<CombatAchievement, Integer> CUM_POINTS_VARBIT_BY_TIER;

    /**
     * The cumulative points needed to unlock rewards for each tier, in a Red-Black tree.
     * <p>
     * This is populated by {@link #initThresholds()} based on {@link #CUM_POINTS_VARBIT_BY_TIER}.
     *
     * @see <a href="https://gachi.gay/01CAv">Rewards Thresholds at the launch of the points-based system</a>
     */
    private final NavigableMap<Integer, CombatAchievement> cumulativeUnlockPoints = new TreeMap<>();

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

            Map.Entry<Integer, CombatAchievement> prev = cumulativeUnlockPoints.floorEntry(totalPoints);
            int prevThreshold = prev != null ? prev.getKey() : 0;

            boolean crossedThreshold = prevThreshold > 0 && totalPoints - taskPoints < prevThreshold;
            String completedTierName = "N/A";
            if (prev != null) {
                CombatAchievement completedTier = crossedThreshold ? prev.getValue() : null;
                completedTierName = completedTier != null ? completedTier.getDisplayName() : "N/A";
            }
            
            String player = getPlayerName();
            CustomWebhookBody combatWebhook = createWebhookBody(player + " has completed a new combat task:");
            CustomWebhookBody.Embed combatAchievementEmbed = createEmbed(null, "combat_achievement");
            
            Map<String, Object> fieldData = new HashMap<>();
            fieldData.put("tier", tier.toString());
            fieldData.put("task", task);
            fieldData.put("points", taskPoints);
            fieldData.put("total_points", totalPoints);
            fieldData.put("completed", completedTierName);
            
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
    static {
        CUM_POINTS_VARBIT_BY_TIER = ImmutableMap.<CombatAchievement, Integer>builderWithExpectedSize(6)
                .put(CombatAchievement.EASY, 4132) // 33 = 33 * 1
                .put(CombatAchievement.MEDIUM, 10660) // 115 = 33 + 41 * 2
                .put(CombatAchievement.HARD, 10661) // 304 = 115 + 63 * 3
                .put(CombatAchievement.ELITE, 14812) // 820 = 304 + 129 * 4
                .put(CombatAchievement.MASTER, 14813) // 1465 = 820 + 129 * 5
                .put(CombatAchievement.GRANDMASTER, GRANDMASTER_TOTAL_POINTS_ID) // 2005 = 1465 + 90 * 6
                .build();
    }
}
