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
import io.droptracker.models.CustomWebhookBody.Embed;
import io.droptracker.models.CustomWebhookBody.Field;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ValidSubmission {
    /* Can represent any type of outgoing submission, such as a drop, pb, clog, ca, etc.
     * ValidSubmissions are created when a user receives something that their registered group(s)
     * have configured to have notifications sent for.
     * 
     * We use the uuidv7 generator when sending data, and store this ID in the ValidSubmission object,
     * so that we can update/track whether the submission arrived at the API and had its notifications
     * processed properly.
     */

    // uuidv7 generated ID
    private String uuid;
    // type of submission
    private String type;
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
    }

    // Constructor that takes a webhook and extracts relevant data
    public ValidSubmission(CustomWebhookBody webhook, String groupId, String type) {
        this();
        this.originalWebhook = webhook;
        this.type = type;
        this.groupIds = new String[] {groupId};
        
        // Extract data from webhook
        extractDataFromWebhook(webhook);
    }
    
    /* @param uuid - uuidv7 generated ID
     * @param type - type of submission
     * @param groupIds - group ID(s) that the submission should have been sent to
     * @param accountHash - account hash
     * @param itemId - item ID
     * @param itemName - item name
     * @param status - current status of the submission (e.g. "pending", "processed", "failed")
     * @param timeSinceReceived - time since submission was created on the client-side
     * @param timeProcessedAt - time that the API responded with a successful processing of the submission
     * @param retryResponses - array of responses from the API on retry attempts
     */
    public ValidSubmission(String uuid, String type, String groupIds, 
                           String accountHash, String itemId, String itemName, String npcName, String description, String status, 
                           String timeReceived, String timeProcessedAt) {
        this.uuid = uuid;
        this.type = type;
        this.groupIds = new String[] {groupIds};
        this.accountHash = accountHash;
        this.itemId = itemId;
        this.itemName = itemName;
        this.npcName = npcName;
        this.description = description;
        this.status = status;
        this.timeReceived = timeReceived;
        this.timeProcessedAt = timeProcessedAt;
        this.retryResponses = new String[0];
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

    // Method to get the original webhook for retry functionality
    public CustomWebhookBody getOriginalWebhook() {
        return originalWebhook;
    }


    public JPanel toSubmissionPanel() {
        JPanel entryPanel = new JPanel();
        entryPanel.setLayout(new BoxLayout(entryPanel, BoxLayout.Y_AXIS));
        JTextArea textArea = new JTextArea();
        String text = "";
        switch (this.type) {
            case "drop":
                text = "Drop: " + this.itemName + " - " + this.timeSinceReceived();
                break;
            case "pb":
                text = "Personal Best: " + this.itemName + " - " + this.timeSinceReceived();
                break;
            case "clog":
                text = "Collection Log: " + this.itemName + " - " + this.timeSinceReceived();
                break;
            case "ca":
                text = "Combat Achievement: " + this.itemName + " - " + this.timeSinceReceived();
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

    @Override
    public String toString() {
        return "ValidSubmission{" +
            "uuid='" + uuid + '\'' +
            ", type='" + type + '\'' +
            ", groupIds='" + groupIds + '\'' +
            ", accountHash='" + accountHash + '\'' +
            ", itemId='" + itemId + '\'' +
            ", itemName='" + itemName + '\'' +
            ", npcName='" + npcName + '\'' +
            ", description='" + description + '\'' +
            ", timeReceived='" + timeReceived + '\'' +
            ", timeProcessedAt='" + timeProcessedAt + '\'' +
            ", status='" + status + '\'' +
            ", retryResponses='" + retryResponses + '\'' +
            ", originalWebhook='" + originalWebhook + '\'' +
            '}';
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
