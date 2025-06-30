package io.droptracker.models.api;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Map;

public class PlayerSearchResult {
    @SerializedName("player_name")
    private String playerName;
    
    @SerializedName("droptracker_player_id")
    private Integer dropTrackerPlayerId;
    
    @SerializedName("registered")
    private boolean registered;
    
    @SerializedName("total_loot")
    private String totalLoot;
    
    @SerializedName("global_rank")
    private int globalRank;
    
    @SerializedName("top_npc")
    private TopNpc topNpc;
    
    @SerializedName("best_pb_rank")
    private Integer bestPbRank;
    
    @SerializedName("groups")
    private List<PlayerGroup> groups;
    
    @SerializedName("recent_submissions")
    private List<RecentSubmission> recentSubmissions;
    
    @SerializedName("top_npcs_by_loot")
    private List<TopNpcByLoot> topNpcsByLoot;
    
    @SerializedName("player_stats")  
    private PlayerStats playerStats;
    
    // Raw JSON data for any additional fields not explicitly mapped
    private transient Map<String, Object> additionalData;
    
    private static final Gson gson = new Gson();

    // Default constructor for Gson
    public PlayerSearchResult() {}

    // Constructor for manual creation
    public PlayerSearchResult(String playerName, Integer dropTrackerPlayerId, boolean registered, 
                            String totalLoot, int globalRank, TopNpc topNpc, Integer bestPbRank) {
        this.playerName = playerName;
        this.dropTrackerPlayerId = dropTrackerPlayerId;
        this.registered = registered;
        this.totalLoot = totalLoot;
        this.globalRank = globalRank;
        this.topNpc = topNpc;
        this.bestPbRank = bestPbRank;
    }

    // Static factory method to create from JSON string
    public static PlayerSearchResult fromJson(String jsonString) {
        return gson.fromJson(jsonString, PlayerSearchResult.class);
    }
    
    // Static factory method to create from JSON object (Map)
    public static PlayerSearchResult fromJsonMap(Map<String, Object> jsonMap) {
        String jsonString = gson.toJson(jsonMap);
        return gson.fromJson(jsonString, PlayerSearchResult.class);
    }

    // Convert back to JSON string
    public String toJson() {
        return gson.toJson(this);
    }

    // Getters
    public String getPlayerName() {
        return playerName;
    }

    public Integer getDropTrackerPlayerId() {
        return dropTrackerPlayerId;
    }

    public boolean isRegistered() {
        return registered;
    }

    public String getTotalLoot() {
        return totalLoot;
    }

    public int getGlobalRank() {
        return globalRank;
    }

    public TopNpc getTopNpc() {
        return topNpc;
    }

    public Integer getBestPbRank() {
        return bestPbRank;
    }

    public List<PlayerGroup> getGroups() {
        return groups;
    }

    public List<RecentSubmission> getRecentSubmissions() {
        return recentSubmissions;
    }

    public List<TopNpcByLoot> getTopNpcsByLoot() {
        return topNpcsByLoot;
    }

    public PlayerStats getPlayerStats() {
        return playerStats;
    }

    public Map<String, Object> getAdditionalData() {
        return additionalData;
    }

    // Setters
    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public void setDropTrackerPlayerId(Integer dropTrackerPlayerId) {
        this.dropTrackerPlayerId = dropTrackerPlayerId;
    }

    public void setRegistered(boolean registered) {
        this.registered = registered;
    }

    public void setTotalLoot(String totalLoot) {
        this.totalLoot = totalLoot;
    }

    public void setGlobalRank(int globalRank) {
        this.globalRank = globalRank;
    }

    public void setTopNpc(TopNpc topNpc) {
        this.topNpc = topNpc;
    }

    public void setBestPbRank(Integer bestPbRank) {
        this.bestPbRank = bestPbRank;
    }

    public void setGroups(List<PlayerGroup> groups) {
        this.groups = groups;
    }

    public void setRecentSubmissions(List<RecentSubmission> recentSubmissions) {
        this.recentSubmissions = recentSubmissions;
    }

    public void setTopNpcsByLoot(List<TopNpcByLoot> topNpcsByLoot) {
        this.topNpcsByLoot = topNpcsByLoot;
    }

    public void setPlayerStats(PlayerStats playerStats) {
        this.playerStats = playerStats;
    }

    public void setAdditionalData(Map<String, Object> additionalData) {
        this.additionalData = additionalData;
    }

    
    public static class TopNpcByLoot {
        @SerializedName("npc_name")
        private String npcName;
        
        @SerializedName("total_loot_value")
        private long totalLootValue;
        
        @SerializedName("kill_count")
        private int killCount;
        
        @SerializedName("average_loot")
        private long averageLoot;
        
        @SerializedName("best_drop_value")
        private long bestDropValue;
        
        @SerializedName("rank")
        private Integer rank;

        // Constructors
        public TopNpcByLoot() {}

        // Getters and setters
        public String getNpcName() { return npcName; }
        public void setNpcName(String npcName) { this.npcName = npcName; }
        
        public long getTotalLootValue() { return totalLootValue; }
        public void setTotalLootValue(long totalLootValue) { this.totalLootValue = totalLootValue; }
        
        public int getKillCount() { return killCount; }
        public void setKillCount(int killCount) { this.killCount = killCount; }
        
        public long getAverageLoot() { return averageLoot; }
        public void setAverageLoot(long averageLoot) { this.averageLoot = averageLoot; }
        
        public long getBestDropValue() { return bestDropValue; }
        public void setBestDropValue(long bestDropValue) { this.bestDropValue = bestDropValue; }
        
        public Integer getRank() { return rank; }
        public void setRank(Integer rank) { this.rank = rank; }
    }

    public static class PlayerStats {
        @SerializedName("total_submissions")
        private int totalSubmissions;
        
        @SerializedName("total_loot_value")
        private long totalLootValue;
        
        @SerializedName("favorite_boss")
        private String favoriteBoss;
        
        
        @SerializedName("registration_date")
        private String registrationDate;

        // Constructors
        public PlayerStats() {}

        // Getters and setters
        public int getTotalSubmissions() { return totalSubmissions; }
        public void setTotalSubmissions(int totalSubmissions) { this.totalSubmissions = totalSubmissions; }
        
        public long getTotalLootValue() { return totalLootValue; }
        public void setTotalLootValue(long totalLootValue) { this.totalLootValue = totalLootValue; }
        
        public String getFavoriteBoss() { return favoriteBoss; }
        public void setFavoriteBoss(String favoriteBoss) { this.favoriteBoss = favoriteBoss; }
        
        public String getRegistrationDate() { return registrationDate; }
        public void setRegistrationDate(String registrationDate) { this.registrationDate = registrationDate; }
    }

    public static class TopNpc {
        @SerializedName("name")
        private String name;
        
        @SerializedName("rank")
        private Integer rank;
        
        @SerializedName("loot")
        private String loot;

        // Constructors
        public TopNpc() {}

        public TopNpc(String name, Integer rank, String loot) {
            this.name = name;
            this.rank = rank;
            this.loot = loot;
        }

        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public Integer getRank() { return rank; }
        public void setRank(Integer rank) { this.rank = rank; }
        
        public String getLoot() { return loot; }
        public void setLoot(String loot) { this.loot = loot; }
    }

    public static class PlayerGroup {
        @SerializedName("name")
        private String name;
        
        @SerializedName("id")
        private Integer id;
        
        @SerializedName("loot")
        private String loot;
        
        @SerializedName("members")
        private Integer members;

        // Constructors
        public PlayerGroup() {}

        public PlayerGroup(String name, Integer id, String loot, Integer members) {
            this.name = name;
            this.id = id;
            this.loot = loot;
            this.members = members;
        }

        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        
        public String getLoot() { return loot; }
        public void setLoot(String loot) { this.loot = loot; }
        
        public Integer getMembers() { return members; }
        public void setMembers(Integer members) { this.members = members; }
    }
}
