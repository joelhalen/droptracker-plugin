package io.droptracker.service;

import com.google.gson.Gson;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.api.EventNotification;
import io.droptracker.models.api.EventState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Wire-format tests: the JSON bodies here mirror what the production API
 * actually returns (captured from live /notifications and /event_state
 * responses), pinning the plugin's parsing of the server contract.
 */
public class EventNotificationParseTest {

    private final Gson gson = new Gson();

    @Test
    public void parsesNotificationsResponse() {
        String json = "{\"notifications\":[{"
            + "\"id\":\"1752770000-abc12345\",\"type\":\"event_completion\",\"ts\":1752770000,"
            + "\"event\":{\"id\":19,\"name\":\"Bingo Extravaganza\"},"
            + "\"data\":{\"task_label\":\"Obtain 3 Bandos hilts\",\"team_id\":38,"
            + "\"team_name\":\"Team Alpha\",\"player_name\":\"Koeppy\",\"points\":5,"
            + "\"icon_item_id\":11804,\"progress\":3,\"target\":3}}],"
            + "\"active_event\":true}";
        DropTrackerApi.NotificationsResponse response =
            gson.fromJson(json, DropTrackerApi.NotificationsResponse.class);
        assertNotNull(response.notifications);
        assertEquals(1, response.notifications.size());
        assertEquals(Boolean.TRUE, response.activeEvent);

        EventNotification n = response.notifications.get(0);
        assertEquals("event_completion", n.getType());
        assertEquals(Integer.valueOf(19), n.getEvent().getId());
        assertEquals("Team Alpha", n.getData().getTeamName());
        assertEquals(Integer.valueOf(11804), n.getData().getIconItemId());
        assertEquals(Long.valueOf(3L), n.getData().getProgress());
    }

    @Test
    public void parsesReceivedItemDetail() {
        // Completion detail fields (received_item/received_qty/points_based)
        // drive the "received X, completing Y" phrasing; points_based must
        // suppress quantity rendering (credits, not item counts).
        EventNotification n = gson.fromJson(
            "{\"id\":\"x\",\"type\":\"event_completion\",\"ts\":1,"
                + "\"data\":{\"received_item\":\"Dragon bones\",\"received_qty\":3,"
                + "\"points_based\":true}}",
            EventNotification.class);
        assertEquals("Dragon bones", n.getData().getReceivedItem());
        assertEquals(Integer.valueOf(3), n.getData().getReceivedQty());
        assertEquals(Boolean.TRUE, n.getData().getPointsBased());
    }

    @Test
    public void catchUpGateNeedsBothSizeAndStaleness() {
        long now = System.currentTimeMillis() / 1000L;
        // Big AND stale -> digest.
        assertTrue(EventNotificationService.isCatchUpBatch(batchWithTs(
            now - 7200, now - 7200, now - 3600, now - 60)));
        // Big but all fresh (live burst, e.g. a teammate spamming tasks) -> normal render.
        assertFalse(EventNotificationService.isCatchUpBatch(batchWithTs(
            now - 5, now - 5, now - 5, now - 5)));
        // Stale but tiny -> normal render (three old lines don't need a digest).
        assertFalse(EventNotificationService.isCatchUpBatch(batchWithTs(
            now - 7200, now - 7200, now - 7200)));
        // Missing ts (0) never counts as stale.
        assertFalse(EventNotificationService.isCatchUpBatch(batchWithTs(0, 0, 0, 0)));
    }

    private java.util.List<EventNotification> batchWithTs(long... timestamps) {
        java.util.List<EventNotification> batch = new java.util.ArrayList<>();
        for (long ts : timestamps) {
            batch.add(gson.fromJson(
                "{\"id\":\"x" + ts + "\",\"type\":\"event_completion\",\"ts\":" + ts + "}",
                EventNotification.class));
        }
        return batch;
    }

    @Test
    public void parsesLongPollMarker() {
        // Long-poll servers stamp long_poll=true on wait-requests; legacy
        // servers omit it entirely, which must parse as null (not false).
        DropTrackerApi.NotificationsResponse held = gson.fromJson(
            "{\"notifications\":[],\"active_event\":true,\"long_poll\":true}",
            DropTrackerApi.NotificationsResponse.class);
        assertEquals(Boolean.TRUE, held.longPoll);

        DropTrackerApi.NotificationsResponse legacy = gson.fromJson(
            "{\"notifications\":[],\"active_event\":true}",
            DropTrackerApi.NotificationsResponse.class);
        assertNull(legacy.longPoll);
    }

    @Test
    public void unknownFieldsAndTypesParseWithoutError() {
        // Forward compatibility: a future type with unknown fields must still
        // deserialize (the service then drops it by type).
        String json = "{\"notifications\":[{"
            + "\"id\":\"x\",\"type\":\"event_meteor_strike\",\"ts\":1,"
            + "\"data\":{\"brand_new_field\":{\"nested\":true}}}],\"active_event\":false}";
        DropTrackerApi.NotificationsResponse response =
            gson.fromJson(json, DropTrackerApi.NotificationsResponse.class);
        assertEquals("event_meteor_strike", response.notifications.get(0).getType());
        assertNull(response.notifications.get(0).getEvent());
    }

    @Test
    public void parsesEventState() {
        // Shape captured from the live /event_state endpoint (board_game +
        // bingo entries, all three focus sources).
        String json = "{\"events\":[{"
            + "\"event\":{\"id\":18,\"name\":\"Droptracker Board\",\"kind\":\"board_game\","
            + "\"has_bingo\":false,\"ends_at\":\"2026-07-30T13:07:00\"},"
            + "\"team\":{\"id\":36,\"name\":\"Jimmy Baller\",\"color\":null,"
            + "\"icon_item_id\":null,\"icon_url\":null,\"score\":0,\"rank\":1,\"team_count\":2},"
            + "\"focus_task\":{\"id\":218,\"label\":\"Bandos Chestplate\",\"have\":0,\"need\":1,"
            + "\"icon_item_id\":11832,\"icon_url\":null,\"source\":\"board\"},"
            + "\"board_status\":\"active\",\"tasks_completed\":1,\"tasks_total\":83,"
            + "\"board\":{\"available\":true,\"team_id\":36},"
            + "\"standings\":[{\"team_id\":36,\"name\":\"Jimmy Baller\",\"score\":0,\"rank\":1,\"color\":null},"
            + "{\"team_id\":37,\"name\":\"Steve-O sickos\",\"score\":0,\"rank\":2,\"color\":null}]},"
            + "{\"event\":{\"id\":19,\"name\":\"Bingo\",\"kind\":\"bingo\",\"has_bingo\":true,\"ends_at\":null},"
            + "\"team\":{\"id\":38,\"name\":\"T\",\"color\":\"#cc4444\",\"icon_item_id\":null,"
            + "\"icon_url\":null,\"score\":5,\"rank\":2,\"team_count\":2},"
            + "\"focus_task\":{\"id\":127,\"label\":\"1,000,000 Magic XP\",\"have\":297656,"
            + "\"need\":1000000,\"icon_item_id\":null,"
            + "\"icon_url\":\"https://www.droptracker.io/img/metrics/magic.png\",\"source\":\"team_progress\"},"
            + "\"board_status\":null,\"tasks_completed\":5,\"tasks_total\":24,"
            + "\"board\":{\"available\":true,\"team_id\":38},\"standings\":[],"
            + "\"tasks\":[{\"id\":127,\"label\":\"1,000,000 Magic XP\",\"type\":\"xp_target\","
            + "\"points\":5,\"have\":297656,\"need\":1000000,\"completed\":false,"
            + "\"icon_item_id\":null,\"icon_url\":\"https://www.droptracker.io/img/metrics/magic.png\","
            + "\"badge\":\"XP TARGET\",\"value\":\"1.00M XP\","
            + "\"description\":\"Gain 1.00M Magic XP as a team.\",\"requirements\":[]},"
            + "{\"id\":128,\"label\":\"Point hunt\",\"type\":\"item_collection\",\"points\":10,"
            + "\"have\":25,\"need\":50,\"completed\":false,\"icon_item_id\":20997,\"icon_url\":null,"
            + "\"badge\":\"POINTS\",\"value\":\"50 pts\","
            + "\"description\":\"Earn 50 points. Each listed item awards its own point value.\","
            + "\"requirements\":[{\"name\":\"Twisted bow\",\"points\":25},"
            + "{\"name\":\"Dragon claws\",\"quantity\":2,\"points\":10,\"obtained\":true}]}],"
            + "\"members\":[{\"player_id\":1,\"name\":\"joelhalen\"},"
            + "{\"player_id\":3422,\"name\":\"Ra ine\"}],\"members_total\":69}]}";
        EventState state = gson.fromJson(json, EventState.class);
        assertEquals(2, state.getEvents().size());

        EventState.Entry board = state.getEvents().get(0);
        assertEquals("board_game", board.getEvent().getKind());
        assertEquals("active", board.getBoardStatus());
        assertEquals("board", board.getFocusTask().getSource());
        assertEquals(Integer.valueOf(11832), board.getFocusTask().getIconItemId());
        assertEquals(2, board.getStandings().size());
        assertTrue(board.getBoard().isAvailable());

        EventState.Entry bingo = state.getEvents().get(1);
        assertNull(bingo.getBoardStatus());
        assertEquals(297656L, bingo.getFocusTask().getHave());
        assertEquals("https://www.droptracker.io/img/metrics/magic.png",
            bingo.getFocusTask().getIconUrl());
        assertEquals("#cc4444", bingo.getTeam().getColor());

        // P3 additions: full task list + roster (absent on older servers).
        assertNull(board.getTasks());
        assertNotNull(bingo.getTasks());
        assertEquals(2, bingo.getTasks().size());
        EventState.TaskInfo xp = bingo.getTasks().get(0);
        assertEquals("XP TARGET", xp.getBadge());
        assertEquals("Gain 1.00M Magic XP as a team.", xp.getDescription());
        assertFalse(xp.isCompleted());
        EventState.TaskInfo points = bingo.getTasks().get(1);
        assertEquals(2, points.getRequirements().size());
        assertEquals("Twisted bow", points.getRequirements().get(0).getName());
        assertEquals(Integer.valueOf(25), points.getRequirements().get(0).getPoints());
        assertEquals(Integer.valueOf(2), points.getRequirements().get(1).getQuantity());
        assertNull(points.getRequirements().get(0).getObtained());
        assertEquals(Boolean.TRUE, points.getRequirements().get(1).getObtained());
        assertEquals(2, bingo.getMembers().size());
        assertEquals("Ra ine", bingo.getMembers().get(1).getName());
        assertEquals(69, bingo.getMembersTotal());
    }

    @Test
    public void cleanStripsTagsAndCaps() {
        assertEquals("hello world",
            EventNotificationService.clean("<col=ff0000>hello</col> world"));
        assertNull(EventNotificationService.clean("   "));
        assertNull(EventNotificationService.clean(null));
        String longText = new String(new char[300]).replace('\0', 'a');
        String cleaned = EventNotificationService.clean(longText);
        assertNotNull(cleaned);
        assertTrue(cleaned.length() <= 121);
        assertTrue(cleaned.endsWith("…"));
    }

    @Test
    public void remoteImageUrlAllowlist() {
        assertTrue(io.droptracker.util.RemoteImageCache.isAllowedUrl(
            "https://www.droptracker.io/img/metrics/magic.png"));
        assertTrue(io.droptracker.util.RemoteImageCache.isAllowedUrl(
            "https://api.droptracker.io/events/19/board.png?team_id=38"));
        assertFalse(io.droptracker.util.RemoteImageCache.isAllowedUrl(
            "https://evil.example.com/x.png"));
        assertFalse(io.droptracker.util.RemoteImageCache.isAllowedUrl(
            "http://www.droptracker.io/img/x.png")); // https only
        assertFalse(io.droptracker.util.RemoteImageCache.isAllowedUrl(
            "https://droptracker.io.evil.com/x.png"));
        assertFalse(io.droptracker.util.RemoteImageCache.isAllowedUrl(null));
    }
}
