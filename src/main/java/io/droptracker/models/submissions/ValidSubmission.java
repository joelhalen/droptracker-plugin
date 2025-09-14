package io.droptracker.models.submissions;

import java.awt.Font;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JTextArea;

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
    // current status of the submission
    private String status;
    
    // number of retry attempts made
    private int retryAttempts;
    
    // last failure reason
    private String lastFailureReason;

    // array of responses from the API on retry attempts
    private String[] retryResponses;
    
    // Store the entire webhook data for retry functionality
    private CustomWebhookBody originalWebhook;

    // Default constructor
    public ValidSubmission() {
        this.timeReceived = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
        this.status = "pending";
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
            
            // Extract data from fields
            if (embed.getFields() != null) {
                for (CustomWebhookBody.Field field : embed.getFields()) {
                    String fieldName = field.getName();
                    String fieldValue = field.getValue();
                    
                    if (fieldName != null && fieldValue != null) {
                        switch (fieldName.toLowerCase()) {
                            case "uuid":
                            case "id":
                                this.uuid = fieldValue;
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
                                this.npcName = fieldValue;
                                break;
                            case "account_hash":
                            case "player":
                                this.accountHash = fieldValue;
                                break;
                        }
                    }
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
        this.status = "failed";
        this.lastFailureReason = reason;
    }
    
    /**
     * Mark the submission as queued for retry
     */
    public void markAsQueued() {
        this.status = "queued";
    }
    
    /**
     * Mark the submission as currently retrying
     */
    public void markAsRetrying() {
        this.status = "retrying";
        this.retryAttempts++;
    }
    
    /**
     * Mark the submission as successfully sent
     */
    public void markAsSuccess() {
        this.status = "sent";
        this.timeProcessedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
    }
    
    /**
     * Mark the submission as processed by the API
     */
    public void markAsProcessed() {
        this.status = "processed";
        this.timeProcessedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
        // Clear webhook data to free memory since we no longer need to retry
        this.originalWebhook = null;
    }
    
    /**
     * Check if this submission can be retried
     */
    public boolean canRetry() {
        return retryAttempts < 5 && !"sent".equals(status) && !"processed".equals(status);
    }
    
    /**
     * Get a human-readable status description
     */
    public String getStatusDescription() {
        switch (status) {
            case "pending":
                return "Sending...";
            case "sent":
                return "Sent successfully";
            case "processed":
                return "Processed by API";
            case "failed":
                return "Failed" + (lastFailureReason != null ? ": " + lastFailureReason : "");
            case "queued":
                return "Queued for retry";
            case "retrying":
                return "Retrying... (attempt " + (retryAttempts + 1) + ")";
            default:
                return status;
        }
    }

    public JPanel toSubmissionPanel() {
        JPanel entryPanel = new JPanel();
        entryPanel.setLayout(new BoxLayout(entryPanel, BoxLayout.Y_AXIS));

        // Create a text area to display the submission information
        JTextArea textArea = new JTextArea();
        String text;
        switch (this.type) {
            case DROP:
                text = "Drop: " + this.itemName + " - " + this.timeSinceReceived();
                break;
            case KILL_TIME:
                text = "Personal Best: " + this.itemName + " - " + this.timeSinceReceived();
                break;
            case COLLECTION_LOG:
                text = "Collection Log: " + this.itemName + " - " + this.timeSinceReceived();
                break;
            case COMBAT_ACHIEVEMENT:
                text = "Combat Achievement: " + this.itemName + " - " + this.timeSinceReceived();
                break;
            case LEVEL_UP:
                text = "Level Up: " + this.itemName + " - " + this.timeSinceReceived();
                break;
            case QUEST_COMPLETION:
                text = "Quest Completion: " + this.itemName + " - " + this.timeSinceReceived();
                break;
            case EXPERIENCE:
                text = "Experience: " + this.itemName + " - " + this.timeSinceReceived();
                break;
            case EXPERIENCE_MILESTONE:
                text = "Experience Milestone: " + this.itemName + " - " + this.timeSinceReceived();
                break;  
            case PET:
                text = "Pet: " + this.itemName + " - " + this.timeSinceReceived();
                break;  
            default:
                text = "Unknown submission type: " + this.type;
                break;
        }
        textArea.setText(text);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setFont(new Font("Arial", Font.PLAIN, 12));    
        entryPanel.add(textArea);
        return entryPanel;
    }

    private String timeSinceReceived() {
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
