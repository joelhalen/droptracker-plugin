package io.droptracker.events;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * The three slayer chat lines. The wording is verbatim from game captures
 * (Dink's SlayerNotifierTest), variants included.
 */
public class SlayerHandlerParseTest {

    // --- "You have completed your task!" --------------------------------

    @Test
    public void readsTheTaskLine() {
        SlayerHandler.TaskLine line = SlayerHandler.parseTaskLine(
            "You have completed your task! You killed 245 Cave Kraken. You gained 62,475 xp.").get();
        assertEquals(245, line.getKilled());
        assertEquals("Cave Kraken", line.getName());
        assertEquals(Integer.valueOf(62475), line.getXp());
    }

    @Test
    public void readsASingleKillWithAHyphenatedName() {
        SlayerHandler.TaskLine line = SlayerHandler.parseTaskLine(
            "You have completed your task! You killed 1 TzTok-Jad. You gained 69,420 xp.").get();
        assertEquals(1, line.getKilled());
        assertEquals("TzTok-Jad", line.getName());
    }

    @Test
    public void readsABossTaskLine() {
        SlayerHandler.TaskLine line = SlayerHandler.parseTaskLine(
            "You have completed your task! You killed 50 Bosses. You gained 14,200 xp.").get();
        assertEquals(50, line.getKilled());
        assertEquals("Bosses", line.getName());
    }

    @Test
    public void theXpIsOptional() {
        SlayerHandler.TaskLine line = SlayerHandler.parseTaskLine(
            "You have completed your task! You killed 1,020 Birds.").get();
        assertEquals(1020, line.getKilled());
        assertEquals("Birds", line.getName());
        assertNull(line.getXp());
    }

    @Test
    public void otherLinesAreNotTaskLines() {
        assertFalse(SlayerHandler.parseTaskLine("You're assigned to kill Cave Kraken; only 150 more to go.").isPresent());
        assertFalse(SlayerHandler.parseTaskLine("You have completed your task!").isPresent());
        assertFalse(SlayerHandler.parseTaskLine(
            "Congratulations, you've completed a hard combat task: Kraken Adept (3 points).").isPresent());
    }

    // --- "You've completed N tasks" -------------------------------------

    @Test
    public void readsStreakAndPoints() {
        SlayerHandler.StreakLine line = SlayerHandler.parseStreakLine(
            "You've completed 1,000 tasks and received 1,000 points, giving you a total of 2,584; return to a Slayer master.").get();
        assertEquals(1000, line.getStreak());
        assertEquals(Integer.valueOf(1000), line.getPointsAwarded());
        assertEquals(Integer.valueOf(2584), line.getPointsTotal());
    }

    @Test
    public void readsTheCommaVariant() {
        SlayerHandler.StreakLine line = SlayerHandler.parseStreakLine(
            "You've completed 1,000 tasks and received 1,000 points, giving you a total of 2,584, return to a Slayer master.").get();
        assertEquals(Integer.valueOf(2584), line.getPointsTotal());
    }

    @Test
    public void readsWildernessAndMortimerStreaks() {
        assertEquals(5, SlayerHandler.parseStreakLine(
            "You've completed 5 Wilderness tasks and received 25 points, giving you a total of 1,234; return to a Slayer master.")
            .get().getStreak());
        assertEquals(12, SlayerHandler.parseStreakLine(
            "You've completed 12 Mortimer tasks and received 10 points, giving you a total of 300; return to a Slayer master.")
            .get().getStreak());
    }

    @Test
    public void readsAtLeast() {
        assertEquals(1000, SlayerHandler.parseStreakLine(
            "You've completed at least 1,000 tasks and received 15 points, giving you a total of 3,000; return to a Slayer master.")
            .get().getStreak());
    }

    @Test
    public void aMasterThatAwardsNothingAwardsZero() {
        SlayerHandler.StreakLine line = SlayerHandler.parseStreakLine(
            "You've completed 17 tasks.You'll be eligible to earn reward points if you complete tasks from a more advanced Slayer Master.").get();
        assertEquals(17, line.getStreak());
        assertEquals(Integer.valueOf(0), line.getPointsAwarded());
        assertNull(line.getPointsTotal());
    }

    @Test
    public void atTheCapTheTotalIsKnownButNotTheAward() {
        SlayerHandler.StreakLine line = SlayerHandler.parseStreakLine(
            "You've completed 500 tasks and reached the maximum amount of Slayer points (64,000).").get();
        assertEquals(500, line.getStreak());
        assertNull(line.getPointsAwarded());
        assertEquals(Integer.valueOf(64000), line.getPointsTotal());
    }

    @Test
    public void aCombatTaskIsNotAStreakLine() {
        assertFalse(SlayerHandler.parseStreakLine(
            "Congratulations, you've completed an elite combat task: Nightmare Veteran (4 points).").isPresent());
    }

    // --- the boss reward line -------------------------------------------

    @Test
    public void readsTheBossTheWayTheCacheSpellsIt() {
        assertEquals("The Cave Kraken boss", SlayerHandler.parseBossLine(
            "You are granted an extra reward of 5k Slayer XP for completing your boss task against the Cave Kraken boss.").get());
        assertEquals("Barrows brothers", SlayerHandler.parseBossLine(
            "You are granted an extra reward of 5k Slayer XP for completing your boss task against Barrows brothers.").get());
        assertEquals("The Chaos Elemental", SlayerHandler.parseBossLine(
            "You are granted an extra reward of 5k Slayer XP for completing your boss task against the Chaos Elemental.").get());
    }
}
