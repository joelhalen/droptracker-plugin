package io.droptracker.service;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.*;
import io.droptracker.models.api.*;
import io.droptracker.util.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;
import okhttp3.Call;

import javax.annotation.Nullable;
import javax.inject.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
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
 * event — at most ONE pop-up per batch (coalesced across events, headlined by
 * the most important update), chat collapses beyond
 * {@value #MAX_CHAT_LINES_PER_EVENT} lines per event, a seen-id LRU guards
 * against replays, and at most one state refresh runs per batch. Pop-ups are
 * additionally deduped by (event, task, type) for
 * {@value #TOAST_DEDUPE_WINDOW_MS}ms across batches, and their lifetime and
 * styling follow the envelope's {@code priority} tier.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
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
    /** Indented detail lines under the catch-up header (plus that header). */
    private static final int CATCHUP_MAX_DETAIL_LINES = 4;
    private static final int MAX_TOASTS_QUEUED = 6;
    /**
     * A pop-up about the same (event, task, type) inside this window is the
     * same update to a human — a task ticking twice in a breath (two players
     * feeding it, a re-drain) must not queue twice.
     */
    static final long TOAST_DEDUPE_WINDOW_MS = 10_000L;
    private static final int TOAST_KEYS_MAX = 32;
    /** Re-check the active_event flag this often when idle (no live event). */
    private static final int IDLE_RECHECK_SECONDS = 60;
    /** Recheck cadence for a 404 (identity not registered) — terminal-ish. */
    private static final long NOT_REGISTERED_RECHECK_MS =
        TimeUnit.MINUTES.toMillis(10);
    private static final int MAX_TEXT_LENGTH = 120;

    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final ChatMessageUtil chatMessageUtil;
    private final Client client;
    private final ScheduledExecutorService executor;
    private final ConfigManager configManager;
    private final ClanRelayService clanRelayService;

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
    /**
     * Last server-reported active_event. Authoritative over the cached group
     * config for choosing the wait mode: the config cache refreshes on
     * login/XP/submission (all throttled) and NEVER for an idle player, so
     * when an event went live the plugin used to keep sending plain polls —
     * which reissued at {@link #REISSUE_DELAY_MS} — a permanent ~4 req/s hot
     * loop exactly at event start (audit P0-2).
     */
    private volatile boolean serverReportedActiveEvent = false;
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

    /** Last shown-at per pop-up dedupe key; guarded by its own monitor. */
    private final Map<String, Long> recentToastKeys =
        new LinkedHashMap<String, Long>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > TOAST_KEYS_MAX;
            }
        };

    /** Latest /event_state snapshot (HUD + Events tab). */
    @Getter
    @Nullable
    private volatile EventState eventState;
    private volatile long eventStateAtMs = 0;

    /**
     * Invoked (off-EDT) whenever a fresh event state lands.
     *
     * A list rather than a single slot: the side panel and the clan-chat team
     * indicators both hang off this signal, and with one setter the second
     * registration silently replaced the first — the panel would simply stop
     * refreshing, with nothing in the log to say why.
     */
    private final List<Runnable> stateUpdatedListeners = new CopyOnWriteArrayList<>();

    /* ===================== lifecycle ===================== */

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        serverSupportsLongPoll = true;
        serverReportedActiveEvent = false;
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
        synchronized (recentToastKeys) {
            recentToastKeys.clear();
        }
        eventState = null;
        eventStateAtMs = 0;
    }

    private boolean enabled() {
        // The poll loop serves two consumers: event notifications and the
        // Discord→game chat bridge. Either keeps it alive.
        return config.useApi()
            && (config.eventNotifications() || clanRelayService.discordChatActive());
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
            // The server's own last report ORs in so a just-started event
            // flips us to held long-polls immediately instead of waiting for
            // the (throttled, maybe-never) config cache refresh (P0-2). The
            // chat bridge holds too — Discord lines should land in seconds.
            final boolean bridgeActive = clanRelayService.discordChatActive();
            final int waitSeconds =
                ((api.hasActiveEvent() || serverReportedActiveEvent || bridgeActive)
                    && serverSupportsLongPoll)
                ? LONG_POLL_WAIT_SECONDS : 0;
            // The clan param doubles as the bridge presence heartbeat: the
            // server fans Discord lines out only to players it has seen
            // polling with this clan recently.
            final String bridgeClan = bridgeActive
                ? clanRelayService.getCurrentClanName() : null;
            Call call = api.newNotificationsCall(playerName, accountHash, waitSeconds, bridgeClan);
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
                            final int httpCode = response.code();
                            DropTrackerApi.NotificationsResponse parsed;
                            try (okhttp3.Response r = response) {
                                parsed = api.parseNotificationsResponse(r);
                            }
                            if (parsed == null) {
                                if (httpCode == 404) {
                                    // Not a registered player: terminal for
                                    // this identity, not a server fault —
                                    // recheck rarely instead of retrying the
                                    // failure backoff forever (audit).
                                    consecutiveFailures = 0;
                                    nextDelayMs = NOT_REGISTERED_RECHECK_MS;
                                } else {
                                    consecutiveFailures++;
                                    nextDelayMs = failureBackoffMs();
                                }
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
        // Bridge lines are chat, not event state: render them immediately and
        // keep them out of the event grouping/catch-up machinery entirely.
        fresh = renderAndStripClanChat(fresh);
        if (!fresh.isEmpty()) {
            DebugLogger.log("[EventNotifications] batch size=" + fresh.size());
            boolean catchUp = firstBatchOfSession && isCatchUpBatch(fresh);
            firstBatchOfSession = false;
            renderBatch(fresh, catchUp);
        }
        if (Boolean.TRUE.equals(response.activeEvent)) {
            serverReportedActiveEvent = true;
        } else if (Boolean.FALSE.equals(response.activeEvent)) {
            serverReportedActiveEvent = false;
        }
        long sinceStateMs = System.currentTimeMillis() - eventStateAtMs;
        boolean stateStale = sinceStateMs > TimeUnit.MINUTES.toMillis(3);
        // Fresh-batch refreshes are coalesced (audit P0-14): notifications
        // fan out to whole teams, and every teammate re-fetching the heavy
        // /event_state on every batch turned one completion into N
        // compositions at once. The batch itself already carries the
        // headline info; the full snapshot follows within the cooldown.
        boolean cooledDown = sinceStateMs > TimeUnit.SECONDS.toMillis(20);
        if ((eventState == null || stateStale || (!fresh.isEmpty() && cooledDown))
                && Boolean.TRUE.equals(response.activeEvent)) {
            refreshEventState(playerName, accountHash);
        }
        if (Boolean.FALSE.equals(response.activeEvent) && eventState != null) {
            // Event(s) ended: clear the HUD snapshot.
            eventState = null;
            notifyStateUpdated();
        }
    }

    /** Age past which a bridge chat line is history, not conversation. */
    private static final long BRIDGE_LINE_MAX_AGE_SECONDS = 120;

    /** Server-side cap for Discord→game lines (clan_chat_bridge.py). */
    private static final int BRIDGE_MESSAGE_MAX_CHARS = 200;

    /**
     * Renders {@code clan_chat_message} envelopes (Discord→game bridge) and
     * {@code clan_chat_muted} ones (a muted clanmate's line) as clan chat and returns the batch without them. Stale lines
     * — an offline backlog draining on login — are silently dropped: chat
     * from twenty minutes ago is noise, exactly like real clan chat you
     * weren't online for.
     */
    private List<EventNotification> renderAndStripClanChat(List<EventNotification> batch) {
        if (batch.isEmpty()) {
            return batch;
        }
        List<EventNotification> remainder = new ArrayList<>(batch.size());
        long nowSeconds = System.currentTimeMillis() / 1000L;
        for (EventNotification n : batch) {
            boolean muted = "clan_chat_muted".equals(n.getType());
            if (!muted && !"clan_chat_message".equals(n.getType())) {
                remainder.add(n);
                continue;
            }
            // Same gate as polling for them: sync on, Discord lines wanted, and
            // still in a clan one of the player's groups bridges.
            if (!clanRelayService.discordChatActive()) {
                continue;
            }
            EventNotification.Data data = n.getData();
            String sender = data != null ? clean(data.getSender()) : null;
            // Matches the server's DISCORD_TO_GAME_MAX_CHARS (200) — the
            // generic 120 cap would re-truncate lines the backend already
            // capped, silently eating the tail of longer Discord messages.
            String message = data != null ? clean(data.getMessage(), BRIDGE_MESSAGE_MAX_CHARS) : null;
            if (sender == null || message == null) {
                continue;
            }
            if (n.getTs() > 0 && nowSeconds - n.getTs() > BRIDGE_LINE_MAX_AGE_SECONDS) {
                continue;
            }
            if (muted) {
                clanRelayService.showMutedLine(sender, message, data.getAccountType());
            } else {
                chatMessageUtil.sendDiscordClanMessage(sender, message);
            }
        }
        return remainder;
    }

    /** Delay before the next cycle, from the just-finished poll's outcome. */
    private long nextDelayMs(DropTrackerApi.NotificationsResponse response,
                             int waitRequestedSeconds, long elapsedMs) {
        boolean active = Boolean.TRUE.equals(response.activeEvent) || api.hasActiveEvent()
            || clanRelayService.discordChatActive();
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
        // Plain poll while an event is live (idle flip or legacy mode). Only
        // fast-reissue when the server itself confirmed the event — the next
        // cycle then upgrades to a HELD long-poll (serverReportedActiveEvent
        // is set). Any other plain-poll outcome paces at the fixed cadence:
        // a cache-only "active" must never spin at REISSUE_DELAY_MS (P0-2).
        boolean nextCycleWillHold =
            serverSupportsLongPoll && Boolean.TRUE.equals(response.activeEvent);
        return nextCycleWillHold ? REISSUE_DELAY_MS : POLL_INTERVAL_SECONDS * 1000L;
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

    /**
     * Item ids the server wants force-screenshotted for event proof, from the
     * latest /event_state snapshot. Empty when no event is live, the API is
     * disabled (the service never polls), or the server predates the field.
     */
    public Set<Integer> getEventScreenshotItemIds() {
        EventState state = eventState;
        List<Integer> ids = state != null ? state.getScreenshotItemIds() : null;
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<>(ids);
    }

    /** Register a listener for fresh state. Safe to call more than once. */
    public void addStateUpdatedListener(Runnable listener) {
        if (listener != null) {
            stateUpdatedListeners.add(listener);
        }
    }

    public void removeStateUpdatedListener(Runnable listener) {
        if (listener != null) {
            stateUpdatedListeners.remove(listener);
        }
    }

    private void notifyStateUpdated() {
        for (Runnable callback : stateUpdatedListeners) {
            // One listener throwing must not cost the others their update.
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
            // No live event: a ::dtpopup preview may lend the HUD a sample
            // one for a minute, so the attached styles can be tried anywhere.
            EventState.Entry preview = previewHudEntry;
            return preview != null && System.currentTimeMillis() < previewHudUntilMs
                ? preview : null;
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

    /* ===================== ::dtpopup previews ===================== */

    @Nullable
    private volatile EventState.Entry previewHudEntry;
    private volatile long previewHudUntilMs = 0;

    /** Lends the HUD a sample event until {@code untilMs}, while no real event is live. */
    public void setPreviewHudEntry(@Nullable EventState.Entry entry, long untilMs) {
        previewHudEntry = entry;
        previewHudUntilMs = untilMs;
    }

    /**
     * Renders a sample envelope through the real renderer and queues its
     * pop-up straight away: no chat line, no "important only" filter, no
     * dedupe. False when the envelope renders no pop-up (unknown type, or
     * muted by the user's settings).
     */
    public boolean previewNotification(EventNotification n, @Nullable EventPopupStyle popup,
                                       int moreCount) {
        Rendered rendered = render(n);
        if (rendered == null || !rendered.toastEligible) {
            return false;
        }
        Toast toast = rendered.toToast(System.currentTimeMillis());
        if (moreCount > 0) {
            toast = toast.withMore(moreCount);
        }
        previewToast(toast.pinned(popup, System.currentTimeMillis()));
        return true;
    }

    /** Queues a ready-made preview pop-up, bypassing every filter. */
    public void previewToast(Toast toast) {
        toasts.addLast(toast);
        while (toasts.size() > MAX_TOASTS_QUEUED) {
            toasts.pollFirst();
        }
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
     *
     * <p>A pin always wins — it is the user saying "this one" — but a server
     * focus the user hid is skipped for the next visible incomplete task, in
     * the order the panel lists them. The server stamps a focus for hours
     * after you progress a task, so without that skip the gold TRACKING box
     * keeps headlining the very tile you just filed under "N hidden".
     */
    @Nullable
    public DisplayTask displayTask(EventState.Entry entry) {
        if (entry == null || entry.getEvent() == null) {
            return null;
        }
        int eventId = entry.getEvent().getId();
        int tracked = trackedTaskId(eventId);
        // Board games have no free task choice: the current tile is the task.
        boolean pickable = !"board_game".equals(entry.getEvent().getKind());
        List<EventState.TaskInfo> tasks = entry.getTasks();
        if (tracked > 0 && pickable && tasks != null) {
            for (EventState.TaskInfo task : tasks) {
                if (task.getId() == tracked && !task.isCompleted()) {
                    return new DisplayTask(task.getId(), task.getLabel(),
                        task.getHave(), task.getNeed(),
                        task.getIconItemId(), task.getIconPath(), true);
                }
            }
        }
        EventState.FocusTask focus = entry.getFocusTask();
        if (focus == null) {
            return null;
        }
        Set<Integer> hidden = pickable ? hiddenTaskIds(eventId) : Collections.emptySet();
        if (!hidden.contains(focus.getId())) {
            return new DisplayTask(focus.getId(), focus.getLabel(),
                focus.getHave(), focus.getNeed(),
                focus.getIconItemId(), focus.getIconPath(), false);
        }
        if (tasks != null) {
            for (EventState.TaskInfo task : tasks) {
                if (!task.isCompleted() && !hidden.contains(task.getId())) {
                    return new DisplayTask(task.getId(), task.getLabel(),
                        task.getHave(), task.getNeed(),
                        task.getIconItemId(), task.getIconPath(), false);
                }
            }
        }
        return null;
    }

    /**
     * Task ids the user hid for this event in the Events tab. Package-private
     * and overridable: this is the seam the notification tests drive, since
     * {@link ConfigManager} cannot be constructed off a live client.
     */
    Set<Integer> hiddenTaskIds(int eventId) {
        return eventId > 0
            ? EventTaskPrefs.hidden(configManager, eventId) : Collections.emptySet();
    }

    /**
     * True when an envelope is a routine update about a task the user hid.
     * Only task-scoped chatter consults this: event-level news (lead changes,
     * lines, start/end) is never about the one task whose id it happens to
     * carry, and a genuine completion is deliberately still announced.
     */
    private boolean hiddenTask(EventNotification n) {
        EventNotification.Data data = n.getData();
        Integer taskId = data != null ? data.getTaskId() : null;
        Integer eventId = n.getEvent() != null ? n.getEvent().getId() : null;
        return taskId != null && taskId > 0 && eventId != null
            && hiddenTaskIds(eventId).contains(taskId);
    }

    /** Unified view of the headlined task, from either source. */
    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    public static class DisplayTask {
        public final int id;
        public final String label;
        public final long have;
        public final long need;
        @Nullable
        public final Integer iconItemId;
        @Nullable
        public final String iconPath;
        /** true = the user's manual pick; false = server-chosen focus. */
        public final boolean tracked;
    }

    /* ===================== batch rendering ===================== */

    /**
     * Renders one drained batch: the digest on the session's first stale
     * flood, else the normal per-event render. Package-private — this is the
     * seam the notification tests drive.
     */
    void renderBatch(List<EventNotification> batch, boolean catchUp) {
        // Group per event id (0 = event-less, e.g. submission notices).
        Map<Integer, List<EventNotification>> byEvent = new LinkedHashMap<>();
        for (EventNotification n : batch) {
            int eventId = n.getEvent() != null && n.getEvent().getId() != null
                ? n.getEvent().getId() : 0;
            byEvent.computeIfAbsent(eventId, k -> new ArrayList<>()).add(n);
        }
        List<Toast> candidates = new ArrayList<>();
        for (Map.Entry<Integer, List<EventNotification>> group : byEvent.entrySet()) {
            if (!catchUp || group.getKey() == 0) {
                candidates.addAll(renderEventGroup(group.getValue()));
            } else {
                // Digest the backlog per event; event-less notices render normally.
                Toast digest = summarizeEventGroup(group.getValue());
                if (digest != null) {
                    candidates.add(digest);
                }
            }
        }
        queueBatchToasts(candidates);
    }

    /** Renders one event's slice of a batch to chat and returns the pop-ups it
     *  earned; the caller coalesces them across events into one card. */
    private List<Toast> renderEventGroup(List<EventNotification> group) {
        List<Rendered> chatLines = new ArrayList<>();
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
                chatLines.add(rendered);
            }
            if (rendered.toastEligible) {
                groupToasts.add(rendered.toToast(System.currentTimeMillis()));
            }
        }

        // Chat: everything up to the cap, then a collapse line.
        int lines = 0;
        for (Rendered line : chatLines) {
            if (lines == MAX_CHAT_LINES_PER_EVENT && chatLines.size() > MAX_CHAT_LINES_PER_EVENT + 1) {
                sendLine(eventName, teamName, null, HEX_INFO, null,
                    "... and " + (chatLines.size() - MAX_CHAT_LINES_PER_EVENT) + " more event updates.");
                break;
            }
            sendLine(eventName, teamName, line.chatTag, line.chatHex,
                line.chatEmphasis, line.text);
            lines++;
        }
        return groupToasts;
    }

    /**
     * At most ONE pop-up per batch: the most important update headlines it and
     * the rest fold into a "+N more" tail. Several events completing tasks in
     * the same poll used to mean one tall card each — three of those at
     * TOP_CENTER is most of the screen.
     */
    private void queueBatchToasts(List<Toast> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        Toast lead = candidates.get(0);
        for (Toast candidate : candidates) {
            // Priority is declared most-important-first; ties keep the first.
            if (candidate.getPriority().ordinal() < lead.getPriority().ordinal()) {
                lead = candidate;
            }
        }
        int folded = candidates.size() - 1;
        offerToast(folded == 0 ? lead : lead.withMore(folded));
    }

    /**
     * Queues a pop-up subject to the display mode, the "important only"
     * filter, the cross-batch dedupe window and the queue cap.
     */
    private void offerToast(Toast toast) {
        if (!config.eventDisplayMode().popupsEnabled()) {
            return;
        }
        if (config.eventImportantPopupsOnly()
                && toast.getPriority() != EventNotification.Priority.HIGH) {
            return;
        }
        if (!claimToastKey(toast.getDedupeKey(), toast.getCreatedAt())) {
            return;
        }
        toasts.addLast(toast);
        while (toasts.size() > MAX_TOASTS_QUEUED) {
            toasts.pollFirst();
        }
    }

    /** False when this dedupe key already popped inside the window. */
    private boolean claimToastKey(@Nullable String key, long now) {
        if (key == null) {
            return true;
        }
        synchronized (recentToastKeys) {
            Long last = recentToastKeys.get(key);
            if (last != null && now - last < TOAST_DEDUPE_WINDOW_MS) {
                return false;
            }
            recentToastKeys.put(key, now);
            return true;
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

    /**
     * "[Event Name] (Team name): TAG line" when the event is known, else the
     * default [DropTracker] tag (submission notices, event-less groups) —
     * which has no accent scheme, so the styling is dropped there.
     */
    private void sendLine(@Nullable String eventName, @Nullable String teamName,
                          @Nullable String tag, @Nullable String accentHex,
                          @Nullable String emphasis, String line) {
        if (eventName != null) {
            chatMessageUtil.sendEventChatMessage(eventName, teamName, tag, accentHex,
                emphasis, line);
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

    /**
     * One event's backlog as a short digest: a "While you were away:" header
     * followed by indented tallies ("4 tasks completed (+23 pts)"), the
     * still-true facts (event started/ended, current leader) and the one
     * actionable item (a pending dice roll). At most a header plus
     * {@value #CATCHUP_MAX_DETAIL_LINES} lines and one pop-up, regardless of
     * backlog size. Returns the pop-up (null when the digest is empty).
     */
    @Nullable
    private Toast summarizeEventGroup(List<EventNotification> group) {
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
                    if (!hiddenTask(n)) {
                        progressedTasks.add(data.getTaskLabel() != null ? data.getTaskLabel() : "?");
                    }
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

        // The closing line is the one that outranks every tally: what state
        // the event is in now, or the action waiting on the team.
        String closing = null;
        String closingHex = null;
        if (ended) {
            closing = "The event has ended.";
            closingHex = HEX_INFO;
        } else if (rollPrompt) {
            closing = "Your team can roll the dice!";
            closingHex = HEX_ACTION;
        }
        int budget = CATCHUP_MAX_DETAIL_LINES - (closing != null ? 1 : 0);

        List<DigestLine> details = new ArrayList<>();
        if (started) {
            details.add(new DigestLine("The event started.", HEX_ACTION));
        }
        if (completions > 0) {
            details.add(new DigestLine(plural(completions, "task") + " completed"
                + (completionPts > 0 ? " (+" + ValueFormat.abbrev(completionPts) + " pts)" : ""),
                HEX_COMPLETE));
        }
        if (bingoLines > 0) {
            details.add(new DigestLine(plural(bingoLines, "bingo line")
                + (bonusPts > 0 && !blackout ? " (+" + ValueFormat.abbrev(bonusPts) + " pts)" : ""),
                HEX_LINE));
        }
        if (blackout) {
            details.add(new DigestLine("Board blackout"
                + (bonusPts > 0 ? " (+" + ValueFormat.abbrev(bonusPts) + " pts)" : ""),
                HEX_BLACKOUT));
        }
        if (leadTeam != null) {
            details.add(new DigestLine(leadTeam + " now leads"
                + (leadScore != null ? " (" + ValueFormat.abbrev(leadScore) + " pts)" : ""),
                HEX_LEAD));
        }
        // Fillers: only worth a line while the important tallies leave room.
        if (boardTurns > 0 && details.size() < budget) {
            details.add(new DigestLine(plural(boardTurns, "dice roll"), HEX_INFO));
        }
        if (!progressedTasks.isEmpty() && config.eventTaskProgressNotifications()
                && details.size() < budget) {
            details.add(new DigestLine("Progress on " + plural(progressedTasks.size(), "task"),
                HEX_MUTED));
        }
        while (details.size() > budget) {
            details.remove(details.size() - 1);
        }
        if (details.isEmpty() && closing == null) {
            return null;
        }

        // Header + indented tallies, rather than one run-on sentence.
        sendLine(eventName, teamName, null, HEX_LEAD, null, "While you were away:");
        List<String> summary = new ArrayList<>(details.size());
        for (DigestLine detail : details) {
            sendLine(eventName, teamName, null, detail.hex, null, CATCHUP_INDENT + detail.text);
            summary.add(detail.text);
        }
        if (closing != null) {
            sendLine(eventName, teamName, null, closingHex, null, CATCHUP_INDENT + closing);
        }

        String body = summary.isEmpty() ? closing : String.join(", ", summary) + ".";
        // The digest is a once-per-session headline: HIGH so "important only"
        // never swallows the one card that explains what you missed.
        EventPopupCard card = new EventPopupCard(EventPopupCard.Kind.DIGEST,
            "While you were away", eventName != null ? eventName : "Your event")
            .detail(body);
        return new Toast("While you were away", body, toastIcon,
            System.currentTimeMillis(), EventNotification.Priority.HIGH, null,
            card, 0, null);
    }

    /** One indented line of a catch-up digest, with its accent colour. */
    @AllArgsConstructor
    private static class DigestLine {
        final String text;
        final String hex;
    }

    private static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    /* ===================== rendering ===================== */

    /**
     * Chat accents, one per kind of news, so a completion, a lead change and
     * a KC tick never read the same. The server names a tone; the colour is
     * ours (an unknown tone stays uncoloured). Mirrors DropTrackerTheme: chat
     * can't reference AWT colours from here, so the hexes are duplicated.
     */
    private static final String HEX_COMPLETE = "#6fbf73";
    private static final String HEX_LEAD = "#ffd966";
    private static final String HEX_LINE = "#ff8c42";
    private static final String HEX_BLACKOUT = "#e05c4d";
    private static final String HEX_ACTION = "#ffb83f";
    private static final String HEX_INFO = "#d8c9a3";
    private static final String HEX_MUTED = "#8f8778";
    private static final Map<String, String> TONES = Map.of(
        "complete", HEX_COMPLETE, "tile", "#7fe08a", "lead", HEX_LEAD, "line", HEX_LINE,
        "blackout", HEX_BLACKOUT, "action", HEX_ACTION, "info", HEX_INFO, "muted", HEX_MUTED);
    private static final String CATCHUP_INDENT = "  - ";

    /** A rendered notification: what chat and the pop-up show. */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    private static class Rendered {
        final String title;
        /** Plain body: the pop-up card and the in-batch dedupe key. */
        final String text;
        /** Uppercase chat tag, e.g. "TILE COMPLETE"; null = no tag. */
        String chatTag;
        /** Chat accent for the tag/body; null = the legacy uncoloured line. */
        String chatHex;
        /** Substring of {@link #text} to lift out of the accent (item name). */
        String chatEmphasis;
        Integer iconItemId;
        EventNotification.Priority priority = EventNotification.Priority.NORMAL;
        boolean chatEligible = true;
        boolean toastEligible = true;
        /** (event, task, type); null opts out of the cross-batch pop-up dedupe. */
        String dedupeKey;
        /** Structured parts for the showcase-style pop-up layouts. */
        EventPopupCard card;

        Toast toToast(long now) {
            return new Toast(title, text, iconItemId, now, priority, dedupeKey,
                card, 0, null);
        }
    }

    /**
     * Renders an envelope from the server's {@code display} block: the
     * wording, the chat tag and the card are composed server-side
     * (services/plugin_display.py), per reader team. This side owns
     * everything about how it looks: the colours (a tone from a closed
     * set), the layouts (a card kind from a closed set), sanitizing every
     * string again, and the "time left" note, which only the reader's clock
     * can keep current. Local mutes still apply. An envelope without a
     * display we can read is dropped, like an unknown type.
     */
    @Nullable
    private Rendered render(EventNotification n) {
        EventNotification.Data data = n.getData() != null ? n.getData() : new EventNotification.Data();
        String type = String.valueOf(n.getType());
        if ("submission_notice".equals(type)) {
            // Legacy server-text channel: sanitized plain chat only, gated
            // by the pre-existing receiveInGameMessages config.
            String message = clean(data.getMessage());
            if (!config.receiveInGameMessages() || message == null) {
                return null;
            }
            Rendered notice = new Rendered("DropTracker", message);
            notice.toastEligible = false;
            return notice;
        }
        if ("event_task_progress".equals(type)
                && (!config.eventTaskProgressNotifications() || hiddenTask(n))) {
            // The client-side mute for the chattiest type; hiding a task in
            // the panel mutes its ticks too.
            return null;
        }
        EventNotification.Display d = n.getDisplay();
        String title = d != null ? clean(d.getTitle()) : null;
        String text = d != null ? clean(d.getText(), MAX_TEXT_LENGTH * 2) : null;
        if (title == null || text == null) {
            DebugLogger.log("[EventNotifications] no display for type=" + type);
            return null;
        }
        Rendered rendered = new Rendered(title, text);
        EventNotification.Chat chat = d.getChat();
        if (chat != null) {
            rendered.chatTag = clean(chat.getTag(), 24);
            rendered.chatHex = chat.getTone() != null ? TONES.get(chat.getTone()) : null;
            rendered.chatEmphasis = clean(chat.getEmphasis());
        }
        rendered.iconItemId = d.getIconItemId();
        rendered.toastEligible = !Boolean.FALSE.equals(d.getToast());
        rendered.priority = n.priorityTier();
        rendered.dedupeKey = dedupeKey(n);
        EventPopupCard card = d.getCard();
        if (card != null && card.getKind() != null) {
            card.sanitize(EventNotificationService::clean);
            if (card.getKind() != EventPopupCard.Kind.ENDED && card.getKind() != EventPopupCard.Kind.STARTED) {
                String left = timeLeft(d.getEndsAt());
                card.note(left != null ? left + " left" : null);
            }
            rendered.card = card;
        }
        return rendered;
    }

    /** "2d 4h" / "3h 12m" / "5m" until the server's UTC ISO timestamp; null
     *  when absent, unparseable or already past. */
    @Nullable
    static String timeLeft(@Nullable String endsAtIso) {
        if (endsAtIso == null || endsAtIso.isEmpty()) {
            return null;
        }
        try {
            Instant ends = LocalDateTime.parse(endsAtIso).toInstant(ZoneOffset.UTC);
            Duration left = Duration.between(Instant.now(), ends);
            if (left.isNegative() || left.isZero()) {
                return null;
            }
            long days = left.toDays();
            long hours = left.toHours() % 24;
            long minutes = left.toMinutes() % 60;
            if (days > 0) {
                return days + "d " + hours + "h";
            }
            if (hours > 0) {
                return hours + "h " + minutes + "m";
            }
            return Math.max(minutes, 1) + "m";
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Pop-up identity across batches: the same task ticking the same way. */
    private static String dedupeKey(EventNotification n) {
        Integer eventId = n.getEvent() != null ? n.getEvent().getId() : null;
        EventNotification.Data data = n.getData();
        Object task = null;
        if (data != null) {
            task = data.getTaskId() != null ? data.getTaskId() : clean(data.getTaskLabel());
        }
        return eventId + "|" + task + "|" + n.getType();
    }

    /** Tag-strip + length-cap every server-supplied string before rendering. */
    @Nullable
    static String clean(@Nullable String value) {
        return clean(value, MAX_TEXT_LENGTH);
    }

    @Nullable
    static String clean(@Nullable String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String stripped = Text.removeTags(value).replace('\u00A0', ' ').trim();
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.length() > maxLength
            ? stripped.substring(0, maxLength - 1) + "…" : stripped;
    }

    /** A transient on-screen pop-up. */
    @Getter
    public static class Toast {
        /** Per-tier lifetimes: the big moments linger, routine ticks flick by. */
        static final long LIFETIME_HIGH_MS = 9000;
        static final long LIFETIME_NORMAL_MS = 6000;
        static final long LIFETIME_LOW_MS = 3500;

        private final String title;
        private final String body;
        @Nullable
        private final Integer iconItemId;
        private final long createdAt;
        private final EventNotification.Priority priority;
        /** (event, task, type) for the cross-batch dedupe; null = always show. */
        @Nullable
        private final String dedupeKey;
        /** Structured parts for the showcase-style layouts; null = text only. */
        @Nullable
        private final EventPopupCard card;
        /** Other updates folded into this card ("+N more"). */
        private final int moreCount;
        /** ::dtpopup previews pin a style; null = the display type's. */
        @Nullable
        private final EventPopupStyle popupStyle;

        public Toast(String title, String body, @Nullable Integer iconItemId, long createdAt) {
            this(title, body, iconItemId, createdAt, EventNotification.Priority.NORMAL, null);
        }

        public Toast(String title, String body, @Nullable Integer iconItemId, long createdAt,
                     EventNotification.Priority priority, @Nullable String dedupeKey) {
            this(title, body, iconItemId, createdAt, priority, dedupeKey, null, 0, null);
        }

        public Toast(String title, String body, @Nullable Integer iconItemId, long createdAt,
                     EventNotification.Priority priority, @Nullable String dedupeKey,
                     @Nullable EventPopupCard card, int moreCount,
                     @Nullable EventPopupStyle popupStyle) {
            this.title = title;
            this.body = body;
            this.iconItemId = iconItemId;
            this.createdAt = createdAt;
            this.priority = priority != null ? priority : EventNotification.Priority.NORMAL;
            this.dedupeKey = dedupeKey;
            this.card = card;
            this.moreCount = moreCount;
            this.popupStyle = popupStyle;
        }

        /** This card headlining {@code more} folded updates. */
        Toast withMore(int more) {
            return new Toast(title, body + " (+" + more + " more)", iconItemId, createdAt,
                priority, dedupeKey, card, more, popupStyle);
        }

        /** A copy pinned to the given style and restarted at {@code now}. */
        public Toast pinned(@Nullable EventPopupStyle popup, long now) {
            return new Toast(title, body, iconItemId, now, priority, dedupeKey, card,
                moreCount, popup);
        }

        /** The server icon path to draw when there is no item sprite. */
        @Nullable
        public String getIconPath() {
            return card != null ? card.getIconPath() : null;
        }

        public long lifetimeMs() {
            switch (priority) {
                case HIGH:
                    return LIFETIME_HIGH_MS;
                case LOW:
                    return LIFETIME_LOW_MS;
                default:
                    return LIFETIME_NORMAL_MS;
            }
        }

        /** Milliseconds of life left; <= 0 once expired. */
        public long remainingMs(long now) {
            return lifetimeMs() - (now - createdAt);
        }

        public boolean expired(long now) {
            return remainingMs(now) <= 0;
        }
    }
}
