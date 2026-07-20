package io.droptracker.events;

import io.droptracker.models.submissions.CombatAchievement;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link CaHandler#parseCombatAchievement} (exposed {@code @VisibleForTesting}):
 * it maps the tier word to a {@link CombatAchievement} and strips the trailing
 * "(N points)" suffix from the task name.
 */
public class CaHandlerParseTest {

    @Test
    public void parsesTierAndTaskName() {
        Optional<Pair<CombatAchievement, String>> result = CaHandler.parseCombatAchievement(
                "Congratulations, you've completed an elite combat task: A Slow Death.");
        assertTrue(result.isPresent());
        assertEquals(CombatAchievement.ELITE, result.get().getLeft());
        assertEquals("A Slow Death", result.get().getRight());
    }

    @Test
    public void stripsTrailingPointsSuffixFromTask() {
        Optional<Pair<CombatAchievement, String>> result = CaHandler.parseCombatAchievement(
                "Congratulations, you've completed a hard combat task: Whack-a-Mole (3 points).");
        assertTrue(result.isPresent());
        assertEquals(CombatAchievement.HARD, result.get().getLeft());
        assertEquals("Whack-a-Mole", result.get().getRight());
    }

    @Test
    public void handlesArticleVariationAAndAn() {
        assertEquals(CombatAchievement.MASTER, CaHandler.parseCombatAchievement(
                "Congratulations, you've completed a master combat task: Peach Conjurer.").get().getLeft());
        assertEquals(CombatAchievement.EASY, CaHandler.parseCombatAchievement(
                "Congratulations, you've completed an easy combat task: Off the Chain.").get().getLeft());
    }

    @Test
    public void returnsEmptyForUnknownTierWord() {
        assertFalse(CaHandler.parseCombatAchievement(
                "Congratulations, you've completed a legendary combat task: Nope.").isPresent());
    }

    @Test
    public void returnsEmptyForUnrelatedMessage() {
        assertFalse(CaHandler.parseCombatAchievement("You have completed 100 hard Treasure Trails.").isPresent());
        assertFalse(CaHandler.parseCombatAchievement("").isPresent());
    }
}
