package io.droptracker.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import io.droptracker.models.api.GroupConfig;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Clan chat sync is on by default, so the relay must stay silent unless one of
 * the player's own groups set up the clan they are in.
 */
public class ClanRelayOptInTest {

    private static GroupConfig group(boolean bridge, boolean tracking, String slug) {
        return new Gson().fromJson(String.format(
            "{\"group_id\":\"1\",\"clan_chat_bridge\":%s,\"clan_broadcast_tracking\":%s,"
                + "\"clan_chat_slug\":%s}",
            bridge, tracking, slug == null ? "null" : "\"" + slug + "\""), GroupConfig.class);
    }

    @Test
    public void slugMatchesTheServerRule() {
        assertEquals("the best clan", ClanRelayService.clanSlug("The_Best-Clan"));
        assertEquals("the best clan", ClanRelayService.clanSlug("<col=ff0000>The Best  Clan</col> "));
        assertEquals("", ClanRelayService.clanSlug(null));
    }

    @Test
    public void bridgedClanRelaysChatAndBroadcasts() {
        List<GroupConfig> groups = Collections.singletonList(group(true, false, "the best clan"));
        assertTrue(ClanRelayService.clanOptedIn("The_Best Clan", groups, true));
        assertTrue(ClanRelayService.clanOptedIn("The_Best Clan", groups, false));
    }

    @Test
    public void trackingOnlyClanRelaysBroadcastsButNotChat() {
        List<GroupConfig> groups = Collections.singletonList(group(false, true, "the best clan"));
        assertFalse(ClanRelayService.clanOptedIn("The Best Clan", groups, true));
        assertTrue(ClanRelayService.clanOptedIn("The Best Clan", groups, false));
    }

    @Test
    public void nothingIsRelayedOutsideAnOptedInClan() {
        List<GroupConfig> groups = Arrays.asList(
            group(true, true, "another clan"), group(false, false, null));
        // In a different clan than the group bridges.
        assertFalse(ClanRelayService.clanOptedIn("The Best Clan", groups, false));
        // Not in a clan at all.
        assertFalse(ClanRelayService.clanOptedIn(null, groups, false));
        // In no group, or group configs not loaded yet.
        assertFalse(ClanRelayService.clanOptedIn("Another Clan", Collections.emptyList(), false));
        assertFalse(ClanRelayService.clanOptedIn("Another Clan", null, false));
    }

    @Test
    public void olderServerWithoutTheFieldsMeansNoRelay() {
        GroupConfig legacy = new Gson().fromJson("{\"group_id\":\"1\"}", GroupConfig.class);
        assertFalse(ClanRelayService.clanOptedIn("Any Clan",
            Collections.singletonList(legacy), false));
    }
}
