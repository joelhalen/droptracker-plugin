package io.droptracker.models.submissions;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CombatAchievementTest {

    @Test
    public void pointsIncreaseByOnePerTier() {
        assertEquals(1, CombatAchievement.EASY.getPoints());
        assertEquals(2, CombatAchievement.MEDIUM.getPoints());
        assertEquals(3, CombatAchievement.HARD.getPoints());
        assertEquals(4, CombatAchievement.ELITE.getPoints());
        assertEquals(5, CombatAchievement.MASTER.getPoints());
        assertEquals(6, CombatAchievement.GRANDMASTER.getPoints());
    }

    @Test
    public void displayNameIsTitleCased() {
        assertEquals("Easy", CombatAchievement.EASY.getDisplayName());
        assertEquals("Grandmaster", CombatAchievement.GRANDMASTER.getDisplayName());
        assertEquals("Easy", CombatAchievement.EASY.toString());
    }

    @Test
    public void tierLookupIsKeyedByLowerCaseName() {
        assertEquals(CombatAchievement.MASTER, CombatAchievement.TIER_BY_LOWER_NAME.get("master"));
        assertEquals(CombatAchievement.GRANDMASTER, CombatAchievement.TIER_BY_LOWER_NAME.get("grandmaster"));
    }

    @Test
    public void tierLookupMissesOnWrongCaseOrUnknown() {
        // The chat regex captures a lowercase tier word, so the map is only keyed lowercase.
        assertNull(CombatAchievement.TIER_BY_LOWER_NAME.get("Master"));
        assertNull(CombatAchievement.TIER_BY_LOWER_NAME.get("legendary"));
    }
}
