package io.droptracker.models.api;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * One typed notification envelope drained from GET /notifications.
 *
 * Safety contract (EVENT_PLUGIN_NOTIFICATIONS_PLAN): the server sends typed
 * data only — the client maps {@code type} onto a hardcoded renderer and
 * silently drops unknown types. All display text is composed locally from
 * these fields; the single exception is {@code submission_notice}, whose
 * {@code data.message} is rendered as sanitized plain chat.
 */
@Getter
public class EventNotification {
    @SerializedName("id")
    private String id;
    @SerializedName("type")
    private String type;
    @SerializedName("ts")
    private long ts;
    @SerializedName("event")
    @Nullable
    private EventRef event;
    /**
     * Broadcast importance ("high" / "normal" / "low"), additive to the
     * versionless envelope. Older servers omit it entirely — read it through
     * {@link #priorityTier()}, never raw.
     */
    @SerializedName("priority")
    @Nullable
    private String priority;
    @SerializedName("data")
    private Data data;

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
        @SerializedName("id")
        private Integer id;
        @SerializedName("name")
        private String name;
    }

    /**
     * Union of the typed fields the known notification types carry; gson
     * leaves fields the envelope doesn't include as null.
     */
    @Getter
    public static class Data {
        @SerializedName("task_id")
        private Integer taskId;
        @SerializedName("task_label")
        private String taskLabel;
        @SerializedName("team_id")
        private Integer teamId;
        @SerializedName("team_name")
        private String teamName;
        @SerializedName("player_name")
        private String playerName;
        @SerializedName("points")
        private Integer points;
        @SerializedName("team_score")
        private Integer teamScore;
        @SerializedName("progress")
        private Long progress;
        @SerializedName("target")
        private Long target;
        @SerializedName("milestone_pct")
        private Integer milestonePct;
        @SerializedName("icon_item_id")
        private Integer iconItemId;
        @SerializedName("received_item")
        private String receivedItem;
        @SerializedName("received_qty")
        private Integer receivedQty;
        /** True on point_collection tasks: ledger quantities are point
         *  credits, never render them as "×N of the item". */
        @SerializedName("points_based")
        private Boolean pointsBased;
        @SerializedName("bonus_points")
        private Integer bonusPoints;
        /**
         * event_completion on a bingo board: the cells this completion filled
         * (empty/absent = the task advanced no tile). Sent since the events
         * rewrite; the labels list only carries the cells that have one.
         */
        @SerializedName("cell_idxs")
        private List<Integer> cellIdxs;
        @SerializedName("cell_labels")
        private List<String> cellLabels;
        /** Tiles this team has completed in total, after this completion. */
        @SerializedName("tiles_completed")
        private Integer tilesCompleted;
        @SerializedName("team_rank")
        private Integer teamRank;
        @SerializedName("team_count")
        private Integer teamCount;
        /** How the credit was earned: "drop", "manual", "collection_log"... */
        @SerializedName("source_type")
        private String sourceType;
        @SerializedName("line")
        private String line;
        @SerializedName("dice_str")
        private String diceStr;
        @SerializedName("tile_to")
        private Integer tileTo;
        @SerializedName("next_task_label")
        private String nextTaskLabel;
        @SerializedName("coins_awarded")
        private Integer coinsAwarded;
        @SerializedName("coin_balance")
        private Integer coinBalance;
        /** submission_notice only: server-supplied plain text. */
        @SerializedName("message")
        private String message;
        // clan_chat_message (Discord→game bridge): who spoke in Discord.
        @SerializedName("sender")
        private String sender;
    }
}
