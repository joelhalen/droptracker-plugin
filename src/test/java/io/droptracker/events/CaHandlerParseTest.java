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

    /**
     * Regression, 2026-08-26: Jagex began wrapping the task name in the
     * {@code @ach_comp@} click-through token. The token travelled with the name
     * into the panel and the submission, and because the server keys a
     * completion on its name, every marked-up task read as one nobody had ever
     * done — a duplicate row and a duplicate notification apiece.
     */
    @Test
    public void stripsAchCompChatToken() {
        Optional<Pair<CombatAchievement, String>> result = CaHandler.parseCombatAchievement(
                "Congratulations, you've completed a hard combat task: @ach_comp@Smite Fight.");
        assertTrue(result.isPresent());
        assertEquals(CombatAchievement.HARD, result.get().getLeft());
        assertEquals("Smite Fight", result.get().getRight());
    }

    @Test
    public void stripsChatTokenAlongsidePointsSuffix() {
        assertEquals("Whack-a-Mole", CaHandler.parseCombatAchievement(
                "Congratulations, you've completed a hard combat task: @ach_comp@Whack-a-Mole (3 points).")
                .get().getRight());
    }

    @Test
    public void cleanTaskNameLeavesOrdinaryNamesAlone() {
        assertEquals("Smite Fight", CaHandler.cleanTaskName("Smite Fight"));
        assertEquals("You're a wizard", CaHandler.cleanTaskName("You're a wizard"));
        assertEquals("", CaHandler.cleanTaskName(null));
    }

    @Test
    public void cleanTaskNameHandlesATrailingToken() {
        // The points suffix anchors on end-of-string, so markup is stripped
        // first; this is what that ordering buys.
        assertEquals("Smite Fight", CaHandler.cleanTaskName("Smite Fight (2 points)@ach_comp@"));
    }

    @Test
    public void returnsEmptyWhenTheTaskNameIsNothingButMarkup() {
        assertFalse(CaHandler.parseCombatAchievement(
                "Congratulations, you've completed a hard combat task: @ach_comp@.").isPresent());
    }

    @Test
    public void keepsTheTrailingPeriodOfTasksThatEndInOne() {
        // Four real tasks end in a period or an ellipsis; stripping it would
        // fork each of them off its own history on the server.
        assertEquals("Back in My Day...", CaHandler.parseCombatAchievement(
                "Congratulations, you've completed a master combat task: @ach_comp@Back in My Day....")
                .get().getRight());
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
