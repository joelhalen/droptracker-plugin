package io.droptracker.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The relay reads the sender's game-mode badge off the raw chat name so the
 * Discord bridge can draw it before the name (server: utils/account_types.py
 * wire strings).
 */
public class ClanRelayAccountTypeTest {

    @Test
    public void everyGameModeBadgeMapsToItsWireString() {
        assertEquals("ironman", ClanRelayService.accountTypeFromName("<img=2>Iron Al"));
        assertEquals("ultimate_ironman", ClanRelayService.accountTypeFromName("<img=3>Uim"));
        assertEquals("hardcore_ironman", ClanRelayService.accountTypeFromName("<img=10>Hc"));
        assertEquals("group_ironman", ClanRelayService.accountTypeFromName("<img=41>Gim"));
        assertEquals("hardcore_group_ironman", ClanRelayService.accountTypeFromName("<img=42>Hcgim"));
        assertEquals("unranked_group_ironman", ClanRelayService.accountTypeFromName("<img=43>Ugim"));
    }

    @Test
    public void noBadgeOrANonModeIconReadsAsNormal() {
        assertEquals("normal", ClanRelayService.accountTypeFromName("Main Mo"));
        assertEquals("normal", ClanRelayService.accountTypeFromName("<img=0>Pmod"));
    }

    @Test
    public void modeBadgeIsFoundBehindAnotherIcon() {
        assertEquals("ironman", ClanRelayService.accountTypeFromName("<img=22><img=2>League Iron"));
    }

    @Test
    public void missingNameIsNull() {
        assertNull(ClanRelayService.accountTypeFromName(null));
    }
}
