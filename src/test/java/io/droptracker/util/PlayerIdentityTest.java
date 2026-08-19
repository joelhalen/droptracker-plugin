package io.droptracker.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The plugin used to submit the literal name "Unknown" whenever the RSN was
 * unreadable. That string matches an empty Wise Old Man record, and adopting it
 * renamed whichever live account the submission resolved to — three weeks of one
 * player's drops, clogs, CAs and PBs were discarded in silence before it was
 * caught. These tests pin the replacement: resolve honestly, or return null so
 * the caller skips the submission.
 */
public class PlayerIdentityTest {

    private static final String HASH = "1234567890123456";

    @Test
    public void liveNameWins() {
        assertEquals("Beast_Owned", PlayerIdentity.resolve("Beast_Owned", HASH, "Someone Else", HASH));
    }

    @Test
    public void neverSubstitutesAPlaceholder() {
        assertNull(PlayerIdentity.resolve(null, HASH, null, null));
        assertNull(PlayerIdentity.resolve("", HASH, null, null));
        assertNull(PlayerIdentity.resolve("   ", HASH, "", HASH));
    }

    // The login window: the name has not arrived yet, but the account hash has,
    // and it matches the one the cached name was stored against.
    @Test
    public void fallsBackToTheCachedNameForTheSameAccount() {
        assertEquals("Wimi", PlayerIdentity.resolve(null, HASH, "Wimi", HASH));
        assertEquals("Wimi", PlayerIdentity.resolve("", HASH, "Wimi", " " + HASH + " "));
    }

    // The whole point of keying the fallback on the hash: after hopping to a
    // second account, the cached name belongs to the first one. Attributing a
    // drop to it would be the same class of bug as "Unknown".
    @Test
    public void refusesACachedNameFromADifferentAccount() {
        assertNull(PlayerIdentity.resolve(null, "9999999999999999", "Wimi", HASH));
    }

    // Logged out (-1) or no client at all (0): nothing to attribute an event to,
    // and both are strings the backend would try to match a player row against.
    @Test
    public void refusesACachedNameWithoutALoggedInAccount() {
        assertNull(PlayerIdentity.resolve(null, "-1", "Wimi", "-1"));
        assertNull(PlayerIdentity.resolve(null, "0", "Wimi", "0"));
        assertNull(PlayerIdentity.resolve(null, null, "Wimi", HASH));
        assertNull(PlayerIdentity.resolve(null, "", "Wimi", ""));
    }

    @Test
    public void usableAccountHashRejectsTheSentinels() {
        assertTrue(PlayerIdentity.isUsableAccountHash(HASH));
        assertFalse(PlayerIdentity.isUsableAccountHash("0"));
        assertFalse(PlayerIdentity.isUsableAccountHash("-1"));
        assertFalse(PlayerIdentity.isUsableAccountHash(" "));
        assertFalse(PlayerIdentity.isUsableAccountHash(null));
    }

    // "Unknown" is a legal RSN — a real player could be called that. The guard
    // keys on the name being absent, never on its spelling, so a player named
    // Unknown keeps submitting normally.
    @Test
    public void doesNotBlacklistTheNameItself() {
        assertEquals("Unknown", PlayerIdentity.resolve("Unknown", HASH, null, null));
        assertFalse(PlayerIdentity.isMissingName("Unknown"));
    }

    @Test
    public void missingNameCoversNullAndBlank() {
        assertTrue(PlayerIdentity.isMissingName(null));
        assertTrue(PlayerIdentity.isMissingName(""));
        assertTrue(PlayerIdentity.isMissingName("   "));
        assertFalse(PlayerIdentity.isMissingName("Wimi"));
    }
}
