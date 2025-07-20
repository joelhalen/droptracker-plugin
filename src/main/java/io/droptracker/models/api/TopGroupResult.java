package io.droptracker.models.api;

import java.util.List;
import java.util.Map;

import com.google.gson.annotations.SerializedName;

public class TopGroupResult {
    // Defines the top groups as returned by the API when the panel initially loads, or when the user refreshes the group page

    @SerializedName("groups")
    private List<TopGroup> groups;

    // Default constructor for Gson
    public TopGroupResult() {}

    // Remove the static gson field and static methods - they don't work with @Inject

    // Getter and setter
    public List<TopGroup> getGroups() {
        return groups;
    }

    public void setGroups(List<TopGroup> groups) {
        this.groups = groups;
    }

    // Nested class for individual group data
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

        // Default constructor for Gson
        public TopGroup() {}

        // Constructor for manual creation
        public TopGroup(String groupName, String totalLoot, Integer rank) {
            this.groupName = groupName;
            this.totalLoot = totalLoot;
            this.rank = rank;
        }

        // Getters
        public String getGroupName() {
            return groupName;
        }

        public String getTotalLoot() {
            return totalLoot;
        }

        public Integer getRank() {
            return rank;
        }

        public Integer getGroupId() {
            return groupId;
        }

        public Integer getMemberCount() {
            return memberCount;
        }

        // Setters
        public void setGroupName(String groupName) {
            this.groupName = groupName;
        }

        public void setTotalLoot(String totalLoot) {
            this.totalLoot = totalLoot;
        }

        public void setRank(Integer rank) {
            this.rank = rank;
        }

        public void setGroupId(Integer groupId) {
            this.groupId = groupId;
        }

        public void setMemberCount(Integer memberCount) {
            this.memberCount = memberCount;
        }

        public String getTopMemberString() {
            return topMemberString;
        }

        public void setTopMemberString(String topMemberString) {
            this.topMemberString = topMemberString;
        }
    }
    
}

