package io.droptracker.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BossNameRegistryTest {

    /**
     * The boss_names.json data file was extracted from the 366 case labels of
     * the former switch statement in WidgetEventHandler.longBossName. If a
     * mapping is added or removed, this count must be updated deliberately.
     */
    @Test
    public void registryLoadsExactMappingCount() {
        assertEquals(366, BossNameRegistry.size());
    }

    // --- Raids: Chambers of Xeric ---

    @Test
    public void chambersOfXericAliases() {
        assertEquals("Chambers of Xeric", BossNameRegistry.canonicalName("cox"));
        assertEquals("Chambers of Xeric", BossNameRegistry.canonicalName("olm"));
        assertEquals("Chambers of Xeric", BossNameRegistry.canonicalName("raids"));
        assertEquals("Chambers of Xeric Solo", BossNameRegistry.canonicalName("cox solo"));
        assertEquals("Chambers of Xeric 2 players", BossNameRegistry.canonicalName("cox duo"));
        assertEquals("Chambers of Xeric 11-15 players", BossNameRegistry.canonicalName("cox 13"));
        assertEquals("Chambers of Xeric 16-23 players", BossNameRegistry.canonicalName("cox 16-23"));
        assertEquals("Chambers of Xeric 24+ players", BossNameRegistry.canonicalName("cox 24+"));
    }

    @Test
    public void chambersOfXericChallengeModeAliases() {
        assertEquals("Chambers of Xeric Challenge Mode", BossNameRegistry.canonicalName("cox cm"));
        assertEquals("Chambers of Xeric Challenge Mode",
                BossNameRegistry.canonicalName("chambers of xeric: challenge mode"));
        assertEquals("Chambers of Xeric Challenge Mode Solo", BossNameRegistry.canonicalName("cox cm solo"));
        assertEquals("Chambers of Xeric Challenge Mode 11-15 players", BossNameRegistry.canonicalName("cox cm 12"));
        assertEquals("Chambers of Xeric Challenge Mode 24+ players", BossNameRegistry.canonicalName("cox cm 24+"));
    }

    // --- Raids: Theatre of Blood ---

    @Test
    public void theatreOfBloodAliases() {
        assertEquals("Theatre of Blood", BossNameRegistry.canonicalName("tob"));
        assertEquals("Theatre of Blood", BossNameRegistry.canonicalName("verzik vitur"));
        assertEquals("Theatre of Blood Solo", BossNameRegistry.canonicalName("tob solo"));
        assertEquals("Theatre of Blood 5 players", BossNameRegistry.canonicalName("tob 5"));
        assertEquals("Theatre of Blood Entry Mode", BossNameRegistry.canonicalName("tob em"));
        assertEquals("Theatre of Blood Entry Mode",
                BossNameRegistry.canonicalName("theatre of blood: story mode"));
        assertEquals("Theatre of Blood Hard Mode", BossNameRegistry.canonicalName("hmt"));
        assertEquals("Theatre of Blood Hard Mode", BossNameRegistry.canonicalName("tob cm"));
        assertEquals("Theatre of Blood Hard Mode 2 players", BossNameRegistry.canonicalName("hmt duo"));
    }

    // --- Raids: Tombs of Amascut ---

    @Test
    public void tombsOfAmascutAliases() {
        assertEquals("Tombs of Amascut", BossNameRegistry.canonicalName("toa"));
        assertEquals("Tombs of Amascut", BossNameRegistry.canonicalName("raids 3"));
        assertEquals("Tombs of Amascut Solo", BossNameRegistry.canonicalName("toa solo"));
        assertEquals("Tombs of Amascut 8 players", BossNameRegistry.canonicalName("toa 8"));
        assertEquals("Tombs of Amascut Entry Mode", BossNameRegistry.canonicalName("toa entry mode"));
        assertEquals("Tombs of Amascut Entry Mode 2 players", BossNameRegistry.canonicalName("toa entry duo"));
        assertEquals("Tombs of Amascut Expert Mode",
                BossNameRegistry.canonicalName("tombs of amascut: expert mode"));
        assertEquals("Tombs of Amascut Expert Mode Solo", BossNameRegistry.canonicalName("toa expert 1"));
        assertEquals("Tombs of Amascut Expert Mode 8 players", BossNameRegistry.canonicalName("toa expert 8"));
    }

    // --- God Wars Dungeon ---

    @Test
    public void godWarsDungeonAliases() {
        assertEquals("Commander Zilyana", BossNameRegistry.canonicalName("zily"));
        assertEquals("Commander Zilyana", BossNameRegistry.canonicalName("saradomin"));
        assertEquals("K'ril Tsutsaroth", BossNameRegistry.canonicalName("zammy"));
        assertEquals("K'ril Tsutsaroth", BossNameRegistry.canonicalName("kril"));
        assertEquals("Kree'arra", BossNameRegistry.canonicalName("arma"));
        assertEquals("Kree'arra", BossNameRegistry.canonicalName("kreearra"));
        assertEquals("General Graardor", BossNameRegistry.canonicalName("bandos"));
        assertEquals("General Graardor", BossNameRegistry.canonicalName("graardor"));
    }

    // --- Wilderness bosses ---

    @Test
    public void wildernessBossAliases() {
        assertEquals("Vet'ion", BossNameRegistry.canonicalName("vetion"));
        assertEquals("Calvar'ion", BossNameRegistry.canonicalName("calv"));
        assertEquals("Venenatis", BossNameRegistry.canonicalName("vene"));
        assertEquals("Chaos Elemental", BossNameRegistry.canonicalName("chaos ele"));
        assertEquals("King Black Dragon", BossNameRegistry.canonicalName("kbd"));
        assertEquals("Crazy Archaeologist", BossNameRegistry.canonicalName("crazy arch"));
    }

    // --- Desert Treasure 2 bosses ---

    @Test
    public void desertTreasure2Aliases() {
        assertEquals("Leviathan", BossNameRegistry.canonicalName("levi"));
        assertEquals("Leviathan", BossNameRegistry.canonicalName("the leviathan"));
        assertEquals("Duke Sucellus", BossNameRegistry.canonicalName("duke"));
        assertEquals("Whisperer", BossNameRegistry.canonicalName("wisp"));
        assertEquals("Vardorvis", BossNameRegistry.canonicalName("vard"));
        assertEquals("Leviathan (awakened)", BossNameRegistry.canonicalName("levi awakened"));
        assertEquals("Duke Sucellus (awakened)", BossNameRegistry.canonicalName("duke awakened"));
        assertEquals("Whisperer (awakened)", BossNameRegistry.canonicalName("the whisperer awakened"));
        assertEquals("Vardorvis (awakened)", BossNameRegistry.canonicalName("vard awakened"));
    }

    // --- Gauntlet variants ---

    @Test
    public void gauntletAliases() {
        assertEquals("Gauntlet", BossNameRegistry.canonicalName("gaunt"));
        assertEquals("Gauntlet", BossNameRegistry.canonicalName("the gauntlet"));
        assertEquals("Corrupted Gauntlet", BossNameRegistry.canonicalName("cg"));
        assertEquals("Corrupted Gauntlet", BossNameRegistry.canonicalName("the corrupted gauntlet"));
    }

    // --- Miscellaneous ---

    @Test
    public void miscellaneousAliases() {
        assertEquals("Corporeal Beast", BossNameRegistry.canonicalName("corp"));
        assertEquals("TzTok-Jad", BossNameRegistry.canonicalName("jad"));
        assertEquals("TzKal-Zuk", BossNameRegistry.canonicalName("inferno"));
        assertEquals("TzHaar-Ket-Rak's Sixth Challenge", BossNameRegistry.canonicalName("jad 6"));
        assertEquals("Phosani's Nightmare", BossNameRegistry.canonicalName("pnm"));
        assertEquals("Hallowed Sepulchre Floor 5", BossNameRegistry.canonicalName("hs5"));
        assertEquals("Guardians of the Rift", BossNameRegistry.canonicalName("gotr"));
        assertEquals("Sol Heredit", BossNameRegistry.canonicalName("colosseum"));
        assertEquals("Larran's big chest", BossNameRegistry.canonicalName("larran's chest"));
        // mappings whose canonical value is intentionally lowercase must be preserved as-is
        assertEquals("crystal chest", BossNameRegistry.canonicalName("crystal chest"));
    }

    // --- Case-insensitivity of lookups ---

    @Test
    public void lookupIsCaseInsensitive() {
        assertEquals("Corporeal Beast", BossNameRegistry.canonicalName("CORP"));
        assertEquals("Theatre of Blood Hard Mode", BossNameRegistry.canonicalName("HMT"));
    }

    // --- Echo suffix behavior ---

    @Test
    public void echoSuffixRecursesOnBaseName() {
        assertEquals("Corporeal Beast (Echo)", BossNameRegistry.canonicalName("corp (echo)"));
        assertEquals("King Black Dragon (Echo)", BossNameRegistry.canonicalName("KBD (Echo)"));
        // unmapped base falls through unchanged (lowercased by the suffix strip)
        assertEquals("some new boss (Echo)", BossNameRegistry.canonicalName("Some New Boss (Echo)"));
    }

    @Test
    public void echoSuffixAppliesFallbackToStrippedBaseName() {
        assertEquals("SOME NEW BOSS (Echo)",
                BossNameRegistry.canonicalName("Some New Boss (echo)", String::toUpperCase));
        // mapped base ignores the fallback entirely
        assertEquals("Corporeal Beast (Echo)",
                BossNameRegistry.canonicalName("corp (echo)", String::toUpperCase));
    }

    // --- Unmapped passthrough ---

    @Test
    public void unmappedNamePassesThroughUnchanged() {
        assertEquals("Totally Unknown Boss", BossNameRegistry.canonicalName("Totally Unknown Boss"));
        assertEquals("scurrius", BossNameRegistry.canonicalName("scurrius"));
    }

    @Test
    public void unmappedNameUsesProvidedFallback() {
        assertEquals("TOTALLY UNKNOWN BOSS",
                BossNameRegistry.canonicalName("Totally Unknown Boss", String::toUpperCase));
        // mapped names never hit the fallback
        assertEquals("Corporeal Beast", BossNameRegistry.canonicalName("corp", String::toUpperCase));
    }
}
