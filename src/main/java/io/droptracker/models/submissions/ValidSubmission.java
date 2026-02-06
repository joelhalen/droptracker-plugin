package io.droptracker.models.submissions;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

import io.droptracker.models.CustomWebhookBody;
import lombok.Data;

/**
 * Can represent any type of outgoing submission, such as a drop, pb, clog, ca, etc.
 * ValidSubmissions are created when a user receives something that their registered group(s)
 * have configured to have notifications sent for.
 * <p>
 * We generate an ID when sending data, and store this ID in the ValidSubmission object,
 * so that we can update/track whether the submission arrived at the API and had its notifications
 * processed properly.
 */
@Data
public class ValidSubmission {
    // generated ID
    private String uuid;
    // type of submission
    private SubmissionType type;
    // group ID(s) that the submission should have been sent to
    private String[] groupIds;
    // account hash
    private String accountHash;
    // item ID
    private String itemId;
    // item name
    private String itemName;
    // npc name
    private String npcName;
    // description
    private String description;
    // time since submission was created on the client-side
    private String timeReceived;

    // message or response from the API on initial submission
    private String initialResponse;
    // time that the API responded with a successful processing of the submission
    private String timeProcessedAt;
    // current status of the submission (enum-based)
    private SubmissionStatus status;
    
    // number of retry attempts made
    private int retryAttempts;
    
    // last failure reason
    private String lastFailureReason;

    // array of responses from the API on retry attempts
    private String[] retryResponses;
    
    // Store the entire webhook data for retry functionality
    private CustomWebhookBody originalWebhook;
    
    // Store screenshot bytes for retry (transient - not persisted to disk)
    private transient byte[] screenshotData;
    
    // Total value of the submission (used for drops)
    private long totalValue;

    // Default constructor
    public ValidSubmission() {
        this.timeReceived = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
        this.status = SubmissionStatus.PENDING;
        this.groupIds = new String[0];
        this.retryResponses = new String[0];
        this.retryAttempts = 0;
    }

    // Constructor that takes a webhook and extracts relevant data
    public ValidSubmission(CustomWebhookBody webhook, String groupId, SubmissionType type) {
        this();
        this.originalWebhook = webhook;
        this.type = type;
        this.groupIds = new String[] {groupId};
        
        // Extract data from webhook
        extractDataFromWebhook(webhook);
    }

    private void extractDataFromWebhook(CustomWebhookBody webhook) {
        if (webhook != null && webhook.getEmbeds() != null && !webhook.getEmbeds().isEmpty()) {
            CustomWebhookBody.Embed embed = webhook.getEmbeds().get(0);
            
            // Extract title for description
            if (embed.getTitle() != null) {
                this.description = embed.getTitle();
            }
            
            // Extract data from fields - process GUID field with highest priority
            String tempGuid = null;
            
            if (embed.getFields() != null) {
                for (CustomWebhookBody.Field field : embed.getFields()) {
                    String fieldName = field.getName();
                    String fieldValue = field.getValue();
                    
                    if (fieldName != null && fieldValue != null) {
                        switch (fieldName.toLowerCase()) {
                            case "guid":
                                tempGuid = fieldValue;
                                break;
                            case "item":
                            case "item_name":
                                this.itemName = fieldValue;
                                break;
                            case "item_id":
                                this.itemId = fieldValue;
                                break;
                            case "npc":
                            case "npc_name":
                            case "boss":
                            case "source":
                                this.npcName = fieldValue;
                                break;
                            case "account_hash":
                            case "acc_hash":
                            case "player":
                            case "player_name":
                                this.accountHash = fieldValue;
                                break;
                            case "pet_name":
                                if (this.itemName == null) {
                                    this.itemName = fieldValue;
                                }
                                break;
                        }
                    }
                }
                if (tempGuid != null) {
                    this.uuid = tempGuid;
                } 
            }
        }
    }

    public void addGroupId(String groupId) {
        String[] newGroupIds = Arrays.copyOf(groupIds, groupIds.length + 1);
        newGroupIds[groupIds.length] = groupId;
        this.groupIds = newGroupIds;
    }
    
    /**
     * Mark the submission as failed with a reason
     */
    public void markAsFailed(String reason) {
        this.status = SubmissionStatus.FAILED;
        this.lastFailureReason = reason;
    }
    
    /**
     * Mark the submission as currently retrying
     */
    public void markAsRetrying() {
        this.status = SubmissionStatus.RETRYING;
        this.retryAttempts++;
    }
    
    /**
     * Mark the submission as actively sending
     */
    public void markAsSending() {
        this.status = SubmissionStatus.SENDING;
    }
    
    /**
     * Mark the submission as successfully sent
     */
    public void markAsSuccess() {
        this.status = SubmissionStatus.SENT;
        this.timeProcessedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
    }
    
    /**
     * Mark the submission as processed by the API.
     * Note: we intentionally do NOT null out originalWebhook here so the user
     * can still manually retry if the "processed" status was incorrect.
     */
    public void markAsProcessed() {
        this.status = SubmissionStatus.PROCESSED;
        this.timeProcessedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
    }
    
    /**
     * Check if this submission can be retried
     */
    public boolean canRetry() {
        return retryAttempts < 5 && originalWebhook != null && status.canRetry();
    }
    
    /**
     * Get a human-readable status description
     */
    public String getStatusDescription() {
        if (status == null) {
            return "Unknown";
        }
        switch (status) {
            case FAILED:
                return "Failed" + (lastFailureReason != null ? ": " + lastFailureReason : "");
            case RETRYING:
                return "Retrying... (attempt " + (retryAttempts + 1) + ")";
            default:
                return status.getDescription();
        }
    }
    
    /**
     * Get a display-friendly label for the submission type
     */
    public String getTypeLabel() {
        if (type == null) {
            return "Submission";
        }
        switch (type) {
            case DROP: return "Drop";
            case KILL_TIME: return "Personal Best";
            case COLLECTION_LOG: return "Collection Log";
            case COMBAT_ACHIEVEMENT: return "Combat Achievement";
            case LEVEL_UP: return "Level Up";
            case QUEST_COMPLETION: return "Quest Completion";
            case EXPERIENCE: return "Experience";
            case EXPERIENCE_MILESTONE: return "Experience Milestone";
            case PET: return "Pet";
            case ADVENTURE_LOG: return "Adventure Log";
            default: return "Submission";
        }
    }
    
    /**
     * Get a short label for the submission type (used in the UI type indicator)
     */
    public String getTypeShortLabel() {
        if (type == null) {
            return "SUB";
        }
        switch (type) {
            case DROP: return "DROP";
            case KILL_TIME: return "PB";
            case COLLECTION_LOG: return "CLOG";
            case COMBAT_ACHIEVEMENT: return "CA";
            case LEVEL_UP: return "LVL";
            case QUEST_COMPLETION: return "QST";
            case PET: return "PET";
            case EXPERIENCE: return "XP";
            case EXPERIENCE_MILESTONE: return "XP";
            case ADVENTURE_LOG: return "LOG";
            default: return "SUB";
        }
    }

    /**
     * Get a single-line display text for this submission
     */
    public String getDisplayText() {
        String name = itemName;
        if (name == null || name.trim().isEmpty()) {
            name = description;
        }
        if (name == null || name.trim().isEmpty()) {
            name = "Unknown";
        }
        return getTypeLabel() + ": " + name;
    }

    /**
     * Get the time since this submission was received, as a human-readable string
     */
    public String getTimeSinceReceived() {
        if (timeReceived == null) {
            return "Unknown";
        }
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
            LocalDateTime receivedDate = LocalDateTime.parse(timeReceived, formatter);
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
}
