package io.droptracker.models.api;

import com.google.gson.annotations.SerializedName;

import lombok.Getter;
import lombok.Setter;

@Getter
public class GroupConfig {
    /* Represents each of a players' group(s) configurations, defining what minimum values
     * and other notification-related settings they have configured for use with ValidSubmissions.
     */
    @SerializedName("group_id")
    private String groupId;
    @SerializedName("group_name")
    private String groupName;
    @SerializedName("min_value")
    private int minValue;
    @SerializedName("only_screenshots")
    private boolean onlyScreenshots;
    @SerializedName("send_drops")
    private boolean sendDrops;
    @SerializedName("send_pbs")
    private boolean sendPbs;
    @SerializedName("send_clogs")
    private boolean sendClogs;
    @SerializedName("send_cas")
    private boolean sendCAs;
    @SerializedName("minimum_ca_tier")
    private String minimumCATier;
    @SerializedName("minimum_drop_value")
    private Integer minimumDropValue;
    @SerializedName("send_stacked_items")
    private boolean sendStackedItems;
    @SerializedName("send_pets")
    private boolean sendPets;
    @SerializedName("send_quests")
    private boolean sendQuests;
    @SerializedName("send_xp")
    private boolean sendXP;
    @SerializedName("minimum_level")
    private int minimumLevel;
    // add for later -- these handlers don't exist "properly" yet
    @SerializedName("send_kills")
    private boolean sendKills;
    @SerializedName("send_deaths")
    private boolean sendDeaths;
    @SerializedName("send_diaries")
    private boolean sendDiaries;
    /* True when the API reports an active event with XP-based tasks that is
     * tracking this player; the plugin then submits periodic experience
     * snapshots instead of only level-ups. */
    @SerializedName("track_xp_events")
    private boolean trackXpEvents;
    /* True when ANY live event is tracking this player — the signal to poll
     * GET /notifications for in-game event notifications. */
    @SerializedName("active_event")
    private boolean activeEvent;

    /* Variables that we'll modify after init */
    @Setter
    private int lastUpdateUnix;
}
