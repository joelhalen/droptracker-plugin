package io.droptracker.events;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.ImmutableList;

import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.VarPlayer;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;

@Slf4j
@Singleton
public class QuestHandler extends BaseEventHandler {
    
    private static final Pattern QUEST_PATTERN_1 = Pattern.compile(".+?ve\\.*? (?<verb>been|rebuilt|.+?ed)? ?(?:the )?'?(?<quest>.+?)'?(?: [Qq]uest)?[!.]?$");
    private static final Pattern QUEST_PATTERN_2 = Pattern.compile("'?(?<quest>.+?)'?(?: [Qq]uest)? (?<verb>[a-z]\\w+?ed)?(?: f.*?)?[!.]?$");
    private static final Collection<String> RFD_TAGS = ImmutableList.of("Another Cook", "freed", "defeated", "saved");
    private static final Collection<String> WORD_QUEST_IN_NAME_TAGS = ImmutableList.of("Another Cook", "Doric", "Heroes", "Legends", "Observatory", "Olaf", "Waterfall");
    private static final Map<String, String> QUEST_REPLACEMENTS = Map.of(
        "Lumbridge Cook... again", "Another Cook's",
        "Skrach 'Bone Crusher' Uglogwee", "Skrach Uglogwee"
    );

    /* VarbitIDs for quest tracking */
    private static final int VARBIT_QUESTS_COMPLETED_COUNT = 6347;
    private static final int VARBIT_QUESTS_TOTAL_COUNT = 11877;
    private static final int VARBIT_QP_MAX = 1782;

    @Override
    public void process(Object... args) {
        /* Unused - quest handling is event-driven */
    }
    
    @Override
    public boolean isEnabled() {
        return config.trackQuests();
    }

    @SuppressWarnings("deprecation")
    public void onWidgetLoaded(WidgetLoaded event) {
        if (event.getGroupId() == WidgetID.QUEST_COMPLETED_GROUP_ID && isEnabled()) {
            Widget questTitle = client.getWidget(InterfaceID.Questscroll.QUEST_TITLE); // Quest title widget
            if (questTitle != null) {
                String questText = questTitle.getText();
                // 1 tick delay to ensure relevant varbits have been processed by the client
                clientThread.invokeLater(() -> handleQuestCompletion(questText));
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void handleQuestCompletion(String questText) {
        // Get quest completion stats
        int completedQuests = client.getVarbitValue(VARBIT_QUESTS_COMPLETED_COUNT);
        int totalQuests = client.getVarbitValue(VARBIT_QUESTS_TOTAL_COUNT);
        boolean validQuests = completedQuests > 0 && totalQuests > 0;

        // Get quest points
        int questPoints = client.getVarpValue(VarPlayer.QUEST_POINTS);
        int totalQuestPoints = client.getVarbitValue(VARBIT_QP_MAX);
        boolean validPoints = questPoints > 0 && totalQuestPoints > 0;

        // Parse the quest name
        String parsedQuestName = parseQuestWidget(questText);
        if (parsedQuestName == null) {
            return;
        }

        // Create webhook body
        CustomWebhookBody webhook = createWebhookBody(getPlayerName() + " completed a quest!");
        CustomWebhookBody.Embed embed = createEmbed("Quest Completed!", "quest");
        
        // Add fields
        Map<String, Object> fieldData = new HashMap<>();
        fieldData.put("quest_name", parsedQuestName);
        
        if (validQuests) {
            fieldData.put("quests_completed", completedQuests);
            fieldData.put("total_quests", totalQuests);
            fieldData.put("completion_percentage", String.format("%.1f%%", (completedQuests * 100.0) / totalQuests));
        }
        
        if (validPoints) {
            fieldData.put("quest_points", questPoints);
            fieldData.put("total_quest_points", totalQuestPoints);
            fieldData.put("qp_percentage", String.format("%.1f%%", (questPoints * 100.0) / totalQuestPoints));
        }
        
        // Add timestamp
        fieldData.put("timestamp", System.currentTimeMillis() / 1000);
        
        addFields(embed, fieldData);
        webhook.getEmbeds().add(embed);
        
        // Send the data
        sendData(webhook, SubmissionType.QUEST_COMPLETION);
    }

    /* Helper methods */

    @Nullable
    private String parseQuestWidget(final String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // Try to match the quest completion text
        Matcher matcher = getMatcher(text);
        if (matcher == null) {
            // If no pattern matches, try to extract quest name directly
            // Some quests might just show the name without additional text
            return cleanQuestName(text);
        }

        String quest = matcher.group("quest");
        quest = QUEST_REPLACEMENTS.getOrDefault(quest, quest);

        String verb = StringUtils.defaultString(matcher.group("verb"));

        if (verb.contains("kind of")) {
            return null;
        } else if (verb.contains("completely")) {
            quest += " II";
        }

        // Handle Recipe for Disaster subquests
        if (RFD_TAGS.stream().anyMatch((quest + verb)::contains)) {
            quest = "Recipe for Disaster - " + quest;
        }

        // Add "Quest" to certain quest names that need it
        if (WORD_QUEST_IN_NAME_TAGS.stream().anyMatch(quest::contains)) {
            quest += " Quest";
        }

        return quest;
    }

    @Nullable
    private Matcher getMatcher(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }


        // "You have completed The Corsair Curse!"
        Matcher questMatch1 = QUEST_PATTERN_1.matcher(text);
        if (questMatch1.matches()) {
            return questMatch1;
        }

        // "'One Small Favour' completed!"
        Matcher questMatch2 = QUEST_PATTERN_2.matcher(text);
        if (questMatch2.matches()) {
            return questMatch2;
        }

        return null;
    }

    private String cleanQuestName(String text) {
        // Remove common prefixes/suffixes
        String cleaned = text;
        cleaned = cleaned.replaceAll("^You have completed ", "");
        cleaned = cleaned.replaceAll("^Congratulations! You've completed ", "");
        cleaned = cleaned.replaceAll("[!.]$", "");
        cleaned = cleaned.trim();
        // If it's still empty or too short, return null
        if (cleaned.isEmpty() || cleaned.length() < 3) {
            return null;
        }
        
        return cleaned;
    }
}
