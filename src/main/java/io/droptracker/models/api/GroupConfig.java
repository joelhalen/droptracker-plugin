package io.droptracker.models.api;

import com.google.gson.annotations.SerializedName;

import lombok.Getter;
import lombok.Setter;

/**
 * Represents a single group's notification configuration as returned by the DropTracker API's
 * {@code /load_config} endpoint.
 *
 * <p>Each player may belong to one or more groups, each with independently configurable
 * notification rules. {@link io.droptracker.service.SubmissionManager} iterates over all
 * loaded {@code GroupConfig} instances for each submission to determine which groups should
 * receive a Discord notification for that event.</p>
 *
 * <p>Field names are mapped from the API's snake_case JSON keys via {@link SerializedName}.</p>
 */
@Getter
public class GroupConfig {

    /** Unique identifier for this group (used when creating {@link io.droptracker.models.submissions.ValidSubmission}). */
    @SerializedName("group_id")
    private String groupId;

    /** Human-readable display name for the group. */
    @SerializedName("group_name")
    private String groupName;

    /** Legacy minimum value field; superseded by {@link #minimumDropValue}. */
    @SerializedName("min_value")
    private int minValue;

    /**
     * When {@code true}, a submission is only forwarded to this group if a screenshot
     * is also being captured for it. Allows groups to opt in to image-only notifications.
     */
    @SerializedName("only_screenshots")
    private boolean onlyScreenshots;

    /** Whether this group wants to receive loot drop notifications. */
    @SerializedName("send_drops")
    private boolean sendDrops;

    /** Whether this group wants to receive personal-best time notifications. */
    @SerializedName("send_pbs")
    private boolean sendPbs;

    /** Whether this group wants to receive collection-log slot unlock notifications. */
    @SerializedName("send_clogs")
    private boolean sendClogs;

    /** Whether this group wants to receive combat achievement notifications. */
    @SerializedName("send_cas")
    private boolean sendCAs;

    /**
     * Minimum combat achievement tier (e.g. {@code "Easy"}, {@code "Medium"}, {@code "Hard"})
     * for which this group wants notifications. Tiers below this value are skipped.
     */
    @SerializedName("minimum_ca_tier")
    private String minimumCATier;

    /**
     * Minimum loot value (in GP) required before a drop triggers a notification for this group.
     * Drops with a total value below this threshold are ignored.
     */
    @SerializedName("minimum_drop_value")
    private Integer minimumDropValue;

    /**
     * When {@code false}, drops whose total stack value exceeds the single-item value are excluded
     * (i.e. the group only wants notifications for single-item drops, not stacked loot).
     */
    @SerializedName("send_stacked_items")
    private boolean sendStackedItems;

    /** Whether this group wants to receive pet drop notifications. */
    @SerializedName("send_pets")
    private boolean sendPets;

    /** Whether this group wants to receive quest completion notifications. */
    @SerializedName("send_quests")
    private boolean sendQuests;

    /** Whether this group wants to receive XP / level-up notifications. */
    @SerializedName("send_xp")
    private boolean sendXP;

    /** Minimum skill level required before a level-up triggers a notification for this group. */
    @SerializedName("minimum_level")
    private int minimumLevel;

    // The following two fields are reserved for future kill/death event handlers
    // that are not yet fully implemented.

    /** Reserved: whether this group wants kill-count notifications (handler not yet implemented). */
    @SerializedName("send_kills")
    private boolean sendKills;

    /** Reserved: whether this group wants death notifications (handler not yet implemented). */
    @SerializedName("send_deaths")
    private boolean sendDeaths;

    /** Unix timestamp of the last time this config was refreshed from the API; settable post-init. */
    @Setter
    private int lastUpdateUnix;
}
