package io.droptracker.models.api;

import java.util.*;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

import lombok.*;

/**
 * Payloads of the plugin's group-admin endpoints ({@code /plugin/admin/...}).
 *
 * <p>The server describes everything the manager window shows: which groups
 * the account may manage, the overview tiles, and every setting as a typed
 * field. The plugin only knows how to draw a fixed set of field types and
 * evaluates nothing it receives, so a new setting on the website needs no
 * plugin update, and the plugin never does anything a reviewer can't see here.
 */
public final class GroupAdmin {

    private GroupAdmin() {
    }

    /** {@code GET /plugin/admin/groups}: the groups this account owns or administers. */
    @Getter
    public static class Groups {
        private List<Group> groups = new ArrayList<>();
    }

    @Getter
    public static class Group {
        private int id;
        private String name;
        /** "owner" or "admin". */
        private String role;
        /** A device session for this group is stored and still valid. */
        private boolean linked;
        @SerializedName("icon_url")
        private String iconUrl;
        @SerializedName("member_count")
        private int memberCount;
        /** Subscription tier label, e.g. "Supporter"; null on the free tier. */
        private String tier;
    }

    /** {@code POST /plugin/admin/link}: a code exchanged for a device session. */
    @Getter
    public static class Link {
        private String token;
        private Group group;
    }

    /** {@code GET /plugin/admin/groups/{id}/overview}. */
    @Getter
    public static class Overview {
        private Group group;
        private List<Stat> stats = new ArrayList<>();
        private List<Check> checklist = new ArrayList<>();
    }

    @Getter
    public static class Stat {
        private String label;
        private String value;
        /** Optional small line under the value, e.g. "+12 this week". */
        private String detail;
    }

    /** One line of the setup checklist: done, or what to do about it. */
    @Getter
    public static class Check {
        private String label;
        private boolean ok;
        private String hint;
    }

    /** {@code GET/PATCH /plugin/admin/groups/{id}/settings}. */
    @Getter
    public static class Settings {
        private List<Category> categories = new ArrayList<>();
        private List<Field> fields = new ArrayList<>();
        /** The group's Discord channels, for channel fields. */
        private List<Choice> channels = new ArrayList<>();
        /** The group's Discord roles, for role fields. */
        private List<Choice> roles = new ArrayList<>();
        /** PATCH only: per-key problems, written for the leader. */
        private Map<String, String> errors = new HashMap<>();
    }

    @Getter
    public static class Category {
        private String key;
        private String label;
        private String help;
    }

    /**
     * One setting. {@code type} is one of {@link #KNOWN_TYPES}; anything else
     * is shown read-only with a link to the website.
     */
    @Getter
    public static class Field {
        private String key;
        private String label;
        private String help;
        private String category;
        private String type;
        private JsonElement value;
        private List<Choice> options = new ArrayList<>();
        private Long min;
        private Long max;
        /** Not editable here, e.g. a premium setting on a free group. */
        private boolean locked;
        @SerializedName("locked_reason")
        private String lockedReason;
    }

    /** A select option, channel or role. {@code group} is a heading (e.g. a channel category). */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Choice {
        private String value;
        private String label;
        private String group;
    }

    /** Field types the plugin draws itself. */
    public static final Set<String> KNOWN_TYPES = new HashSet<>(Arrays.asList(
        "boolean", "int", "string", "text", "select", "channel", "role", "csv"));
}
