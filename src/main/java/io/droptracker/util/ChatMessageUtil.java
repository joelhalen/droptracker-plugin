package io.droptracker.util;

import com.google.inject.Inject;

import io.droptracker.DropTrackerConfig;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.LinkBrowser;

import java.awt.*;


public class ChatMessageUtil {

    @Inject
    private DropTrackerConfig config;

    @Inject
    private ChatMessageManager chatMessageManager;

    @Inject
    private Client client;
    @Inject
    private ClientThread clientThread;

    @Subscribe
    public void onCommandExecuted(CommandExecuted event)
    {
        if (event.getCommand().equals("droptracker"))
        {
            LinkBrowser.browse("https://www.droptracker.io/wiki/why-api/");
        }
        if (event.getCommand().equals("debugurl"))
        {
            String apiUrlToUse = event.getArguments()[0];
            config.setCustomApiEndpoint(apiUrlToUse);
            sendChatMessage("All outgoing requests will now be sent to " + apiUrlToUse);
        }
    }
    public void warnApiSetting() {
        String message = "It is strongly recommended that you enable our API connections in the DropTracker plugin configuration. To learn more, type ::droptracker";
        Color color = ColorUtil.fromHex("#ff0000");
        String formatted = String.format("[%s] %s: %s",
                ColorUtil.wrapWithColorTag("DropTracker.io", color),
                "Warning",
                ColorUtil.wrapWithColorTag(message, color));
        chatMessageManager.queue(
                QueuedMessage.builder()
                        .type(ChatMessageType.CONSOLE)
                        .runeLiteFormattedMessage(formatted)
                        .build()
        );
    }
    public void warnClogSetting() {
        String message = "Your collection log slots will not be tracked unless you enabled the game setting: Collection log - New addition notification";
        Color color = ColorUtil.fromHex("#ff0000");
        String formatted = String.format("[%s] %s: %s",
                ColorUtil.wrapWithColorTag("DropTracker.io", color),
                "Warning",
                ColorUtil.wrapWithColorTag(message, color));
        chatMessageManager.queue(
                QueuedMessage.builder()
                        .type(ChatMessageType.CONSOLE)
                        .runeLiteFormattedMessage(formatted)
                        .build()
        );
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
        Color color = ColorUtil.fromHex("#ff0000");
        String formatted = String.format("[%s] %s: %s",
                ColorUtil.wrapWithColorTag("DropTracker.io", color),
                "Warning",
                ColorUtil.wrapWithColorTag(finalMessage, Color.black));
        chatMessageManager.queue(
                QueuedMessage.builder()
                        .type(ChatMessageType.CONSOLE)
                        .runeLiteFormattedMessage(finalMessage)
                        .build()
        );
    }
}
