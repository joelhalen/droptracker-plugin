package io.droptracker.events;

import org.junit.Test;

import java.util.regex.Matcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link ClogHandler#COLLECTION_LOG_REGEX}, which lifts the item name out
 * of the "New item added to your collection log:" chat message.
 */
public class ClogHandlerRegexTest {

    @Test
    public void capturesItemName() {
        Matcher m = ClogHandler.COLLECTION_LOG_REGEX.matcher(
                "New item added to your collection log: Twisted bow");
        assertTrue(m.find());
        assertEquals("Twisted bow", m.group("itemName"));
    }

    @Test
    public void capturesItemNameWithPunctuation() {
        Matcher m = ClogHandler.COLLECTION_LOG_REGEX.matcher(
                "New item added to your collection log: Vet'ion jr.");
        assertTrue(m.find());
        assertEquals("Vet'ion jr.", m.group("itemName"));
    }

    @Test
    public void doesNotMatchUnrelatedMessage() {
        assertFalse(ClogHandler.COLLECTION_LOG_REGEX.matcher(
                "You have a funny feeling like you're being followed.").find());
    }
}
