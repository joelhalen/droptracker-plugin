package io.droptracker.service;

import com.google.gson.Gson;
import io.droptracker.DropTrackerConfig;
import io.droptracker.models.EventDisplayMode;
import io.droptracker.models.api.EventNotification;
import io.droptracker.models.api.EventNotification.Priority;
import io.droptracker.models.api.EventState;
import io.droptracker.service.EventNotificationService.DisplayTask;
import io.droptracker.service.EventNotificationService.Toast;
import io.droptracker.util.ChatMessageUtil;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Broadcast legibility: the importance tier drives which pop-up survives a
 * batch, how long it lives and whether "important only" lets it through, and
 * every chat line carries its own type tag. The JSON bodies mirror what the
 * server actually enqueues (services/event_engine.py, event_completion).
 *
 * <p>Also covers what a task hidden in the Events tab is allowed to say: the
 * hide has to reach the chat, the pop-ups and the HUD, not just the list it
 * was clicked in.
 */
public class EventBroadcastPriorityTest {

    private final Gson gson = new Gson();

    /* ===================== envelope contract ===================== */

    @Test
    public void missingOrUnknownPriorityReadsAsNormal() {
        // The field post-dates the wire contract: a server that never sends it
        // (or sends something we don't know) must not break the plugin.
        assertEquals(Priority.NORMAL, envelope("event_completion", "{}", null).priorityTier());
        assertEquals(Priority.NORMAL, envelope("event_completion", "{}", "critical").priorityTier());
        assertEquals(Priority.NORMAL, Priority.from(null));
        assertEquals(Priority.NORMAL, Priority.from(""));
        assertEquals(Priority.HIGH, envelope("event_completion", "{}", "high").priorityTier());
        assertEquals(Priority.LOW, envelope("event_task_progress", "{}", "low").priorityTier());
        assertEquals(Priority.HIGH, Priority.from("HIGH"));
    }

    @Test
    public void parsesTileCompletionFields() {
        EventNotification n = envelope("event_completion",
            "{\"task_id\":218,\"task_label\":\"Obtain 3 Bandos hilts\",\"team_name\":\"Team Alpha\","
                + "\"player_name\":\"Koeppy\",\"points\":5,\"icon_item_id\":11804,"
                + "\"received_item\":\"Bandos hilt\",\"received_qty\":1,"
                + "\"cell_idxs\":[4,5],\"cell_labels\":[\"Bandos set\",\"Godwars row\"],"
                + "\"tiles_completed\":7,\"team_rank\":2,\"team_count\":5,"
                + "\"source_type\":\"drop\"}", "high");
        EventNotification.Data data = n.getData();
        assertEquals(Integer.valueOf(218), data.getTaskId());
        assertEquals(Arrays.asList(4, 5), data.getCellIdxs());
        assertEquals(Arrays.asList("Bandos set", "Godwars row"), data.getCellLabels());
        assertEquals(Integer.valueOf(7), data.getTilesCompleted());
        assertEquals(Integer.valueOf(2), data.getTeamRank());
        assertEquals(Integer.valueOf(5), data.getTeamCount());
        assertEquals("drop", data.getSourceType());
    }

    /* ===================== pop-up policy ===================== */

    @Test
    public void tileCompletionHeadlinesTheBatchAndTheRestFold() {
        RecordingChat chat = new RecordingChat();
        EventNotificationService service = service(new TestConfig(), chat);
        service.renderBatch(Arrays.asList(
            progressTick(), tileCompletion(null), otherEventCompletion()), false);

        // One card for the whole batch, headlined by the important update.
        List<Toast> toasts = new ArrayList<>(service.getToasts());
        assertEquals(1, toasts.size());
        Toast toast = toasts.get(0);
        assertEquals(Priority.HIGH, toast.getPriority());
        assertEquals("Tile complete!", toast.getTitle());
        assertTrue(toast.getBody(), toast.getBody().contains("tile: Bandos set"));
        assertTrue(toast.getBody(), toast.getBody().contains("7 tiles, 2nd of 5"));
        assertTrue(toast.getBody(), toast.getBody().endsWith("(+2 more)"));
    }

    @Test
    public void tileNameIsNotRepeatedWhenItMatchesTheTask() {
        RecordingChat chat = new RecordingChat();
        EventNotificationService service = service(new TestConfig(), chat);
        // Cells usually inherit the task's own label on a bingo board.
        service.renderBatch(java.util.Collections.singletonList(envelope("event_completion",
            "{\"task_id\":9,\"task_label\":\"Bandos set\",\"player_name\":\"Koeppy\","
                + "\"cell_idxs\":[4],\"cell_labels\":[\"Bandos set\"],"
                + "\"tiles_completed\":3}", null)), false);
        Toast toast = service.getToasts().getFirst();
        assertFalse(toast.getBody(), toast.getBody().contains("tile: Bandos set"));
        assertTrue(toast.getBody(), toast.getBody().contains("3 tiles"));
        assertEquals(Priority.HIGH, toast.getPriority()); // still a tile finish
    }

    @Test
    public void serverPriorityOverridesTheLocalDefault() {
        RecordingChat chat = new RecordingChat();
        EventNotificationService service = service(new TestConfig(), chat);
        // The same tile completion the server chose to call routine.
        service.renderBatch(java.util.Collections.singletonList(tileCompletion("low")), false);
        assertEquals(Priority.LOW, service.getToasts().getFirst().getPriority());
    }

    @Test
    public void importantOnlyDropsOrdinaryPopUpsButKeepsChat() {
        TestConfig config = new TestConfig();
        config.importantOnly = true;
        RecordingChat chat = new RecordingChat();
        EventNotificationService service = service(config, chat);

        service.renderBatch(java.util.Collections.singletonList(progressTick()), false);
        assertTrue(service.getToasts().isEmpty());
        assertEquals(1, chat.lines.size()); // chat is never filtered by tier

        service.renderBatch(java.util.Collections.singletonList(tileCompletion(null)), false);
        assertEquals(1, service.getToasts().size());
    }

    @Test
    public void dedupesTheSameTaskAcrossBatches() {
        RecordingChat chat = new RecordingChat();
        EventNotificationService service = service(new TestConfig(), chat);
        // Distinct envelope ids (the seen-id guard is upstream of this), same
        // (event, task, type) — one human-visible update, one card.
        service.renderBatch(java.util.Collections.singletonList(progressTick()), false);
        service.renderBatch(java.util.Collections.singletonList(progressTick()), false);
        assertEquals(1, service.getToasts().size());
        assertEquals(2, chat.lines.size());
    }

    @Test
    public void popUpsRespectTheChatOnlyDisplayMode() {
        TestConfig config = new TestConfig();
        config.mode = EventDisplayMode.CHAT;
        RecordingChat chat = new RecordingChat();
        EventNotificationService service = service(config, chat);
        service.renderBatch(java.util.Collections.singletonList(tileCompletion(null)), false);
        assertTrue(service.getToasts().isEmpty());
        assertEquals(1, chat.lines.size());
    }

    @Test
    public void lifetimesFollowThePriorityTier() {
        long now = 1_000_000L;
        Toast high = new Toast("t", "b", null, now, Priority.HIGH, null);
        Toast normal = new Toast("t", "b", null, now, Priority.NORMAL, null);
        Toast low = new Toast("t", "b", null, now, Priority.LOW, null);
        assertTrue(high.lifetimeMs() > normal.lifetimeMs());
        assertTrue(normal.lifetimeMs() > low.lifetimeMs());
        // A KC tick is gone while the tile completion is still on screen.
        assertTrue(low.expired(now + low.lifetimeMs() + 1));
        assertFalse(high.expired(now + low.lifetimeMs() + 1));
        // The legacy constructor keeps today's (NORMAL) behaviour.
        assertEquals(Priority.NORMAL, new Toast("t", "b", null, now).getPriority());
    }

    /* ===================== chat scheme ===================== */

    @Test
    public void chatLinesCarryPerTypeTagsAndColours() {
        RecordingChat chat = new RecordingChat();
        EventNotificationService service = service(new TestConfig(), chat);
        service.renderBatch(Arrays.asList(tileCompletion(null), progressTick(), leadChange()), false);

        assertEquals(3, chat.lines.size());
        assertEquals("TILE COMPLETE", chat.lines.get(0).tag);
        assertEquals("PROGRESS", chat.lines.get(1).tag);
        assertEquals("LEAD CHANGE", chat.lines.get(2).tag);
        // Distinct accents, and the item name is the emphasised span.
        assertNotNull(chat.lines.get(0).hex);
        assertFalse(chat.lines.get(0).hex.equals(chat.lines.get(1).hex));
        assertEquals("Bandos hilt", chat.lines.get(0).emphasis);
    }

    @Test
    public void catchUpDigestIsAHeaderPlusIndentedTallies() {
        RecordingChat chat = new RecordingChat();
        EventNotificationService service = service(new TestConfig(), chat);
        service.renderBatch(Arrays.asList(
            tileCompletion(null), progressTick(), leadChange(), bingoLine()), true);

        assertEquals("While you were away:", chat.lines.get(0).body);
        for (int i = 1; i < chat.lines.size(); i++) {
            assertTrue(chat.lines.get(i).body, chat.lines.get(i).body.startsWith("  - "));
        }
        assertTrue(chat.lines.size() <= 5); // header + at most four details
        assertEquals("While you were away", service.getToasts().getFirst().getTitle());
    }

    /* ===================== hidden tasks ===================== */

    @Test
    public void hidingATaskMutesItsProgressTicks() {
        RecordingChat chat = new RecordingChat();
        PrefsService service = new PrefsService(new TestConfig(), chat);
        service.hidden.add(127); // the task the progress fixture ticks

        service.renderBatch(java.util.Collections.singletonList(progressTick()), false);
        assertTrue(chat.lines.isEmpty());
        assertTrue(service.getToasts().isEmpty());

        // Control: the identical tick on a task the user did not hide.
        service.hidden.clear();
        service.renderBatch(java.util.Collections.singletonList(progressTick()), false);
        assertEquals(1, chat.lines.size());
        assertEquals(1, service.getToasts().size());
    }

    @Test
    public void aHiddenTaskStillAnnouncesItsCompletion() {
        // Deliberate: hiding declutters the routine ticks, but a completion
        // fires once, moves the score and never repeats — a board that changes
        // in silence confuses more than the one extra line.
        RecordingChat chat = new RecordingChat();
        PrefsService service = new PrefsService(new TestConfig(), chat);
        service.hidden.add(218);
        service.renderBatch(java.util.Collections.singletonList(tileCompletion(null)), false);
        assertEquals(1, chat.lines.size());
        assertEquals("TILE COMPLETE", chat.lines.get(0).tag);
        assertEquals(1, service.getToasts().size());
    }

    @Test
    public void hidingATaskDoesNotMuteEventLevelNews() {
        // A lead change carries the task id that triggered it, but it is news
        // about the whole event — hiding one tile must not silence the race.
        RecordingChat chat = new RecordingChat();
        PrefsService service = new PrefsService(new TestConfig(), chat);
        service.hidden.add(218);
        service.renderBatch(java.util.Collections.singletonList(leadChange()), false);
        assertEquals(1, chat.lines.size());
        assertEquals("LEAD CHANGE", chat.lines.get(0).tag);
    }

    @Test
    public void catchUpDigestLeavesOutHiddenTaskProgress() {
        List<EventNotification> backlog = Arrays.asList(
            tileCompletion(null), progressTick(), leadChange(), bingoLine());
        RecordingChat visible = new RecordingChat();
        new PrefsService(new TestConfig(), visible).renderBatch(backlog, true);
        assertTrue(bodies(visible), bodies(visible).contains("Progress on 1 task"));

        RecordingChat chat = new RecordingChat();
        PrefsService service = new PrefsService(new TestConfig(), chat);
        service.hidden.add(127);
        service.renderBatch(backlog, true);
        assertFalse(bodies(chat), bodies(chat).contains("Progress on"));
    }

    @Test
    public void aPinnedTaskStillWinsTheHud() {
        PrefsService service = new PrefsService(new TestConfig(), new RecordingChat());
        EventState.Entry entry = entry(500, task(400, false), task(500, false));
        service.tracked = 400;
        DisplayTask display = service.displayTask(entry);
        assertNotNull(display);
        assertEquals(400, display.id);
        assertTrue(display.tracked);
    }

    @Test
    public void theHudSkipsAHiddenServerFocusInsteadOfHeadliningIt() {
        PrefsService service = new PrefsService(new TestConfig(), new RecordingChat());
        EventState.Entry entry = entry(500, task(500, false), task(600, false), task(700, true));
        assertEquals(500, service.displayTask(entry).id); // the server's pick

        // Hidden: the next task the panel lists takes the box, not nothing.
        service.hidden.add(500);
        DisplayTask fallback = service.displayTask(entry);
        assertNotNull(fallback);
        assertEquals(600, fallback.id);
        assertFalse(fallback.tracked);

        // Nothing visible and open left: quiet, never a resurrected hide.
        service.hidden.add(600);
        assertNull(service.displayTask(entry));
    }

    @Test
    public void aBoardGameTileIsNeverSuppressed() {
        // The current tile is the task on a board game — the panel offers no
        // pin or hide there, and a stray hide must not blank the HUD.
        PrefsService service = new PrefsService(new TestConfig(), new RecordingChat());
        EventState.Entry entry = entry("board_game", 500, task(500, false), task(600, false));
        service.hidden.add(500);
        assertEquals(500, service.displayTask(entry).id);
    }

    /* ===================== fixtures ===================== */

    /** One /event_state entry: the server's focus task plus the team's list. */
    private EventState.Entry entry(int focusId, String... tasks) {
        return entry("bingo", focusId, tasks);
    }

    private EventState.Entry entry(String kind, int focusId, String... tasks) {
        return gson.fromJson("{\"event\":{\"id\":19,\"name\":\"Bingo Extravaganza\","
            + "\"kind\":\"" + kind + "\"},\"team\":{\"id\":3,\"name\":\"Team Alpha\"},"
            + "\"focus_task\":{\"id\":" + focusId + ",\"label\":\"Task " + focusId
            + "\",\"have\":1,\"need\":3,\"source\":\"team_progress\"},"
            + "\"tasks\":[" + String.join(",", tasks) + "]}", EventState.Entry.class);
    }

    /** Every captured chat body, joined — the digest indents its lines. */
    private static String bodies(RecordingChat chat) {
        StringBuilder all = new StringBuilder();
        for (Line line : chat.lines) {
            all.append(line.body).append('\n');
        }
        return all.toString();
    }

    private static String task(int id, boolean completed) {
        return "{\"id\":" + id + ",\"label\":\"Task " + id + "\",\"have\":1,\"need\":3,"
            + "\"completed\":" + completed + "}";
    }

    private EventNotification envelope(String type, String dataJson, String priority) {
        return gson.fromJson("{\"id\":\"" + type + "-" + Math.random()
            + "\",\"type\":\"" + type + "\",\"ts\":1,"
            + (priority != null ? "\"priority\":\"" + priority + "\"," : "")
            + "\"event\":{\"id\":19,\"name\":\"Bingo Extravaganza\"},"
            + "\"data\":" + dataJson + "}", EventNotification.class);
    }

    private EventNotification tileCompletion(String priority) {
        return envelope("event_completion",
            "{\"task_id\":218,\"task_label\":\"Obtain 3 Bandos hilts\",\"player_name\":\"Koeppy\","
                + "\"points\":5,\"received_item\":\"Bandos hilt\",\"received_qty\":1,"
                + "\"cell_idxs\":[4],\"cell_labels\":[\"Bandos set\"],"
                + "\"tiles_completed\":7,\"team_rank\":2,\"team_count\":5}", priority);
    }

    private EventNotification otherEventCompletion() {
        return gson.fromJson("{\"id\":\"other\",\"type\":\"event_completion\",\"ts\":1,"
            + "\"event\":{\"id\":20,\"name\":\"Board\"},"
            + "\"data\":{\"task_id\":9,\"task_label\":\"Zulrah x5\",\"player_name\":\"Ra ine\"}}",
            EventNotification.class);
    }

    private EventNotification progressTick() {
        return envelope("event_task_progress",
            "{\"task_id\":127,\"task_label\":\"1,000,000 Magic XP\",\"player_name\":\"Koeppy\","
                + "\"milestone_pct\":50}", null);
    }

    private EventNotification leadChange() {
        return envelope("event_lead_change",
            "{\"task_id\":218,\"team_name\":\"Team Alpha\",\"team_score\":120}", null);
    }

    private EventNotification bingoLine() {
        return envelope("event_line",
            "{\"team_name\":\"Team Alpha\",\"bonus_points\":10}", null);
    }

    private EventNotificationService service(DropTrackerConfig config, ChatMessageUtil chat) {
        // Only the config and the chat sink are touched while rendering a
        // batch; the polling collaborators stay unused (and so null) here.
        return new EventNotificationService(config, null, chat, null, null, null, null);
    }

    /**
     * A service whose per-event task prefs come from the test: ConfigManager
     * is final-by-construction (private constructor), so the pin and hide
     * reads are the seam instead.
     */
    private static class PrefsService extends EventNotificationService {
        private final Set<Integer> hidden = new LinkedHashSet<>();
        private int tracked;

        PrefsService(DropTrackerConfig config, ChatMessageUtil chat) {
            super(config, null, chat, null, null, null, null);
        }

        @Override
        public int trackedTaskId(int eventId) {
            return tracked;
        }

        @Override
        Set<Integer> hiddenTaskIds(int eventId) {
            return hidden;
        }
    }

    /** Captures the styled event lines instead of queueing them in-game. */
    private static class RecordingChat extends ChatMessageUtil {
        private final List<Line> lines = new ArrayList<>();

        @Override
        public void sendEventChatMessage(String eventName, String teamName, String tag,
                                         String accentHex, String emphasis, String messageContent) {
            lines.add(new Line(tag, accentHex, emphasis, messageContent));
        }

        @Override
        public void sendEventChatMessage(String eventName, String teamName, String messageContent) {
            lines.add(new Line(null, null, null, messageContent));
        }

        @Override
        public void sendChatMessage(String messageContent) {
            lines.add(new Line(null, null, null, messageContent));
        }
    }

    private static class Line {
        private final String tag;
        private final String hex;
        private final String emphasis;
        private final String body;

        Line(String tag, String hex, String emphasis, String body) {
            this.tag = tag;
            this.hex = hex;
            this.emphasis = emphasis;
            this.body = body;
        }
    }

    /** Config stub: every event setting at its shipped default unless a test
     *  flips it (the interface's own defaults cover everything else). */
    private static class TestConfig implements DropTrackerConfig {
        private EventDisplayMode mode = EventDisplayMode.POPUP;
        private boolean importantOnly = false;

        @Override
        public EventDisplayMode eventDisplayMode() {
            return mode;
        }

        @Override
        public boolean eventImportantPopupsOnly() {
            return importantOnly;
        }

        @Override
        public void setPinnedEventId(int eventId) {
        }

        @Override
        public void setLastVersionNotified(String versionNotified) {
        }

        @Override
        public void setLastAccountName(String accountName) {
        }

        @Override
        public void setCustomApiEndpoint(String customApiEndpoint) {
        }

        @Override
        public void setLastAccountHash(String accountHash) {
        }
    }
}
