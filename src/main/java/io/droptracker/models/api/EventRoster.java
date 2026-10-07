package io.droptracker.models.api;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Response of GET /event_roster: who is on which team, for the clan-chat team
 * badges.
 *
 * The roster is the event's real one — nobody types names into a panel — so a
 * player an admin moved between teams is badged correctly on the next refresh,
 * and a clanmate who has never opened the website is badged at all.
 *
 * Fetched only when the {@code roster_version} on /event_state stops matching
 * what the client holds, which is why a payload this size can carry a whole
 * event's membership without costing anything on a settled event.
 */
@Getter
public class EventRoster {
    @Nullable
    private List<Entry> events;

    @Getter
    public static class Entry {
        @SerializedName("event_id")
        private int eventId;
        @SerializedName("roster_version")
        @Nullable
        private String rosterVersion;
        @Nullable
        private List<Team> teams;
        /**
         * Team id (as a string key) to the names on it, already normalized
         * server-side for comparison: lowercased, with '-' and '_' folded to
         * spaces. Chat senders are put through the matching client-side
         * normalizer before lookup — never compare a raw name.
         */
        @Nullable
        private Map<String, List<String>> members;
        @SerializedName("members_total")
        private int membersTotal;
        /** True when teams or names were capped; the map is then partial. */
        private boolean truncated;
    }

    @Getter
    public static class Team {
        private int id;
        private String name;
        /** Short label printed beside a teammate's name, e.g. "RR". */
        @SerializedName("short_tag")
        @Nullable
        private String shortTag;
        /** Admin-set accent, "#rrggbb", or null. */
        @Nullable
        private String color;
        /**
         * The circle Discord shows on this team's channel. Chat cannot draw an
         * emoji, so this is carried for parity/diagnostics only — the sprite is
         * drawn from {@link #orbColor}.
         */
        @Nullable
        private String orb;
        /** That circle's own fill, so the in-game badge matches Discord. */
        @SerializedName("orb_color")
        @Nullable
        private String orbColor;
        @SerializedName("icon_item_id")
        @Nullable
        private Integer iconItemId;
        @SerializedName("icon_path")
        @Nullable
        private String iconPath;
    }
}
