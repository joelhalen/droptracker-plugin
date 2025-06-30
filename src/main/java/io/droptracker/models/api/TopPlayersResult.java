package io.droptracker.models.api;

import java.util.List;
import com.google.gson.annotations.SerializedName;

public class TopPlayersResult {
    private List<TopPlayer> players;

    public List<TopPlayer> getPlayers() {
        return players;
    }

    public void setPlayers(List<TopPlayer> players) {
        this.players = players;
    }

    public static class TopPlayer {
        @SerializedName("player_name")
        private String playerName;
        
        @SerializedName("rank") 
        private Integer rank;
        
        @SerializedName("total_loot")
        private String totalLoot;

        public TopPlayer() {}

        public TopPlayer(String playerName, Integer rank, String totalLoot) {
            this.playerName = playerName;
            this.rank = rank;
            this.totalLoot = totalLoot;
        }

        public String getPlayerName() {
            return playerName;
        }

        public void setPlayerName(String playerName) {
            this.playerName = playerName;
        }

        public Integer getRank() {
            return rank;
        }

        public void setRank(Integer rank) {
            this.rank = rank;
        }

        public String getTotalLoot() {
            return totalLoot;
        }

        public void setTotalLoot(String totalLoot) {
            this.totalLoot = totalLoot;
        }
    }
} 