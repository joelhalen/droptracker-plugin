package io.droptracker.models.api;

import com.google.gson.annotations.SerializedName;
import io.droptracker.models.submissions.RecentSubmission;
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
    private List<Entry> events;

    /**
     * Item ids the server wants force-screenshotted for event proof: everything
     * an incomplete task of the player's active events can still be credited
     * by. Null from older servers.
     */
    @SerializedName("screenshot_item_ids")
    @Nullable
    private List<Integer> screenshotItemIds;

    @Getter
    public static class Entry {
        private EventInfo event;
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
        private BoardInfo board;
        private List<Standing> standings;
        /** Full team task list (picker + tooltips); null from older servers. */
        @Nullable
        private List<TaskInfo> tasks;
        /** Own-team roster (capped server-side); null from older servers. */
        @Nullable
        private List<Member> members;
        @SerializedName("members_total")
        private int membersTotal;
        /**
         * The own team's latest scoring submissions, in the shape the Player
         * and Group tabs already render (capped server-side); null from older
         * servers, empty for a team that has not scored yet.
         */
        @SerializedName("team_recent_submissions")
        @Nullable
        private List<RecentSubmission> teamRecentSubmissions;
        /**
         * Changes whenever this event's clan-chat team badges would look
         * different — a roster edit, a rename, a recolor, a retag. The team
         * indicator service refetches GET /event_roster only when it stops
         * matching what it holds, so a settled event never repeats a payload
         * that carries the whole event's membership. Null from older servers,
         * which simply means no badges.
         */
        @SerializedName("roster_version")
        @Nullable
        private String rosterVersion;
    }

    @Getter
    public static class EventInfo {
        private int id;
        private String name;
        private String kind;
        @SerializedName("has_bingo")
        private boolean hasBingo;
        @SerializedName("ends_at")
        @Nullable
        private String endsAt;
    }

    @Getter
    public static class TeamInfo {
        private int id;
        private String name;
        @Nullable
        private String color;
        @SerializedName("icon_item_id")
        @Nullable
        private Integer iconItemId;
        @SerializedName("icon_path")
        @Nullable
        private String iconPath;
        private int score;
        @Nullable
        private Integer rank;
        @SerializedName("team_count")
        private int teamCount;
    }

    @Getter
    public static class FocusTask {
        private int id;
        private String label;
        private long have;
        private long need;
        @SerializedName("icon_item_id")
        @Nullable
        private Integer iconItemId;
        @SerializedName("icon_path")
        @Nullable
        private String iconPath;
        /** "board" | "inferred" | "team_progress" | "first_task" */
        private String source;
    }

    /** One task on the team's board/list, with team progress and the
     *  server-composed explanation shown in tooltips. */
    @Getter
    public static class TaskInfo {
        private int id;
        private String label;
        private String type;
        /** Points awarded on completion (0 = event doesn't use points). */
        private int points;
        private long have;
        private long need;
        private boolean completed;
        @SerializedName("icon_item_id")
        @Nullable
        private Integer iconItemId;
        @SerializedName("icon_path")
        @Nullable
        private String iconPath;
        /** Tile badge in the legacy board style ("KC TARGET", "FULL SET"...). */
        @Nullable
        private String badge;
        /** Short value string ("100.00M GP", "sub 1:45"). */
        @Nullable
        private String value;
        @Nullable
        private String description;
        @Nullable
        private List<Requirement> requirements;
    }

    /** One item requirement of a task
     *  ({name, quantity?, points?, obtained?, icon_item_id?, icon_path?}). */
    @Getter
    public static class Requirement {
        private String name;
        @Nullable
        private Integer quantity;
        @Nullable
        private Integer points;
        /** True when the team has banked this item and re-receiving it can
         *  no longer advance the task (all_of/assembly) — rendered struck
         *  through. Absent on point/any_of tasks where re-receives count. */
        @Nullable
        private Boolean obtained;
        /** Optional server-authoritative item sprite id for this requirement;
         *  null on current servers (the panel then resolves it from the name). */
        @SerializedName("icon_item_id")
        @Nullable
        private Integer iconItemId;
        /** Optional allowlisted remote icon URL, used when there is no sprite. */
        @SerializedName("icon_path")
        @Nullable
        private String iconPath;
    }

    @Getter
    public static class Member {
        @SerializedName("player_id")
        private int playerId;
        private String name;
    }

    @Getter
    public static class BoardInfo {
        private boolean available;
        @SerializedName("team_id")
        private int teamId;
    }

    @Getter
    public static class Standing {
        @SerializedName("team_id")
        private int teamId;
        private String name;
        private int score;
        private int rank;
        @Nullable
        private String color;
    }
}
