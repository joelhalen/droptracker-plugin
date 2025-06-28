package io.droptracker.util;

import java.util.concurrent.ScheduledExecutorService;

import com.google.inject.Inject;

import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.api.DropTrackerApi;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;


public class ChatMessageUtil {

    private boolean isMessageChecked = false;
    @Inject
    private DropTrackerConfig config;
    @Inject
    private DropTrackerApi api;
    @Inject
    private DropTrackerPlugin plugin;
    @Inject
    private ScheduledExecutorService executor;
    private ConfigManager configManager;

    @Inject
    private Client client;
    @Inject
    private ClientThread clientThread;
            
    @Inject
    public ChatMessageUtil(DropTrackerConfig config, DropTrackerApi api, DropTrackerPlugin plugin, ScheduledExecutorService executor, ConfigManager configManager) {
        this.config = config;
        this.api = api;
        this.plugin = plugin;
        this.executor = executor;
        this.configManager = configManager;
    }

    public void checkForMessage() {
        if (isMessageChecked) {
        return;
    }

    // determine whether the player needs to be notified about a possible change to the plugin
    // based on the last version they loaded, and the currently stored version
    String currentVersion = config.lastVersionNotified();
		if (currentVersion != null && !plugin.pluginVersion.equals(currentVersion + "1")) {
			executor.submit(() -> {
				String newNotificationData = api.getLatestUpdateString();
				sendChatMessage(newNotificationData);
				// Update the internal config value of this update message
				configManager.setConfiguration("droptracker", "lastVersionNotified", plugin.pluginVersion + "1");
				// Add a flag to prevent multiple checks in the same session
			});
			isMessageChecked = true;

		}
	}

    public void sendChatMessage(String messageContent) {
		ChatMessageBuilder messageBuilder = new ChatMessageBuilder();
		messageBuilder.append(ChatColorType.HIGHLIGHT)
				.append("[")
				.append(ChatColorType.NORMAL)
				.append("DropTracker")
				.append(ChatColorType.HIGHLIGHT)
				.append("] ")
				.append(ChatColorType.NORMAL);
		messageBuilder.append(messageContent);
		final String finalMessage = messageBuilder.build();
		clientThread.invokeLater(() -> {
			client.addChatMessage(ChatMessageType.CONSOLE, finalMessage, finalMessage,"DropTracker.io");
		});
	}
}
