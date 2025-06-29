package io.droptracker.events;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.models.CombatAchievement;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.util.ItemIDSearch;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.annotations.Varbit;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.VisibleForTesting;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ScheduledExecutorService;


@Slf4j
public class CaHandler {
    @Inject
    private final DropTrackerPlugin plugin;
    private final DropTrackerConfig config;

    @Inject
    protected Client client;
    

    @Inject
    private ConfigManager configManager;

    @Inject
    private ClientThread clientThread;

    private ItemIDSearch itemIDFinder;

    @Inject
    public CaHandler(DropTrackerPlugin plugin, DropTrackerConfig config, ItemIDSearch itemIDFinder,
                            ScheduledExecutorService executor, ConfigManager configManager) {
        this.plugin = plugin;
        this.config = config;
        this.itemIDFinder = itemIDFinder;
        this.executor = executor;
        this.configManager = configManager;
    }
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

    private final ScheduledExecutorService executor;


    public boolean isEnabled() {
        return true;
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
            String player = client.getLocalPlayer().getName();
            String accountHash = String.valueOf(client.getAccountHash());
            CustomWebhookBody combatWebhook = new CustomWebhookBody();
            combatWebhook.setContent(player + " has completed a new combat task:");
            CustomWebhookBody.Embed combatAchievementEmbed = new CustomWebhookBody.Embed();
            combatAchievementEmbed.addField("type", "combat_achievement",true);
            combatAchievementEmbed.addField("tier", tier.toString(),true);
            combatAchievementEmbed.addField("task", task,true);
            combatAchievementEmbed.addField("player_name", plugin.getLocalPlayerName(), true);
            combatAchievementEmbed.addField("acc_hash", accountHash, true);
            combatAchievementEmbed.addField("points", String.valueOf(taskPoints),true);
            combatAchievementEmbed.addField("total_points", String.valueOf(totalPoints),true);
            combatAchievementEmbed.addField("completed", completedTierName,true);
            combatAchievementEmbed.addField("p_v", plugin.pluginVersion,true);
            combatWebhook.getEmbeds().add(combatAchievementEmbed);
            plugin.sendDataToDropTracker(combatWebhook, "3");
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

    private void initThresholds() {
        CUM_POINTS_VARBIT_BY_TIER.forEach((tier, varbitId) -> {
            int cumulativePoints = client.getVarbitValue(varbitId);
            if (cumulativePoints > 0)
                cumulativeUnlockPoints.put(cumulativePoints, tier);
        });
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
