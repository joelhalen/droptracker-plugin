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
    
    @SerializedName("group_image_url")
    private String groupImageUrl;
    
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

    @SerializedName("public_discord_link")
    private String publicDiscordLink;
    
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
