package io.droptracker.util;

import io.droptracker.api.DropTrackerUrls;
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
            LinkBrowser.browse(DropTrackerUrls.web("wiki", "why-api").toString());
        }
        if (event.getCommand().equals("debugurl"))
        {
            String apiUrlToUse = event.getArguments()[0];
            config.setCustomApiEndpoint(apiUrlToUse);
            sendChatMessage("All outgoing requests will now be sent to " + apiUrlToUse);
        }
    }
    public void warnApiSetting() {
        queueWarning("It is strongly recommended that you enable our API connections in the DropTracker plugin configuration. To learn more, type ::droptracker");
    }
    public void warnClogSetting() {
        queueWarning("Your collection log slots will not be tracked unless you enabled the game setting: Collection log - New addition notification");
    }

    private void queueWarning(String message) {
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
        chatMessageManager.queue(
                QueuedMessage.builder()
                        .type(ChatMessageType.CONSOLE)
                        .runeLiteFormattedMessage(finalMessage)
                        .build()
        );
    }

    /**
     * Event-notification line: prefixed "[Event Name] (Team name):" instead
     * of the generic [DropTracker] tag so lines read as the event talking.
     * Both names must already be sanitized/capped by the caller; teamName is
     * optional (omitted before the first /event_state snapshot lands).
     */
    /**
     * Discord→game bridge line, rendered to look like clan chat (visible only
     * to this client — nothing is sent to the game server). The sender is
     * suffixed so a Discord user can never be mistaken for an in-game
     * clanmate; both strings must already be sanitized/capped by the caller.
     */
    public void sendDiscordClanMessage(String sender, String messageContent) {
        String clanName = client.getClanChannel() != null
                ? net.runelite.client.util.Text.removeTags(client.getClanChannel().getName())
                : "Discord";
        chatMessageManager.queue(
                QueuedMessage.builder()
                        .type(ChatMessageType.CLAN_CHAT)
                        .name(sender + " (Discord)")
                        .sender(clanName)
                        .value(messageContent)
                        .build()
        );
    }

    public void sendEventChatMessage(String eventName, String teamName, String messageContent) {
        sendEventChatMessage(eventName, teamName, null, null, null, messageContent);
    }

    /**
     * Accented event line: an uppercase type tag ("COMPLETE", "LEAD CHANGE",
     * "PROGRESS"...) in a brightened accent, the body in the accent itself,
     * and — when {@code emphasis} occurs in the body — that one substring
     * (usually the item name) lifted back to the bright shade. A null accent
     * reproduces the old uncoloured line exactly.
     *
     * <p>Colours must go through {@link ChatMessageBuilder#append(Color,
     * String)}: the plain {@code append(String)} runs {@code escapeJagex},
     * which would print the tags literally. All strings must already be
     * sanitized/capped by the caller.</p>
     */
    public void sendEventChatMessage(String eventName, String teamName,
                                     String tag, String accentHex,
                                     String emphasis, String messageContent) {
        ChatMessageBuilder messageBuilder = new ChatMessageBuilder();
        messageBuilder.append(ChatColorType.HIGHLIGHT)
                .append("[")
                .append(ChatColorType.NORMAL)
                .append(eventName)
                .append(ChatColorType.HIGHLIGHT)
                .append("] ");
        if (teamName != null && !teamName.isEmpty()) {
            messageBuilder.append("(")
                    .append(ChatColorType.NORMAL)
                    .append(teamName)
                    .append(ChatColorType.HIGHLIGHT)
                    .append("): ");
        }
        Color accent = accentHex != null ? ColorUtil.fromHex(accentHex) : null;
        if (accent == null) {
            if (tag != null && !tag.isEmpty()) {
                messageBuilder.append(ChatColorType.HIGHLIGHT).append(tag).append(" ");
            }
            messageBuilder.append(ChatColorType.NORMAL).append(messageContent);
        } else {
            Color bright = ColorUtil.colorLerp(accent, Color.WHITE, 0.45);
            if (tag != null && !tag.isEmpty()) {
                messageBuilder.append(bright, tag).append(" ");
            }
            appendEmphasized(messageBuilder, messageContent, emphasis, accent, bright);
        }
        chatMessageManager.queue(
                QueuedMessage.builder()
                        .type(ChatMessageType.CONSOLE)
                        .runeLiteFormattedMessage(messageBuilder.build())
                        .build()
        );
    }

    /** Body in {@code accent}, with the first occurrence of {@code emphasis}
     *  (if any) in {@code bright}. Segments are appended side by side, never
     *  nested: RuneScape's {@code </col>} resets to the chat default rather
     *  than to an enclosing tag. */
    private static void appendEmphasized(ChatMessageBuilder builder, String body,
                                         String emphasis, Color accent, Color bright) {
        int at = emphasis != null && !emphasis.isEmpty() ? body.indexOf(emphasis) : -1;
        if (at < 0) {
            builder.append(accent, body);
            return;
        }
        if (at > 0) {
            builder.append(accent, body.substring(0, at));
        }
        builder.append(bright, emphasis);
        String rest = body.substring(at + emphasis.length());
        if (!rest.isEmpty()) {
            builder.append(accent, rest);
        }
    }
}
