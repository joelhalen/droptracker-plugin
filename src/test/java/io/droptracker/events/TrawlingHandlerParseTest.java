package io.droptracker.events;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Tests the trawling catch-message parser and the trawling-species id map
 * exposed via {@code @VisibleForTesting}. The species map doubles as the
 * allowlist that keeps ordinary fishing "You catch a ..." messages from being
 * submitted as trawling loot.
 */
public class TrawlingHandlerParseTest {

    @Test
    public void parsesSingleCatch() {
        TrawlingHandler.ParsedCatch parsed = TrawlingHandler.parseCatch("You catch a raw halibut!");
        assertNotNull(parsed);
        assertEquals("raw halibut", parsed.getFishName());
        assertEquals(1, parsed.getQuantity());
    }

    @Test
    public void parsesMultiCatchWithCount() {
        TrawlingHandler.ParsedCatch parsed = TrawlingHandler.parseCatch("You catch 2 raw giant krill!");
        assertNotNull(parsed);
        assertEquals("raw giant krill", parsed.getFishName());
        assertEquals(2, parsed.getQuantity());
    }

    @Test
    public void parsesAnArticleAndPeriodEnding() {
        TrawlingHandler.ParsedCatch parsed = TrawlingHandler.parseCatch("You catch an orangefin.");
        assertNotNull(parsed);
        assertEquals("orangefin", parsed.getFishName());
        assertEquals(1, parsed.getQuantity());
    }

    @Test
    public void rejectsCrewmateCatches() {
        assertNull(TrawlingHandler.parseCatch("First mate Dave catches 3 raw haddock!"));
        assertNull(TrawlingHandler.parseCatch("Koeppy catches a raw marlin!"));
    }

    @Test
    public void rejectsTrawlersTrustBonusLines() {
        assertNull(TrawlingHandler.parseCatch("Your Trawler's trust grants you an extra catch: You catch a raw bluefin!"));
    }

    @Test
    public void rejectsNetCollectionMessages() {
        assertNull(TrawlingHandler.parseCatch("You empty the net into your cargo hold."));
        assertNull(TrawlingHandler.parseCatch("You take all of the fish from the net."));
        assertNull(TrawlingHandler.parseCatch("You take some fish from the net"));
    }

    @Test
    public void rejectsZeroQuantity() {
        assertNull(TrawlingHandler.parseCatch("You catch 0 raw haddock!"));
    }

    @Test
    public void resolvesAllTrawlingSpecies() {
        assertEquals(Integer.valueOf(32309), TrawlingHandler.resolveFishId("raw giant krill"));
        assertEquals(Integer.valueOf(32317), TrawlingHandler.resolveFishId("raw haddock"));
        assertEquals(Integer.valueOf(32325), TrawlingHandler.resolveFishId("raw yellowfin"));
        assertEquals(Integer.valueOf(32333), TrawlingHandler.resolveFishId("raw halibut"));
        assertEquals(Integer.valueOf(32341), TrawlingHandler.resolveFishId("raw bluefin"));
        assertEquals(Integer.valueOf(32349), TrawlingHandler.resolveFishId("raw marlin"));
    }

    @Test
    public void resolvesTrophyFish() {
        assertEquals(Integer.valueOf(31408), TrawlingHandler.resolveFishId("giant blue krill"));
        assertEquals(Integer.valueOf(31412), TrawlingHandler.resolveFishId("golden haddock"));
        assertEquals(Integer.valueOf(31416), TrawlingHandler.resolveFishId("orangefin"));
        assertEquals(Integer.valueOf(31420), TrawlingHandler.resolveFishId("huge halibut"));
        assertEquals(Integer.valueOf(31424), TrawlingHandler.resolveFishId("purplefin"));
        assertEquals(Integer.valueOf(31428), TrawlingHandler.resolveFishId("swift marlin"));
    }

    @Test
    public void resolvesPluralizedNames() {
        assertEquals(Integer.valueOf(32317), TrawlingHandler.resolveFishId("raw haddocks"));
        assertEquals(Integer.valueOf(32349), TrawlingHandler.resolveFishId("marlins"));
    }

    @Test
    public void ordinaryFishingSpeciesDoNotResolve() {
        assertNull(TrawlingHandler.resolveFishId("lobster"));
        assertNull(TrawlingHandler.resolveFishId("swordfish"));
        assertNull(TrawlingHandler.resolveFishId("shark"));
        assertNull(TrawlingHandler.resolveFishId("raw anglerfish"));
    }
}
