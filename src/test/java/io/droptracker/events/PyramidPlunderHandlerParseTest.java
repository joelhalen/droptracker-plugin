package io.droptracker.events;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the Pharaoh's sceptre game-message matcher. Both the inventory and the
 * full-inventory ("It fell on the floor.") variants must count; the Guardian
 * mummy's overhead line seen by other players and unrelated sceptre messages
 * must not.
 */
public class PyramidPlunderHandlerParseTest {

    @Test
    public void matchesFloorVariant() {
        assertTrue(PyramidPlunderHandler.isSceptreMessage(
            "You have found a Pharaoh's sceptre! It fell on the floor."));
    }

    @Test
    public void matchesInventoryVariant() {
        assertTrue(PyramidPlunderHandler.isSceptreMessage("You have found a Pharaoh's sceptre!"));
    }

    @Test
    public void toleratesArticleCaseAndCurlyApostrophe() {
        assertTrue(PyramidPlunderHandler.isSceptreMessage("You have found the Pharaoh's Sceptre!"));
        assertTrue(PyramidPlunderHandler.isSceptreMessage("You have found a Pharaoh’s sceptre!"));
    }

    @Test
    public void ignoresOtherMessages() {
        assertFalse(PyramidPlunderHandler.isSceptreMessage(
            "Grrr! Someone has found the Pharaoh's sceptre!"));
        assertFalse(PyramidPlunderHandler.isSceptreMessage(
            "Your Pharaoh's sceptre has 3 charges left."));
        assertFalse(PyramidPlunderHandler.isSceptreMessage(
            "Koeppy: You have found a Pharaoh's sceptre!"));
    }
}
