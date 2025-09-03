package io.droptracker.models.api;

import com.google.gson.annotations.SerializedName;

import io.droptracker.models.submissions.RecentSubmission;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
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

    @Data
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
    }

    @Data
    public static class PlayerStats {
        @SerializedName("total_submissions")
        private int totalSubmissions;
        
        @SerializedName("total_loot_value")
        private long totalLootValue;
        
        @SerializedName("favorite_boss")
        private String favoriteBoss;
        
        
        @SerializedName("registration_date")
        private String registrationDate;
    }

    @Data
    public static class TopNpc {
        @SerializedName("name")
        private String name;
        
        @SerializedName("rank")
        private Integer rank;
        
        @SerializedName("loot")
        private String loot;
    }

    @Data
    public static class PlayerGroup {
        @SerializedName("name")
        private String name;
        
        @SerializedName("id")
        private Integer id;
        
        @SerializedName("loot")
        private String loot;
        
        @SerializedName("members")
        private Integer members;
    }
}
