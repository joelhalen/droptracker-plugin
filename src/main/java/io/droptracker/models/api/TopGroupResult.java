package io.droptracker.models.api;

import java.util.List;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/// Defines the top groups as returned by the API when the panel initially loads, or when the user refreshes the group page
@Data
public class TopGroupResult {
    @SerializedName("groups")
    private List<TopGroup> groups;

    /// Nested class for individual group data
    @Data
    public static class TopGroup {
        @SerializedName("group_name")
        private String groupName;
        
        @SerializedName("total_loot")
        private String totalLoot;
        
        @SerializedName("rank")
        private Integer rank;
        
        @SerializedName("group_id")
        private Integer groupId;
        
        @SerializedName("member_count")
        private Integer memberCount;

        @SerializedName("top_member")
        private String topMemberString;
    }
    
}
