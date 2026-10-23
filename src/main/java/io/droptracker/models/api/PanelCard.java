package io.droptracker.models.api;

import com.google.gson.annotations.SerializedName;
import io.droptracker.models.submissions.RecentSubmission;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.*;

/**
 * A detail card on the Groups or Players page, composed by the server
 * (api/panel_cards.py) and drawn by {@code SearchPage#showCard}. Plain text in
 * fixed slots, a tone from a closed set and actions from a closed set this
 * plugin implements; links are built on the plugin's own hosts.
 */
@Getter
public class PanelCard {
    private String title;
    /** Set on group cards: the group the lootboard and icon belong to. */
    @SerializedName("group_id")
    @Nullable
    private Integer groupId;
    /** Under {@code /img/}, on the plugin's own host. */
    @SerializedName("icon_path")
    @Nullable
    private String iconPath;
    @Nullable
    private Subtitle subtitle;
    @Nullable
    private String description;
    @SerializedName("list_title")
    @Nullable
    private String listTitle;
    private List<String> list = new ArrayList<>();
    private List<Stat> stats = new ArrayList<>();
    /** Null: not loaded yet, so the placeholder shows {@link #recentEmpty}. */
    @Nullable
    private List<RecentSubmission> recent;
    @SerializedName("recent_group")
    private boolean recentGroup;
    @SerializedName("recent_empty")
    private String recentEmpty;
    private List<Button> actions = new ArrayList<>();

    @Getter
    public static class Subtitle {
        private String text;
        /** "good" | "warn"; anything else is muted. */
        private String tone;
        @Nullable
        private String tip;
    }

    @Getter
    public static class Stat {
        private String label;
        private String value;
        @Nullable
        private Action action;
        @Nullable
        private String tip;
    }

    @Getter
    public static class Button {
        private String label;
        private Action action;
        /** "external" | "lootboard" | null. */
        @Nullable
        private String icon;
    }

    /** type: player | web | discord | lootboard | refresh. */
    @Getter
    public static class Action {
        private String type;
        @Nullable
        private String value;
    }

    /** One row of a top list; groups' first rows carry a preview {@link PanelCard}. */
    @Getter
    public static class Row {
        private String name;
        @SerializedName("total_loot")
        private String totalLoot;
        private Integer rank;
        @Nullable
        private PanelCard card;
    }

    /** GET /top_groups and /top_players (and the /panel_data copies). */
    public static class Top {
        public List<Row> groups;
        public List<Row> players;

        @Nullable
        public List<Row> rows() {
            return groups != null ? groups : players;
        }
    }

    /** GET /group_search and /player_search: the card next to the raw fields older plugins read. */
    public static class Search {
        public PanelCard card;
    }
}
