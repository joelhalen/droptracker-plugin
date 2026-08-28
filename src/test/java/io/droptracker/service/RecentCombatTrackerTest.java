package io.droptracker.service;

import java.util.Optional;

import io.droptracker.service.RecentCombatTracker.Engagement;
import io.droptracker.testing.RuneLiteStubs;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link RecentCombatTracker}, the memory that lets a death be attributed
 * after every live candidate has vanished.
 *
 * <p>The production failure this exists for: on the tick the local player dies,
 * the killer has usually already dropped its target (the game clears an NPC's
 * interaction when that target dies), so a scan of "who is attacking me right
 * now" finds nobody and the death is submitted with a blank source and a killer
 * type of "unknown".
 */
public class RecentCombatTrackerTest {

    private final RecentCombatTracker tracker = new RecentCombatTracker();

    /** The incident shape: the boss let go of us at the very tick we died. */
    @Test
    public void attackerIsStillBlamedAfterItDropsItsTargetOnTheDeathTick() {
        NPC vorkath = RuneLiteStubs.npc(1, 8061, "Vorkath", 732);

        tracker.recordAttackedBy(vorkath, 100);
        tracker.recordAttacking(vorkath, 100);
        // Death tick: the client tells us it stopped interacting with us, then
        // fires ActorDeath for the local player.
        tracker.endAttackedBy(vorkath, 118);

        Engagement killer = require(tracker.mostLikelyKiller(118, false));
        assertEquals("Vorkath", killer.getName());
        assertEquals(Integer.valueOf(8061), killer.getNpcId());
        assertEquals(732, killer.getCombatLevel());
        assertFalse(killer.isPlayer());
        assertTrue(killer.isNpc());
    }

    @Test
    public void engagementThatEndedLongAgoIsNotBlamed() {
        NPC goblin = RuneLiteStubs.npc(1, 100, "Goblin", 2);
        tracker.recordAttackedBy(goblin, 100);
        tracker.endAttackedBy(goblin, 100);

        assertTrue(tracker.mostLikelyKiller(100 + RecentCombatTracker.RECENT_WINDOW_TICKS, false).isPresent());
        assertFalse(tracker.mostLikelyKiller(100 + RecentCombatTracker.RECENT_WINDOW_TICKS + 1, false).isPresent());
    }

    /**
     * A boss that despawns with its instance never reports that it disengaged,
     * so the engagement stays open. It is still the right answer for a while —
     * but only a while, or a Gauntlet leftover gets blamed for a bank-stand
     * death minutes later.
     */
    @Test
    public void openEngagementIsTrustedLongerThanAFinishedOneButNotForever() {
        NPC hunllef = RuneLiteStubs.npc(1, 9035, "Crystalline Hunllef", 890);
        tracker.recordAttackedBy(hunllef, 100);

        int longAfterRecent = 100 + RecentCombatTracker.RECENT_WINDOW_TICKS + 5;
        assertTrue(tracker.mostLikelyKiller(longAfterRecent, false).isPresent());
        assertTrue(tracker.mostLikelyKiller(100 + RecentCombatTracker.OPEN_WINDOW_TICKS, false).isPresent());
        assertFalse(tracker.mostLikelyKiller(100 + RecentCombatTracker.OPEN_WINDOW_TICKS + 1, false).isPresent());
    }

    /**
     * The thing we were trading blows with is the answer people expect, even
     * when something bigger wandered in and aggroed us on the way down. The
     * distractor is deliberately the higher-level NPC so that only the
     * direction of the engagement can decide this.
     */
    @Test
    public void mutualCombatOutranksAHigherLevelBystanderThatMerelyAggroed() {
        NPC weFought = RuneLiteStubs.npc(1, 100, "Green dragon", 79);
        NPC aggroed = RuneLiteStubs.npc(2, 101, "Chaos Elemental", 305);

        tracker.recordAttacking(weFought, 100);
        tracker.recordAttackedBy(weFought, 100);
        tracker.recordAttackedBy(aggroed, 104);

        assertEquals("Green dragon", require(tracker.mostLikelyKiller(105, false)).getName());
    }

    /**
     * Something that attacked us beats something we merely clicked on and that
     * never fought back — again with the levels inverted so only the direction
     * can be doing the work.
     */
    @Test
    public void inboundAttackerOutranksAHigherLevelOutboundTarget() {
        NPC weClicked = RuneLiteStubs.npc(1, 8061, "Vorkath", 732);
        NPC itAttackedUs = RuneLiteStubs.npc(2, 101, "Skeletal Wyvern", 140);

        tracker.recordAttacking(weClicked, 100);
        tracker.recordAttackedBy(itAttackedUs, 100);

        assertEquals("Skeletal Wyvern", require(tracker.mostLikelyKiller(101, false)).getName());
    }

    /**
     * Something the client told us disengaged three ticks ago was still on us at
     * the death; something that walked off twenty seconds ago was not, and must
     * not outrank it just for being a bigger monster.
     */
    @Test
    public void anEngagementLiveAtTheDeathBeatsAStalerBiggerOne() {
        NPC oldBoss = RuneLiteStubs.npc(1, 8061, "Vorkath", 732);
        NPC current = RuneLiteStubs.npc(2, 100, "Green dragon", 79);

        tracker.recordAttackedBy(oldBoss, 100);
        tracker.endAttackedBy(oldBoss, 101);
        tracker.recordAttackedBy(current, 108);
        tracker.endAttackedBy(current, 110);

        assertEquals("Green dragon", require(tracker.mostLikelyKiller(111, false)).getName());
    }

    /** Pets follow us around all day; they are not killers. */
    @Test
    public void followersAreNotTracked() {
        NPC pet = RuneLiteStubs.npc(1, 13178, "Vorki", 0, false, true);
        tracker.recordAttackedBy(pet, 100);
        assertEquals(0, tracker.size());
        assertFalse(tracker.mostLikelyKiller(100, false).isPresent());
    }

    /** Talking to a banker is an interaction, not a fight. */
    @Test
    public void harmlessNpcsAreNotTracked() {
        NPC banker = RuneLiteStubs.npc(1, 1613, "Banker", 0, false, false);
        tracker.recordAttacking(banker, 100);
        assertEquals(0, tracker.size());
    }

    /**
     * Regression: the old handler only remembered targets with a combat level
     * above zero, which silently excluded every boss the game gives no level to.
     */
    @Test
    public void attackableNpcWithNoCombatLevelIsStillTracked() {
        NPC zalcano = RuneLiteStubs.npc(1, 9049, "Zalcano", 0, true, false);
        tracker.recordAttackedBy(zalcano, 100);
        assertEquals("Zalcano", require(tracker.mostLikelyKiller(101, false)).getName());
    }

    @Test
    public void playersAreOnlyBlamedWhereTheyCouldHaveKilledUs() {
        Player pker = RuneLiteStubs.player("Zezima", 126);
        tracker.recordAttackedBy(pker, 100);

        assertFalse(tracker.mostLikelyKiller(101, false).isPresent());

        Engagement killer = require(tracker.mostLikelyKiller(101, true));
        assertEquals("Zezima", killer.getName());
        assertTrue(killer.isPlayer());
        assertNull(killer.getNpcId());
    }

    /** Trading a clanmate in the wilderness must not make them the killer. */
    @Test
    public void friendsAreNeverBlamed() {
        Player friend = RuneLiteStubs.friendlyPlayer("Buddy", 126);
        tracker.recordAttacking(friend, 100);
        assertFalse(tracker.mostLikelyKiller(101, true).isPresent());
    }

    @Test
    public void anActorThatDiedFirstIsNotBlamed() {
        NPC dagannoth = RuneLiteStubs.npc(1, 100, "Dagannoth Rex", 303);
        tracker.recordAttacking(dagannoth, 100);
        tracker.recordAttackedBy(dagannoth, 100);

        RuneLiteStubs.state(dagannoth).put("isDead", true);
        assertFalse(tracker.mostLikelyKiller(101, false).isPresent());
    }

    @Test
    public void forgetDropsAnActorAndClearWipesEverything() {
        NPC first = RuneLiteStubs.npc(1, 100, "Goblin", 2);
        NPC second = RuneLiteStubs.npc(2, 101, "Imp", 2);
        tracker.recordAttackedBy(first, 100);
        tracker.recordAttackedBy(second, 100);

        tracker.forget(first);
        assertEquals(1, tracker.size());

        tracker.clear();
        assertEquals(0, tracker.size());
    }

    /**
     * The whole point of snapshotting: the attribution has to survive the actor
     * leaving the scene, so it cannot read anything off the actor at death time.
     */
    @Test
    public void attributionSurvivesTheActorLosingItsIdentity() {
        NPC boss = RuneLiteStubs.npc(1, 11751, "The Whisperer", 480);
        tracker.recordAttackedBy(boss, 100);

        // The instance is torn down: the NPC still exists as an object but no
        // longer reports a name or a composition.
        RuneLiteStubs.state(boss).put("getName", null);
        RuneLiteStubs.state(boss).put("getTransformedComposition", null);
        RuneLiteStubs.state(boss).put("getComposition", null);

        Engagement killer = require(tracker.mostLikelyKiller(101, false));
        assertEquals("The Whisperer", killer.getName());
        assertEquals(Integer.valueOf(11751), killer.getNpcId());
    }

    /** A relog or hop resets the tick counter; the old history means nothing. */
    @Test
    public void historyFromBeforeATickCounterResetIsDiscarded() {
        NPC goblin = RuneLiteStubs.npc(1, 100, "Goblin", 2);
        tracker.recordAttackedBy(goblin, 5_000);
        assertFalse(tracker.mostLikelyKiller(12, false).isPresent());
    }

    @Test
    public void switchingTargetClosesThePreviousOutboundEngagement() {
        NPC first = RuneLiteStubs.npc(1, 100, "Goblin", 2);
        NPC second = RuneLiteStubs.npc(2, 101, "Imp", 2);

        tracker.recordAttacking(first, 100);
        tracker.recordAttacking(second, 102);

        // The first is now a finished engagement and ages out on the short
        // window; the second is still open and does not.
        int afterRecentWindow = 102 + RecentCombatTracker.RECENT_WINDOW_TICKS + 1;
        assertEquals("Imp", require(tracker.mostLikelyKiller(afterRecentWindow, false)).getName());
    }

    @Test
    public void nameIsStrippedOfMarkupAndNonBreakingSpaces() {
        assertEquals("Dark wizard", RecentCombatTracker.cleanName("<col=ff0000>Dark wizard</col>"));
        assertEquals("Elite Black Knight", RecentCombatTracker.cleanName("Elite Black Knight"));
        assertNull(RecentCombatTracker.cleanName("<col=ff0000></col>"));
        assertNull(RecentCombatTracker.cleanName("   "));
        assertNull(RecentCombatTracker.cleanName(null));
    }

    /** A player with no readable name cannot be named as a killer. */
    @Test
    public void namelessPlayersAreNotTracked() {
        Player nameless = RuneLiteStubs.player(null, 100);
        tracker.recordAttackedBy(nameless, 100);
        assertEquals(0, tracker.size());
    }

    private static Engagement require(Optional<Engagement> engagement) {
        assertTrue("expected a killer to be attributed", engagement.isPresent());
        return engagement.get();
    }
}
