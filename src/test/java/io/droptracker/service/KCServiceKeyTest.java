package io.droptracker.service;

import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Tests {@link KCService#cleanBossName} and {@link KCService#getCacheKey}
 * (exposed {@code @VisibleForTesting}). These derive the keys under which kill
 * counts are stored/looked up, so a wrong key silently loses a boss's KC.
 */
public class KCServiceKeyTest {

    @Test
    public void cleanBossNameStripsTheArticleForSpecialBosses() {
        assertEquals("gauntlet", KCService.cleanBossName("The Gauntlet"));
        assertEquals("leviathan", KCService.cleanBossName("The Leviathan"));
        assertEquals("whisperer", KCService.cleanBossName("The Whisperer"));
    }

    @Test
    public void cleanBossNameCollapsesBarrowsToChests() {
        assertEquals("barrows chests", KCService.cleanBossName("Barrows Chests"));
        assertEquals("barrows chests", KCService.cleanBossName("Barrows"));
    }

    @Test
    public void cleanBossNameMapsParentheticalActivities() {
        assertEquals("hallowed sepulchre", KCService.cleanBossName("Coffin (Hallowed Sepulchre)"));
        assertEquals("tempoross", KCService.cleanBossName("Reward pool (Tempoross)"));
        assertEquals("wintertodt", KCService.cleanBossName("Supply crate (Wintertodt)"));
    }

    @Test
    public void cleanBossNameLowercasesAndDropsColons() {
        assertEquals("zulrah", KCService.cleanBossName("Zulrah"));
        assertEquals("vet'ion normal", KCService.cleanBossName("Vet'ion: Normal"));
    }

    @Test
    public void cacheKeyPrefixesPickpocketAndPlayer() {
        assertEquals("pickpocket_Man", KCService.getCacheKey(LootRecordType.PICKPOCKET, "Man"));
        assertEquals("player_Zezima", KCService.getCacheKey(LootRecordType.PLAYER, "Zezima"));
    }

    @Test
    public void cacheKeyRemapsGauntletVariantsToBossNames() {
        assertEquals("Crystalline Hunllef", KCService.getCacheKey(LootRecordType.NPC, "The Gauntlet"));
        assertEquals("Corrupted Hunllef", KCService.getCacheKey(LootRecordType.NPC, "Corrupted Gauntlet"));
    }

    @Test
    public void cacheKeyPassesThroughOrdinaryNpcNames() {
        assertEquals("Zulrah", KCService.getCacheKey(LootRecordType.NPC, "Zulrah"));
        assertEquals("Vorkath", KCService.getCacheKey(LootRecordType.UNKNOWN, "Vorkath"));
    }
}
