package io.droptracker.events;

import com.google.inject.Inject;
import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import io.droptracker.service.SubmissionManager;
import io.droptracker.util.DebugLogger;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;

import java.util.concurrent.ScheduledExecutorService;
import java.util.Map;

/**
 * Base abstract class for all event handlers
 * Provides common dependencies, shared logic, and standardized methods for webhook creation and data transmission.
 */
@Slf4j
public abstract class BaseEventHandler {

    @Inject
    protected DropTrackerPlugin plugin;

    @Inject
    protected Client client;

    @Inject
    protected ConfigManager configManager;

    @Inject
    protected ClientThread clientThread;

    @Inject
    protected DropTrackerApi api;

    @Inject
    protected DropTrackerConfig config;

    @Inject
    protected SubmissionManager submissionManager;

    // Optional dependency - not all handlers use the executor
    @Inject(optional = true)
    protected ScheduledExecutorService executor;

    /**
     * Determines if this event handler is currently enabled.
     * Override this method in subclasses to implement specific enable/disable logic.
     * 
     * @return true if the handler is enabled, false otherwise
     */
    public boolean isEnabled() {
        return true;
    }

    /**
     * Gets the local player's name.
     * 
     * @return the local player's name, or "Unknown" if null/empty
     */
    protected String getPlayerName() {
        String playerName = plugin.getLocalPlayerName();
        return (playerName != null && !playerName.trim().isEmpty()) ? playerName : "Unknown";
    }

    /**
     * Gets the account hash as a string.
     * 
     * @return the account hash, or "0" if client is null
     */
    protected String getAccountHash() {
        return client != null ? String.valueOf(client.getAccountHash()) : "0";
    }

    /**
     * Adds common fields that are present in all webhook embeds.
     * This includes player_name, acc_hash, p_v, and any global fields.
     * 
     * @param embed the embed to add fields to
     */
    protected void addCommonFields(CustomWebhookBody.Embed embed) {
        if (embed == null) {
            log.warn("Attempted to add common fields to null embed");
            return;
        }
        
        String playerName = getPlayerName();
        String accountHash = getAccountHash();
        String pluginVersion = plugin != null && plugin.pluginVersion != null ? plugin.pluginVersion : "unknown";
        String guid = api != null ? api.generateGuidForSubmission() : "unknown";
        
        embed.addField("player_name", playerName, true);
        embed.addField("acc_hash", accountHash, true);
        embed.addField("p_v", pluginVersion, true);
        embed.addField("guid", guid, true);
    }

    /**
     * Creates a new webhook body with basic setup.
     * 
     * @param content the main content message for the webhook
     * @return a new CustomWebhookBody instance
     */
    protected CustomWebhookBody createWebhookBody(String content) {
        CustomWebhookBody webhook = new CustomWebhookBody();
        webhook.setContent(content != null ? content : "DropTracker Event");
        return webhook;
    }

    /**
     * Creates a new embed with the specified title and type, and automatically adds common fields.
     * 
     * @param title the title for the embed
     * @param type the type of the embed (e.g., "drop", "combat_achievement", "collection_log", "npc_kill")
     * @return a new CustomWebhookBody.Embed instance with common fields already added
     */
    protected CustomWebhookBody.Embed createEmbed(String title, String type) {
        CustomWebhookBody.Embed embed = new CustomWebhookBody.Embed();
        if (title != null && !title.trim().isEmpty()) {
            embed.setTitle(title);
        } else {
            embed.setTitle("DropTracker Event");
        }
        if (type != null && !type.trim().isEmpty()) {
            embed.addField("type", type, true);
        }
        addCommonFields(embed);
        return embed;
    }

    /**
     * Creates a new embed with the specified title (no type field).
     * 
     * @param title the title for the embed
     * @return a new CustomWebhookBody.Embed instance
     */
    protected CustomWebhookBody.Embed createEmbed(String title) {
        return createEmbed(title, null);
    }

    /**
     * Adds multiple fields to an embed from a map of field data.
     * 
     * @param embed the embed to add fields to
     * @param fieldData map of field names to values
     */
    protected void addFields(CustomWebhookBody.Embed embed, Map<String, Object> fieldData) {
        addFields(embed, fieldData, true);
    }

    /**
     * Adds multiple fields to an embed from a map of field data.
     * 
     * @param embed the embed to add fields to
     * @param fieldData map of field names to values
     * @param inline whether the fields should be inline
     */
    protected void addFields(CustomWebhookBody.Embed embed, Map<String, Object> fieldData, boolean inline) {
        if (embed == null) {
            log.warn("Attempted to add fields to null embed");
            return;
        }
        if (fieldData == null) {
            log.warn("Attempted to add null fieldData to embed");
            return;
        }
        
        for (Map.Entry<String, Object> entry : fieldData.entrySet()) {
            String fieldName = entry.getKey();
            if (fieldName == null || fieldName.trim().isEmpty()) {
                log.debug("Skipping field with null/empty name");
                continue;
            }
            
            String value;
            Object rawValue = entry.getValue();
            if (rawValue == null) {
                value = "N/A";
            } else if (rawValue instanceof String && ((String) rawValue).trim().isEmpty()) {
                value = "N/A";
            } else {
                value = String.valueOf(rawValue);
            }
            
            embed.addField(fieldName, value, inline);
        }
    }

    /**
     * Sends webhook data to the DropTracker API with a string type identifier.
     * Used for events like combat achievements, collection logs, and kills.
     * 
     * @param webhook the webhook body to send
     * @param type the event type identifier
     */
    protected void sendData(CustomWebhookBody webhook, SubmissionType type) {
        DebugLogger.log("[BaseEventHandler][send] sendData called; type=" + type
            + ", embedCount=" + (webhook != null && webhook.getEmbeds() != null ? webhook.getEmbeds().size() : 0)
            + ", payload=" + webhook);
        if (webhook == null) {
            log.warn("Attempted to send null webhook data");
            return;
        }
        if (webhook.getEmbeds() == null || webhook.getEmbeds().isEmpty()) {
            log.warn("Attempted to send webhook with no embeds");
            return;
        }
        if (type == null) {
            log.warn("Attempted to send webhook with null submission type");
            return;
        }
        if (submissionManager == null) {
            log.error("SubmissionManager is null, cannot send webhook data");
            return;
        }
        
        try {
            submissionManager.sendDataToDropTracker(webhook, type);
        } catch (Exception e) {
            log.error("Failed to send webhook data: {}", e.getMessage(), e);
        }
    }

    /**
     * Sends webhook data to the DropTracker API with a numeric value.
     * Used for drops where the value is the total drop value.
     * 
     * @param webhook the webhook body to send
     * @param value the total value of the event
     * @param singleValue the value of individual items (for stacked item checking)
     */
    protected void sendData(CustomWebhookBody webhook, int value, int singleValue, boolean valueModified) {
        if (webhook == null) {
            log.warn("Attempted to send null webhook data");
            return;
        }
        if (webhook.getEmbeds() == null || webhook.getEmbeds().isEmpty()) {
            log.warn("Attempted to send webhook with no embeds");
            return;
        }
        if (submissionManager == null) {
            log.error("SubmissionManager is null, cannot send webhook data");
            return;
        }
        
        try {
            submissionManager.sendDataToDropTracker(webhook, value, singleValue, valueModified);
        } catch (Exception e) {
            log.error("Failed to send webhook data: {}", e.getMessage(), e);
        }
    }

    /**
     * Gets the kill count for a specific boss from the configuration.
     * 
     * @param bossName the name of the boss
     * @return the kill count, or 0 if not found
     */
    protected int getKillCount(String bossName) {
        if (bossName == null) {
            return 0;
        }
        Integer killCount = configManager.getRSProfileConfiguration("killcount", bossName.toLowerCase(), int.class);
        return killCount != null ? killCount : 0;
    }

    
} 