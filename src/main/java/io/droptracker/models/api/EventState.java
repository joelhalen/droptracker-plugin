package io.droptracker.models.api;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Response of GET /event_state: one entry per active event the player is
 * rostered in. Composed entirely server-side (focus-task selection,
 * standings, ranks) so the client just renders typed fields.
 */
@Getter
public class EventState {
    @SerializedName("events")
    private List<Entry> events;

    @Getter
    public static class Entry {
        @SerializedName("event")
        private EventInfo event;
        @SerializedName("team")
        private TeamInfo team;
        @SerializedName("focus_task")
        @Nullable
        private FocusTask focusTask;
        /** Board-game turn state ("active" | "awaiting_roll"), else null. */
        @SerializedName("board_status")
        @Nullable
        private String boardStatus;
        @SerializedName("tasks_completed")
        private int tasksCompleted;
        @SerializedName("tasks_total")
        private int tasksTotal;
        @SerializedName("board")
        private BoardInfo board;
        @SerializedName("standings")
        private List<Standing> standings;
    }

    @Getter
    public static class EventInfo {
        @SerializedName("id")
        private int id;
        @SerializedName("name")
        private String name;
        @SerializedName("kind")
        private String kind;
        @SerializedName("has_bingo")
        private boolean hasBingo;
        @SerializedName("ends_at")
        @Nullable
        private String endsAt;
    }

    @Getter
    public static class TeamInfo {
        @SerializedName("id")
        private int id;
        @SerializedName("name")
        private String name;
        @SerializedName("color")
        @Nullable
        private String color;
        @SerializedName("icon_item_id")
        @Nullable
        private Integer iconItemId;
        @SerializedName("icon_url")
        @Nullable
        private String iconUrl;
        @SerializedName("score")
        private int score;
        @SerializedName("rank")
        @Nullable
        private Integer rank;
        @SerializedName("team_count")
        private int teamCount;
    }

    @Getter
    public static class FocusTask {
        @SerializedName("id")
        private int id;
        @SerializedName("label")
        private String label;
        @SerializedName("have")
        private long have;
        @SerializedName("need")
        private long need;
        @SerializedName("icon_item_id")
        @Nullable
        private Integer iconItemId;
        @SerializedName("icon_url")
        @Nullable
        private String iconUrl;
        /** "board" | "inferred" | "team_progress" | "first_task" */
        @SerializedName("source")
        private String source;
    }

    @Getter
    public static class BoardInfo {
        @SerializedName("available")
        private boolean available;
        @SerializedName("team_id")
        private int teamId;
    }

    @Getter
    public static class Standing {
        @SerializedName("team_id")
        private int teamId;
        @SerializedName("name")
        private String name;
        @SerializedName("score")
        private int score;
        @SerializedName("rank")
        private int rank;
        @SerializedName("color")
        @Nullable
        private String color;
    }
}
