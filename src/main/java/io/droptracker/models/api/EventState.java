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
        /** Full team task list (picker + tooltips); null from older servers. */
        @SerializedName("tasks")
        @Nullable
        private List<TaskInfo> tasks;
        /** Own-team roster (capped server-side); null from older servers. */
        @SerializedName("members")
        @Nullable
        private List<Member> members;
        @SerializedName("members_total")
        private int membersTotal;
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

    /** One task on the team's board/list, with team progress and the
     *  server-composed explanation shown in tooltips. */
    @Getter
    public static class TaskInfo {
        @SerializedName("id")
        private int id;
        @SerializedName("label")
        private String label;
        @SerializedName("type")
        private String type;
        /** Points awarded on completion (0 = event doesn't use points). */
        @SerializedName("points")
        private int points;
        @SerializedName("have")
        private long have;
        @SerializedName("need")
        private long need;
        @SerializedName("completed")
        private boolean completed;
        @SerializedName("icon_item_id")
        @Nullable
        private Integer iconItemId;
        @SerializedName("icon_url")
        @Nullable
        private String iconUrl;
        /** Tile badge in the legacy board style ("KC TARGET", "FULL SET"...). */
        @SerializedName("badge")
        @Nullable
        private String badge;
        /** Short value string ("100.00M GP", "sub 1:45"). */
        @SerializedName("value")
        @Nullable
        private String value;
        @SerializedName("description")
        @Nullable
        private String description;
        @SerializedName("requirements")
        @Nullable
        private List<Requirement> requirements;
    }

    /** One item requirement of a task ({name, quantity?, points?}). */
    @Getter
    public static class Requirement {
        @SerializedName("name")
        private String name;
        @SerializedName("quantity")
        @Nullable
        private Integer quantity;
        @SerializedName("points")
        @Nullable
        private Integer points;
    }

    @Getter
    public static class Member {
        @SerializedName("player_id")
        private int playerId;
        @SerializedName("name")
        private String name;
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
