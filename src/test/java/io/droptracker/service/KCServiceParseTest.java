package io.droptracker.service;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class KCServiceParseTest {

    @Test
    public void parsesClueCompletionMessage() {
        Map.Entry<String, Integer> result =
                KCService.parseClue("You have completed 100 hard Treasure Trails.");
        assertEquals("hard", result.getKey());
        assertEquals(Integer.valueOf(100), result.getValue());
    }

    @Test
    public void parsesEachClueTier() {
        String[] tiers = {"beginner", "easy", "medium", "hard", "elite", "master"};
        for (String tier : tiers) {
            Map.Entry<String, Integer> result =
                    KCService.parseClue("You have completed 7 " + tier + " Treasure Trails.");
            assertEquals(tier, result.getKey());
            assertEquals(Integer.valueOf(7), result.getValue());
        }
    }

    @Test
    public void parsesSingleCompletion() {
        Map.Entry<String, Integer> result =
                KCService.parseClue("You have completed 1 master Treasure Trails.");
        assertEquals("master", result.getKey());
        assertEquals(Integer.valueOf(1), result.getValue());
    }

    @Test
    public void parsesClueMessageEmbeddedInLongerText() {
        // parseClue uses find(), so surrounding text should not break it
        Map.Entry<String, Integer> result =
                KCService.parseClue("Well done! You have completed 250 elite Treasure Trails.");
        assertEquals("elite", result.getKey());
        assertEquals(Integer.valueOf(250), result.getValue());
    }

    @Test
    public void returnsNullForNonClueMessages() {
        assertNull(KCService.parseClue("Your Zulrah kill count is: 100"));
        assertNull(KCService.parseClue("You have a funny feeling like you're being followed."));
        assertNull(KCService.parseClue(""));
    }

    @Test
    public void returnsNullWhenCountIsMissing() {
        assertNull(KCService.parseClue("You have completed some hard Treasure Trails."));
    }

    @Test
    public void returnsNullWithoutTrailingPeriod() {
        assertNull(KCService.parseClue("You have completed 100 hard Treasure Trails"));
    }
}
