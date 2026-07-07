package io.droptracker.util;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BossNameRegistryTest {

    // Tests are not part of the shipped plugin jar, so constructing a plain
    // Gson here does not trip the plugin-hub instantiation check.
    private static final BossNameRegistry REGISTRY = new BossNameRegistry(new Gson());

    /**
     * The boss_names.json data file was extracted from the 366 case labels of
     * the former switch statement in WidgetEventHandler.longBossName. If a
     * mapping is added or removed, this count must be updated deliberately.
     */
    @Test
    public void registryLoadsExactMappingCount() {
        assertEquals(366, REGISTRY.size());
    }

    // --- Raids: Chambers of Xeric ---

    @Test
    public void chambersOfXericAliases() {
        assertEquals("Chambers of Xeric", REGISTRY.canonicalName("cox"));
        assertEquals("Chambers of Xeric", REGISTRY.canonicalName("olm"));
        assertEquals("Chambers of Xeric", REGISTRY.canonicalName("raids"));
        assertEquals("Chambers of Xeric Solo", REGISTRY.canonicalName("cox solo"));
        assertEquals("Chambers of Xeric 2 players", REGISTRY.canonicalName("cox duo"));
        assertEquals("Chambers of Xeric 11-15 players", REGISTRY.canonicalName("cox 13"));
        assertEquals("Chambers of Xeric 16-23 players", REGISTRY.canonicalName("cox 16-23"));
        assertEquals("Chambers of Xeric 24+ players", REGISTRY.canonicalName("cox 24+"));
    }

    @Test
    public void chambersOfXericChallengeModeAliases() {
        assertEquals("Chambers of Xeric Challenge Mode", REGISTRY.canonicalName("cox cm"));
        assertEquals("Chambers of Xeric Challenge Mode",
                REGISTRY.canonicalName("chambers of xeric: challenge mode"));
        assertEquals("Chambers of Xeric Challenge Mode Solo", REGISTRY.canonicalName("cox cm solo"));
        assertEquals("Chambers of Xeric Challenge Mode 11-15 players", REGISTRY.canonicalName("cox cm 12"));
        assertEquals("Chambers of Xeric Challenge Mode 24+ players", REGISTRY.canonicalName("cox cm 24+"));
    }

    // --- Raids: Theatre of Blood ---

    @Test
    public void theatreOfBloodAliases() {
        assertEquals("Theatre of Blood", REGISTRY.canonicalName("tob"));
        assertEquals("Theatre of Blood", REGISTRY.canonicalName("verzik vitur"));
        assertEquals("Theatre of Blood Solo", REGISTRY.canonicalName("tob solo"));
        assertEquals("Theatre of Blood 5 players", REGISTRY.canonicalName("tob 5"));
        assertEquals("Theatre of Blood Entry Mode", REGISTRY.canonicalName("tob em"));
        assertEquals("Theatre of Blood Entry Mode",
                REGISTRY.canonicalName("theatre of blood: story mode"));
        assertEquals("Theatre of Blood Hard Mode", REGISTRY.canonicalName("hmt"));
        assertEquals("Theatre of Blood Hard Mode", REGISTRY.canonicalName("tob cm"));
        assertEquals("Theatre of Blood Hard Mode 2 players", REGISTRY.canonicalName("hmt duo"));
    }

    // --- Raids: Tombs of Amascut ---

    @Test
    public void tombsOfAmascutAliases() {
        assertEquals("Tombs of Amascut", REGISTRY.canonicalName("toa"));
        assertEquals("Tombs of Amascut", REGISTRY.canonicalName("raids 3"));
        assertEquals("Tombs of Amascut Solo", REGISTRY.canonicalName("toa solo"));
        assertEquals("Tombs of Amascut 8 players", REGISTRY.canonicalName("toa 8"));
        assertEquals("Tombs of Amascut Entry Mode", REGISTRY.canonicalName("toa entry mode"));
        assertEquals("Tombs of Amascut Entry Mode 2 players", REGISTRY.canonicalName("toa entry duo"));
        assertEquals("Tombs of Amascut Expert Mode",
                REGISTRY.canonicalName("tombs of amascut: expert mode"));
        assertEquals("Tombs of Amascut Expert Mode Solo", REGISTRY.canonicalName("toa expert 1"));
        assertEquals("Tombs of Amascut Expert Mode 8 players", REGISTRY.canonicalName("toa expert 8"));
    }

    // --- God Wars Dungeon ---

    @Test
    public void godWarsDungeonAliases() {
        assertEquals("Commander Zilyana", REGISTRY.canonicalName("zily"));
        assertEquals("Commander Zilyana", REGISTRY.canonicalName("saradomin"));
        assertEquals("K'ril Tsutsaroth", REGISTRY.canonicalName("zammy"));
        assertEquals("K'ril Tsutsaroth", REGISTRY.canonicalName("kril"));
        assertEquals("Kree'arra", REGISTRY.canonicalName("arma"));
        assertEquals("Kree'arra", REGISTRY.canonicalName("kreearra"));
        assertEquals("General Graardor", REGISTRY.canonicalName("bandos"));
        assertEquals("General Graardor", REGISTRY.canonicalName("graardor"));
    }

    // --- Wilderness bosses ---

    @Test
    public void wildernessBossAliases() {
        assertEquals("Vet'ion", REGISTRY.canonicalName("vetion"));
        assertEquals("Calvar'ion", REGISTRY.canonicalName("calv"));
        assertEquals("Venenatis", REGISTRY.canonicalName("vene"));
        assertEquals("Chaos Elemental", REGISTRY.canonicalName("chaos ele"));
        assertEquals("King Black Dragon", REGISTRY.canonicalName("kbd"));
        assertEquals("Crazy Archaeologist", REGISTRY.canonicalName("crazy arch"));
    }

    // --- Desert Treasure 2 bosses ---

    @Test
    public void desertTreasure2Aliases() {
        assertEquals("Leviathan", REGISTRY.canonicalName("levi"));
        assertEquals("Leviathan", REGISTRY.canonicalName("the leviathan"));
        assertEquals("Duke Sucellus", REGISTRY.canonicalName("duke"));
        assertEquals("Whisperer", REGISTRY.canonicalName("wisp"));
        assertEquals("Vardorvis", REGISTRY.canonicalName("vard"));
        assertEquals("Leviathan (awakened)", REGISTRY.canonicalName("levi awakened"));
        assertEquals("Duke Sucellus (awakened)", REGISTRY.canonicalName("duke awakened"));
        assertEquals("Whisperer (awakened)", REGISTRY.canonicalName("the whisperer awakened"));
        assertEquals("Vardorvis (awakened)", REGISTRY.canonicalName("vard awakened"));
    }

    // --- Gauntlet variants ---

    @Test
    public void gauntletAliases() {
        assertEquals("Gauntlet", REGISTRY.canonicalName("gaunt"));
        assertEquals("Gauntlet", REGISTRY.canonicalName("the gauntlet"));
        assertEquals("Corrupted Gauntlet", REGISTRY.canonicalName("cg"));
        assertEquals("Corrupted Gauntlet", REGISTRY.canonicalName("the corrupted gauntlet"));
    }

    // --- Miscellaneous ---

    @Test
    public void miscellaneousAliases() {
        assertEquals("Corporeal Beast", REGISTRY.canonicalName("corp"));
        assertEquals("TzTok-Jad", REGISTRY.canonicalName("jad"));
        assertEquals("TzKal-Zuk", REGISTRY.canonicalName("inferno"));
        assertEquals("TzHaar-Ket-Rak's Sixth Challenge", REGISTRY.canonicalName("jad 6"));
        assertEquals("Phosani's Nightmare", REGISTRY.canonicalName("pnm"));
        assertEquals("Hallowed Sepulchre Floor 5", REGISTRY.canonicalName("hs5"));
        assertEquals("Guardians of the Rift", REGISTRY.canonicalName("gotr"));
        assertEquals("Sol Heredit", REGISTRY.canonicalName("colosseum"));
        assertEquals("Larran's big chest", REGISTRY.canonicalName("larran's chest"));
        // mappings whose canonical value is intentionally lowercase must be preserved as-is
        assertEquals("crystal chest", REGISTRY.canonicalName("crystal chest"));
    }

    // --- Case-insensitivity of lookups ---

    @Test
    public void lookupIsCaseInsensitive() {
        assertEquals("Corporeal Beast", REGISTRY.canonicalName("CORP"));
        assertEquals("Theatre of Blood Hard Mode", REGISTRY.canonicalName("HMT"));
    }

    // --- Echo suffix behavior ---

    @Test
    public void echoSuffixRecursesOnBaseName() {
        assertEquals("Corporeal Beast (Echo)", REGISTRY.canonicalName("corp (echo)"));
        assertEquals("King Black Dragon (Echo)", REGISTRY.canonicalName("KBD (Echo)"));
        // unmapped base falls through unchanged (lowercased by the suffix strip)
        assertEquals("some new boss (Echo)", REGISTRY.canonicalName("Some New Boss (Echo)"));
    }

    @Test
    public void echoSuffixAppliesFallbackToStrippedBaseName() {
        assertEquals("SOME NEW BOSS (Echo)",
                REGISTRY.canonicalName("Some New Boss (echo)", String::toUpperCase));
        // mapped base ignores the fallback entirely
        assertEquals("Corporeal Beast (Echo)",
                REGISTRY.canonicalName("corp (echo)", String::toUpperCase));
    }

    // --- Unmapped passthrough ---

    @Test
    public void unmappedNamePassesThroughUnchanged() {
        assertEquals("Totally Unknown Boss", REGISTRY.canonicalName("Totally Unknown Boss"));
        assertEquals("scurrius", REGISTRY.canonicalName("scurrius"));
    }

    @Test
    public void unmappedNameUsesProvidedFallback() {
        assertEquals("TOTALLY UNKNOWN BOSS",
                REGISTRY.canonicalName("Totally Unknown Boss", String::toUpperCase));
        // mapped names never hit the fallback
        assertEquals("Corporeal Beast", REGISTRY.canonicalName("corp", String::toUpperCase));
    }
}
