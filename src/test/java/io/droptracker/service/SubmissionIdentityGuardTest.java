package io.droptracker.service;

import io.droptracker.models.CustomWebhookBody;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The chokepoint every submission passes through. Handlers already skip events
 * they cannot name; this is what stops a future path from quietly reintroducing
 * an unattributable submission.
 */
public class SubmissionIdentityGuardTest {

    private static CustomWebhookBody webhookWithName(String... playerNames) {
        CustomWebhookBody webhook = new CustomWebhookBody();
        for (String playerName : playerNames) {
            CustomWebhookBody.Embed embed = new CustomWebhookBody.Embed();
            embed.addField("type", "drop", true);
            embed.addField("player_name", playerName, true);
            embed.addField("acc_hash", "1234567890123456", true);
            webhook.getEmbeds().add(embed);
        }
        return webhook;
    }

    @Test
    public void passesAnIdentifiedSubmission() {
        assertFalse(SubmissionManager.hasUnidentifiedEmbed(webhookWithName("Wimi")));
    }

    @Test
    public void rejectsABlankName() {
        assertTrue(SubmissionManager.hasUnidentifiedEmbed(webhookWithName("")));
        assertTrue(SubmissionManager.hasUnidentifiedEmbed(webhookWithName("   ")));
        assertTrue(SubmissionManager.hasUnidentifiedEmbed(webhookWithName((String) null)));
    }

    // A drop is one embed per item: one nameless embed condemns the payload,
    // since they all describe the same event.
    @Test
    public void rejectsAPayloadWhereOnlyOneEmbedIsNameless() {
        assertTrue(SubmissionManager.hasUnidentifiedEmbed(webhookWithName("Wimi", "", "Wimi")));
    }

    // The adventure log names its identity field "player", not "player_name" —
    // it must not be caught by a guard aimed at a field it does not carry.
    @Test
    public void ignoresEmbedsWithoutAPlayerNameField() {
        CustomWebhookBody webhook = new CustomWebhookBody();
        CustomWebhookBody.Embed embed = new CustomWebhookBody.Embed();
        embed.addField("type", "adventure_log", true);
        embed.addField("player", "Wimi", true);
        webhook.getEmbeds().add(embed);
        assertFalse(SubmissionManager.hasUnidentifiedEmbed(webhook));
    }

    @Test
    public void toleratesEmptyAndNullPayloads() {
        assertFalse(SubmissionManager.hasUnidentifiedEmbed(null));
        assertFalse(SubmissionManager.hasUnidentifiedEmbed(new CustomWebhookBody()));
    }
}
