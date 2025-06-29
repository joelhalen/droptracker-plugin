package io.droptracker.models.api;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import net.runelite.client.game.ItemStack;

import java.util.List;
import java.util.Map;

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
    
    private static final Gson gson = new Gson();

    // Default constructor for Gson
    public GroupSearchResult() {}

    // Constructor for manual creation (keeping your original signature)
    public GroupSearchResult(String groupName, String groupDescription, String groupImageUrl, 
                           Integer groupDropTrackerId, String groupMembers, String groupRank, 
                           String groupLoot, String groupTopPlayer) {
        this.groupName = groupName;
        this.groupDescription = groupDescription;
        this.groupImageUrl = groupImageUrl;
        this.groupDropTrackerId = groupDropTrackerId;
        this.groupMembers = groupMembers;
        this.groupRank = groupRank;
        this.groupLoot = groupLoot;
        this.groupTopPlayer = groupTopPlayer;
    }

    // Static factory method to create from JSON string
    public static GroupSearchResult fromJson(String jsonString) {
        return gson.fromJson(jsonString, GroupSearchResult.class);
    }
    
    // Static factory method to create from JSON object (Map)
    public static GroupSearchResult fromJsonMap(Map<String, Object> jsonMap) {
        String jsonString = gson.toJson(jsonMap);
        return gson.fromJson(jsonString, GroupSearchResult.class);
    }

    // Convert back to JSON string
    public String toJson() {
        return gson.toJson(this);
    }

    // Getters
    public String getGroupName() {
        return groupName;
    }

    public String getGroupDescription() {
        return groupDescription;
    }

    public String getGroupImageUrl() {
        return groupImageUrl;
    }

    public Integer getGroupDropTrackerId() {
        return groupDropTrackerId;
    }

    public String getGroupMembers() {
        return groupMembers;
    }

    public String getGroupRank() {
        return groupRank;
    }

    public String getGroupLoot() {
        return groupLoot;
    }

    public String getGroupTopPlayer() {
        return groupTopPlayer;
    }

    public List<RecentSubmission> getGroupRecentSubmissions() {
        return groupRecentSubmissions;
    }

    public GroupStats getGroupStats() {
        return groupStats;
    }

    public String getPublicDiscordLink() {
        return publicDiscordLink;
    }

    public Map<String, Object> getAdditionalData() {
        return additionalData;
    }

    // Setters
    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public void setGroupDescription(String groupDescription) {
        this.groupDescription = groupDescription;
    }

    public void setGroupImageUrl(String groupImageUrl) {
        this.groupImageUrl = groupImageUrl;
    }

    public void setGroupMembers(String groupMembers) {
        this.groupMembers = groupMembers;
    }

    public void setGroupRank(String groupRank) {
        this.groupRank = groupRank;
    }

    public void setGroupLoot(String groupLoot) {
        this.groupLoot = groupLoot;
    }

    public void setGroupTopPlayer(String groupTopPlayer) {
        this.groupTopPlayer = groupTopPlayer;
    }

    public void setGroupRecentSubmissions(List<RecentSubmission> groupRecentSubmissions) {
        this.groupRecentSubmissions = groupRecentSubmissions;
    }

    public void setGroupStats(GroupStats groupStats) {
        this.groupStats = groupStats;
    }

    public void setGroupDropTrackerId(Integer groupDropTrackerId) {
        this.groupDropTrackerId = groupDropTrackerId;
    }

    public void setPublicDiscordLink(String publicDiscordLink) {
        this.publicDiscordLink = publicDiscordLink;
    }

    public void setAdditionalData(Map<String, Object> additionalData) {
        this.additionalData = additionalData;
    }

    // Nested classes for complex JSON structures
    public static class RecentSubmission {
        @SerializedName("player_name")
        private String playerName;
        
        @SerializedName("loot_value")
        private long lootValue;
        
        @SerializedName("boss_name")
        private String bossName;
        
        @SerializedName("submission_date")
        private String submissionDate;
        
        @SerializedName("items")
        private List<ItemStack> items;

        // Constructors
        public RecentSubmission() {}

        // Getters and setters
        public String getPlayerName() { return playerName; }
        public void setPlayerName(String playerName) { this.playerName = playerName; }
        
        public long getLootValue() { return lootValue; }
        public void setLootValue(long lootValue) { this.lootValue = lootValue; }
        
        public String getBossName() { return bossName; }
        public void setBossName(String bossName) { this.bossName = bossName; }
        
        public String getSubmissionDate() { return submissionDate; }
        public void setSubmissionDate(String submissionDate) { this.submissionDate = submissionDate; }
        
        public List<ItemStack> getItems() { return items; }
        public void setItems(List<ItemStack> items) { this.items = items; }
    }

    public static class GroupMember {
        @SerializedName("player_name")
        private String playerName;
        
        @SerializedName("total_loot")
        private long totalLoot;
        
        @SerializedName("rank")
        private int rank;
        
        @SerializedName("join_date")
        private String joinDate;

        // Constructors
        public GroupMember() {}

        // Getters and setters
        public String getPlayerName() { return playerName; }
        public void setPlayerName(String playerName) { this.playerName = playerName; }
        
        public long getTotalLoot() { return totalLoot; }
        public void setTotalLoot(long totalLoot) { this.totalLoot = totalLoot; }
        
        public int getRank() { return rank; }
        public void setRank(int rank) { this.rank = rank; }
        
        public String getJoinDate() { return joinDate; }
        public void setJoinDate(String joinDate) { this.joinDate = joinDate; }
    }

    public static class GroupStats {
        @SerializedName("total_members")
        private int totalMembers;
        
        @SerializedName("monthly_loot")
        private String monthlyLoot;
        
        @SerializedName("global_rank")
        private String globalRank;
        
        @SerializedName("total_submissions")
        private int totalSubmissions;

        // Constructors
        public GroupStats() {}

        // Getters and setters
        public int getTotalMembers() { return totalMembers; }
        public void setTotalMembers(int totalMembers) { this.totalMembers = totalMembers; }
        
        public String getMonthlyLoot() { return monthlyLoot; }
        public void setMonthlyLoot(String monthlyLoot) { this.monthlyLoot = monthlyLoot; }
        
        public String getGlobalRank() { return globalRank; }
        public void setGlobalRank(String globalRank) { this.globalRank = globalRank; }
        
        public int getTotalSubmissions() { return totalSubmissions; }
        public void setTotalSubmissions(int totalSubmissions) { this.totalSubmissions = totalSubmissions; }
    }

    
}
