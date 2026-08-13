package io.droptracker.service;

import io.droptracker.DropTrackerConfig;
import io.droptracker.models.api.EventState;
import net.runelite.client.config.ConfigManager;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-event task pins and hides for the Events tab, persisted through
 * {@link ConfigManager} with the same per-event keying as the tracked task:
 * {@code pinnedTasks_<eventId>} and {@code hiddenTasks_<eventId>}, each a
 * comma-separated list of task ids in the order the user picked them.
 *
 * <p>The single tracked task is the degenerate one-pin case. The first
 * <i>incomplete</i> pinned task is mirrored into {@code trackedTask_<eventId>}
 * by {@link #syncFocus} — that key stays the canonical "focus" that
 * {@link EventNotificationService#trackedTaskId(int)} reads, so the HUD,
 * {@link EventNotificationService#displayTask(EventState.Entry)} and anything
 * else built on it keep working without knowing pins exist. Config written by
 * an older build (a tracked task, no pin list) is read back as a single pin.
 *
 * <p>The hide set is readable without a panel instance
 * ({@link #hidden(ConfigManager, int)}) because hiding has to reach past the
 * list it was clicked in: {@link EventNotificationService#hiddenTaskIds(int)}
 * reads it to keep a hidden task out of the HUD and to mute its progress
 * chat/pop-ups. Completions are still announced — see that class.
 */
public class EventTaskPrefs {
    private static final String PINNED_PREFIX = "pinnedTasks_";
    private static final String HIDDEN_PREFIX = "hiddenTasks_";
    /** Stored for an empty list: 0 is not a task id, so it parses away. */
    private static final String NO_IDS = "0";

    private final ConfigManager configManager;
    private final EventNotificationService service;

    public EventTaskPrefs(ConfigManager configManager, EventNotificationService service) {
        this.configManager = configManager;
        this.service = service;
    }

    /** Task ids the user pinned for this event, in pin order. */
    public Set<Integer> pinned(int eventId) {
        String raw = get(PINNED_PREFIX + eventId);
        if (raw == null) {
            // Pre-pin-set config: the tracked task is the one and only pin.
            Set<Integer> migrated = new LinkedHashSet<>();
            int tracked = service.trackedTaskId(eventId);
            if (tracked > 0) {
                migrated.add(tracked);
            }
            return migrated;
        }
        return parse(raw);
    }

    /** Task ids the user hid for this event. */
    public Set<Integer> hidden(int eventId) {
        return hidden(configManager, eventId);
    }

    /** {@link #hidden(int)} without a panel — for callers that only have the
     *  ConfigManager (the HUD and notification filtering). */
    public static Set<Integer> hidden(ConfigManager configManager, int eventId) {
        return parse(get(configManager, HIDDEN_PREFIX + eventId));
    }

    /** Pin or unpin a task, then re-point the HUD focus at the top pin. */
    public void togglePin(EventState.Entry entry, int taskId) {
        int eventId = eventId(entry);
        if (eventId <= 0) {
            return;
        }
        Set<Integer> ids = pinned(eventId);
        if (!ids.remove(taskId)) {
            ids.add(taskId);
            // A task cannot be both focused and out of sight.
            unhide(eventId, taskId);
        }
        put(PINNED_PREFIX + eventId, ids);
        syncFocus(entry);
    }

    /** Drop one pin (the tracked-task box's "unpin"), promoting the next. */
    public void unpin(EventState.Entry entry, int taskId) {
        int eventId = eventId(entry);
        if (eventId <= 0) {
            return;
        }
        Set<Integer> ids = pinned(eventId);
        if (ids.remove(taskId)) {
            put(PINNED_PREFIX + eventId, ids);
        }
        syncFocus(entry);
    }

    /** Hide or unhide a task; hiding it also gives up its pin. */
    public void toggleHidden(EventState.Entry entry, int taskId) {
        int eventId = eventId(entry);
        if (eventId <= 0) {
            return;
        }
        Set<Integer> ids = hidden(eventId);
        if (ids.remove(taskId)) {
            put(HIDDEN_PREFIX + eventId, ids);
        } else {
            ids.add(taskId);
            put(HIDDEN_PREFIX + eventId, ids);
            unpin(entry, taskId);
        }
    }

    /** Bring every hidden task of this event back into the list. */
    public void unhideAll(int eventId) {
        put(HIDDEN_PREFIX + eventId, new LinkedHashSet<>());
    }

    /**
     * Mirror the first incomplete pin into the canonical tracked-task key, so
     * the HUD headlines it. No pins (or every pin completed) means "let the
     * server decide", exactly as an unset tracked task always has.
     *
     * <p>Skipped for board games — their current tile is forced, never picked —
     * and while the server sends no task list, where completion is unknowable
     * and clearing the key would silently drop the user's pick.
     */
    public void syncFocus(@Nullable EventState.Entry entry) {
        int eventId = eventId(entry);
        if (eventId <= 0 || "board_game".equals(entry.getEvent().getKind())) {
            return;
        }
        List<EventState.TaskInfo> tasks = entry.getTasks();
        if (tasks == null) {
            return;
        }
        Set<Integer> pins = pinned(eventId);
        if (!pins.isEmpty() && get(PINNED_PREFIX + eventId) == null) {
            // Migrated from a tracked task: write the pin list out once so the
            // pin survives the task completing (which clears the tracked key).
            put(PINNED_PREFIX + eventId, pins);
        }
        int focus = 0;
        for (Integer id : pins) {
            EventState.TaskInfo task = task(tasks, id);
            if (task != null && !task.isCompleted()) {
                focus = id;
                break;
            }
        }
        if (focus != service.trackedTaskId(eventId)) {
            service.setTrackedTask(eventId, focus);
        }
    }

    private void unhide(int eventId, int taskId) {
        Set<Integer> ids = hidden(eventId);
        if (ids.remove(taskId)) {
            put(HIDDEN_PREFIX + eventId, ids);
        }
    }

    @Nullable
    private static EventState.TaskInfo task(List<EventState.TaskInfo> tasks, int id) {
        for (EventState.TaskInfo task : tasks) {
            if (task.getId() == id) {
                return task;
            }
        }
        return null;
    }

    private static int eventId(@Nullable EventState.Entry entry) {
        return entry != null && entry.getEvent() != null ? entry.getEvent().getId() : 0;
    }

    @Nullable
    private String get(String key) {
        return get(configManager, key);
    }

    @Nullable
    private static String get(ConfigManager configManager, String key) {
        try {
            return configManager.getConfiguration(DropTrackerConfig.GROUP, key);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * An empty set is stored as {@link #NO_IDS}, never unset and never blank:
     * "the user cleared their pins" must not read back as "never used pins",
     * which would resurrect the tracked task the pins were migrated from.
     */
    private void put(String key, Collection<Integer> ids) {
        StringBuilder csv = new StringBuilder();
        for (Integer id : ids) {
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(id);
        }
        if (csv.length() == 0) {
            csv.append(NO_IDS);
        }
        try {
            configManager.setConfiguration(DropTrackerConfig.GROUP, key, csv.toString());
        } catch (Exception ignored) {
            // A failed write costs the user a pin, never the panel.
        }
    }

    private static Set<Integer> parse(@Nullable String raw) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) {
            return ids;
        }
        for (String part : raw.split(",")) {
            try {
                int id = Integer.parseInt(part.trim());
                if (id > 0) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
                // Hand-edited config: skip the junk, keep the rest.
            }
        }
        return ids;
    }
}
