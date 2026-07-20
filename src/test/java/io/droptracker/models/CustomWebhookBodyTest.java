package io.droptracker.models;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CustomWebhookBodyTest {

    @Test
    public void addFieldAppendsInOrder() {
        CustomWebhookBody.Embed embed = new CustomWebhookBody.Embed();
        embed.addField("source", "Zulrah", true);
        embed.addField("item", "Tanzanite fang", true);

        assertEquals(2, embed.getFields().size());
        assertEquals("source", embed.getFields().get(0).getName());
        assertEquals("Zulrah", embed.getFields().get(0).getValue());
        assertTrue(embed.getFields().get(0).isInline());
    }

    @Test
    public void getFieldReturnsFirstMatchingValueOrNull() {
        CustomWebhookBody body = new CustomWebhookBody();
        CustomWebhookBody.Embed embed = new CustomWebhookBody.Embed();
        embed.addField("kc", "1234", true);

        assertEquals("1234", body.getField(embed, "kc"));
        assertNull(body.getField(embed, "missing"));
    }

    @Test
    public void setImageWrapsUrl() {
        CustomWebhookBody.Embed embed = new CustomWebhookBody.Embed();
        assertNull(embed.getImage());
        embed.setImage("https://www.droptracker.io/img/x.png");
        assertEquals("https://www.droptracker.io/img/x.png", embed.getImage().getUrl());
    }

    @Test
    public void embedsListStartsEmpty() {
        assertTrue(new CustomWebhookBody().getEmbeds().isEmpty());
    }
}
