package io.droptracker.models.api;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;

import javax.annotation.Nullable;

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
    @SerializedName("data")
    private Data data;

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
    }
}
