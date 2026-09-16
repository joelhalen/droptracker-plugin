package io.droptracker.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.testing.RuneLiteStubs;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives {@link SlayerHandler} the way the client does: var updates arrive as
 * events and are read at the end of the frame, chat lines arrive as text, and
 * the handler decides on the tick.
 *
 * <p>The cache rows are real ({@code slayer_task}, {@code slayer_task_sublist}),
 * trimmed to the assignments these tests hand out.
 */
public class SlayerHandlerTest {

    private static final int TURAEL = 1;
    private static final int DURADEL = 5;
    private static final int NIEVE = 6;
    private static final int KRYSTILIA = 7;
    private static final int KONAR = 8;

    private static final int BIRDS = 5;
    private static final int DUST_DEVILS = 49;
    private static final int CAVE_KRAKEN = 92;
    private static final int HYDRAS = 113;
    private static final int BOSS = SlayerHandler.BOSS_TASK_ID;

    private static final int CAVE_KRAKEN_BOSS = 18;
    private static final int CHAOS_ELEMENTAL = 12;

    /* slayer_task: task id -> row, row -> name. */
    private static final Map<Integer, Integer> TASK_ROWS = new HashMap<>();
    private static final Map<Integer, String> TASK_NAMES = new HashMap<>();
    /* slayer_task_sublist: boss id -> row, row -> slayer_task row. */
    private static final Map<Integer, Integer> BOSS_ROWS = new HashMap<>();
    private static final Map<Integer, Integer> BOSS_TASK_ROWS = new HashMap<>();

    static {
        task(BIRDS, 6218, "Birds");
        task(DUST_DEVILS, 6262, "Dust Devils");
        task(CAVE_KRAKEN, 6304, "Cave Kraken");
        task(HYDRAS, 6326, "Hydras");
        task(BOSS, 6311, "Boss");
        TASK_NAMES.put(6187, "The Cave Kraken boss");
        BOSS_ROWS.put(CAVE_KRAKEN_BOSS, 6186);
        BOSS_TASK_ROWS.put(6186, 6187);
        // The Chaos Elemental's rows are left out: a boss the tables don't know.
    }

    private static void task(int id, int row, String name) {
        TASK_ROWS.put(id, row);
        TASK_NAMES.put(row, name);
    }

    private static final String KRAKEN_LINE =
        "You have completed your task! You killed 150 Cave Kraken. You gained 62,475 xp.";
    private static final String POINTS_LINE =
        "You've completed 1,000 tasks and received 1,000 points, giving you a total of 2,584; return to a Slayer master.";

    private final Map<Integer, Integer> varps = new HashMap<>();
    private final Map<Integer, Integer> varbits = new HashMap<>();
    private final List<Runnable> frameReads = new ArrayList<>();
    private final List<SlayerHandler.Completion> submitted = new ArrayList<>();

    private Client client;
    private DropTrackerConfig config;
    private DropTrackerPlugin plugin;
    private SlayerHandler handler;
    private int tick;

    @Before
    public void setUp() {
        client = RuneLiteStubs.client(RuneLiteStubs.player("Local", 126));
        Map<String, Object> state = RuneLiteStubs.state(client);
        state.put("getVarpValue", (RuneLiteStubs.Answer) args -> read(varps, args));
        state.put("getVarbitValue", (RuneLiteStubs.Answer) args -> read(varbits, args));
        state.put("getDBRowsByValue", (RuneLiteStubs.Answer) SlayerHandlerTest::rowsByValue);
        state.put("getDBTableField", (RuneLiteStubs.Answer) SlayerHandlerTest::tableField);
        state.put("getGameState", GameState.LOGGED_IN);

        config = RuneLiteStubs.stub(DropTrackerConfig.class, "config");
        RuneLiteStubs.state(config).put("slayerEmbeds", true);
        plugin = new DropTrackerPlugin();

        handler = new SlayerHandler() {
            @Override
            void afterVarps(Runnable read) {
                frameReads.add(read);
            }

            @Override
            void submit(Completion completion) {
                submitted.add(completion);
            }
        };
        handler.client = client;
        handler.config = config;
        handler.plugin = plugin;
        tickTo(1);
    }

    // --- the fake client ------------------------------------------------

    private static Object read(Map<Integer, Integer> vars, Object[] args) {
        return args.length == 1 ? vars.getOrDefault((Integer) args[0], 0) : 0;
    }

    private static Object rowsByValue(Object[] args) {
        int table = (Integer) args[0];
        int column = (Integer) args[1];
        Object value = args[3];
        Integer row = null;
        if (table == DBTableID.SlayerTask.ID && column == DBTableID.SlayerTask.COL_ID) {
            row = TASK_ROWS.get(value);
        } else if (table == DBTableID.SlayerTaskSublist.ID
            && column == DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID) {
            row = BOSS_ROWS.get(value);
        }
        return row == null ? Collections.emptyList() : Collections.singletonList(row);
    }

    private static Object tableField(Object[] args) {
        int row = (Integer) args[0];
        int column = (Integer) args[1];
        if (column == DBTableID.SlayerTaskSublist.COL_TASK && BOSS_TASK_ROWS.containsKey(row)) {
            return new Object[] { BOSS_TASK_ROWS.get(row) };
        }
        if (column == DBTableID.SlayerTask.COL_NAME_UPPERCASE && TASK_NAMES.containsKey(row)) {
            return new Object[] { TASK_NAMES.get(row) };
        }
        return new Object[0];
    }

    private void varp(int id, int value) {
        varps.put(id, value);
        VarbitChanged event = new VarbitChanged();
        event.setVarpId(id);
        event.setValue(value);
        handler.onVarbitChanged(event);
    }

    private void varbit(int id, int value) {
        varbits.put(id, value);
        VarbitChanged event = new VarbitChanged();
        event.setVarbitId(id);
        event.setValue(value);
        handler.onVarbitChanged(event);
    }

    /** The client drains its invokeLater queue once the frame's packets are in. */
    private void endFrame() {
        List<Runnable> reads = new ArrayList<>(frameReads);
        frameReads.clear();
        reads.forEach(Runnable::run);
    }

    private void tickTo(int next) {
        tick = next;
        RuneLiteStubs.state(client).put("getTickCount", next);
    }

    /** One game tick: the frame's reads land, then the tick event. */
    private void nextTick() {
        endFrame();
        tickTo(tick + 1);
        handler.onTick();
    }

    private void assign(int master, int taskId, int amount) {
        varbit(VarbitID.SLAYER_MASTER, master);
        varp(VarPlayerID.SLAYER_TARGET, taskId);
        varp(VarPlayerID.SLAYER_COUNT_ORIGINAL, amount);
        varp(VarPlayerID.SLAYER_COUNT, amount);
        nextTick();
    }

    private void killsLeft(int remaining) {
        varp(VarPlayerID.SLAYER_COUNT, remaining);
    }

    private void chat(String... lines) {
        for (String line : lines) {
            handler.onGameMessage(line);
        }
    }

    private void idle(int ticks) {
        for (int i = 0; i < ticks; i++) {
            nextTick();
        }
    }

    private SlayerHandler.Completion onlySubmission() {
        assertEquals("submissions", 1, submitted.size());
        return submitted.get(0);
    }

    // --- completions ----------------------------------------------------

    @Test
    public void aFinishedTaskIsSubmittedAsTheTaskItWas() {
        assign(DURADEL, CAVE_KRAKEN, 150);
        killsLeft(1);
        nextTick();

        chat(KRAKEN_LINE, POINTS_LINE);
        killsLeft(0);
        nextTick();

        SlayerHandler.Completion done = onlySubmission();
        assertEquals("Cave Kraken", done.getTaskName());
        assertEquals(CAVE_KRAKEN, done.getTaskId());
        assertEquals(DURADEL, done.getMasterId());
        assertFalse(done.isBoss());
        assertEquals(150, done.getAmountInitial());
        assertEquals(150, done.getAmountKilled());
        assertEquals(Integer.valueOf(62475), done.getXpGained());
        assertEquals(Integer.valueOf(1000), done.getStreak());
        assertEquals(Integer.valueOf(1000), done.getPointsAwarded());
        assertEquals(Integer.valueOf(2584), done.getPointsTotal());
        assertEquals(KRAKEN_LINE + " " + POINTS_LINE, done.getCompletionMessage());
    }

    @Test
    public void theCountMayDropBeforeTheLine() {
        assign(DURADEL, CAVE_KRAKEN, 150);
        killsLeft(0);
        nextTick();
        assertTrue(submitted.isEmpty());

        chat(KRAKEN_LINE, POINTS_LINE);
        nextTick();

        assertEquals("Cave Kraken", onlySubmission().getTaskName());
    }

    @Test
    public void theCountMayDropAfterTheLine() {
        assign(DURADEL, CAVE_KRAKEN, 150);
        chat(KRAKEN_LINE, POINTS_LINE);
        nextTick();
        assertTrue(submitted.isEmpty());

        killsLeft(0);
        nextTick();

        assertEquals(DURADEL, onlySubmission().getMasterId());
    }

    @Test
    public void aTaskIsSubmittedOnce() {
        assign(DURADEL, CAVE_KRAKEN, 150);
        chat(KRAKEN_LINE, POINTS_LINE);
        killsLeft(0);
        idle(5);

        assertEquals(1, submitted.size());
    }

    // --- things that are not completions --------------------------------

    /** Cancelling with points, or a task replaced: the count drops, no line. */
    @Test
    public void aCountDropWithoutTheLineIsNotACompletion() {
        assign(DURADEL, CAVE_KRAKEN, 150);
        killsLeft(0);
        idle(3);
        // A stray line well after the drop does not revive it.
        chat(KRAKEN_LINE, POINTS_LINE);
        idle(5);

        assertTrue(submitted.isEmpty());
    }

    /** Anything can print a line; only the game can zero the count. */
    @Test
    public void theLineWithoutACountDropIsNotACompletion() {
        assign(DURADEL, CAVE_KRAKEN, 150);
        chat(KRAKEN_LINE, POINTS_LINE);
        idle(5);
        // The count dropping much later is some other event.
        killsLeft(0);
        idle(5);

        assertTrue(submitted.isEmpty());
    }

    @Test
    public void theLineWithNoTaskLiveIsNotACompletion() {
        chat(KRAKEN_LINE, POINTS_LINE);
        killsLeft(0);
        idle(5);

        assertTrue(submitted.isEmpty());
    }

    /** A login or hop re-sends the varps; a count reading zero then ends nothing. */
    @Test
    public void aHopSyncIsNotACompletion() {
        assign(DURADEL, CAVE_KRAKEN, 150);
        handler.onGameStateChanged(GameState.HOPPING);
        killsLeft(0);
        endFrame();
        chat(KRAKEN_LINE, POINTS_LINE);
        idle(5);

        assertTrue(submitted.isEmpty());
    }

    /** A hop that changes nothing sends no var events; the task must survive it. */
    @Test
    public void aTaskFinishedAfterAHopStillCounts() {
        assign(DURADEL, CAVE_KRAKEN, 150);
        handler.onGameStateChanged(GameState.HOPPING);
        handler.onGameStateChanged(GameState.LOGGED_IN);
        nextTick();

        chat(KRAKEN_LINE, POINTS_LINE);
        killsLeft(0);
        nextTick();

        assertEquals("Cave Kraken", onlySubmission().getTaskName());
    }

    /** The next login may be another account, whose varps may never fire. */
    @Test
    public void loggingOutForgetsTheTask() {
        assign(DURADEL, CAVE_KRAKEN, 150);
        handler.onGameStateChanged(GameState.LOGIN_SCREEN);
        handler.onGameStateChanged(GameState.LOGGING_IN);
        varps.put(VarPlayerID.SLAYER_COUNT, 0);
        nextTick();

        chat(KRAKEN_LINE, POINTS_LINE);
        killsLeft(0);
        idle(5);

        assertTrue(submitted.isEmpty());
    }

    /** No game ticks pass on the login screen, so the window alone can't expire it. */
    @Test
    public void aCountDropJustBeforeLoggingOutDoesNotCarryOver() {
        assign(DURADEL, CAVE_KRAKEN, 150);
        killsLeft(0);
        endFrame();
        handler.onGameStateChanged(GameState.LOGIN_SCREEN);
        handler.onGameStateChanged(GameState.LOGGING_IN);

        chat(KRAKEN_LINE, POINTS_LINE);
        idle(5);

        assertTrue(submitted.isEmpty());
    }

    @Test
    public void aTaskAlreadyLiveWhenThePluginStartsIsPickedUp() {
        varbits.put(VarbitID.SLAYER_MASTER, DURADEL);
        varps.put(VarPlayerID.SLAYER_TARGET, CAVE_KRAKEN);
        varps.put(VarPlayerID.SLAYER_COUNT_ORIGINAL, 150);
        varps.put(VarPlayerID.SLAYER_COUNT, 3);
        // What startUp runs on the client thread.
        handler.syncIfLoggedIn();
        nextTick();

        chat(KRAKEN_LINE, POINTS_LINE);
        killsLeft(0);
        nextTick();

        assertEquals("Cave Kraken", onlySubmission().getTaskName());
    }

    @Test
    public void nothingIsSubmittedWithTheOptionOff() {
        RuneLiteStubs.state(config).put("slayerEmbeds", false);
        assign(DURADEL, CAVE_KRAKEN, 150);
        chat(KRAKEN_LINE, POINTS_LINE);
        killsLeft(0);
        idle(3);

        assertTrue(submitted.isEmpty());
    }

    @Test
    public void nothingIsSubmittedWhileTrackingIsPaused() {
        plugin.isTracking = false;
        assign(DURADEL, CAVE_KRAKEN, 150);
        chat(KRAKEN_LINE, POINTS_LINE);
        killsLeft(0);
        idle(3);

        assertTrue(submitted.isEmpty());
    }

    // --- what a completion carries --------------------------------------

    @Test
    public void aBossTaskIsNamedAfterItsBoss() {
        assign(NIEVE, BOSS, 50);
        varbit(VarbitID.SLAYER_TARGET_BOSSID, CAVE_KRAKEN_BOSS);
        nextTick();

        chat("You are granted an extra reward of 5k Slayer XP for completing your boss task against the Cave Kraken boss.",
            "You have completed your task! You killed 50 Bosses. You gained 14,200 xp.",
            "You've completed 150 tasks and received 10 points, giving you a total of 200; return to a Slayer master.");
        killsLeft(0);
        nextTick();

        SlayerHandler.Completion done = onlySubmission();
        assertEquals("The Cave Kraken boss", done.getTaskName());
        assertTrue(done.isBoss());
        assertEquals(CAVE_KRAKEN_BOSS, done.getBossId());
        assertEquals(50, done.getAmountKilled());
    }

    @Test
    public void aBossTheTablesDontKnowIsNamedFromTheRewardLine() {
        assign(NIEVE, BOSS, 11);
        varbit(VarbitID.SLAYER_TARGET_BOSSID, CHAOS_ELEMENTAL);
        nextTick();

        chat("You are granted an extra reward of 5k Slayer XP for completing your boss task against the Chaos Elemental.",
            "You have completed your task! You killed 11 Bosses. You gained 7,954 xp.",
            "You've completed 242 tasks and received 15 points, giving you a total of 3,254; return to a Slayer master.");
        killsLeft(0);
        nextTick();

        assertEquals("The Chaos Elemental", onlySubmission().getTaskName());
    }

    @Test
    public void aTaskTheTablesDontKnowIsNamedFromTheLine() {
        assign(DURADEL, 250, 20);
        chat("You have completed your task! You killed 20 Moon Moths. You gained 900 xp.",
            "You've completed 7 tasks and received 12 points, giving you a total of 90; return to a Slayer master.");
        killsLeft(0);
        nextTick();

        SlayerHandler.Completion done = onlySubmission();
        assertEquals("Moon Moths", done.getTaskName());
        assertEquals(250, done.getTaskId());
    }

    /** "Turael skipping": reported with its master; the server decides it doesn't count. */
    @Test
    public void aTuraelTaskEarnsNothingAndSaysWhoGaveIt() {
        varbits.put(VarbitID.SLAYER_POINTS, 420);
        assign(TURAEL, BIRDS, 20);
        chat("You have completed your task! You killed 20 Birds. You gained 1,200 xp.",
            "You've completed 3 tasks.You'll be eligible to earn reward points if you complete tasks from a more advanced Slayer Master.");
        killsLeft(0);
        nextTick();

        SlayerHandler.Completion done = onlySubmission();
        assertEquals(TURAEL, done.getMasterId());
        assertEquals("Birds", done.getTaskName());
        assertEquals(Integer.valueOf(3), done.getStreak());
        assertEquals(Integer.valueOf(0), done.getPointsAwarded());
        assertEquals("the total is not in the line, so it comes from the varbit",
            Integer.valueOf(420), done.getPointsTotal());
    }

    @Test
    public void withoutTheStreakLineTheCompletionWaitsATickThenReadsTheVars() {
        varbits.put(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED, 12);
        varbits.put(VarbitID.SLAYER_TASKS_COMPLETED, 999);
        varbits.put(VarbitID.SLAYER_POINTS, 1500);
        assign(KRYSTILIA, DUST_DEVILS, 100);
        chat("You have completed your task! You killed 100 Dust Devils. You gained 9,000 xp.");
        killsLeft(0);
        endFrame();
        // A tick event carrying the line's own tick count: the streak line
        // could still be on its way.
        handler.onTick();
        assertTrue(submitted.isEmpty());

        nextTick();

        SlayerHandler.Completion done = onlySubmission();
        assertEquals("Krystilia's streak is her own", Integer.valueOf(12), done.getStreak());
        assertNull(done.getPointsAwarded());
        assertEquals(Integer.valueOf(1500), done.getPointsTotal());
        assertEquals("You have completed your task! You killed 100 Dust Devils. You gained 9,000 xp.",
            done.getCompletionMessage());
    }

    @Test
    public void aKonarTaskCarriesItsArea() {
        varp(VarPlayerID.SLAYER_AREA, 3);
        assign(KONAR, HYDRAS, 120);
        chat("You have completed your task! You killed 120 Hydras. You gained 43,000 xp.",
            "You've completed 50 tasks and received 25 points, giving you a total of 800; return to a Slayer master.");
        killsLeft(0);
        nextTick();

        SlayerHandler.Completion done = onlySubmission();
        assertEquals(3, done.getAreaId());
        assertEquals(KONAR, done.getMasterId());
    }

    @Test
    public void aQuantityModifierMovesTheStartingAmount() {
        varbits.put(VarbitID.SLAYER_MODIFIER_ID, 2);
        varbits.put(VarbitID.SLAYER_MODIFIER_VALUE, 20);
        varbits.put(VarbitID.SLAYER_MODIFIER_NEGATIVE, 1);
        assign(DURADEL, CAVE_KRAKEN, 150);
        chat("You have completed your task! You killed 130 Cave Kraken. You gained 54,000 xp.", POINTS_LINE);
        killsLeft(0);
        nextTick();

        SlayerHandler.Completion done = onlySubmission();
        assertEquals(130, done.getAmountInitial());
        assertEquals(130, done.getAmountKilled());
    }

    /**
     * If the game clears the other task vars in the same batch as the count,
     * reading between updates would report a task with no master and no name.
     * The task is read once the batch is in, so the count drop still finds it.
     */
    @Test
    public void varsClearedAlongsideTheCountDoNotRewriteTheTask() {
        assign(DURADEL, CAVE_KRAKEN, 150);
        chat(KRAKEN_LINE, POINTS_LINE);
        varbit(VarbitID.SLAYER_MASTER, 0);
        varp(VarPlayerID.SLAYER_TARGET, 0);
        killsLeft(0);
        nextTick();

        SlayerHandler.Completion done = onlySubmission();
        assertEquals("Cave Kraken", done.getTaskName());
        assertEquals(CAVE_KRAKEN, done.getTaskId());
        assertEquals(DURADEL, done.getMasterId());
    }

    /** A new task replaces the old one wholesale, master included. */
    @Test
    public void theNextTaskIsReportedWithItsOwnMaster() {
        assign(DURADEL, CAVE_KRAKEN, 150);
        chat(KRAKEN_LINE, POINTS_LINE);
        killsLeft(0);
        nextTick();
        submitted.clear();

        assign(NIEVE, DUST_DEVILS, 100);
        chat("You have completed your task! You killed 100 Dust Devils. You gained 9,000 xp.",
            "You've completed 1,001 tasks and received 12 points, giving you a total of 2,596; return to a Slayer master.");
        killsLeft(0);
        nextTick();

        SlayerHandler.Completion done = onlySubmission();
        assertEquals("Dust Devils", done.getTaskName());
        assertEquals(NIEVE, done.getMasterId());
        assertEquals(100, done.getAmountInitial());
        assertEquals(Integer.valueOf(1001), done.getStreak());
    }

    // --- fields ---------------------------------------------------------

    @Test
    public void fieldsCarryWhatTheServerReads() {
        SlayerHandler.Completion done = new SlayerHandler.Completion(
            "The Cave Kraken boss", BOSS, CAVE_KRAKEN_BOSS, NIEVE, 0, 50, 50,
            14200, 150, 10, 200, "line");

        Map<String, Object> fields = SlayerHandler.fields(done, 1_700_000_000L);

        assertEquals("The Cave Kraken boss", fields.get("task_name"));
        assertEquals(BOSS, fields.get("task_id"));
        assertEquals(true, fields.get("is_boss"));
        assertEquals(CAVE_KRAKEN_BOSS, fields.get("boss_id"));
        assertEquals(NIEVE, fields.get("master_id"));
        assertEquals(50, fields.get("amount_initial"));
        assertEquals(50, fields.get("amount_killed"));
        assertEquals(14200, fields.get("xp_gained"));
        assertEquals(150, fields.get("streak"));
        assertEquals(10, fields.get("points_awarded"));
        assertEquals(200, fields.get("points_total"));
        assertEquals("line", fields.get("completion_message"));
        assertEquals(1_700_000_000L, fields.get("timestamp"));
        assertFalse("no area outside Konar", fields.containsKey("area_id"));
    }

    /**
     * addFields turns null into "N/A", which the server would keep as text: a
     * master called "N/A", a message reading "N/A". Unknowns stay out.
     */
    @Test
    public void unknownsAreLeftOut() {
        SlayerHandler.Completion done = new SlayerHandler.Completion(
            "Birds", BIRDS, 0, TURAEL, 0, 0, 20, null, null, null, null, "line");

        Map<String, Object> fields = SlayerHandler.fields(done, 1L);

        assertFalse(fields.containsValue(null));
        assertFalse(fields.containsKey("boss_id"));
        assertFalse(fields.containsKey("amount_initial"));
        assertFalse(fields.containsKey("xp_gained"));
        assertFalse(fields.containsKey("streak"));
        assertFalse(fields.containsKey("points_awarded"));
        assertFalse(fields.containsKey("points_total"));
        assertFalse(fields.containsKey("master_name"));
        assertEquals(false, fields.get("is_boss"));
    }
}
