package io.droptracker.util;

import java.util.concurrent.ScheduledExecutorService;

import com.google.inject.Inject;
import com.google.inject.Provider;

import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.api.UrlManager;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

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

    @Inject
    private Client client;
    @Inject
    private ClientThread clientThread;



    @Inject
    public ChatMessageUtil(DropTrackerConfig config, DropTrackerApi api, Provider<UrlManager> urlManagerProvider,
            DropTrackerPlugin plugin, ScheduledExecutorService executor) {
        this.config = config;
        this.api = api;
        this.urlManagerProvider = urlManagerProvider;
        this.plugin = plugin;
        this.executor = executor;
    }

    public void checkForMessage() {
        if (isMessageChecked) {
            return;
        }
        // determine whether the player needs to be notified about a possible change to
        // the plugin
        // based on the last version they loaded, and the currently stored version
        String currentVersion = config.lastVersionNotified();
        if (currentVersion != null && !plugin.pluginVersion.equals(currentVersion + "1")) {
            executor.submit(() -> {
                String newNotificationData = api.getLatestUpdateString();
                sendChatMessage(newNotificationData);
                // Update the internal config value of this update message
                config.setLastVersionNotified(currentVersion);
                // Add a flag to prevent multiple checks in the same session
            });
            isMessageChecked = true;

        }
    }

    @Subscribe
    public void onCommandExecuted(CommandExecuted event)
    {
        if (event.getCommand().equals("droptracker"))
        {
            urlManagerProvider.get().openLink("https://www.droptracker.io/wiki/why-api/");
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
            client.addChatMessage(ChatMessageType.CONSOLE, finalMessage, finalMessage, "DropTracker.io");
        });
    }
}
