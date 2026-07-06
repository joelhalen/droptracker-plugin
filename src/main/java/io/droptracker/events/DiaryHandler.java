package io.droptracker.events;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import lombok.extern.slf4j.Slf4j;

/**
 * Tracks achievement diary completions via the game-message announcement, e.g.
 * "Congratulations! You have completed all of the easy tasks in the Ardougne area."
 *
 * Invoked manually from DropTrackerPlugin's event subscriptions (handlers in
 * this package are not registered on the RuneLite event bus).
 */
@Slf4j
public class DiaryHandler extends BaseEventHandler {

    private static final Pattern DIARY_COMPLETION_PATTERN = Pattern.compile(
        "Congratulations! You have completed all of the (?<tier>easy|medium|hard|elite) tasks in (?:the )?(?<area>.+?) area",
        Pattern.CASE_INSENSITIVE
    );

    @Override
    public boolean isEnabled() {
        return config.diaryEmbeds();
    }

    public void onGameMessage(String message) {
        if (!isEnabled() || !plugin.isTracking || message == null) {
            return;
        }

        Matcher matcher = DIARY_COMPLETION_PATTERN.matcher(message);
        if (!matcher.find()) {
            return;
        }

        String tier = ucFirst(matcher.group("tier"));
        String area = matcher.group("area").trim();

        String playerName = getPlayerName();
        CustomWebhookBody webhook = createWebhookBody(playerName + " completed an achievement diary!");
        CustomWebhookBody.Embed embed = createEmbed(playerName + " completed the " + tier + " " + area + " diary!", "diary");

        Map<String, Object> fieldData = new HashMap<>();
        fieldData.put("diary_name", area);
        fieldData.put("diary_tier", tier);
        addFields(embed, fieldData);
        webhook.getEmbeds().add(embed);

        sendData(webhook, SubmissionType.DIARY);
    }

    private static String ucFirst(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1).toLowerCase();
    }
}
