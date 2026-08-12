package io.droptracker.events;

import org.junit.Test;

import static io.droptracker.events.AgilityPyramidHandler.ARM_WINDOW_TICKS;
import static io.droptracker.events.AgilityPyramidHandler.PYRAMID_REGION_ID;
import static io.droptracker.events.AgilityPyramidHandler.shouldEmit;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the pyramid-top emit predicate. A grab requires: a known baseline,
 * a count increase, an armed summit click inside the window, and the player
 * still inside the pyramid region.
 */
public class AgilityPyramidHandlerTest {

    private static final int ELSEWHERE_REGION = 12850; // Lumbridge

    @Test
    public void emitsOnArmedGrabInRegion() {
        assertTrue(shouldEmit(0, 1, 1, PYRAMID_REGION_ID));
        assertTrue(shouldEmit(2, 3, ARM_WINDOW_TICKS, PYRAMID_REGION_ID));
    }

    @Test
    public void unknownBaselineNeverEmits() {
        // First inventory sync after login/hop while holding a top
        assertFalse(shouldEmit(-1, 1, 1, PYRAMID_REGION_ID));
    }

    @Test
    public void decreaseNeverEmits() {
        // Turn-in to Simon Templeton
        assertFalse(shouldEmit(1, 0, 1, PYRAMID_REGION_ID));
    }

    @Test
    public void unarmedGainNeverEmits() {
        // No summit click recorded (Integer.MIN_VALUE sentinel -> huge delta)
        assertFalse(shouldEmit(0, 1, ARM_WINDOW_TICKS + 1, PYRAMID_REGION_ID));
        assertFalse(shouldEmit(0, 1, -5, PYRAMID_REGION_ID));
    }

    @Test
    public void wrongRegionNeverEmits() {
        // Bank withdrawal with a recent unrelated click
        assertFalse(shouldEmit(0, 1, 1, ELSEWHERE_REGION));
        assertFalse(shouldEmit(0, 1, 1, -1));
    }
}
