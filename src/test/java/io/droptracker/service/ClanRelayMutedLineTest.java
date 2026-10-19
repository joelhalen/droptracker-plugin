package io.droptracker.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * A muted clanmate's line comes back from the server and must match the copy
 * the game delivers (if it did), and our own render when it echoes through
 * the chat subscriber, or it is shown twice or relayed onward.
 */
public class ClanRelayMutedLineTest {

    @Test
    public void renderedNameMatchesTheGameCopy() {
        assertEquals(
            ClanRelayService.lineKey("Bob Smith", "hi there"),
            ClanRelayService.lineKey("<img=2><img=57>Bob Smith", "Hi  there "));
    }

    @Test
    public void differentSpeakersOrTextDoNotMatch() {
        assertNotEquals(
            ClanRelayService.lineKey("Bob", "gz"),
            ClanRelayService.lineKey("Rob", "gz"));
        assertNotEquals(
            ClanRelayService.lineKey("Bob", "gz"),
            ClanRelayService.lineKey("Bob", "gg"));
    }
}
