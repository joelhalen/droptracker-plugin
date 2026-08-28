package io.droptracker.events;

import java.util.Collections;
import java.util.EnumSet;

import io.droptracker.DropTrackerConfig;
import io.droptracker.testing.RuneLiteStubs;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.InteractingChanged;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link DeathHandler#identifyKiller}, which decides what a death
 * submission reports as its source.
 *
 * <p>Attribution has two layers: a live scan for whoever is still targeting the
 * local player, and — when that finds nobody, which in production is most of the
 * time — the engagement history built from {@code InteractingChanged}. Without
 * the second layer the submission goes out with no source and a killer type of
 * "unknown".
 */
public class DeathHandlerKillerAttributionTest {

    private DeathHandler handler;
    private Player localPlayer;
    private Client client;

    @Before
    public void setUp() {
        handler = new DeathHandler();
        localPlayer = RuneLiteStubs.player("Local", 126);
        client = RuneLiteStubs.client(localPlayer);
        handler.client = client;
        handler.config = RuneLiteStubs.stub(DropTrackerConfig.class, "config");
    }

    private void tick(int tick) {
        RuneLiteStubs.state(client).put("getTickCount", tick);
    }

    private void targets(NPC npc, Object target) {
        RuneLiteStubs.state(npc).put("getInteracting", target);
    }

    // --- live scan ------------------------------------------------------

    @Test
    public void npcStillAttackingUsIsAttributed() {
        NPC vorkath = RuneLiteStubs.npc(1, 8061, "Vorkath", 732);
        targets(vorkath, localPlayer);
        RuneLiteStubs.setScene(client, localPlayer, Collections.emptyList(), Collections.singletonList(vorkath));

        DeathHandler.Killer killer = handler.identifyKiller(localPlayer);
        assertNotNull(killer);
        assertEquals("Vorkath", killer.getName());
        assertEquals(Integer.valueOf(8061), killer.getNpcId());
        assertTrue(killer.isNpc());
        assertFalse(killer.isPlayer());
    }

    /**
     * Instanced NPCs sometimes report no name of their own; the composition still
     * has one, and an embed with an NPC id but no source is what a blank killer
     * looks like on the site.
     */
    @Test
    public void npcWithNoNameOfItsOwnFallsBackToItsComposition() {
        NPC boss = RuneLiteStubs.npc(1, 11751, "The Whisperer", 480);
        RuneLiteStubs.state(boss).put("getName", null);
        targets(boss, localPlayer);
        RuneLiteStubs.setScene(client, localPlayer, Collections.emptyList(), Collections.singletonList(boss));

        assertEquals("The Whisperer", handler.identifyKiller(localPlayer).getName());
    }

    // --- the fallback ---------------------------------------------------

    /**
     * The production failure. The boss dropped its target when we died and the
     * instance took it out of the scene, so nothing is left to scan — but the
     * interaction history still knows who we were fighting two ticks ago.
     */
    @Test
    public void deathWithNothingLeftToScanIsAttributedFromRecentCombat() {
        NPC vorkath = RuneLiteStubs.npc(1, 8061, "Vorkath", 732);

        tick(100);
        handler.onInteractingChanged(new InteractingChanged(localPlayer, vorkath));
        handler.onInteractingChanged(new InteractingChanged(vorkath, localPlayer));

        // Death tick: it lets go of us and leaves the scene entirely.
        tick(118);
        handler.onInteractingChanged(new InteractingChanged(vorkath, null));
        RuneLiteStubs.setScene(client, localPlayer, Collections.emptyList(), Collections.emptyList());

        DeathHandler.Killer killer = handler.identifyKiller(localPlayer);
        assertNotNull("a death with no live attacker must still name a killer", killer);
        assertEquals("Vorkath", killer.getName());
        assertEquals(Integer.valueOf(8061), killer.getNpcId());
        assertEquals(732, killer.getCombatLevel());
    }

    /**
     * Regression: only the local player's outbound target used to be recorded,
     * so anything that attacked us without us ever clicking it — the common way
     * to die — left no trace at all.
     */
    @Test
    public void anNpcAttackingUsIsRememberedEvenIfWeNeverAttackedIt() {
        NPC dagannoth = RuneLiteStubs.npc(1, 100, "Dagannoth", 90);

        tick(50);
        handler.onInteractingChanged(new InteractingChanged(dagannoth, localPlayer));
        assertEquals(1, handler.recentCombat.size());

        tick(52);
        assertEquals("Dagannoth", handler.identifyKiller(localPlayer).getName());
    }

    @Test
    public void aDeathNothingExplainsIsStillReportedAsUnattributed() {
        // Poison, falling damage, a wilderness dragonfire wall: nothing ever
        // engaged us, so there is nobody to blame and we say so.
        assertNull(handler.identifyKiller(localPlayer));
    }

    // --- players --------------------------------------------------------

    @Test
    public void aPlayerIsNotBlamedOutsidePvp() {
        Player bystander = RuneLiteStubs.player("Zezima", 126);
        RuneLiteStubs.state(bystander).put("getInteracting", localPlayer);
        RuneLiteStubs.setScene(client, localPlayer, Collections.singletonList(bystander), Collections.emptyList());

        tick(10);
        handler.onInteractingChanged(new InteractingChanged(bystander, localPlayer));

        assertNull(handler.identifyKiller(localPlayer));
    }

    @Test
    public void aPkerInTheWildernessIsAttributed() {
        insideWilderness();
        Player pker = RuneLiteStubs.player("Zezima", 126);
        RuneLiteStubs.state(pker).put("getInteracting", localPlayer);
        RuneLiteStubs.setScene(client, localPlayer, Collections.singletonList(pker), Collections.emptyList());

        DeathHandler.Killer killer = handler.identifyKiller(localPlayer);
        assertNotNull(killer);
        assertEquals("Zezima", killer.getName());
        assertTrue(killer.isPlayer());
        assertNull(killer.getNpcId());
    }

    /**
     * A PvP world's bank is a safe zone: nobody standing there can have killed
     * us, so no bystander should be named.
     */
    @Test
    public void nobodyIsBlamedInsideAPvpSafeZone() {
        RuneLiteStubs.state(client).put("getWorldType", EnumSet.of(WorldType.PVP));
        RuneLiteStubs.state(client).put("getWidget", RuneLiteStubs.visibleWidget());

        Player bystander = RuneLiteStubs.player("Zezima", 126);
        RuneLiteStubs.state(bystander).put("getInteracting", localPlayer);
        RuneLiteStubs.setScene(client, localPlayer, Collections.singletonList(bystander), Collections.emptyList());

        assertNull(handler.identifyKiller(localPlayer));
    }

    /** A dead attacker is a corpse we outlived, not our killer. */
    @Test
    public void anNpcThatDiedFirstIsNotBlamed() {
        NPC dying = RuneLiteStubs.npc(1, 100, "Dagannoth", 90);
        targets(dying, localPlayer);

        tick(20);
        handler.onInteractingChanged(new InteractingChanged(dying, localPlayer));
        RuneLiteStubs.state(dying).put("isDead", true);
        RuneLiteStubs.setScene(client, localPlayer, Collections.emptyList(), Collections.singletonList(dying));

        assertNull(handler.identifyKiller(localPlayer));
    }

    // --- history lifecycle ----------------------------------------------

    @Test
    public void ourOwnDeathWipesTheHistory() {
        NPC vorkath = RuneLiteStubs.npc(1, 8061, "Vorkath", 732);
        tick(100);
        handler.onInteractingChanged(new InteractingChanged(vorkath, localPlayer));
        assertEquals(1, handler.recentCombat.size());

        // deathEmbeds() is false on the stub, so the handler skips the
        // submission — the history must still be reset for the next life.
        handler.onActorDeath(new ActorDeath(localPlayer));
        assertEquals(0, handler.recentCombat.size());
    }

    @Test
    public void killingSomethingRemovesItFromTheHistory() {
        NPC vorkath = RuneLiteStubs.npc(1, 8061, "Vorkath", 732);
        tick(100);
        handler.onInteractingChanged(new InteractingChanged(localPlayer, vorkath));
        assertEquals(1, handler.recentCombat.size());

        handler.onActorDeath(new ActorDeath(vorkath));
        assertEquals(0, handler.recentCombat.size());
    }

    @Test
    public void interactingWithAHarmlessNpcLeavesNoCandidate() {
        NPC banker = RuneLiteStubs.npc(1, 1613, "Banker", 0, false, false);
        tick(100);
        handler.onInteractingChanged(new InteractingChanged(localPlayer, banker));

        assertEquals(0, handler.recentCombat.size());
        assertNull(handler.identifyKiller(localPlayer));
    }

    private void insideWilderness() {
        RuneLiteStubs.state(client).put("getVarbitValue", 1);
    }

    /**
     * Guards the stub itself: if the scene helper handed back an empty set the
     * live-scan tests above would pass for the wrong reason.
     */
    @Test
    public void sceneStubExposesItsNpcsToTheScan() {
        NPC npc = RuneLiteStubs.npc(1, 100, "Goblin", 2);
        RuneLiteStubs.setScene(client, localPlayer, Collections.emptyList(), Collections.singletonList(npc));
        assertEquals(1L, localPlayer.getWorldView().npcs().stream().count());
    }
}
