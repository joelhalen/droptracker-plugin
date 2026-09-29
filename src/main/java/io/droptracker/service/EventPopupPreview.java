package io.droptracker.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.droptracker.DropTrackerConfig;
import io.droptracker.models.EventDisplayMode;
import io.droptracker.models.EventNudgeStyle;
import io.droptracker.models.EventPopupCard;
import io.droptracker.models.EventPopupStyle;
import io.droptracker.models.api.EventNotification;
import io.droptracker.models.api.EventState;
import io.droptracker.service.EventNotificationService.Toast;
import io.droptracker.util.ChatMessageUtil;
import net.runelite.client.config.ConfigManager;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * {@code ::dtpopup}: fake event pop-ups on demand, so the layout candidates
 * can be judged in a real client without a live event. Samples are ordinary
 * notification envelopes pushed through the real renderer, so they read
 * exactly as the live ones would; only the chat line, the filters and the
 * dedupe are skipped. Nothing is sent to the server.
 *
 * <pre>
 *   ::dtpopup                   usage + current styles
 *   ::dtpopup test [kind]       one sample pop-up (kinds: see KINDS)
 *   ::dtpopup all               every kind, one after another
 *   ::dtpopup demo [kind]       the same pop-up in every style, in turn
 *   ::dtpopup style [name|next] pick the stand-alone pop-up style
 *   ::dtpopup hud [name|next]   pick the style under the HUD
 *   ::dtpopup mode [chat|popup|hud]  switch the event display type
 * </pre>
 */
@Singleton
public class EventPopupPreview {
    public static final String COMMAND = "dtpopup";

    private static final long DEMO_STEP_MS = 5500;
    private static final long ALL_STEP_MS = 1800;
    /** How long a preview may lend the HUD its sample event. */
    private static final long PREVIEW_HUD_MS = 90_000;
    private static final int PREVIEW_EVENT_ID = -4242;
    private static final String PREVIEW_EVENT = "Autumn Bingo";
    private static final String PREVIEW_TEAM = "Iron Eagles";

    private final DropTrackerConfig config;
    private final ConfigManager configManager;
    private final EventNotificationService service;
    private final ChatMessageUtil chat;
    private final ScheduledExecutorService executor;
    private final Gson gson;

    /** Sample makers by kind name, in the order "all" plays them. */
    private final Map<String, Supplier<JsonObject>> kinds = new LinkedHashMap<>();
    private final List<ScheduledFuture<?>> scheduled = new ArrayList<>();

    @Inject
    public EventPopupPreview(DropTrackerConfig config, ConfigManager configManager,
                             EventNotificationService service, ChatMessageUtil chat,
                             ScheduledExecutorService executor, Gson gson) {
        this.config = config;
        this.configManager = configManager;
        this.service = service;
        this.chat = chat;
        this.executor = executor;
        this.gson = gson;
        kinds.put("tile", this::tileSample);
        kinds.put("complete", this::completeSample);
        kinds.put("progress", this::progressSample);
        kinds.put("lead", () -> envelope("event_lead_change", "high", data()
            .put("team_name", PREVIEW_TEAM).put("team_score", 1240).json));
        kinds.put("line", () -> envelope("event_line", "high", data()
            .put("team_name", PREVIEW_TEAM).put("bonus_points", 25).json));
        kinds.put("blackout", () -> envelope("event_blackout", "high", data()
            .put("team_name", PREVIEW_TEAM).put("bonus_points", 100).json));
        kinds.put("start", () -> envelope("event_started", "high", data().json));
        kinds.put("end", () -> envelope("event_ended", "high", data().json));
        kinds.put("board", () -> envelope("event_board_turn", "normal", data()
            .put("player_name", "Zezima").put("dice_str", "4 + 2").put("tile_to", 18)
            .put("next_task_label", "Obtain any Zenyte jewel").json));
        kinds.put("roll", () -> envelope("event_board_roll_prompt", "high", data()
            .put("coins_awarded", 3).json));
        kinds.put("digest", () -> null); // built as a toast directly, see sample()
        kinds.put("more", this::tileSample); // a tile headlining two folded updates
    }

    /** Entry point from the plugin's CommandExecuted subscriber. */
    public void onCommand(String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        String arg = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : null;
        switch (sub) {
            case "test":
            case "show":
                test(arg != null ? arg.toLowerCase(Locale.ROOT) : "tile");
                break;
            case "all":
                playAll();
                break;
            case "demo":
                demo(arg != null ? arg.toLowerCase(Locale.ROOT) : "tile");
                break;
            case "style":
            case "popup":
                setPopupStyle(arg);
                break;
            case "hud":
            case "nudge":
                setNudgeStyle(arg);
                break;
            case "mode":
                setMode(arg);
                break;
            case "stop":
            case "clear":
                cancelScheduled();
                service.getToasts().clear();
                say("Cleared the pop-ups.");
                break;
            default:
                help();
        }
    }

    /* ===================== subcommands ===================== */

    private void help() {
        EventDisplayMode mode = config.eventDisplayMode();
        say("Pop-up preview. Display type: " + mode
            + ". Pop-up style: " + config.eventPopupStyle()
            + ". HUD style: " + config.eventNudgeStyle() + ".");
        say("::dtpopup demo - the same pop-up in every style. ::dtpopup test [kind], ::dtpopup all.");
        say("::dtpopup style [name|next], ::dtpopup hud [name|next], ::dtpopup mode [chat|popup|hud].");
        say("Kinds: " + String.join(", ", kinds.keySet()) + ".");
    }

    private void test(String kind) {
        if (!kinds.containsKey(kind)) {
            say("Unknown kind '" + kind + "'. Kinds: " + String.join(", ", kinds.keySet()) + ".");
            return;
        }
        if (!ready()) {
            return;
        }
        cancelScheduled();
        show(kind, null, null);
    }

    private void playAll() {
        if (!ready()) {
            return;
        }
        cancelScheduled();
        say("Playing every kind of pop-up (" + kinds.size() + "), in your current style.");
        long delay = 0;
        for (String kind : kinds.keySet()) {
            schedule(() -> show(kind, null, null), delay);
            delay += ALL_STEP_MS;
        }
    }

    /**
     * One kind of pop-up in each style for the current display type, one at
     * a time, each introduced in chat with the command that keeps it.
     */
    private void demo(String kind) {
        if (!kinds.containsKey(kind)) {
            say("Unknown kind '" + kind + "'. Kinds: " + String.join(", ", kinds.keySet()) + ".");
            return;
        }
        if (!ready()) {
            return;
        }
        cancelScheduled();
        long delay = 0;
        if (config.eventDisplayMode().hudEnabled()) {
            EventNudgeStyle[] styles = EventNudgeStyle.values();
            say("Showing each of the " + styles.length + " HUD pop-up styles, "
                + DEMO_STEP_MS / 1000 + "s apart.");
            for (EventNudgeStyle style : styles) {
                schedule(() -> {
                    service.getToasts().clear();
                    say("HUD style " + (style.ordinal() + 1) + "/" + styles.length + ": "
                        + style + " - " + style.blurb() + ". Keep it: ::dtpopup hud " + style.key());
                    show(kind, null, style);
                }, delay);
                delay += DEMO_STEP_MS;
            }
        } else {
            EventPopupStyle[] styles = EventPopupStyle.values();
            say("Showing each of the " + styles.length + " pop-up styles, "
                + DEMO_STEP_MS / 1000 + "s apart.");
            for (EventPopupStyle style : styles) {
                schedule(() -> {
                    service.getToasts().clear();
                    say("Style " + (style.ordinal() + 1) + "/" + styles.length + ": "
                        + style + " - " + style.blurb() + ". Keep it: ::dtpopup style " + style.key());
                    show(kind, style, null);
                }, delay);
                delay += DEMO_STEP_MS;
            }
        }
    }

    private void setPopupStyle(@Nullable String arg) {
        if (arg == null) {
            say("Pop-up styles: " + listStyles(EventPopupStyle.values(), config.eventPopupStyle())
                + ". Pick one with ::dtpopup style <name>.");
            return;
        }
        EventPopupStyle style = "next".equalsIgnoreCase(arg)
            ? config.eventPopupStyle().next() : EventPopupStyle.parse(arg);
        if (style == null) {
            say("No pop-up style '" + arg + "'. Styles: "
                + listStyles(EventPopupStyle.values(), config.eventPopupStyle()) + ".");
            return;
        }
        configManager.setConfiguration(DropTrackerConfig.GROUP, "eventPopupStyle", style);
        say("Pop-up style is now " + style + " (" + style.blurb() + ").");
        if (config.eventDisplayMode().hudEnabled()) {
            say("You're on the HUD display type, which uses the HUD style. ::dtpopup mode popup to see this one.");
        } else if (ready()) {
            cancelScheduled();
            show("tile", style, null);
        }
    }

    private void setNudgeStyle(@Nullable String arg) {
        if (arg == null) {
            say("HUD pop-up styles: " + listStyles(EventNudgeStyle.values(), config.eventNudgeStyle())
                + ". Pick one with ::dtpopup hud <name>.");
            return;
        }
        EventNudgeStyle style = "next".equalsIgnoreCase(arg)
            ? config.eventNudgeStyle().next() : EventNudgeStyle.parse(arg);
        if (style == null) {
            say("No HUD style '" + arg + "'. Styles: "
                + listStyles(EventNudgeStyle.values(), config.eventNudgeStyle()) + ".");
            return;
        }
        configManager.setConfiguration(DropTrackerConfig.GROUP, "eventNudgeStyle", style);
        say("HUD pop-up style is now " + style + " (" + style.blurb() + ").");
        if (!config.eventDisplayMode().hudEnabled()) {
            say("It shows under the Enhanced display HUD. ::dtpopup mode hud to see it.");
        } else if (ready()) {
            cancelScheduled();
            show("tile", null, style);
        }
    }

    private void setMode(@Nullable String arg) {
        EventDisplayMode mode = null;
        if (arg != null) {
            switch (arg.toLowerCase(Locale.ROOT)) {
                case "chat":
                    mode = EventDisplayMode.CHAT;
                    break;
                case "popup":
                case "popups":
                    mode = EventDisplayMode.POPUP;
                    break;
                case "hud":
                case "enhanced":
                    mode = EventDisplayMode.ENHANCED;
                    break;
                default:
                    break;
            }
        }
        if (mode == null) {
            say("Display type is " + config.eventDisplayMode() + ". Use ::dtpopup mode chat, popup or hud.");
            return;
        }
        configManager.setConfiguration(DropTrackerConfig.GROUP, "eventDisplayMode", mode);
        say("Display type is now " + mode + ".");
        if (mode.popupsEnabled() && ready()) {
            cancelScheduled();
            show("tile", null, null);
        }
    }

    /* ===================== plumbing ===================== */

    /** False (and says why) when pop-ups can't show at all right now. */
    private boolean ready() {
        if (!config.eventNotifications()) {
            say("Event notifications are off. Turn on 'Receive notifications' in the Events settings.");
            return false;
        }
        if (!config.eventDisplayMode().popupsEnabled()) {
            say("Your display type is chat only, so there are no pop-ups. Try ::dtpopup mode popup or ::dtpopup mode hud.");
            return false;
        }
        return true;
    }

    private void show(String kind, @Nullable EventPopupStyle popup, @Nullable EventNudgeStyle nudge) {
        if (config.eventDisplayMode().hudEnabled() && !service.hasLiveHudEntry()) {
            // The HUD only paints with an event to show; lend it a sample one.
            service.setPreviewHudEntry(previewHudEntry(), System.currentTimeMillis() + PREVIEW_HUD_MS);
        }
        if ("digest".equals(kind)) {
            EventPopupCard card = new EventPopupCard(EventPopupCard.Kind.DIGEST,
                "While you were away", PREVIEW_EVENT)
                .detail("4 tasks completed (+23 pts), 1 bingo line, " + PREVIEW_TEAM + " took the lead.");
            service.previewToast(new Toast("While you were away",
                "4 tasks completed (+23 pts), 1 bingo line, " + PREVIEW_TEAM + " took the lead.",
                20997, System.currentTimeMillis(), EventNotification.Priority.HIGH, null,
                card, 0, popup, nudge));
            return;
        }
        EventNotification n = gson.fromJson(kinds.get(kind).get(), EventNotification.class);
        boolean shown = service.previewNotification(n, popup, nudge, "more".equals(kind) ? 2 : 0);
        if (!shown) {
            say("That kind is muted by your settings (Task progress notifications is off).");
        }
    }

    private void schedule(Runnable task, long delayMs) {
        synchronized (scheduled) {
            scheduled.add(executor.schedule(task, delayMs, TimeUnit.MILLISECONDS));
        }
    }

    private void cancelScheduled() {
        synchronized (scheduled) {
            for (ScheduledFuture<?> future : scheduled) {
                future.cancel(false);
            }
            scheduled.clear();
        }
    }

    private void say(String message) {
        chat.sendChatMessage(message);
    }

    private static <E extends Enum<E>> String listStyles(E[] values, E current) {
        List<String> names = new ArrayList<>(values.length);
        for (E value : values) {
            String key = value.name().toLowerCase(Locale.ROOT);
            names.add((value.ordinal() + 1) + ". " + key + (value == current ? " (current)" : ""));
        }
        return String.join(", ", names);
    }

    /* ===================== samples ===================== */

    private JsonObject tileSample() {
        JsonArray idxs = new JsonArray();
        idxs.add(6);
        JsonArray labels = new JsonArray();
        labels.add("Bandos set");
        JsonObject d = data()
            .put("player_name", "Zezima").put("task_label", "Obtain Bandos tassets")
            .put("received_item", "Bandos tassets").put("icon_item_id", 11834)
            .put("points", 10).put("tiles_completed", 7).put("team_rank", 2).put("team_count", 5)
            .put("team_name", PREVIEW_TEAM).json;
        d.add("cell_idxs", idxs);
        d.add("cell_labels", labels);
        return envelope("event_completion", "high", d);
    }

    private JsonObject completeSample() {
        return envelope("event_completion", "normal", data()
            .put("player_name", "Zezima").put("task_label", "Obtain a Dragon warhammer")
            .put("received_item", "Dragon warhammer").put("icon_item_id", 13576)
            .put("points", 5).put("team_name", PREVIEW_TEAM).json);
    }

    private JsonObject progressSample() {
        return envelope("event_task_progress", "low", data()
            .put("player_name", "Lynx Titan").put("task_label", "Obtain 5 Abyssal whips")
            .put("received_item", "Abyssal whip").put("icon_item_id", 4151)
            .put("progress", 2).put("target", 5).json);
    }

    private JsonObject envelope(String type, String priority, JsonObject data) {
        JsonObject event = new JsonObject();
        event.addProperty("id", PREVIEW_EVENT_ID);
        event.addProperty("name", PREVIEW_EVENT);
        JsonObject n = new JsonObject();
        // A fresh id and task each time, so nothing downstream sees a repeat.
        n.addProperty("id", "preview-" + System.nanoTime());
        n.addProperty("type", type);
        n.addProperty("ts", System.currentTimeMillis() / 1000L);
        n.addProperty("priority", priority);
        n.add("event", event);
        if (!data.has("task_id")) {
            data.addProperty("task_id", (int) (System.nanoTime() & 0x7fffffff));
        }
        n.add("data", data);
        return n;
    }

    private static DataBuilder data() {
        return new DataBuilder();
    }

    /** Tiny fluent wrapper so the samples read as one expression each. */
    private static final class DataBuilder {
        final JsonObject json = new JsonObject();

        DataBuilder put(String key, String value) {
            json.addProperty(key, value);
            return this;
        }

        DataBuilder put(String key, Number value) {
            json.addProperty(key, value);
            return this;
        }
    }

    private EventState.Entry previewHudEntry() {
        JsonObject event = new JsonObject();
        event.addProperty("id", PREVIEW_EVENT_ID);
        event.addProperty("name", PREVIEW_EVENT + " (preview)");
        event.addProperty("kind", "bingo");
        event.addProperty("has_bingo", true);
        JsonObject team = new JsonObject();
        team.addProperty("id", 1);
        team.addProperty("name", PREVIEW_TEAM);
        team.addProperty("color", "#3d9be9");
        team.addProperty("score", 1240);
        team.addProperty("rank", 2);
        team.addProperty("team_count", 5);
        JsonObject focus = new JsonObject();
        focus.addProperty("id", 1);
        focus.addProperty("label", "Obtain 5 Abyssal whips");
        focus.addProperty("have", 2);
        focus.addProperty("need", 5);
        focus.addProperty("icon_item_id", 4151);
        JsonObject entry = new JsonObject();
        entry.add("event", event);
        entry.add("team", team);
        entry.add("focus_task", focus);
        entry.addProperty("tasks_completed", 7);
        entry.addProperty("tasks_total", 25);
        return gson.fromJson(entry, EventState.Entry.class);
    }
}
