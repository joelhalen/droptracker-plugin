package io.droptracker.models.api;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import net.runelite.client.game.ItemStack;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    public List<RecentSubmission> getRecentSubmissions() {
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
        
        @SerializedName("submission_type") // pb, clog, drop    
        private String submissionType;  
        
        @SerializedName("source_name") 
        private String sourceName;
        
        @SerializedName("date_received")
        private String dateReceived;
        
        @SerializedName("display_name") 
        private String displayName;

        @SerializedName("value") // if not a pb
        private String value;

        @SerializedName("data")
        private List<Map<String, Object>> data;

        @SerializedName("image_url")
        private String imageUrl;

        // Constructors
        public RecentSubmission() {} // Default constructor for Gson
        
        public RecentSubmission(String playerName, String submissionType, String sourceName, String dateReceived, String displayName, long value, List<Map<String, Object>> data, String imageUrl) {
            this.playerName = playerName;
            this.submissionType = submissionType;
            this.sourceName = sourceName;
            this.dateReceived = dateReceived;
            this.displayName = displayName;
            this.value = value == 0 ? "-1" : String.valueOf(value);
            this.data = data;
            this.imageUrl = imageUrl;
        }

        @Override
        public String toString() {
            return "RecentSubmission{" +
                "playerName='" + playerName + '\'' +
                ", submissionType='" + submissionType + '\'' +
                ", sourceName='" + sourceName + '\'' +
                ", dateReceived='" + dateReceived + '\'' +
                ", displayName='" + displayName + '\'' +
                ", value='" + value + '\'' +
                ", data=" + data +
                ", imageUrl='" + imageUrl + '\'' +
                '}';
        }

        // Getters and setters
        public String getPlayerName() { return playerName; }
        public void setPlayerName(String playerName) { this.playerName = playerName; }
        
        public String getSubmissionType() { return submissionType; }
        public void setSubmissionType(String submissionType) { this.submissionType = submissionType; }
        
        public String getSourceName() { return sourceName; }
        public void setSourceName(String sourceName) { this.sourceName = sourceName; }
        
        public String getDateReceived() { return dateReceived; }
        public void setDateReceived(String dateReceived) { this.dateReceived = dateReceived; }
        
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        
        public String getValue() { return value == "-1" ? "0" : value; }
        public void setValue(String value) { this.value = value == "0" ? "-1" : value; }
        
        public List<Map<String, Object>> getData() { return data; }
        public void setData(List<Map<String, Object>> data) { this.data = data; }

        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

        public String timeSinceReceived() {
            if (dateReceived == null) {
                return "Unknown";
            }
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
                LocalDateTime receivedDate = LocalDateTime.parse(dateReceived, formatter);
                LocalDateTime now = LocalDateTime.now();
                
                Duration duration = Duration.between(receivedDate, now);
                
                if (duration.toDays() > 0) {
                    return duration.toDays() + " days ago";
                } else if (duration.toHours() > 0) {
                    return duration.toHours() + " hours ago"; 
                } else if (duration.toMinutes() > 0) {
                    return duration.toMinutes() + " minutes ago";
                } else {
                    return "Just now";
                }
            } catch (Exception e) {
                return "Unknown";
            }
        }

        // Generic method to extract data by type and key
        private Object getDataValueByTypeAndKey(String dataType, String key) {
            if (data == null || data.isEmpty()) {
                return null;
            }
            
            for (Map<String, Object> dataEntry : data) {
                if (dataEntry != null && dataEntry.containsKey("type")) {
                    String entryType = dataEntry.get("type").toString();
                    if (entryType.equalsIgnoreCase(dataType) && dataEntry.containsKey(key)) {
                        return dataEntry.get(key);
                    }
                }
            }
            return null;
        }

        // Generic method to get all data entries of a specific type
       

        // Personal Best related methods
        public String getPbTime() {
            if (!submissionType.equalsIgnoreCase("pb")) {
                return null;
            }
            
            Object timeValue = getDataValueByTypeAndKey("best_time", "time");
            return timeValue != null ? timeValue.toString() : null;
        }

        
        // Drop related methods
        public String getDropItemName() {
            if (!submissionType.equalsIgnoreCase("drop")) {
                return null;
            }
            
            Object itemName = getDataValueByTypeAndKey("item", "name");
            return itemName != null ? itemName.toString() : null;
        }

        public Integer getDropItemId() {
            if (!submissionType.equalsIgnoreCase("drop")) {
                return null;
            }
            
            Object itemId = getDataValueByTypeAndKey("item", "id");
            if (itemId != null) {
                try {
                    // Handle both Integer and Double/Float types from JSON
                    if (itemId instanceof Number) {
                        return ((Number) itemId).intValue();
                    }
                    return Integer.valueOf(itemId.toString().replaceAll("\\.0*$", ""));
                } catch (NumberFormatException e) {
                    System.err.println("Failed to parse item ID: " + itemId + " - " + e.getMessage());
                    return null;
                }
            }
            return null;
        }

        public Integer getDropQuantity() {
            if (!submissionType.equalsIgnoreCase("drop")) {
                return null;
            }
            
            Object quantity = getDataValueByTypeAndKey("item", "quantity");
            if (quantity != null) {
                try {
                    // Handle both Integer and Double/Float types from JSON
                    if (quantity instanceof Number) {
                        return ((Number) quantity).intValue();
                    }
                    return Integer.valueOf(quantity.toString().replaceAll("\\.0*$", ""));
                } catch (NumberFormatException e) {
                    System.err.println("Failed to parse quantity: " + quantity + " - " + e.getMessage());
                    return 1; // Default to 1 if parsing fails
                }
            }
            // Default to 1 if quantity field is missing (as it appears to be in your API)
            return 1;
        }

        public Long getDropValue() {
            if (!submissionType.equalsIgnoreCase("drop")) {
                return null;
            }
            
            Object dropValue = getDataValueByTypeAndKey("item", "value");
            if (dropValue != null) {
                try {
                    // Handle value with suffixes like "48.98M", "1.14M", etc.
                    String valueStr = dropValue.toString();
                    if (valueStr.contains("M")) {
                        double millions = Double.parseDouble(valueStr.replace("M", ""));
                        return (long) (millions * 1_000_000);
                    } else if (valueStr.contains("K")) {
                        double thousands = Double.parseDouble(valueStr.replace("K", ""));
                        return (long) (thousands * 1_000);
                    } else if (valueStr.contains("B")) {
                        double billions = Double.parseDouble(valueStr.replace("B", ""));
                        return (long) (billions * 1_000_000_000);
                    } else {
                        return Long.valueOf(valueStr.replaceAll("\\.0*$", ""));
                    }
                } catch (NumberFormatException e) {
                    System.err.println("Failed to parse drop value: " + dropValue + " - " + e.getMessage());
                    return null;
                }
            }
            return null;
        }

        // Collection Log related methods
        public String getClogItemName() {
            if (!submissionType.equalsIgnoreCase("clog")) {
                return null;
            }
            
            Object itemName = getDataValueByTypeAndKey("clog_item", "name");
            return itemName != null ? itemName.toString() : null;
        }

        public Integer getClogItemId() {
            if (!submissionType.equalsIgnoreCase("clog")) {
                return null;
            }
            
            Object itemId = getDataValueByTypeAndKey("clog_item", "id");
            if (itemId != null) {
                try {
                    // Handle both Integer and Double/Float types from JSON
                    if (itemId instanceof Number) {
                        return ((Number) itemId).intValue();
                    }
                    return Integer.valueOf(itemId.toString().replaceAll("\\.0*$", ""));
                } catch (NumberFormatException e) {
                    System.err.println("Failed to parse clog item ID: " + itemId + " - " + e.getMessage());
                    return null;
                }
            }
            return null;
        }

        public Integer getClogKillCount() {
            if (!submissionType.equalsIgnoreCase("clog")) {
                return null;
            }
            
            Object kcValue = getDataValueByTypeAndKey("clog_item", "kill_count");
            if (kcValue != null) {
                try {
                    // Handle both Integer and Double/Float types from JSON
                    if (kcValue instanceof Number) {
                        return ((Number) kcValue).intValue();
                    }
                    return Integer.valueOf(kcValue.toString().replaceAll("\\.0*$", ""));
                } catch (NumberFormatException e) {
                    System.err.println("Failed to parse kill count: " + kcValue + " - " + e.getMessage());
                    return null;
                }
            }
            return null;
        }

        // Utility methods for checking submission types
        public boolean isPersonalBest() {
            return submissionType != null && submissionType.equalsIgnoreCase("pb");
        }

        public boolean isDrop() {
            return submissionType != null && submissionType.equalsIgnoreCase("drop");
        }

        public boolean isCollectionLog() {
            return submissionType != null && submissionType.equalsIgnoreCase("clog");
        }

        // Method to get a formatted display string based on submission type
        public String getFormattedDisplay() {
            if (isPersonalBest()) {
                String time = getPbTime();
                return time != null ? String.format("PB: %s", time) : "Personal Best";
            } else if (isDrop()) {
                String itemName = getDropItemName();
                Integer quantity = getDropQuantity();
                if (itemName != null && quantity != null) {
                    return String.format("%s x%d", itemName, quantity);
                } else if (itemName != null) {
                    return itemName;
                }
                return "Drop";
            } else if (isCollectionLog()) {
                String itemName = getClogItemName();
                return itemName != null ? String.format("Clog: %s", itemName) : "Collection Log";
            }
            return displayName != null ? displayName : "Unknown";
        }

        

        // Method to get raw data entry by type (for custom processing)
        public Map<String, Object> getDataEntryByType(String dataType) {
            if (data == null || data.isEmpty()) {
                return null;
            }
            
            return data.stream()
                .filter(entry -> entry != null && entry.containsKey("type"))
                .filter(entry -> entry.get("type").toString().equalsIgnoreCase(dataType))
                .findFirst()
                .orElse(null);
        }
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
