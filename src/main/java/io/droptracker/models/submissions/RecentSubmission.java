package io.droptracker.models.submissions;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

import javax.annotation.Nullable;

import lombok.*;

import com.google.gson.annotations.SerializedName;

import io.droptracker.api.DropTrackerUrls;
import okhttp3.HttpUrl;

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

    /**
     * Path under {@code /img/} of the icon for this submission, e.g.
     * {@code "itemdb/4151.png"}. The API deliberately sends a path rather than a
     * URL — see {@link io.droptracker.api.DropTrackerUrls}.
     */
    @SerializedName("image_path")
    @Getter @Setter
    private String imagePath;

    /** Path under {@code /img/} of the full submission screenshot. */
    @SerializedName("submission_image_path")
    @Getter @Setter
    private String submissionImagePath;

    /** Absolute URL for {@link #imagePath}, or null if absent or malformed. */
    @Nullable
    public HttpUrl imageUrl() {
        return DropTrackerUrls.image(imagePath);
    }

    /** Absolute URL for {@link #submissionImagePath}, or null if absent or malformed. */
    @Nullable
    public HttpUrl submissionImageUrl() {
        return DropTrackerUrls.image(submissionImagePath);
    }

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

    /** The {@code key} of the first {@code dataType} entry, as text, when this is a {@code type} submission. */
    private String dataText(String type, String dataType, String key) {
        if (!submissionType.equalsIgnoreCase(type)) {
            return null;
        }
        Object value = getDataValueByTypeAndKey(dataType, key);
        return value != null ? value.toString() : null;
    }

    /** JSON numbers arrive as Double, and ids sometimes as text like "4151.0". */
    private static Integer toInt(Object value, Integer fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            return Integer.valueOf(value.toString().replaceAll("\\.0*$", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String getPbTime() {
        return dataText("pb", "best_time", "time");
    }

    public String getDropItemName() {
        return dataText("drop", "item", "name");
    }

    public Integer getDropItemId() {
        return submissionType.equalsIgnoreCase("drop") ? toInt(getDataValueByTypeAndKey("item", "id"), null) : null;
    }

    /** Defaults to 1: the API often leaves quantity out. */
    public Integer getDropQuantity() {
        return submissionType.equalsIgnoreCase("drop") ? toInt(getDataValueByTypeAndKey("item", "quantity"), 1) : null;
    }

    public String getClogItemName() {
        return dataText("clog", "clog_item", "name");
    }

    public Integer getClogItemId() {
        return submissionType.equalsIgnoreCase("clog") ? toInt(getDataValueByTypeAndKey("clog_item", "id"), null) : null;
    }
}
