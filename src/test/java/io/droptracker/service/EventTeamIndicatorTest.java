package io.droptracker.service;

import com.google.gson.Gson;
import io.droptracker.models.TeamIndicatorStyle;
import io.droptracker.models.api.EventRoster;
import io.droptracker.models.api.EventState;
import org.junit.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Clan-chat team badges (suggestion #150): the wire format of GET
 * /event_roster and the pure rendering/matching logic behind the decoration.
 *
 * The parts that need a live {@code Client} — sprite slots and the chat
 * buffer walk — are not covered here; everything that decides *what* a line
 * says is.
 */
public class EventTeamIndicatorTest {

    private final Gson gson = new Gson();

    // ── wire format ──────────────────────────────────────────────────────────

    @Test
    public void parsesRosterResponse() {
        String json = "{\"events\":[{"
            + "\"event_id\":46,\"roster_version\":\"3f1c9a0b12345678\","
            + "\"teams\":[{\"id\":101,\"name\":\"Red Rockets\",\"short_tag\":\"RR\","
            + "\"color\":\"#cc3333\",\"orb\":\"🔴\",\"orb_color\":\"#dd2e44\","
            + "\"icon_item_id\":11802,\"icon_path\":null},"
            + "{\"id\":102,\"name\":\"Blue Blazers\",\"short_tag\":\"BB\","
            + "\"color\":\"#3355cc\",\"orb\":\"🔵\",\"orb_color\":\"#55acee\","
            + "\"icon_item_id\":null,\"icon_path\":null}],"
            + "\"members\":{\"101\":[\"beast owned\",\"zezima\"],\"102\":[\"woox\"]},"
            + "\"members_total\":3,\"truncated\":false}]}";

        EventRoster roster = gson.fromJson(json, EventRoster.class);
        assertNotNull(roster.getEvents());
        assertEquals(1, roster.getEvents().size());

        EventRoster.Entry entry = roster.getEvents().get(0);
        assertEquals(46, entry.getEventId());
        assertEquals("3f1c9a0b12345678", entry.getRosterVersion());
        assertEquals(3, entry.getMembersTotal());
        assertEquals(false, entry.isTruncated());

        EventRoster.Team red = entry.getTeams().get(0);
        assertEquals(101, red.getId());
        assertEquals("RR", red.getShortTag());
        assertEquals("#dd2e44", red.getOrbColor());
        assertEquals(Integer.valueOf(11802), red.getIconItemId());

        List<String> redMembers = entry.getMembers().get("101");
        assertEquals(2, redMembers.size());
        assertTrue(redMembers.contains("beast owned"));
    }

    @Test
    public void toleratesAnEmptyRoster() {
        EventRoster roster = gson.fromJson("{\"events\":[]}", EventRoster.class);
        assertNotNull(roster.getEvents());
        assertTrue(roster.getEvents().isEmpty());
    }

    @Test
    public void rosterVersionIsOptionalOnEventState() {
        // Older servers do not send the stamp; the client must parse the rest
        // of the entry rather than lose the HUD over a missing field.
        String json = "{\"events\":[{\"event\":{\"id\":46,\"name\":\"Bingo\",\"kind\":\"bingo\"},"
            + "\"team\":{\"id\":101,\"name\":\"Red Rockets\",\"score\":5,\"team_count\":2}}]}";
        EventState state = gson.fromJson(json, EventState.class);
        assertNull(state.getEvents().get(0).getRosterVersion());
    }

    @Test
    public void readsRosterVersionWhenPresent() {
        String json = "{\"events\":[{\"event\":{\"id\":46,\"name\":\"Bingo\",\"kind\":\"bingo\"},"
            + "\"team\":{\"id\":101,\"name\":\"Red Rockets\",\"score\":5,\"team_count\":2},"
            + "\"roster_version\":\"abc123\"}]}";
        EventState state = gson.fromJson(json, EventState.class);
        assertEquals("abc123", state.getEvents().get(0).getRosterVersion());
    }

    // ── name matching ────────────────────────────────────────────────────────

    @Test
    public void nameKeyFoldsEverySpellingOfTheSameRsn() {
        // The plugin submits "Beast_Owned", the database stores "Beast Owned",
        // and the game renders the space as U+00A0. All three are one player.
        String expected = "beast owned";
        assertEquals(expected, EventTeamIndicatorService.normalize("Beast Owned"));
        assertEquals(expected, EventTeamIndicatorService.normalize("Beast_Owned"));
        assertEquals(expected, EventTeamIndicatorService.normalize("Beast-Owned"));
        assertEquals(expected, EventTeamIndicatorService.normalize("Beast Owned"));
        assertEquals(expected, EventTeamIndicatorService.normalize("BEAST OWNED"));
    }

    @Test
    public void nameKeyIgnoresIconsAlreadyOnTheName() {
        // Clan rank icons and our own badge are both baked into the name field.
        assertEquals("zezima", EventTeamIndicatorService.normalize("<img=25>Zezima"));
        assertEquals("zezima",
            EventTeamIndicatorService.normalize("<img=42><col=cc3333>[RR]</col>Zezima"));
    }

    @Test
    public void nameKeyCollapsesWhitespaceLikeTheServerDoes() {
        // normalize_player_display_equivalence collapses runs; two normalizers
        // that disagree by one space badge nobody and look right on both sides.
        assertEquals("beast owned", EventTeamIndicatorService.normalize("Beast__Owned"));
        assertEquals("beast owned", EventTeamIndicatorService.normalize("Beast -_ Owned"));
    }

    @Test
    public void nameKeyHandlesNullAndBlank() {
        assertEquals("", EventTeamIndicatorService.normalize(null));
        assertEquals("", EventTeamIndicatorService.normalize("   "));
    }

    // ── colors ───────────────────────────────────────────────────────────────

    @Test
    public void parsesHexColors() {
        assertEquals(new Color(0xdd2e44), EventTeamIndicatorService.colorOf("#dd2e44"));
        assertEquals(new Color(0xdd2e44), EventTeamIndicatorService.colorOf("DD2E44"));
    }

    @Test
    public void unusableColorsAreNull() {
        assertNull(EventTeamIndicatorService.colorOf(null));
        assertNull(EventTeamIndicatorService.colorOf(""));
        assertNull(EventTeamIndicatorService.colorOf("#fff"));
        assertNull(EventTeamIndicatorService.colorOf("rebeccapurple"));
    }

    // ── rendering ────────────────────────────────────────────────────────────

    private EventTeamIndicatorService.TeamBadge badge() {
        return new EventTeamIndicatorService.TeamBadge(
            101, "RR", new Color(0xdd2e44), new Color(0xcc3333));
    }

    @Test
    public void tagStyleWrapsTheTagInTheTeamColor() {
        String out = badge().render(TeamIndicatorStyle.TAG, "Zezima", -1, false);
        assertEquals("<col=cc3333>[RR]</col>Zezima", out);
    }

    @Test
    public void orbStyleUsesTheSpriteSlot() {
        String out = badge().render(TeamIndicatorStyle.ORB, "Zezima", 42, false);
        assertEquals("<img=42>Zezima", out);
    }

    @Test
    public void orbAndTagShowsBoth() {
        String out = badge().render(TeamIndicatorStyle.ORB_AND_TAG, "Zezima", 42, false);
        assertEquals("<img=42><col=cc3333>[RR]</col>Zezima", out);
    }

    @Test
    public void orbDegradesToTheTagWithoutASlot() {
        // The default style must not depend on winning the mod-icon race with
        // whatever else the user runs.
        String out = badge().render(TeamIndicatorStyle.ORB, "Zezima", -1, false);
        assertEquals("<col=cc3333>[RR]</col>Zezima", out);
    }

    @Test
    public void colorsTheNameWhenAsked() {
        String out = badge().render(TeamIndicatorStyle.ORB, "Zezima", 42, true);
        assertEquals("<img=42><col=cc3333>Zezima</col>", out);
    }

    @Test
    public void colorSpansAreSiblingsNotNested() {
        // </col> resets to the chat default rather than to an enclosing tag, so
        // a nested span would leave the rest of the line the wrong color.
        String out = badge().render(TeamIndicatorStyle.ORB_AND_TAG, "Zezima", 42, true);
        assertEquals("<img=42><col=cc3333>[RR]</col><col=cc3333>Zezima</col>", out);
    }

    @Test
    public void theGamesOwnRankIconSurvives() {
        // The badge is prefixed onto the name, never rebuilt from a stripped
        // one — the client bakes the clan rank icon into the same field.
        String out = badge().render(TeamIndicatorStyle.ORB, "<img=25>Zezima", 42, false);
        assertEquals("<img=42><img=25>Zezima", out);
    }

    @Test
    public void fallsBackToTheOrbColorWhenNoAccentIsSet() {
        EventTeamIndicatorService.TeamBadge noAccent =
            new EventTeamIndicatorService.TeamBadge(101, "RR", new Color(0x55acee), null);
        assertEquals("<col=55acee>Zezima</col>",
            noAccent.render(TeamIndicatorStyle.ORB, "Zezima", 42, true).substring(8));
    }

    @Test
    public void aTeamWithNoTagAndNoSlotIsNotDecorated() {
        // Nothing would mark the line as ours, so the idempotency check could
        // not recognise it later and the badge would stack on every refresh.
        EventTeamIndicatorService.TeamBadge tagless =
            new EventTeamIndicatorService.TeamBadge(101, null, new Color(0xdd2e44), null);
        assertNull(tagless.render(TeamIndicatorStyle.ORB, "Zezima", -1, true));
    }

    @Test
    public void offIsNeverRenderedAsABadge() {
        assertNull(badge().render(TeamIndicatorStyle.OFF, "Zezima", 42, false));
    }

    // ── style semantics ──────────────────────────────────────────────────────

    @Test
    public void styleFlagsMatchTheirNames() {
        assertTrue(TeamIndicatorStyle.TAG.showsTag());
        assertTrue(!TeamIndicatorStyle.TAG.showsOrb());
        assertTrue(TeamIndicatorStyle.ORB.showsOrb());
        assertTrue(!TeamIndicatorStyle.ORB.showsTag());
        assertTrue(TeamIndicatorStyle.ORB_AND_TAG.showsOrb());
        assertTrue(TeamIndicatorStyle.ORB_AND_TAG.showsTag());
        assertTrue(!TeamIndicatorStyle.OFF.showsOrb());
        assertTrue(!TeamIndicatorStyle.OFF.showsTag());
    }
}
