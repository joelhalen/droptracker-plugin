package io.droptracker.models.api;

import com.google.gson.annotations.SerializedName;

import io.droptracker.models.submissions.RecentSubmission;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class GroupSearchResult {
    @SerializedName("group_name")
    private String groupName;
    
    @SerializedName("group_description")
    private String groupDescription;
    
    /**
     * Path under {@code /img/} of the group's icon, e.g. {@code "clans/2/icon.png"}.
     * A path rather than a URL so the plugin, not the API, decides the host — see
     * {@link io.droptracker.api.DropTrackerUrls}.
     */
    @SerializedName("group_image_path")
    private String groupImagePath;

    @SerializedName("group_droptracker_id")
    private Integer groupDropTrackerId;
    
    @SerializedName("group_members")
    private String groupMembers;
    
    @SerializedName("group_rank")
    private String groupRank;
    
    @SerializedName("group_loot")
    private String groupLoot;
    
    @SerializedName("group_top_player")
    private String groupTopPlayer;
    
    @SerializedName("group_recent_submissions")
    private List<RecentSubmission> groupRecentSubmissions;
    
    @SerializedName("group_stats")
    private GroupStats groupStats;

    /** Invite code only, e.g. {@code "droptracker"}; the plugin builds the discord.gg link. */
    @SerializedName("discord_invite_code")
    private String discordInviteCode;
    
    // Raw JSON data for any additional fields not explicitly mapped
    private transient Map<String, Object> additionalData;

    @Data
    public static class GroupStats {
        @SerializedName("total_members")
        private int totalMembers;
        
        @SerializedName("monthly_loot")
        private String monthlyLoot;
        
        @SerializedName("global_rank")
        private String globalRank;
        
        @SerializedName("total_submissions")
        private int totalSubmissions;
    }
}
