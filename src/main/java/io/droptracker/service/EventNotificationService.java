package io.droptracker.service;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.EventDisplayMode;
import io.droptracker.models.api.EventNotification;
import io.droptracker.models.api.EventState;
import io.droptracker.util.ChatMessageUtil;
import io.droptracker.util.DebugLogger;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-game event notifications + HUD state (EVENT_PLUGIN_NOTIFICATIONS_PLAN P2).
 *
 * Polls GET /notifications every {@value #POLL_INTERVAL_SECONDS}s while the
 * server reports a live event tracking this player (the {@code active_event}
 * flag in the group configs / notifications response), dispatches each typed
 * envelope through a hardcoded renderer registry (unknown types are dropped
 * silently — the forward-compatibility contract), and keeps the
 * {@code /event_state} snapshot the HUD and Events tab render.
 *
 * Stacking rules: envelopes are processed per poll as one batch, grouped by
 * event — at most one pop-up per event per batch, chat collapses beyond
 * {@value #MAX_CHAT_LINES_PER_EVENT} lines per event, a seen-id LRU guards
 * against replays, and at most one state refresh runs per batch.
 */
@Slf4j
@Singleton
public class EventNotificationService {
    static final int POLL_INTERVAL_SECONDS = 10;
    private static final int SEEN_IDS_MAX = 200;
    private static final int MAX_CHAT_LINES_PER_EVENT = 3;
    private static final int MAX_TOASTS_QUEUED = 6;
    /** Re-check the active_event flag this often when idle (no live event). */
    private static final int IDLE_RECHECK_SECONDS = 60;
    private static final int MAX_TEXT_LENGTH = 120;

    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final ChatMessageUtil chatMessageUtil;
    private final Client client;
    private final ScheduledExecutorService executor;
    private final ConfigManager configManager;

    private ScheduledFuture<?> pollTask;
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private int idleTicks = 0;

    /** LRU of processed envelope ids (replay guard). */
    private final Map<String, Boolean> seenIds =
        Collections.synchronizedMap(new LinkedHashMap<String, Boolean>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > SEEN_IDS_MAX;
            }
        });

    /** Toasts pending display; consumed by EventToastOverlay. */
    @Getter
    private final ConcurrentLinkedDeque<Toast> toasts = new ConcurrentLinkedDeque<>();

    /** Latest /event_state snapshot (HUD + Events tab). */
    @Getter
    @Nullable
    private volatile EventState eventState;
    private volatile long eventStateAtMs = 0;

    /** Invoked (off-EDT) whenever a fresh event state lands. */
    @Setter
    @Nullable
    private Runnable onStateUpdated;

    @Inject
    public EventNotificationService(DropTrackerConfig config, DropTrackerApi api,
                                    ChatMessageUtil chatMessageUtil, Client client,
                                    ScheduledExecutorService executor,
                                    ConfigManager configManager) {
        this.config = config;
        this.api = api;
        this.chatMessageUtil = chatMessageUtil;
        this.client = client;
        this.executor = executor;
        this.configManager = configManager;
    }

    /* ===================== lifecycle ===================== */

    public void start() {
        if (pollTask != null) {
            return;
        }
        pollTask = executor.scheduleWithFixedDelay(
            this::pollSafely, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
        toasts.clear();
        eventState = null;
        eventStateAtMs = 0;
    }

    private boolean enabled() {
        return config.useApi() && config.eventNotifications();
    }

    private void pollSafely() {
        try {
            poll();
        } catch (Exception e) {
            log.debug("event notification poll failed: {}", e.getMessage());
        }
    }

    private void poll() {
        if (!enabled() || !polling.compareAndSet(false, true)) {
            return;
        }
        try {
            String playerName = client.getLocalPlayer() != null
                ? client.getLocalPlayer().getName() : null;
            long accountHash = client.getAccountHash();
            if (playerName == null || accountHash == -1L) {
                return;
            }
            // Idle gate: without a live event there is nothing to poll — the
            // group-config refresh (or a slow recheck here) flips us active.
            if (!api.hasActiveEvent()) {
                idleTicks++;
                if (idleTicks * POLL_INTERVAL_SECONDS < IDLE_RECHECK_SECONDS) {
                    return;
                }
            }
            idleTicks = 0;

            DropTrackerApi.NotificationsResponse response =
                api.fetchNotifications(playerName, accountHash);
            if (response == null) {
                return;
            }
            List<EventNotification> fresh = new ArrayList<>();
            if (response.notifications != null) {
                for (EventNotification n : response.notifications) {
                    if (n == null || n.getType() == null) {
                        continue;
                    }
                    if (n.getId() != null && seenIds.put(n.getId(), Boolean.TRUE) != null) {
                        continue; // replay
                    }
                    fresh.add(n);
                }
            }
            if (!fresh.isEmpty()) {
                DebugLogger.log("[EventNotifications] batch size=" + fresh.size());
                processBatch(fresh);
            }
            boolean stateStale = (System.currentTimeMillis() - eventStateAtMs)
                > TimeUnit.MINUTES.toMillis(3);
            if ((!fresh.isEmpty() || (eventState == null || stateStale))
                    && Boolean.TRUE.equals(response.activeEvent)) {
                refreshEventState(playerName, accountHash);
            }
            if (Boolean.FALSE.equals(response.activeEvent) && eventState != null) {
                // Event(s) ended: clear the HUD snapshot.
                eventState = null;
                notifyStateUpdated();
            }
        } finally {
            polling.set(false);
        }
    }

    /** Fetch the state snapshot now (panel open / manual refresh). Off-EDT. */
    public void refreshEventStateNow() {
        String playerName = client.getLocalPlayer() != null
            ? client.getLocalPlayer().getName() : null;
        long accountHash = client.getAccountHash();
        if (playerName == null || accountHash == -1L || !enabled()) {
            return;
        }
        refreshEventState(playerName, accountHash);
    }

    private void refreshEventState(String playerName, long accountHash) {
        EventState state = api.fetchEventState(playerName, accountHash);
        if (state != null) {
            eventState = state;
            eventStateAtMs = System.currentTimeMillis();
            notifyStateUpdated();
        }
    }

    private void notifyStateUpdated() {
        Runnable callback = onStateUpdated;
        if (callback != null) {
            try {
                callback.run();
            } catch (Exception e) {
                log.debug("state-updated callback failed: {}", e.getMessage());
            }
        }
    }

    /** The state entry the HUD shows: the pinned event, else the first. */
    @Nullable
    public EventState.Entry hudEntry() {
        EventState state = eventState;
        if (state == null || state.getEvents() == null || state.getEvents().isEmpty()) {
            return null;
        }
        int pinned = config.pinnedEventId();
        if (pinned > 0) {
            for (EventState.Entry entry : state.getEvents()) {
                if (entry.getEvent() != null && entry.getEvent().getId() == pinned) {
                    return entry;
                }
            }
        }
        return state.getEvents().get(0);
    }

    /* ===================== tracked-task override ===================== */

    private String trackedTaskKey(int eventId) {
        return "trackedTask_" + eventId;
    }

    /** The user's manually tracked task for an event, or 0 = server decides. */
    public int trackedTaskId(int eventId) {
        try {
            Integer stored = configManager.getConfiguration(
                DropTrackerConfig.GROUP, trackedTaskKey(eventId), Integer.class);
            return stored != null ? stored : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Track a task on the HUD for this event; taskId <= 0 reverts to auto. */
    public void setTrackedTask(int eventId, int taskId) {
        if (taskId <= 0) {
            configManager.unsetConfiguration(DropTrackerConfig.GROUP, trackedTaskKey(eventId));
        } else {
            configManager.setConfiguration(DropTrackerConfig.GROUP, trackedTaskKey(eventId), taskId);
        }
    }

    /**
     * The task the HUD and the Events tab headline for an entry: the user's
     * tracked pick while it exists and is incomplete, else the server's focus
     * task ("the server decides"). Null when neither applies.
     */
    @Nullable
    public DisplayTask displayTask(EventState.Entry entry) {
        if (entry == null || entry.getEvent() == null) {
            return null;
        }
        int tracked = trackedTaskId(entry.getEvent().getId());
        // Board games have no free task choice: the current tile is the task.
        boolean pickable = !"board_game".equals(entry.getEvent().getKind());
        if (tracked > 0 && pickable && entry.getTasks() != null) {
            for (EventState.TaskInfo task : entry.getTasks()) {
                if (task.getId() == tracked && !task.isCompleted()) {
                    return new DisplayTask(task.getId(), task.getLabel(),
                        task.getHave(), task.getNeed(),
                        task.getIconItemId(), task.getIconUrl(), true);
                }
            }
        }
        EventState.FocusTask focus = entry.getFocusTask();
        if (focus == null) {
            return null;
        }
        return new DisplayTask(focus.getId(), focus.getLabel(),
            focus.getHave(), focus.getNeed(),
            focus.getIconItemId(), focus.getIconUrl(), false);
    }

    /** Unified view of the headlined task, from either source. */
    public static class DisplayTask {
        public final int id;
        public final String label;
        public final long have;
        public final long need;
        @Nullable
        public final Integer iconItemId;
        @Nullable
        public final String iconUrl;
        /** true = the user's manual pick; false = server-chosen focus. */
        public final boolean tracked;

        DisplayTask(int id, String label, long have, long need,
                    @Nullable Integer iconItemId, @Nullable String iconUrl,
                    boolean tracked) {
            this.id = id;
            this.label = label;
            this.have = have;
            this.need = need;
            this.iconItemId = iconItemId;
            this.iconUrl = iconUrl;
            this.tracked = tracked;
        }
    }

    /* ===================== batch rendering ===================== */

    private void processBatch(List<EventNotification> batch) {
        // Group per event id (0 = event-less, e.g. submission notices).
        Map<Integer, List<EventNotification>> byEvent = new LinkedHashMap<>();
        for (EventNotification n : batch) {
            int eventId = n.getEvent() != null && n.getEvent().getId() != null
                ? n.getEvent().getId() : 0;
            byEvent.computeIfAbsent(eventId, k -> new ArrayList<>()).add(n);
        }
        for (Map.Entry<Integer, List<EventNotification>> group : byEvent.entrySet()) {
            renderEventGroup(group.getValue());
        }
    }

    private void renderEventGroup(List<EventNotification> group) {
        EventDisplayMode mode = config.eventDisplayMode();
        List<String> chatLines = new ArrayList<>();
        List<Toast> groupToasts = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();

        for (EventNotification n : group) {
            Rendered rendered = render(n);
            if (rendered == null || !dedupe.add(rendered.text)) {
                continue; // unknown type, filtered, or identical line in batch
            }
            if (rendered.chatEligible) {
                chatLines.add(rendered.text);
            }
            if (rendered.toastEligible) {
                groupToasts.add(new Toast(rendered.title, rendered.text,
                    rendered.iconItemId, System.currentTimeMillis()));
            }
        }

        // Chat: everything up to the cap, then a collapse line.
        int lines = 0;
        for (String line : chatLines) {
            if (lines == MAX_CHAT_LINES_PER_EVENT && chatLines.size() > MAX_CHAT_LINES_PER_EVENT + 1) {
                chatMessageUtil.sendChatMessage(
                    "... and " + (chatLines.size() - MAX_CHAT_LINES_PER_EVENT) + " more event updates.");
                break;
            }
            chatMessageUtil.sendChatMessage(line);
            lines++;
        }

        // Pop-ups: at most ONE per event per batch — a multi-envelope batch
        // becomes a single summarizing toast.
        if (mode.popupsEnabled() && !groupToasts.isEmpty()) {
            Toast toast;
            if (groupToasts.size() == 1) {
                toast = groupToasts.get(0);
            } else {
                Toast first = groupToasts.get(0);
                toast = new Toast(first.title,
                    groupToasts.size() + " event updates — " + first.body,
                    first.iconItemId, System.currentTimeMillis());
            }
            toasts.addLast(toast);
            while (toasts.size() > MAX_TOASTS_QUEUED) {
                toasts.pollFirst();
            }
        }
    }

    /* ===================== per-type renderers ===================== */

    /** A rendered notification: local text composed from typed fields only. */
    private static class Rendered {
        final String title;
        final String text;
        final Integer iconItemId;
        final boolean chatEligible;
        final boolean toastEligible;

        Rendered(String title, String text, Integer iconItemId,
                 boolean chatEligible, boolean toastEligible) {
            this.title = title;
            this.text = text;
            this.iconItemId = iconItemId;
            this.chatEligible = chatEligible;
            this.toastEligible = toastEligible;
        }
    }

    @Nullable
    private Rendered render(EventNotification n) {
        EventNotification.Data data = n.getData();
        String eventName = n.getEvent() != null ? clean(n.getEvent().getName()) : null;
        String type = n.getType();
        if (data == null) {
            data = new EventNotification.Data();
        }
        String team = clean(data.getTeamName());
        String player = clean(data.getPlayerName());
        String task = clean(data.getTaskLabel());

        switch (type) {
            case "event_completion": {
                String who = player != null ? player : (team != null ? team : "Your team");
                StringBuilder text = new StringBuilder(who + " completed: " + orUnknown(task));
                if (data.getPoints() != null && data.getPoints() > 0) {
                    text.append(" (+").append(data.getPoints()).append(" pts)");
                }
                return new Rendered("Task complete!", text.toString(), data.getIconItemId(), true, true);
            }
            case "event_task_progress": {
                if (!config.eventTaskProgressNotifications()) {
                    return null; // the client-side mute switch for the chattiest type
                }
                String who = player != null ? player : "A teammate";
                String progress = data.getProgress() != null && data.getTarget() != null
                    ? " (" + data.getProgress() + "/" + data.getTarget() + ")" : "";
                return new Rendered("Task progress",
                    who + " progressed " + orUnknown(task) + progress,
                    data.getIconItemId(), true, true);
            }
            case "event_lead_change": {
                String leader = team != null ? team : "A team";
                String score = data.getTeamScore() != null
                    ? " (" + data.getTeamScore() + " pts)" : "";
                return new Rendered("Lead change!",
                    leader + " took the lead" + score
                        + (eventName != null ? " in " + eventName : "") + "!",
                    null, true, true);
            }
            case "event_started":
                return new Rendered("Event started",
                    (eventName != null ? eventName : "Your event") + " has started!",
                    null, true, true);
            case "event_ended":
                return new Rendered("Event ended",
                    (eventName != null ? eventName : "Your event") + " has ended.",
                    null, true, true);
            case "event_line": {
                String who = team != null ? team : "Your team";
                String bonus = data.getBonusPoints() != null
                    ? " (+" + data.getBonusPoints() + " pts)" : "";
                return new Rendered("Bingo line!", who + " completed a line" + bonus + "!",
                    null, true, true);
            }
            case "event_blackout": {
                String who = team != null ? team : "Your team";
                String bonus = data.getBonusPoints() != null
                    ? " (+" + data.getBonusPoints() + " pts)" : "";
                return new Rendered("Blackout!", who + " blacked out the board" + bonus + "!",
                    null, true, true);
            }
            case "event_board_turn": {
                String who = player != null ? player : (team != null ? team : "Your team");
                StringBuilder text = new StringBuilder(who + " rolled");
                if (data.getDiceStr() != null) {
                    text.append(" ").append(clean(data.getDiceStr()));
                }
                if (data.getTileTo() != null) {
                    text.append(" to tile ").append(data.getTileTo());
                }
                if (data.getNextTaskLabel() != null) {
                    text.append(": ").append(clean(data.getNextTaskLabel()));
                }
                return new Rendered("Board roll", text.toString(), null, true, true);
            }
            case "event_board_roll_prompt":
                return new Rendered("Roll the dice!",
                    "Task complete — your team can roll the dice!"
                        + (data.getCoinsAwarded() != null && data.getCoinsAwarded() > 0
                            ? " (+" + data.getCoinsAwarded() + " coins)" : ""),
                    null, true, true);
            case "submission_notice": {
                // Legacy server-text channel: sanitized plain chat only, gated
                // by the pre-existing receiveInGameMessages config.
                if (!config.receiveInGameMessages() || data.getMessage() == null) {
                    return null;
                }
                return new Rendered("DropTracker", clean(data.getMessage()), null, true, false);
            }
            default:
                DebugLogger.log("[EventNotifications] dropping unknown type=" + type);
                return null;
        }
    }

    private static String orUnknown(String value) {
        return value != null ? value : "a task";
    }

    /** Tag-strip + length-cap every server-supplied string before rendering. */
    @Nullable
    static String clean(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String stripped = Text.removeTags(value).replace('\u00A0', ' ').trim();
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.length() > MAX_TEXT_LENGTH
            ? stripped.substring(0, MAX_TEXT_LENGTH - 1) + "…" : stripped;
    }

    /** A transient on-screen pop-up. */
    @Getter
    public static class Toast {
        public static final long LIFETIME_MS = 6000;
        private final String title;
        private final String body;
        @Nullable
        private final Integer iconItemId;
        private final long createdAt;

        public Toast(String title, String body, @Nullable Integer iconItemId, long createdAt) {
            this.title = title;
            this.body = body;
            this.iconItemId = iconItemId;
            this.createdAt = createdAt;
        }

        public boolean expired(long now) {
            return now - createdAt > LIFETIME_MS;
        }
    }
}
