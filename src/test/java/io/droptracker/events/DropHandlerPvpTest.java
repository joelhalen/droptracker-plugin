package io.droptracker.events;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The Wilderness level sent with a PvP kill (6.1.9). The game draws a level
 * line every eight tiles north of the ditch, and again in the dungeons under
 * the Wilderness, which sit 6400 tiles further north on the map.
 */
public class DropHandlerPvpTest {

    @Test
    public void theDitchIsLevelOne() {
        assertEquals(1, DropHandler.wildernessLevel(3520));
        assertEquals(1, DropHandler.wildernessLevel(3527));
        assertEquals(2, DropHandler.wildernessLevel(3528));
    }

    @Test
    public void theNorthernEdgeIsLevelFiftySix() {
        assertEquals(56, DropHandler.wildernessLevel(3967));
    }

    @Test
    public void dungeonsUseTheirOwnBaseline() {
        assertEquals(1, DropHandler.wildernessLevel(9920));
        assertEquals(28, DropHandler.wildernessLevel(10140));
    }

    @Test
    public void neverReportsBelowOne() {
        // Just south of the ditch while the varbit still reads "inside".
        assertEquals(1, DropHandler.wildernessLevel(3515));
    }
}
