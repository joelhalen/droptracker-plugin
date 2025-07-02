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
    // add for later -- these handlers don't exist "properly" yet
    @SerializedName("send_pets")
    private boolean sendPets;
    @SerializedName("send_kills")
    private boolean sendKills;
    @SerializedName("send_deaths")
    private boolean sendDeaths;

    /* Variables that we'll modify after init */
    @Setter
    private int lastUpdateUnix;
    

    public GroupConfig(String groupId, String groupName, int minValue, boolean onlyScreenshots, boolean sendDrops, boolean sendPbs, boolean sendClogs, boolean sendCAs, String minimumCATier, Integer minimumDropValue, boolean sendStackedItems, boolean sendPets, boolean sendKills, boolean sendDeaths) {
        this.groupId = groupId;
        this.groupName = groupName;
        this.minValue = minValue;
        this.onlyScreenshots = onlyScreenshots;
        this.sendDrops = sendDrops;
        this.sendPbs = sendPbs;
        this.sendClogs = sendClogs;
        this.sendCAs = sendCAs;
        this.minimumCATier = minimumCATier;
        this.minimumDropValue = minimumDropValue;
        this.sendStackedItems = sendStackedItems;
        this.sendPets = false;
        this.sendKills = false;
        this.sendDeaths = false;
    }   
}
