package io.droptracker.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins which sources hold their screenshot back a game tick (issue #48).
 * Nightmare announces its loot the tick after the loot event, so capturing
 * the next frame immediately photographs the moment before the drop message
 * exists and the screenshot can't corroborate the drop.
 */
public class NpcUtilitiesDeferredScreenshotTest {

    @Test
    public void bothNightmareVariantsDeferTheirScreenshot() {
        assertTrue(NpcUtilities.needsDeferredScreenshot("Phosani's Nightmare"));
        assertTrue(NpcUtilities.needsDeferredScreenshot("The Nightmare"));
    }

    @Test
    public void ordinaryBossesCaptureImmediately() {
        // Deferring costs a tick of latency, so the set stays narrow.
        assertFalse(NpcUtilities.needsDeferredScreenshot("Zulrah"));
        assertFalse(NpcUtilities.needsDeferredScreenshot("Vorkath"));
        assertFalse(NpcUtilities.needsDeferredScreenshot("Theatre of Blood"));
        assertFalse(NpcUtilities.needsDeferredScreenshot("Grotesque Guardians"));
    }

    @Test
    public void unknownAndMissingSourcesCaptureImmediately() {
        // extractSourceName returns null when an embed carries no source
        // field; that must not blow up the capture path.
        assertFalse(NpcUtilities.needsDeferredScreenshot(null));
        assertFalse(NpcUtilities.needsDeferredScreenshot(""));
    }

    @Test
    public void matchesTheCanonicalNameTheDropEmbedActuallyCarries() {
        // DropHandler puts the canonicalised name in the embed's "source"
        // field, which is what the capture gate reads — so the set has to
        // hold post-canonicalisation spellings, not raw sub-NPC names.
        assertTrue(NpcUtilities.needsDeferredScreenshot(
            NpcUtilities.canonicalizeSpecialSource("The Nightmare")));
        assertTrue(NpcUtilities.needsDeferredScreenshot(
            NpcUtilities.canonicalizeSpecialSource("Phosani's Nightmare")));
    }
}
