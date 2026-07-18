package io.droptracker.service;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.EventDisplayMode;
import io.droptracker.models.api.EventNotification;
import io.droptracker.models.api.EventState;
import io.droptracker.util.ChatMessageUtil;
import io.droptracker.util.DebugLogger;
import io.droptracker.util.ValueFormat;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;
import okhttp3.Call;

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
 * Long-polls GET /notifications?wait={@value #LONG_POLL_WAIT_SECONDS} while
 * the server reports a live event tracking this player (the
 * {@code active_event} flag in the group configs / notifications response):
 * the server holds an empty-inbox request open until a notification lands,
 * so delivery is near-immediate, and each completed request immediately
 * re-issues the next. Servers that predate the {@code wait} param (no
 * {@code long_poll: true} in the response) fall back to a fixed
 * {@value #POLL_INTERVAL_SECONDS}s poll. The held call runs on OkHttp's own
 * dispatcher via enqueue — the shared client executor is only ever used to
 * schedule the next cycle, never blocked through a hold. Each typed envelope
 * dispatches through a hardcoded renderer registry (unknown types are
 * dropped silently — the forward-compatibility contract), and the service
 * keeps the {@code /event_state} snapshot the HUD and Events tab render.
 *
 * Stacking rules: envelopes are processed per poll as one batch, grouped by
 * event — at most one pop-up per event per batch, chat collapses beyond
 * {@value #MAX_CHAT_LINES_PER_EVENT} lines per event, a seen-id LRU guards
 * against replays, and at most one state refresh runs per batch.
 */
@Slf4j
@Singleton
public class EventNotificationService {
    /** Fallback poll cadence for legacy servers that ignore the wait param. */
    static final int POLL_INTERVAL_SECONDS = 10;
    /** Requested server-side hold per long-poll (the server clamps to its cap). */
    static final int LONG_POLL_WAIT_SECONDS = 25;
    /** Pause between held polls so a busy inbox can't turn into a hot loop. */
    private static final long REISSUE_DELAY_MS = 250;
    /**
     * An allegedly-held poll that returns empty this fast wasn't actually
     * held (misbehaving proxy/server); use the legacy cadence for that cycle.
     */
    private static final long SUSPICIOUS_FAST_EMPTY_MS = 1500;
    private static final long MAX_FAILURE_BACKOFF_MS = 60_000L;
    private static final int SEEN_IDS_MAX = 200;
    /**
     * Catch-up digest gate: the session's first non-empty batch collapses
     * into a "while you were away" summary instead of a message flood, when
     * it is at least this big and provably stale (an envelope older than
     * {@link #CATCHUP_MIN_AGE_SECONDS} — inbox entries survive 24h offline).
     */
    private static final int CATCHUP_MIN_ENVELOPES = 4;
    private static final long CATCHUP_MIN_AGE_SECONDS = 600;
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

    /** Next scheduled poll cycle; guarded by {@code this}. */
    private ScheduledFuture<?> pollTask;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Set while a cycle (including its in-flight HTTP call) owns the chain. */
    private final AtomicBoolean cycleInFlight = new AtomicBoolean(false);
    @Nullable
    private volatile Call inFlightCall;
    /** Only mutated by the single in-flight cycle chain. */
    private volatile int consecutiveFailures = 0;
    /** Cleared when a wait-request comes back without long_poll=true. */
    private volatile boolean serverSupportsLongPoll = true;
    /** True until the session's first non-empty batch (catch-up candidate). */
    private volatile boolean firstBatchOfSession = true;

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

    /**
     * Stamped by EventHudOverlay each frame it paints. While fresh, the HUD
     * renders pop-ups as nudges anchored beneath itself and the stand-alone
     * toast overlay stays quiet — exactly one owner draws the queue, and the
     * user only ever positions the HUD.
     */
    private volatile long hudRenderedAtMs = 0;

    public void markHudRendered() {
        hudRenderedAtMs = System.currentTimeMillis();
    }

    /** True while the HUD painted within the last second. */
    public boolean hudOwnsToasts() {
        return System.currentTimeMillis() - hudRenderedAtMs < 1000;
    }

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
        if (!running.compareAndSet(false, true)) {
            return;
        }
        serverSupportsLongPoll = true;
        consecutiveFailures = 0;
        firstBatchOfSession = true;
        scheduleNext(POLL_INTERVAL_SECONDS * 1000L);
    }

    public void stop() {
        running.set(false);
        synchronized (this) {
            if (pollTask != null) {
                pollTask.cancel(false);
                pollTask = null;
            }
        }
        Call call = inFlightCall;
        if (call != null) {
            call.cancel();
        }
        toasts.clear();
        eventState = null;
        eventStateAtMs = 0;
    }

    private boolean enabled() {
        return config.useApi() && config.eventNotifications();
    }

    private void scheduleNext(long delayMs) {
        if (!running.get()) {
            return;
        }
        synchronized (this) {
            if (!running.get()) {
                return;
            }
            pollTask = executor.schedule(this::runCycleSafely, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void runCycleSafely() {
        try {
            runCycle();
        } catch (Exception e) {
            log.debug("event notification cycle failed: {}", e.getMessage());
            cycleInFlight.set(false);
            scheduleNext(POLL_INTERVAL_SECONDS * 1000L);
        }
    }

    /**
     * One poll cycle. Runs briefly on the shared executor: it only picks the
     * wait mode and enqueues the HTTP call; the OkHttp callback processes the
     * response and schedules the next cycle. Exactly one cycle owns the chain
     * at a time ({@link #cycleInFlight}); the flag is always cleared before
     * the owner schedules its successor, so the chain cannot strand itself.
     */
    private void runCycle() {
        if (!running.get() || !cycleInFlight.compareAndSet(false, true)) {
            return;
        }
        boolean handedOff = false;
        try {
            if (!enabled()) {
                scheduleNext(IDLE_RECHECK_SECONDS * 1000L);
                return;
            }
            final String playerName = client.getLocalPlayer() != null
                ? client.getLocalPlayer().getName() : null;
            final long accountHash = client.getAccountHash();
            if (playerName == null || accountHash == -1L) {
                scheduleNext(POLL_INTERVAL_SECONDS * 1000L);
                return;
            }
            // Idle (no live event): a plain poll checks the authoritative
            // active_event flag; long-holds are reserved for live events.
            final int waitSeconds = (api.hasActiveEvent() && serverSupportsLongPoll)
                ? LONG_POLL_WAIT_SECONDS : 0;
            Call call = api.newNotificationsCall(playerName, accountHash, waitSeconds);
            if (call == null) {
                scheduleNext(POLL_INTERVAL_SECONDS * 1000L);
                return;
            }
            inFlightCall = call;
            final long startedAtMs = System.currentTimeMillis();
            try {
                call.enqueue(new okhttp3.Callback() {
                    @Override
                    public void onFailure(okhttp3.Call c, java.io.IOException e) {
                        inFlightCall = null;
                        cycleInFlight.set(false);
                        if (!running.get() || c.isCanceled()) {
                            return;
                        }
                        consecutiveFailures++;
                        log.debug("/notifications poll failed: {}", e.getMessage());
                        scheduleNext(failureBackoffMs());
                    }

                    @Override
                    public void onResponse(okhttp3.Call c, okhttp3.Response response) {
                        inFlightCall = null;
                        long nextDelayMs;
                        try {
                            DropTrackerApi.NotificationsResponse parsed;
                            try (okhttp3.Response r = response) {
                                parsed = api.parseNotificationsResponse(r);
                            }
                            if (parsed == null) {
                                consecutiveFailures++;
                                nextDelayMs = failureBackoffMs();
                            } else {
                                consecutiveFailures = 0;
                                handleResponse(parsed, playerName, accountHash);
                                nextDelayMs = nextDelayMs(parsed, waitSeconds,
                                    System.currentTimeMillis() - startedAtMs);
                            }
                        } catch (Exception e) {
                            log.debug("/notifications processing failed: {}", e.getMessage());
                            nextDelayMs = POLL_INTERVAL_SECONDS * 1000L;
                        } finally {
                            cycleInFlight.set(false);
                        }
                        scheduleNext(nextDelayMs);
                    }
                });
                handedOff = true;
            } catch (Exception e) {
                inFlightCall = null;
                log.debug("/notifications enqueue failed: {}", e.getMessage());
                scheduleNext(POLL_INTERVAL_SECONDS * 1000L);
            }
        } finally {
            if (!handedOff) {
                cycleInFlight.set(false);
            }
        }
    }

    /** Runs on the OkHttp callback thread (off-EDT, off-client-thread). */
    private void handleResponse(DropTrackerApi.NotificationsResponse response,
                                String playerName, long accountHash) {
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
            boolean catchUp = firstBatchOfSession && isCatchUpBatch(fresh);
            firstBatchOfSession = false;
            if (catchUp) {
                processCatchUpBatch(fresh);
            } else {
                processBatch(fresh);
            }
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
    }

    /** Delay before the next cycle, from the just-finished poll's outcome. */
    private long nextDelayMs(DropTrackerApi.NotificationsResponse response,
                             int waitRequestedSeconds, long elapsedMs) {
        boolean active = Boolean.TRUE.equals(response.activeEvent) || api.hasActiveEvent();
        if (!active) {
            return IDLE_RECHECK_SECONDS * 1000L;
        }
        if (waitRequestedSeconds > 0) {
            if (!Boolean.TRUE.equals(response.longPoll)) {
                // Legacy server: the wait param was ignored. Stop asking and
                // poll on the fixed cadence for the rest of the session.
                serverSupportsLongPoll = false;
                DebugLogger.log("[EventNotifications] server lacks long-poll; using fixed cadence");
                return POLL_INTERVAL_SECONDS * 1000L;
            }
            boolean empty = response.notifications == null || response.notifications.isEmpty();
            if (empty && elapsedMs < SUSPICIOUS_FAST_EMPTY_MS) {
                return POLL_INTERVAL_SECONDS * 1000L;
            }
            return REISSUE_DELAY_MS;
        }
        // Plain poll while an event is live (idle flip or legacy mode).
        return serverSupportsLongPoll ? REISSUE_DELAY_MS : POLL_INTERVAL_SECONDS * 1000L;
    }

    private long failureBackoffMs() {
        int failures = Math.max(1, Math.min(consecutiveFailures, 5));
        return Math.min(MAX_FAILURE_BACKOFF_MS, 5000L * (1L << (failures - 1)));
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
        String eventName = groupEventName(group);
        String teamName = teamNameFor(groupEventId(group));

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
                sendLine(eventName, teamName,
                    "... and " + (chatLines.size() - MAX_CHAT_LINES_PER_EVENT) + " more event updates.");
                break;
            }
            sendLine(eventName, teamName, line);
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

    /** First event name carried by the group's envelopes, cleaned; null for
     *  event-less groups (submission notices), which keep the default tag. */
    @Nullable
    private static String groupEventName(List<EventNotification> group) {
        for (EventNotification n : group) {
            if (n.getEvent() != null && n.getEvent().getName() != null) {
                return clean(n.getEvent().getName());
            }
        }
        return null;
    }

    @Nullable
    private static Integer groupEventId(List<EventNotification> group) {
        for (EventNotification n : group) {
            if (n.getEvent() != null && n.getEvent().getId() != null) {
                return n.getEvent().getId();
            }
        }
        return null;
    }

    /** The player's own team in this event, from the last state snapshot
     *  (null before the first snapshot — the prefix then omits the team). */
    @Nullable
    private String teamNameFor(@Nullable Integer eventId) {
        if (eventId == null) {
            return null;
        }
        EventState state = eventState;
        if (state == null || state.getEvents() == null) {
            return null;
        }
        for (EventState.Entry entry : state.getEvents()) {
            if (entry.getEvent() != null && entry.getEvent().getId() == eventId
                    && entry.getTeam() != null) {
                return clean(entry.getTeam().getName());
            }
        }
        return null;
    }

    /** "[Event Name] (Team name): line" when the event is known, else the
     *  default [DropTracker] tag (submission notices, event-less groups). */
    private void sendLine(@Nullable String eventName, @Nullable String teamName, String line) {
        if (eventName != null) {
            chatMessageUtil.sendEventChatMessage(eventName, teamName, line);
        } else {
            chatMessageUtil.sendChatMessage(line);
        }
    }

    /* ===================== login catch-up digest ===================== */

    /**
     * A first batch big and stale enough that the player was clearly away:
     * summarize instead of replaying each envelope ("and 30 more..." spam).
     */
    static boolean isCatchUpBatch(List<EventNotification> batch) {
        if (batch.size() < CATCHUP_MIN_ENVELOPES) {
            return false;
        }
        long staleBefore = System.currentTimeMillis() / 1000L - CATCHUP_MIN_AGE_SECONDS;
        for (EventNotification n : batch) {
            if (n.getTs() > 0 && n.getTs() < staleBefore) {
                return true;
            }
        }
        return false;
    }

    /** Digest the backlog per event; event-less notices render normally. */
    private void processCatchUpBatch(List<EventNotification> batch) {
        Map<Integer, List<EventNotification>> byEvent = new LinkedHashMap<>();
        for (EventNotification n : batch) {
            int eventId = n.getEvent() != null && n.getEvent().getId() != null
                ? n.getEvent().getId() : 0;
            byEvent.computeIfAbsent(eventId, k -> new ArrayList<>()).add(n);
        }
        for (Map.Entry<Integer, List<EventNotification>> group : byEvent.entrySet()) {
            if (group.getKey() == 0) {
                renderEventGroup(group.getValue());
            } else {
                summarizeEventGroup(group.getValue());
            }
        }
    }

    /**
     * One event's backlog as a short digest: a tally line ("While you were
     * away: 4 tasks completed (+23 pts), 2 bingo lines..."), the still-true
     * facts (event started/ended, current leader), and the one actionable
     * item (a pending dice roll). At most four lines and one pop-up,
     * regardless of backlog size.
     */
    private void summarizeEventGroup(List<EventNotification> group) {
        String eventName = groupEventName(group);
        String teamName = teamNameFor(groupEventId(group));

        int completions = 0;
        long completionPts = 0;
        int bingoLines = 0;
        long bonusPts = 0;
        boolean blackout = false;
        int boardTurns = 0;
        Set<String> progressedTasks = new LinkedHashSet<>();
        String leadTeam = null;
        Integer leadScore = null;
        long leadTs = Long.MIN_VALUE;
        boolean started = false;
        boolean ended = false;
        boolean rollPrompt = false;
        Integer toastIcon = null;

        for (EventNotification n : group) {
            EventNotification.Data data = n.getData() != null ? n.getData() : new EventNotification.Data();
            switch (n.getType()) {
                case "event_completion":
                    completions++;
                    if (data.getPoints() != null) {
                        completionPts += data.getPoints();
                    }
                    if (toastIcon == null) {
                        toastIcon = data.getIconItemId();
                    }
                    break;
                case "event_task_progress":
                    progressedTasks.add(data.getTaskLabel() != null ? data.getTaskLabel() : "?");
                    break;
                case "event_line":
                    bingoLines++;
                    if (data.getBonusPoints() != null) {
                        bonusPts += data.getBonusPoints();
                    }
                    break;
                case "event_blackout":
                    blackout = true;
                    if (data.getBonusPoints() != null) {
                        bonusPts += data.getBonusPoints();
                    }
                    break;
                case "event_lead_change":
                    if (n.getTs() >= leadTs) {
                        leadTs = n.getTs();
                        leadTeam = clean(data.getTeamName());
                        leadScore = data.getTeamScore();
                    }
                    break;
                case "event_board_turn":
                    boardTurns++;
                    break;
                case "event_board_roll_prompt":
                    rollPrompt = true;
                    break;
                case "event_started":
                    started = true;
                    break;
                case "event_ended":
                    ended = true;
                    break;
                default:
                    break;
            }
        }

        List<String> parts = new ArrayList<>();
        if (completions > 0) {
            parts.add(plural(completions, "task") + " completed"
                + (completionPts > 0 ? " (+" + ValueFormat.abbrev(completionPts) + " pts)" : ""));
        }
        if (bingoLines > 0) {
            parts.add(plural(bingoLines, "bingo line")
                + (bonusPts > 0 && !blackout ? " (+" + ValueFormat.abbrev(bonusPts) + " pts)" : ""));
        }
        if (blackout) {
            parts.add("a board blackout"
                + (bonusPts > 0 ? " (+" + ValueFormat.abbrev(bonusPts) + " pts)" : ""));
        }
        if (boardTurns > 0) {
            parts.add(plural(boardTurns, "dice roll"));
        }
        if (!progressedTasks.isEmpty() && config.eventTaskProgressNotifications()) {
            parts.add("progress on " + plural(progressedTasks.size(), "task"));
        }

        List<String> lines = new ArrayList<>();
        if (started) {
            lines.add("The event started while you were away!");
        }
        if (!parts.isEmpty()) {
            lines.add("While you were away: " + String.join(", ", parts) + ".");
        }
        if (leadTeam != null) {
            lines.add(leadTeam + " now leads"
                + (leadScore != null ? " (" + ValueFormat.abbrev(leadScore) + " pts)" : "") + ".");
        }
        if (ended) {
            lines.add("The event has ended.");
        } else if (rollPrompt) {
            lines.add("Task complete — your team can roll the dice!");
        }
        if (lines.isEmpty()) {
            return;
        }
        for (String line : lines) {
            sendLine(eventName, teamName, line);
        }

        if (config.eventDisplayMode().popupsEnabled()) {
            String body = !parts.isEmpty()
                ? "While you were away: " + String.join(", ", parts) + "."
                : lines.get(0);
            toasts.addLast(new Toast("While you were away", body, toastIcon,
                System.currentTimeMillis()));
            while (toasts.size() > MAX_TOASTS_QUEUED) {
                toasts.pollFirst();
            }
        }
    }

    private static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
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
                String item = clean(data.getReceivedItem());
                StringBuilder text = new StringBuilder(who);
                if (item != null) {
                    // "K0eppy received Bandos hilt, completing: Bandos set"
                    text.append(" received ").append(item).append(qtySuffix(data))
                        .append(", completing: ").append(orUnknown(task));
                } else {
                    text.append(" completed: ").append(orUnknown(task));
                }
                if (data.getPoints() != null && data.getPoints() > 0) {
                    text.append(" (+").append(ValueFormat.abbrev(data.getPoints())).append(" pts)");
                }
                return new Rendered("Task complete!", text.toString(), data.getIconItemId(), true, true);
            }
            case "event_task_progress": {
                if (!config.eventTaskProgressNotifications()) {
                    return null; // the client-side mute switch for the chattiest type
                }
                String who = player != null ? player : "A teammate";
                String item = clean(data.getReceivedItem());
                String progress = data.getProgress() != null && data.getTarget() != null
                    ? " (" + ValueFormat.abbrev(data.getProgress())
                        + "/" + ValueFormat.abbrev(data.getTarget()) + ")" : "";
                // With the driving drop named: "K0eppy received Bandos hilt,
                // progressing Bandos set (2/5)"; without (XP/GP/KC ticks),
                // the compact form.
                String text = item != null
                    ? who + " received " + item + qtySuffix(data)
                        + ", progressing " + orUnknown(task) + progress
                    : who + " progressed " + orUnknown(task) + progress;
                return new Rendered("Task progress", text,
                    data.getIconItemId(), true, true);
            }
            case "event_lead_change": {
                String leader = team != null ? team : "A team";
                String score = data.getTeamScore() != null
                    ? " (" + ValueFormat.abbrev(data.getTeamScore()) + " pts)" : "";
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
                    ? " (+" + ValueFormat.abbrev(data.getBonusPoints()) + " pts)" : "";
                return new Rendered("Bingo line!", who + " completed a line" + bonus + "!",
                    null, true, true);
            }
            case "event_blackout": {
                String who = team != null ? team : "Your team";
                String bonus = data.getBonusPoints() != null
                    ? " (+" + ValueFormat.abbrev(data.getBonusPoints()) + " pts)" : "";
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
                            ? " (+" + ValueFormat.abbrev(data.getCoinsAwarded()) + " coins)" : ""),
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

    /** " ×3" when a real item stack drove the update; never for point
     *  credits (points_based), whose quantities are not item counts. */
    private static String qtySuffix(EventNotification.Data data) {
        if (Boolean.TRUE.equals(data.getPointsBased())) {
            return "";
        }
        Integer qty = data.getReceivedQty();
        return qty != null && qty > 1 ? " ×" + qty : "";
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
