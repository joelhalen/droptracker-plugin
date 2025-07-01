package io.droptracker.events;

import com.google.inject.Inject;
import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.SubmissionType;
import io.droptracker.service.SubmissionManager;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;

import java.util.concurrent.ScheduledExecutorService;
import java.util.HashMap;
import java.util.Map;

/**
 * Base abstract class for all event handlers
 * Provides common dependencies, shared logic, and standardized methods for webhook creation and data transmission.
 */
@Slf4j
public abstract class BaseEventHandler {

    // Global fields that get added to all embeds
    private final Map<String, String> globalFields = new HashMap<>();

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
     * @return the local player's name
     */
    protected String getPlayerName() {
        return plugin.getLocalPlayerName();
    }

    /**
     * Gets the account hash as a string.
     * 
     * @return the account hash
     */
    protected String getAccountHash() {
        return String.valueOf(client.getAccountHash());
    }

    /**
     * Adds a global field that will be included in all embeds created by this handler.
     * 
     * @param key the field key
     * @param value the field value
     */
    protected void addGlobalField(String key, String value) {
        globalFields.put(key, value);
    }

    /**
     * Removes a global field.
     * 
     * @param key the field key to remove
     */
    protected void removeGlobalField(String key) {
        globalFields.remove(key);
    }

    /**
     * Adds common fields that are present in all webhook embeds.
     * This includes player_name, acc_hash, p_v, and any global fields.
     * 
     * @param embed the embed to add fields to
     */
    protected void addCommonFields(CustomWebhookBody.Embed embed) {
        embed.addField("player_name", getPlayerName(), true);
        embed.addField("acc_hash", getAccountHash(), true);
        embed.addField("p_v", plugin.pluginVersion, true);
        embed.addField("guid", api.generateGuidForSubmission(), true);
        
        // Add any global fields
        for (Map.Entry<String, String> globalField : globalFields.entrySet()) {
            embed.addField(globalField.getKey(), globalField.getValue(), true);
        }
    }

    /**
     * Creates a new webhook body with basic setup.
     * 
     * @param content the main content message for the webhook
     * @return a new CustomWebhookBody instance
     */
    protected CustomWebhookBody createWebhookBody(String content) {
        CustomWebhookBody webhook = new CustomWebhookBody();
        webhook.setContent(content);
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
        if (title != null) {
            embed.setTitle(title);
        }
        if (type != null) {
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
        for (Map.Entry<String, Object> entry : fieldData.entrySet()) {
            String value = entry.getValue() != null ? String.valueOf(entry.getValue()) : "null";
            embed.addField(entry.getKey(), value, inline);
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
        System.out.println("Sending data to DropTracker API with type: " + type);
        if (webhook != null && !webhook.getEmbeds().isEmpty()) {
            submissionManager.sendDataToDropTracker(webhook, type);
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
    protected void sendData(CustomWebhookBody webhook, int value, int singleValue) {
        if (webhook != null && !webhook.getEmbeds().isEmpty()) {
            submissionManager.sendDataToDropTracker(webhook, value, singleValue);
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

    /**
     * Abstract method that subclasses must implement to define their specific processing logic.
     * The parameters will vary depending on the type of event being processed.
     * 
     * @param args variable arguments specific to each handler type
     */
    public abstract void process(Object... args);

    
} 