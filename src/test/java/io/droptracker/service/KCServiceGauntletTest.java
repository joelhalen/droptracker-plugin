package io.droptracker.service;

import io.droptracker.DropTrackerPlugin;
import io.droptracker.models.submissions.Drop;
import io.droptracker.util.NpcUtilities;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class KCServiceGauntletTest {
    private DropTrackerPlugin plugin;
    private KCService service;

    @Before
    public void setUp() {
        plugin = new DropTrackerPlugin();
        service = new KCService(null, null, null, null, plugin) {
            @Override
            protected boolean isPluginDisabled(String name) {
                return false;
            }
        };
        service.reset();
    }

    @After
    public void tearDown() {
        service.reset();
    }

    @Test
    public void corruptedRewardRetainsCompletionCountUnderEverySourceAlias() {
        complete("Gauntlet", "The Gauntlet", 12);
        complete("Corrupted Gauntlet", "The Corrupted Gauntlet", 39);

        for (String alias : new String[] {
                "Corrupted Hunllef", "Corrupted Gauntlet", "The Corrupted Gauntlet"}) {
            assertEquals(alias, Integer.valueOf(39), service.getKillCount(LootRecordType.NPC, alias));
        }
        assertEquals(Integer.valueOf(12), service.getKillCount(LootRecordType.EVENT, "The Gauntlet"));

        // A subsequent regular run must not inherit the corrupted count.
        complete("Gauntlet", "The Gauntlet", 13);
        assertEquals(Integer.valueOf(13), service.getKillCount(LootRecordType.NPC, "The Gauntlet"));
        assertEquals(Integer.valueOf(39), service.getKillCount(LootRecordType.NPC, "The Corrupted Gauntlet"));
    }

    @Test
    public void corruptedAliasesIdentifyOnlyGauntletRewardEvents() {
        for (String alias : new String[] {
                "Corrupted Hunllef", "Corrupted Gauntlet", "The Corrupted Gauntlet"}) {
            plugin.lastDrop = new Drop(alias, LootRecordType.NPC, Collections.emptyList());
            assertEquals("Corrupted Gauntlet", NpcUtilities.getStandardizedSource(loot("The Gauntlet"), plugin));
            assertEquals("Barrows", NpcUtilities.getStandardizedSource(loot("Barrows"), plugin));
            LootReceived npcLoot = new LootReceived("The Gauntlet", 0, LootRecordType.NPC,
                    Collections.emptyList(), 1, null);
            assertEquals("The Gauntlet", NpcUtilities.getStandardizedSource(npcLoot, plugin));
        }
    }

    private void complete(String chatName, String dropName, int count) {
        service.onGameMessage("Your " + chatName + " completion count is: " + count);
        // The drop handler can replace lastDrop with the canonical encounter
        // name before KCService sees the generic Gauntlet reward event.
        plugin.lastDrop = new Drop(dropName, LootRecordType.NPC, Collections.emptyList());
        service.onLoot(loot("The Gauntlet"));
    }

    private LootReceived loot(String name) {
        return new LootReceived(name, 0, LootRecordType.EVENT, Collections.emptyList(), 1, null);
    }
}
