package io.droptracker.models.api;

import java.util.List;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.Value;

@Data
public class TopPlayersResult {
    private List<TopPlayer> players;

    @Value
    public static class TopPlayer {
        @SerializedName("player_name")
        String playerName;
        
        @SerializedName("rank")
        Integer rank;
        
        @SerializedName("total_loot")
        String totalLoot;
    }
} 