package io.droptracker.models.submissions;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

import com.google.gson.annotations.SerializedName;
import lombok.ToString;

/// Nested classes for complex JSON structures
@ToString
public class RecentSubmission {
    @SerializedName("player_name")
    @Getter @Setter
    private String playerName;
    
    @SerializedName("submission_type") // pb, clog, drop  
    @Getter @Setter  
    private String submissionType;  
    
    @SerializedName("source_name") 
    @Getter @Setter
    private String sourceName;
    
    @SerializedName("date_received")
    @Getter @Setter
    private String dateReceived;
    
    @SerializedName("display_name") 
    @Getter @Setter
    private String displayName;

    @SerializedName("value") // if not a pb
    @Getter @Setter
    private String value;

    @SerializedName("data")
    @Getter @Setter
    private List<Map<String, Object>> data;

    @SerializedName("image_url")
    @Getter @Setter
    private String imageUrl;

    @SerializedName("submission_image_url")
    @Getter @Setter
    private String submissionImageUrl;

    // Getters and setters
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
                return 1; // Default to 1 if parsing fails
            }
        }
        // Default to 1 if quantity field is missing (as it appears to be in your API)
        return 1;
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
                return null;
            }
        }
        return null;
    }

    public boolean isDrop() {
        return submissionType != null && submissionType.equalsIgnoreCase("drop");
    }
}
