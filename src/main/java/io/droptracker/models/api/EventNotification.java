package io.droptracker.models.api;

import com.google.gson.annotations.SerializedName;
import io.droptracker.models.EventPopupCard;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.*;

/**
 * One notification envelope drained from GET /notifications.
 *
 * <p>Event news arrives with a {@link Display}: the server composes the
 * wording (services/plugin_display.py) and this plugin decides how it looks.
 * The display is plain text in fixed slots, a tone and a card kind from
 * closed sets, an item id or an {@code /img}-relative path; never markup,
 * colours, format strings or links, and every string is sanitized again
 * here. {@link Data} keeps only the typed fields this side still reads (the
 * hidden-task mute, the dedupe, the clan chat bridge). {@code submission_notice}
 * stays plain server text rendered as sanitized chat.
 */
@Getter
public class EventNotification {
    private String id;
    private String type;
    private long ts;
    @Nullable
    private EventRef event;
    /**
     * Broadcast importance ("high" / "normal" / "low"), additive to the
     * versionless envelope. Older servers omit it entirely — read it through
     * {@link #priorityTier()}, never raw.
     */
    @Nullable
    private String priority;
    private Data data;
    @Nullable
    private Display display;

    /**
     * Parsed broadcast importance. Missing or unrecognised values are
     * {@link Priority#NORMAL}: the field post-dates the plugin's own wire
     * contract, so a server that never sends it must keep working exactly as
     * before.
     */
    public Priority priorityTier() {
        return Priority.from(priority);
    }

    /** Importance tiers, declared most-important first (see {@code ordinal}). */
    public enum Priority {
        /** Finished a bingo tile/cell, lead change, line, blackout, start/end. */
        HIGH,
        /** An ordinary task completion that did not finish a tile. */
        NORMAL,
        /** Routine progress ticks and KC/XP milestones. */
        LOW;

        public static Priority from(@Nullable String raw) {
            if (raw != null) {
                switch (raw.toLowerCase(Locale.ROOT)) {
                    case "high":
                        return HIGH;
                    case "low":
                        return LOW;
                    default:
                        break;
                }
            }
            return NORMAL;
        }
    }

    @Getter
    public static class EventRef {
        private Integer id;
        private String name;
    }

    /** The server-composed wording of an event notification (see the class note). */
    @Getter
    public static class Display {
        private String title;
        private String text;
        @Nullable
        private Chat chat;
        /** False: chat only, no pop-up. */
        @Nullable
        private Boolean toast;
        @SerializedName("icon_item_id")
        @Nullable
        private Integer iconItemId;
        @Nullable
        private EventPopupCard card;
        /** When the event ends (naive UTC ISO): the card's "time left" note is ours to keep current. */
        @SerializedName("ends_at")
        @Nullable
        private String endsAt;
        /** event_digest only: the indented tallies under the header. */
        @Nullable
        private List<Chat> lines;
    }

    @Getter
    public static class Chat {
        /** Digest lines only. */
        @Nullable
        private String text;
        private String tag;
        /** A tone name from a closed set; the colour is the plugin's. */
        private String tone;
        @Nullable
        private String emphasis;
    }

    /** The typed fields still read here; gson leaves absent ones null. */
    @Getter
    public static class Data {
        @SerializedName("task_id")
        private Integer taskId;
        @SerializedName("task_label")
        private String taskLabel;
        /** submission_notice only: server-supplied plain text. */
        private String message;
        // clan_chat_message (Discord→game bridge): who spoke in Discord;
        // clan_chat_muted: the muted clanmate.
        private String sender;
        @SerializedName("account_type")
        private String accountType;
    }
}
