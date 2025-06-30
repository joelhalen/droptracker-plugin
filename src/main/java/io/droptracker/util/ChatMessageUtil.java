package io.droptracker.util;

import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;

import com.google.inject.Inject;
import com.google.inject.Provider;

import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.api.UrlManager;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;


public class ChatMessageUtil {

    private boolean isMessageChecked = false;
    @Inject
    private DropTrackerConfig config;
    @Inject
    private DropTrackerApi api;
    @Inject
    private Provider<UrlManager> urlManagerProvider;
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
	private ChatCommandManager chatCommandManager;
            
    @Inject
    public ChatMessageUtil(DropTrackerConfig config, DropTrackerApi api, Provider<UrlManager> urlManagerProvider, DropTrackerPlugin plugin, ScheduledExecutorService executor, ConfigManager configManager) {
        this.config = config;
        this.api = api;
        this.urlManagerProvider = urlManagerProvider;
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
    

    public void registerCommands() {
        chatCommandManager.registerCommandAsync("!droptracker", (chatMessage, s) -> {
			BiConsumer<ChatMessage, String> linkOpener = urlManagerProvider.get().openLink("discord");
			if (linkOpener != null && chatMessage.getSender().equalsIgnoreCase(client.getLocalPlayer().getName())) {
				linkOpener.accept(chatMessage, s);
			}
		});
		chatCommandManager.registerCommandAsync("!dtapi", (chatMessage, s) -> {
			BiConsumer<ChatMessage, String> linkOpener = urlManagerProvider.get().openLink("https://www.droptracker.io/wiki/why-api/");
			if (linkOpener != null && chatMessage.getSender().equalsIgnoreCase(client.getLocalPlayer().getName())) {
				linkOpener.accept(chatMessage, s);
			}
		});
        return; 
    }

    public void unregisterCommands() {
        chatCommandManager.unregisterCommand("!droptracker");
		chatCommandManager.unregisterCommand("!loot");
        return;
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
