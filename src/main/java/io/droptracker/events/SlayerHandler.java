package io.droptracker.events;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.inject.Singleton;

import org.jetbrains.annotations.VisibleForTesting;

import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import io.droptracker.util.DebugLogger;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Slayer task completions, for event goals that count them ("25 tasks, no
 * Turael skipping", "10 tasks from Duradel").
 *
 * <p>A completion needs two signals, landing within {@value #WINDOW_TICKS}
 * ticks of each other:
 * <ol>
 *   <li>the game's completion line, "You have completed your task! You killed
 *       245 Cave Kraken. You gained 62,475 xp.", and</li>
 *   <li>the kills-remaining varp ({@code SLAYER_COUNT}) dropping from above
 *       zero to zero.</li>
 * </ol>
 * Neither is enough alone. Cancelling a task zeroes the count without the
 * line, and the line is only text: another plugin can print one.
 *
 * <p>What the task was (assignment, boss, master, area, starting amount) is
 * read the way RuneLite's own slayer plugin reads it, from varps and the
 * cache's slayer tables, never from chat. It is snapshotted while the task is
 * live, because finishing the task zeroes the count those reads depend on.
 *
 * <p>Every completion goes out with the raw {@code SLAYER_MASTER} value. Which
 * masters count toward a goal is the event task's own config, decided on the
 * server; the plugin never filters. The ids match the cache's
 * {@code slayer_master_task} rows (1 Turael ... 10 Mortimer).
 *
 * <p>Invoked from DropTrackerPlugin's subscriptions; handlers in this package
 * are not registered on the RuneLite event bus.
 */
@Slf4j
@Singleton
public class SlayerHandler extends BaseEventHandler {

    /** Most ticks allowed between the completion line and the count drop. */
    @VisibleForTesting
    static final int WINDOW_TICKS = 2;

    /**
     * {@code SLAYER_TARGET} for a boss task; which boss is in
     * {@code SLAYER_TARGET_BOSSID}. From [proc,helper_slayer_current_assignment].
     */
    @VisibleForTesting
    static final int BOSS_TASK_ID = 98;

    /* Masters whose streak lives in its own var (RuneLite's SlayerPlugin). */
    private static final int KRYSTILIA = 7;
    private static final int MORTIMER = 10;

    /** {@code slayer_modifiers} row 2: the kill count was changed at assignment. */
    private static final int MODIFIER_QUANTITY_CHANGE = 2;

    /** The server keeps this much of the raw lines. */
    private static final int MAX_MESSAGE_LENGTH = 255;

    @VisibleForTesting
    static final Pattern TASK_LINE = Pattern.compile(
        "You have completed your task! You killed (?<killed>[\\d,]+) (?<name>[^.]+)\\."
            + "(?:.*?You gained (?<xp>[\\d,]+) xp)?");

    /**
     * Precedes the completion line on a boss task, which itself only says
     * "You killed 11 Bosses". The cache name wins; this is the fallback.
     */
    @VisibleForTesting
    static final Pattern BOSS_LINE = Pattern.compile(
        "for completing your boss task against (?<name>.+?)\\.?$");

    /**
     * Follows the completion line. Three endings: points awarded, the points
     * cap reached, or a master that awards none (Turael/Aya, Spria).
     */
    @VisibleForTesting
    static final Pattern STREAK_LINE = Pattern.compile(
        "You've completed (?:at least )?(?<streak>[\\d,]+) (?:Wilderness |Mortimer )?tasks?"
            + "(?: and received (?<points>[\\d,]+) points?, giving you a total of (?<total>[\\d,]+)"
            + "| and reached the maximum amount of Slayer points \\((?<cap>[\\d,]+)\\)"
            + "|(?<nopoints>\\. ?You'll be eligible to earn reward points))?");

    /** The task the game has live right now; null when there is none. */
    private LiveTask liveTask;
    /** The task whose count just dropped to zero, and the tick it did. */
    private LiveTask zeroedTask;
    private int zeroedTick;
    /** The chat half of a completion, waiting on the count. */
    private PendingChat chat;
    /** A boss-task reward line, waiting on the completion line after it. */
    private String bossLineName;
    private int bossLineTick;
    /**
     * Varps arriving now are a login or world-hop sync, not changes, so a count
     * reading zero is not a task ending. RuneLite's {@code loginFlag}.
     */
    private boolean syncing;

    @Override
    public boolean isEnabled() {
        return config.slayerEmbeds();
    }

    /**
     * Enabling the plugin mid-task fires no varp change, so read the live task
     * directly. Task state is kept whatever the config says: turning the option
     * on mid-task must not lose the task it is on.
     */
    public void startUp() {
        clientThread.invokeLater(this::syncIfLoggedIn);
    }

    @VisibleForTesting
    void syncIfLoggedIn() {
        if (client.getGameState() == GameState.LOGGED_IN) {
            syncing = true;
            refreshTask();
        }
    }

    public void reset() {
        liveTask = null;
        zeroedTask = null;
        chat = null;
        bossLineName = null;
        syncing = false;
    }

    public void onGameStateChanged(GameState state) {
        switch (state) {
            case LOGIN_SCREEN:
                // The next login may be another account.
                reset();
                break;
            case HOPPING:
            case LOGGING_IN:
            case CONNECTION_LOST:
                syncing = true;
                break;
            default:
                break;
        }
    }

    public void onVarbitChanged(VarbitChanged event) {
        int varp = event.getVarpId();
        int varbit = event.getVarbitId();
        if (varp == VarPlayerID.SLAYER_COUNT
            || varp == VarPlayerID.SLAYER_TARGET
            || varp == VarPlayerID.SLAYER_AREA
            || varp == VarPlayerID.SLAYER_COUNT_ORIGINAL
            || varbit == VarbitID.SLAYER_TARGET_BOSSID
            || varbit == VarbitID.SLAYER_MASTER
            || varbit == VarbitID.SLAYER_MODIFIER_ID
            || varbit == VarbitID.SLAYER_MODIFIER_VALUE
            || varbit == VarbitID.SLAYER_MODIFIER_NEGATIVE) {
            afterVarps(this::refreshTask);
        }
    }

    /**
     * Reads once the whole batch of var updates has landed, so a new assignment
     * is never snapshotted half-written (the count from the new task, the master
     * from the old one).
     */
    @VisibleForTesting
    void afterVarps(Runnable read) {
        clientThread.invokeLater(read);
    }

    public void onGameMessage(String message) {
        // All three lines say "task"; most game messages don't.
        if (message == null || !message.contains("task")) {
            return;
        }
        int tick = client.getTickCount();

        Optional<TaskLine> taskLine = parseTaskLine(message);
        if (taskLine.isPresent()) {
            String bossName = bossLineName != null && tick - bossLineTick <= WINDOW_TICKS ? bossLineName : null;
            chat = new PendingChat(taskLine.get(), bossName, tick);
            bossLineName = null;
            return;
        }

        Optional<StreakLine> streakLine = parseStreakLine(message);
        if (streakLine.isPresent()) {
            if (chat != null && chat.streak == null && tick - chat.tick <= WINDOW_TICKS) {
                chat.streak = streakLine.get();
            }
            return;
        }

        Optional<String> bossName = parseBossLine(message);
        if (bossName.isPresent()) {
            bossLineName = bossName.get();
            bossLineTick = tick;
        }
    }

    public void onTick() {
        if (syncing) {
            refreshTask();
            syncing = false;
        }
        int now = client.getTickCount();

        if (chat != null) {
            boolean countDropped = zeroedTask != null && Math.abs(zeroedTick - chat.tick) <= WINDOW_TICKS;
            // The streak line normally lands on the completion line's tick;
            // one tick later, go without it rather than lose the completion.
            if (countDropped && (chat.streak != null || now - chat.tick >= 1)) {
                Completion completion = complete(chat, zeroedTask);
                chat = null;
                zeroedTask = null;
                if (isEnabled() && plugin.isTracking) {
                    submit(completion);
                }
                return;
            }
            if (now - chat.tick > WINDOW_TICKS) {
                DebugLogger.log("[SlayerHandler] completion line with no task count drop; not submitted: "
                    + chat.taskLine.getRaw());
                chat = null;
            }
        }
        // A count drop no line followed (a cancelled task) needs no clean-up:
        // the window check above keeps it from pairing with a later line.
    }

    @VisibleForTesting
    void refreshTask() {
        int remaining = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
        if (remaining > 0) {
            liveTask = readTask();
            return;
        }
        LiveTask ended = liveTask;
        liveTask = null;
        if (ended != null && !syncing) {
            zeroedTask = ended;
            zeroedTick = client.getTickCount();
        }
    }

    private LiveTask readTask() {
        int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
        int bossId = taskId == BOSS_TASK_ID ? client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID) : 0;

        int initial = client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL);
        if (client.getVarbitValue(VarbitID.SLAYER_MODIFIER_ID) == MODIFIER_QUANTITY_CHANGE) {
            int change = client.getVarbitValue(VarbitID.SLAYER_MODIFIER_VALUE);
            initial += client.getVarbitValue(VarbitID.SLAYER_MODIFIER_NEGATIVE) == 1 ? -change : change;
        }

        return new LiveTask(
            taskId,
            bossId,
            lookUpTaskName(taskId, bossId),
            client.getVarpValue(VarPlayerID.SLAYER_AREA),
            initial,
            client.getVarbitValue(VarbitID.SLAYER_MASTER));
    }

    /**
     * The assignment's name as the cache spells it ("Cave Kraken", "The
     * Alchemical Hydra"), the same lookup RuneLite's slayer plugin makes. Null
     * when the tables have no row for it; the chat line then names the task.
     */
    @Nullable
    private String lookUpTaskName(int taskId, int bossId) {
        try {
            int taskRow;
            if (taskId == BOSS_TASK_ID) {
                List<Integer> bossRows = client.getDBRowsByValue(
                    DBTableID.SlayerTaskSublist.ID, DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, 0, bossId);
                if (bossRows == null || bossRows.isEmpty()) {
                    return null;
                }
                Object[] task = client.getDBTableField(bossRows.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0);
                if (task == null || task.length == 0 || !(task[0] instanceof Integer)) {
                    return null;
                }
                taskRow = (Integer) task[0];
            } else {
                List<Integer> taskRows = client.getDBRowsByValue(
                    DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
                if (taskRows == null || taskRows.isEmpty()) {
                    return null;
                }
                taskRow = taskRows.get(0);
            }
            Object[] name = client.getDBTableField(taskRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0);
            if (name == null || name.length == 0 || !(name[0] instanceof String)) {
                return null;
            }
            String text = ((String) name[0]).trim();
            return text.isEmpty() ? null : text;
        } catch (RuntimeException e) {
            log.debug("Slayer task lookup failed (task {}, boss {})", taskId, bossId, e);
            return null;
        }
    }

    private Completion complete(PendingChat chat, LiveTask task) {
        TaskLine line = chat.taskLine;
        StreakLine streak = chat.streak;

        String name = task.getTaskName();
        if (name == null && task.getTaskId() == BOSS_TASK_ID) {
            name = chat.bossName;
        }
        if (name == null) {
            name = line.getName();
        }

        Integer streakCount = streak != null ? streak.getStreak() : null;
        if (streakCount == null) {
            streakCount = readStreak(task.getMasterId());
        }
        Integer pointsTotal = streak != null ? streak.getPointsTotal() : null;
        if (pointsTotal == null) {
            pointsTotal = client.getVarbitValue(VarbitID.SLAYER_POINTS);
        }

        String message = streak != null ? line.getRaw() + " " + streak.getRaw() : line.getRaw();
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH);
        }

        return new Completion(
            name,
            task.getTaskId(),
            task.getBossId(),
            task.getMasterId(),
            task.getAreaId(),
            task.getAmountInitial(),
            line.getKilled(),
            line.getXp(),
            streakCount,
            streak != null ? streak.getPointsAwarded() : null,
            pointsTotal,
            message);
    }

    private int readStreak(int masterId) {
        switch (masterId) {
            case KRYSTILIA:
                return client.getVarbitValue(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED);
            case MORTIMER:
                return client.getVarpValue(VarPlayerID.SLAYER_MORTIMER_TASKS_COMPLETED);
            default:
                return client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED);
        }
    }

    @VisibleForTesting
    void submit(Completion completion) {
        String playerName = getPlayerName();
        if (playerName == null) {
            log.debug("Skipping slayer task submission: no resolvable player name");
            return;
        }
        CustomWebhookBody webhook = createWebhookBody(playerName + " completed a slayer task!");
        CustomWebhookBody.Embed embed = createEmbed(completion.getTaskName(), "slayer_task");
        addFields(embed, fields(completion, System.currentTimeMillis() / 1000));
        webhook.getEmbeds().add(embed);
        sendData(webhook, SubmissionType.SLAYER_TASK);
    }

    /**
     * The submission's fields. Unknowns are left out rather than sent as
     * {@code addFields}' "N/A", which the server would store as text.
     */
    @VisibleForTesting
    static Map<String, Object> fields(Completion completion, long timestamp) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("task_name", completion.getTaskName());
        fields.put("task_id", completion.getTaskId());
        fields.put("is_boss", completion.isBoss());
        if (completion.isBoss() && completion.getBossId() > 0) {
            fields.put("boss_id", completion.getBossId());
        }
        fields.put("master_id", completion.getMasterId());
        if (completion.getAreaId() > 0) {
            fields.put("area_id", completion.getAreaId());
        }
        if (completion.getAmountInitial() > 0) {
            fields.put("amount_initial", completion.getAmountInitial());
        }
        fields.put("amount_killed", completion.getAmountKilled());
        putIfKnown(fields, "xp_gained", completion.getXpGained());
        putIfKnown(fields, "streak", completion.getStreak());
        putIfKnown(fields, "points_awarded", completion.getPointsAwarded());
        putIfKnown(fields, "points_total", completion.getPointsTotal());
        fields.put("completion_message", completion.getCompletionMessage());
        fields.put("timestamp", timestamp);
        return fields;
    }

    private static void putIfKnown(Map<String, Object> fields, String key, @Nullable Integer value) {
        if (value != null) {
            fields.put(key, value);
        }
    }

    // --- chat parsing ---------------------------------------------------

    @VisibleForTesting
    static Optional<TaskLine> parseTaskLine(String message) {
        Matcher m = TASK_LINE.matcher(message);
        if (!m.find()) {
            return Optional.empty();
        }
        Integer killed = parseNumber(m.group("killed"));
        String name = m.group("name").trim();
        if (killed == null || name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TaskLine(killed, name, parseNumber(m.group("xp")), message.trim()));
    }

    @VisibleForTesting
    static Optional<StreakLine> parseStreakLine(String message) {
        Matcher m = STREAK_LINE.matcher(message);
        if (!m.find()) {
            return Optional.empty();
        }
        Integer streak = parseNumber(m.group("streak"));
        if (streak == null) {
            return Optional.empty();
        }
        Integer awarded = parseNumber(m.group("points"));
        Integer total = parseNumber(m.group("total"));
        Integer cap = parseNumber(m.group("cap"));
        if (m.group("nopoints") != null) {
            // Turael/Aya and Spria: nothing awarded. The total is untouched and
            // not in the line; the caller reads it from the varbit.
            awarded = 0;
        } else if (cap != null) {
            // Capped: the line gives the new total but not what was added.
            total = cap;
        }
        return Optional.of(new StreakLine(streak, awarded, total, message.trim()));
    }

    /**
     * The boss's name as the reward line gives it, capitalised the way the cache
     * spells boss tasks ("the Cave Kraken boss" becomes "The Cave Kraken boss").
     */
    @VisibleForTesting
    static Optional<String> parseBossLine(String message) {
        Matcher m = BOSS_LINE.matcher(message);
        if (!m.find()) {
            return Optional.empty();
        }
        String name = m.group("name").trim();
        if (name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Character.toUpperCase(name.charAt(0)) + name.substring(1));
    }

    @Nullable
    private static Integer parseNumber(@Nullable String digits) {
        if (digits == null) {
            return null;
        }
        try {
            return Integer.parseInt(digits.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- values ---------------------------------------------------------

    /** A task as the varps describe it, captured while it is live. */
    @Value
    @VisibleForTesting
    static class LiveTask {
        int taskId;
        int bossId;
        @Nullable
        String taskName;
        int areaId;
        int amountInitial;
        int masterId;
    }

    /** "You have completed your task! You killed N name. You gained X xp." */
    @Value
    @VisibleForTesting
    static class TaskLine {
        int killed;
        String name;
        @Nullable
        Integer xp;
        String raw;
    }

    /** "You've completed N tasks and received P points, giving you a total of T..." */
    @Value
    @VisibleForTesting
    static class StreakLine {
        int streak;
        @Nullable
        Integer pointsAwarded;
        @Nullable
        Integer pointsTotal;
        String raw;
    }

    private static final class PendingChat {
        private final TaskLine taskLine;
        @Nullable
        private final String bossName;
        private final int tick;
        @Nullable
        private StreakLine streak;

        private PendingChat(TaskLine taskLine, @Nullable String bossName, int tick) {
            this.taskLine = taskLine;
            this.bossName = bossName;
            this.tick = tick;
        }
    }

    /** One completed task, as it is submitted. */
    @Value
    @VisibleForTesting
    static class Completion {
        String taskName;
        int taskId;
        int bossId;
        /** Raw {@code SLAYER_MASTER}; the server names it. */
        int masterId;
        int areaId;
        int amountInitial;
        int amountKilled;
        @Nullable
        Integer xpGained;
        @Nullable
        Integer streak;
        @Nullable
        Integer pointsAwarded;
        @Nullable
        Integer pointsTotal;
        String completionMessage;

        boolean isBoss() {
            return taskId == BOSS_TASK_ID;
        }
    }
}
