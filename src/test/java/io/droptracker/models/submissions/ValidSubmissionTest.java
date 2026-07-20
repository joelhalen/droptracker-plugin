package io.droptracker.models.submissions;

import io.droptracker.models.CustomWebhookBody;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests the webhook-to-submission field extraction and the derived UI labels.
 * extractDataFromWebhook is the bridge from the outgoing embed to the retry
 * record shown in the panel, so a mismapped field name loses retry metadata.
 */
public class ValidSubmissionTest {

    private static CustomWebhookBody webhookWith(CustomWebhookBody.Embed embed) {
        CustomWebhookBody body = new CustomWebhookBody();
        body.getEmbeds().add(embed);
        return body;
    }

    @Test
    public void extractsDropFieldsFromEmbed() {
        CustomWebhookBody.Embed embed = new CustomWebhookBody.Embed();
        embed.title = "Koeppy received some drops:";
        embed.addField("source", "Zulrah", true);
        embed.addField("item", "Tanzanite fang", true);
        embed.addField("item_id", "12922", true);
        embed.addField("player_name", "Koeppy", true);
        embed.addField("guid", "abc-123", true);

        ValidSubmission sub = new ValidSubmission(webhookWith(embed), "5", SubmissionType.DROP);

        assertEquals("Koeppy received some drops:", sub.getDescription());
        assertEquals("Zulrah", sub.getNpcName());
        assertEquals("Tanzanite fang", sub.getItemName());
        assertEquals("12922", sub.getItemId());
        assertEquals("Koeppy", sub.getAccountHash());
        assertEquals("abc-123", sub.getUuid());
        assertEquals(SubmissionType.DROP, sub.getType());
        assertEquals("5", sub.getGroupIds()[0]);
    }

    @Test
    public void petNameFillsItemNameOnlyWhenAbsent() {
        CustomWebhookBody.Embed embed = new CustomWebhookBody.Embed();
        embed.addField("pet_name", "Herbi", true);
        ValidSubmission sub = new ValidSubmission(webhookWith(embed), "1", SubmissionType.PET);
        assertEquals("Herbi", sub.getItemName());
    }

    @Test
    public void defaultConstructorStartsPending() {
        ValidSubmission sub = new ValidSubmission();
        assertEquals(SubmissionStatus.PENDING, sub.getStatus());
        assertEquals(0, sub.getGroupIds().length);
        assertEquals(0, sub.getRetryAttempts());
    }

    @Test
    public void typeLabelsMapToDisplayStrings() {
        ValidSubmission sub = new ValidSubmission();
        sub.setType(SubmissionType.KILL_TIME);
        assertEquals("Personal Best", sub.getTypeLabel());
        assertEquals("PB", sub.getTypeShortLabel());

        sub.setType(null);
        assertEquals("Submission", sub.getTypeLabel());
        assertEquals("SUB", sub.getTypeShortLabel());
    }

    @Test
    public void displayTextFallsBackFromItemToDescriptionToUnknown() {
        ValidSubmission sub = new ValidSubmission();
        sub.setType(SubmissionType.DROP);
        sub.setItemName("Dragon warhammer");
        assertEquals("Drop: Dragon warhammer", sub.getDisplayText());

        sub.setItemName(null);
        sub.setDescription("A rare event");
        assertEquals("Drop: A rare event", sub.getDisplayText());

        sub.setDescription(null);
        assertEquals("Drop: Unknown", sub.getDisplayText());
    }

    @Test
    public void statusDescriptionAnnotatesFailureAndRetry() {
        ValidSubmission sub = new ValidSubmission();
        sub.setStatus(SubmissionStatus.FAILED);
        sub.setLastFailureReason("HTTP 500");
        assertEquals("Failed: HTTP 500", sub.getStatusDescription());

        sub.setStatus(SubmissionStatus.RETRYING);
        sub.setRetryAttempts(2);
        assertEquals("Retrying... (attempt 3)", sub.getStatusDescription());
    }

    @Test
    public void canRetryRequiresAttemptsWebhookAndRetryableStatus() {
        ValidSubmission sub = new ValidSubmission();
        sub.setStatus(SubmissionStatus.FAILED);
        // No originalWebhook stored yet.
        assertFalse(sub.canRetry());

        ValidSubmission withWebhook =
                new ValidSubmission(new CustomWebhookBody(), "1", SubmissionType.DROP);
        withWebhook.setStatus(SubmissionStatus.FAILED);
        assertTrue(withWebhook.canRetry());

        withWebhook.setRetryAttempts(5);
        assertFalse(withWebhook.canRetry());
    }
}
