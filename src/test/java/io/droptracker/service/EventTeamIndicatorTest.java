package io.droptracker.service;

import com.google.gson.Gson;
import io.droptracker.DropTrackerConfig;
import io.droptracker.models.TeamIndicatorStyle;
import io.droptracker.models.api.EventRoster;
import io.droptracker.models.api.EventState;
import io.droptracker.testing.RuneLiteStubs;
import io.droptracker.util.ChatMessageUtil;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.IterableHashTable;
import net.runelite.api.MessageNode;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.client.util.Text;
import org.junit.Before;
import org.junit.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.droptracker.testing.RuneLiteStubs.state;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Clan-chat team badges (suggestion #150): the wire format of GET
 * /event_roster, the name matching, the badge sprites, and the chat builder
 * callback that puts a badge on a line as it is drawn.
 *
 * The callback tests drive {@code chatMessageBuilding} against a stubbed
 * client. They pin the two rules whose absence broke private messages: only
 * clan, friends-chat and opted-in public lines are badged, and the
 * {@link MessageNode} itself is never renamed.
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
    public void nameKeyIgnoresIconsAndColoursOnTheName() {
        // Icons and colours another plugin put on a name are not part of it.
        assertEquals("zezima", EventTeamIndicatorService.normalize("<img=25>Zezima"));
        assertEquals("zezima",
            EventTeamIndicatorService.normalize("<img=42><col=cc3333>Zezima</col>"));
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

    // ── rendering the name ───────────────────────────────────────────────────

    private static final int SLOT = 42;

    private EventTeamIndicatorService.TeamBadge badge() {
        return new EventTeamIndicatorService.TeamBadge(
            101, "RR", new Color(0xdd2e44), new Color(0xcc3333));
    }

    @Test
    public void aSlotPrefixesTheName() {
        assertEquals("<img=42>Zezima", badge().render("Zezima", SLOT, false));
    }

    @Test
    public void colorsTheNameWhenAsked() {
        assertEquals("<img=42><col=cc3333>Zezima</col>", badge().render("Zezima", SLOT, true));
    }

    @Test
    public void theGameCanAlwaysStripTheBadgeBackOff() {
        // The chat scripts removetags() the drawn name to find the clan rank
        // icon and to target Report, Kick, Ban, Add friend and Message. The
        // tag used to be typed in as "[RR]", which survives that, so every one
        // of those looked up a player called "[RR]Zezima".
        for (int slot : new int[] {SLOT, -1}) {
            for (boolean colorName : new boolean[] {true, false}) {
                String drawn = badge().render("Zezima", slot, colorName);
                if (drawn != null) {
                    assertEquals("Zezima", Text.removeTags(drawn));
                }
            }
        }
    }

    @Test
    public void withoutASlotTheNameIsStillColoured() {
        assertEquals("<col=cc3333>Zezima</col>", badge().render("Zezima", -1, true));
    }

    @Test
    public void withoutASlotOrAColourTheNameIsLeftAlone() {
        assertNull(badge().render("Zezima", -1, false));
    }

    @Test
    public void anIconAlreadyOnTheNameSurvives() {
        // e.g. RuneLite's friends-chat rank icon, added by a subscriber that
        // ran before this one.
        assertEquals("<img=42><img=25>Zezima", badge().render("<img=25>Zezima", SLOT, false));
    }

    @Test
    public void aModeratorCrownStaysFirst() {
        // The chat builder offers "Crown Info" only when the name starts with
        // <img=0> or <img=1> (script 2759), so the badge goes after a crown.
        assertEquals("<img=0><img=42><col=cc3333>Zezima</col>",
            badge().render("<img=0>Zezima", SLOT, true));
        assertEquals("<img=1><img=42>Zezima", badge().render("<img=1>Zezima", SLOT, false));
        assertEquals("<img=0><col=cc3333>Zezima</col>", badge().render("<img=0>Zezima", -1, true));
        // Any other icon is not a crown, and the badge leads as usual.
        assertEquals("<img=42><img=10>Zezima", badge().render("<img=10>Zezima", SLOT, false));
    }

    @Test
    public void theTeamColourReplacesOneAlreadyOnTheName() {
        // RuneLite's chat colour config may have wrapped the name first. A
        // nested span would leave its colour showing instead of the team's.
        assertEquals("<img=42><col=cc3333>Zezima</col>",
            badge().render("<col=ff9040>Zezima</col>", SLOT, true));
    }

    @Test
    public void fallsBackToTheOrbColorWhenNoAccentIsSet() {
        EventTeamIndicatorService.TeamBadge noAccent =
            new EventTeamIndicatorService.TeamBadge(101, "RR", new Color(0x55acee), null);
        assertEquals("<img=42><col=55acee>Zezima</col>", noAccent.render("Zezima", SLOT, true));
    }

    // ── sprites ──────────────────────────────────────────────────────────────

    /** The chat font as RuneLite ships it, loaded without FontManager's side effects. */
    private static Font chatFont() throws Exception {
        try (InputStream in = EventTeamIndicatorService.class.getClassLoader()
            .getResourceAsStream("net/runelite/client/ui/runescape.ttf")) {
            assertNotNull("runescape.ttf missing from the client jar", in);
            return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(Font.PLAIN, 16f);
        }
    }

    private static int rgb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFFFFFF;
    }

    private static boolean inked(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >>> 24) != 0;
    }

    private static boolean rowInked(BufferedImage image, int y) {
        for (int x = 0; x < image.getWidth(); x++) {
            if (inked(image, x, y)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void orbStyleDrawsTheOrbAlone() throws Exception {
        EventTeamIndicatorService.BadgeArt art =
            EventTeamIndicatorService.drawBadge(badge(), TeamIndicatorStyle.ORB, chatFont());
        assertNotNull(art);
        assertEquals(12, art.image.getWidth());
        assertEquals(12, art.image.getHeight());
        assertEquals("the whole icon sits above the baseline", 12, art.ascent);
        assertEquals(0xdd2e44, rgb(art.image, 6, 6));
        assertTrue(!inked(art.image, 0, 0));
    }

    @Test
    public void theTagIsDrawnInTheTeamColour() throws Exception {
        EventTeamIndicatorService.BadgeArt art =
            EventTeamIndicatorService.drawBadge(badge(), TeamIndicatorStyle.TAG, chatFont());
        assertNotNull(art);
        boolean anyInk = false;
        for (int y = 0; y < art.image.getHeight(); y++) {
            for (int x = 0; x < art.image.getWidth(); x++) {
                if (inked(art.image, x, y)) {
                    anyInk = true;
                    assertEquals("sprites hold one flat colour, no antialiasing",
                        0xcc3333, rgb(art.image, x, y));
                }
            }
        }
        assertTrue(anyInk);
        assertTrue("cropped to the ink", rowInked(art.image, 0));
        assertTrue(rowInked(art.image, art.image.getHeight() - 1));
    }

    @Test
    public void theTagStandsOnTheTextBaseline() throws Exception {
        Font font = chatFont();
        // A capital sits exactly on the baseline, so its drawing has nothing
        // hanging below it...
        EventTeamIndicatorService.BadgeArt capital =
            EventTeamIndicatorService.drawText("RR", new Color(0xcc3333), font);
        assertNotNull(capital);
        assertEquals(capital.image.getHeight(), capital.ascent);
        // ...while a bracket's tail hangs below the baseline, the way the game
        // draws it beside the name, instead of lifting the whole tag. In this
        // font a bracket starts at cap height, so the tag stands exactly where
        // a bare capital does, plus the one row of tail.
        EventTeamIndicatorService.BadgeArt tag =
            EventTeamIndicatorService.drawText("[RR]", new Color(0xcc3333), font);
        assertNotNull(tag);
        assertEquals("brackets and capitals share a baseline", capital.ascent, tag.ascent);
        assertEquals("only the bracket tails hang below it", tag.ascent + 1, tag.image.getHeight());
    }

    @Test
    public void orbAndTagShareOneBaseline() throws Exception {
        Font font = chatFont();
        EventTeamIndicatorService.BadgeArt orb =
            EventTeamIndicatorService.drawBadge(badge(), TeamIndicatorStyle.ORB, font);
        EventTeamIndicatorService.BadgeArt tag =
            EventTeamIndicatorService.drawBadge(badge(), TeamIndicatorStyle.TAG, font);
        EventTeamIndicatorService.BadgeArt both =
            EventTeamIndicatorService.drawBadge(badge(), TeamIndicatorStyle.ORB_AND_TAG, font);
        assertNotNull(orb);
        assertNotNull(tag);
        assertNotNull(both);
        assertEquals(orb.image.getWidth() + 2 + tag.image.getWidth(), both.image.getWidth());
        assertEquals(Math.max(orb.ascent, tag.ascent), both.ascent);
        // The orb sits exactly where it does on its own...
        for (int y = 0; y < orb.image.getHeight(); y++) {
            for (int x = 0; x < orb.image.getWidth(); x++) {
                int shift = both.ascent - orb.ascent;
                assertEquals(orb.image.getRGB(x, y), both.image.getRGB(x, y + shift));
            }
        }
        // ...and the tag's baseline row lands on the orb's bottom row.
        int tagLeft = orb.image.getWidth() + 2;
        int tagTop = both.ascent - tag.ascent;
        for (int y = 0; y < tag.image.getHeight(); y++) {
            for (int x = 0; x < tag.image.getWidth(); x++) {
                assertEquals(tag.image.getRGB(x, y), both.image.getRGB(tagLeft + x, tagTop + y));
            }
        }
    }

    @Test
    public void orbDegradesToTheTagWithoutAnOrbColour() throws Exception {
        Font font = chatFont();
        EventTeamIndicatorService.TeamBadge noOrb =
            new EventTeamIndicatorService.TeamBadge(101, "RR", null, new Color(0xcc3333));
        EventTeamIndicatorService.BadgeArt art =
            EventTeamIndicatorService.drawBadge(noOrb, TeamIndicatorStyle.ORB, font);
        EventTeamIndicatorService.BadgeArt tag =
            EventTeamIndicatorService.drawBadge(noOrb, TeamIndicatorStyle.TAG, font);
        assertNotNull(art);
        assertNotNull(tag);
        assertEquals(tag.image.getWidth(), art.image.getWidth());
        assertEquals(tag.ascent, art.ascent);
    }

    @Test
    public void withoutAFontTheTagIsLeftOut() {
        EventTeamIndicatorService.BadgeArt art =
            EventTeamIndicatorService.drawBadge(badge(), TeamIndicatorStyle.ORB_AND_TAG, null);
        assertNotNull(art);
        assertEquals(12, art.image.getWidth());
    }

    @Test
    public void aTeamWithNothingToDrawGetsNoSprite() throws Exception {
        EventTeamIndicatorService.TeamBadge bare =
            new EventTeamIndicatorService.TeamBadge(101, null, null, null);
        assertNull(EventTeamIndicatorService.drawBadge(bare, TeamIndicatorStyle.ORB_AND_TAG, chatFont()));
    }

    @Test
    public void offDrawsNothing() throws Exception {
        assertNull(EventTeamIndicatorService.drawBadge(badge(), TeamIndicatorStyle.OFF, chatFont()));
    }

    @Test
    public void blackIsNeverTheTransparentIndex() {
        // ImageUtil.getImageIndexedSprite reads rgb 0 as its transparent
        // index, so a black team would come out invisible.
        assertEquals(0x010101, EventTeamIndicatorService.spriteSafe(Color.BLACK).getRGB() & 0xFFFFFF);
        EventTeamIndicatorService.TeamBadge black =
            new EventTeamIndicatorService.TeamBadge(101, "BK", Color.BLACK, null);
        EventTeamIndicatorService.BadgeArt art =
            EventTeamIndicatorService.drawBadge(black, TeamIndicatorStyle.ORB, null);
        assertNotNull(art);
        assertEquals(0x010101, rgb(art.image, 6, 6));
    }

    // ── the chat builder callback ────────────────────────────────────────────

    private final Map<Long, MessageNode> nodes = new HashMap<>();
    private final List<String> renamed = new ArrayList<>();
    private Client client;
    private DropTrackerConfig config;
    private EventTeamIndicatorService service;

    @Before
    @SuppressWarnings("unchecked")
    public void setUpChat() {
        client = RuneLiteStubs.stub(Client.class, "client");
        IterableHashTable<MessageNode> messages = RuneLiteStubs.stub(IterableHashTable.class, "messages");
        state(messages).put("get", (RuneLiteStubs.Answer) args -> nodes.get((Long) args[0]));
        state(client).put("getMessages", messages);

        config = RuneLiteStubs.stub(DropTrackerConfig.class, "config");
        state(config).put("eventTeamIndicators", TeamIndicatorStyle.ORB);
        state(config).put("eventTeamIndicatorColorNames", true);
        state(config).put("eventTeamIndicatorsPublicChat", false);

        service = new EventTeamIndicatorService(client, null, null, config, null, null, null, null);
        Map<String, EventTeamIndicatorService.TeamBadge> badges = new HashMap<>();
        badges.put("zezima", badge());
        badges.put("beast owned", badge());
        service.useBadges(badges, Collections.singletonMap(101, SLOT));
    }

    /**
     * Run the chatMessageBuilding callback for one line, with the stacks laid
     * out the way ChatBuilder pushes them, and return the name it would draw.
     */
    private String draw(ChatMessageType type, String sender, String shown, boolean splitPmPane) {
        long uid = nodes.size() + 1;
        MessageNode node = RuneLiteStubs.stub(MessageNode.class, "node:" + sender);
        state(node).put("getType", type);
        state(node).put("getName", sender);
        state(node).put("setName", (RuneLiteStubs.Answer) args -> {
            renamed.add((String) args[0]);
            return null;
        });
        nodes.put(uid, node);

        int[] ints = {7, splitPmPane ? 1 : 0, (int) uid};
        Object[] objects = {"below", "Clan", shown, "hello", ""};
        state(client).put("getIntStack", ints);
        state(client).put("getIntStackSize", ints.length);
        state(client).put("getObjectStack", objects);
        state(client).put("getObjectStackSize", objects.length);

        ScriptCallbackEvent event = new ScriptCallbackEvent();
        event.setEventName("chatMessageBuilding");
        service.onScriptCallbackEvent(event);

        assertEquals("the rest of the line is untouched", "Clan", objects[1]);
        assertEquals("hello", objects[3]);
        return (String) objects[2];
    }

    private String draw(ChatMessageType type, String sender) {
        return draw(type, sender, sender, false);
    }

    @Test
    public void aTeammatesClanLineIsBadgedAsItIsDrawn() {
        String drawn = draw(ChatMessageType.CLAN_CHAT, "Zezima");
        assertEquals("<img=42><col=cc3333>Zezima</col>", drawn);
        assertEquals("Zezima", Text.removeTags(drawn));
    }

    @Test
    public void everyClanStyleChannelIsBadged() {
        for (ChatMessageType type : new ChatMessageType[] {
            ChatMessageType.CLAN_CHAT, ChatMessageType.CLAN_GUEST_CHAT,
            ChatMessageType.CLAN_GIM_CHAT, ChatMessageType.FRIENDSCHAT}) {
            assertEquals(type.name(), "<img=42><col=cc3333>Zezima</col>", draw(type, "Zezima"));
        }
    }

    @Test
    public void privateMessagesAreNeverTouched() {
        // The reported bug: a teammate's PMs recoloured, and hidden outright
        // for anyone with Private set to Friends.
        for (ChatMessageType type : new ChatMessageType[] {
            ChatMessageType.PRIVATECHAT, ChatMessageType.PRIVATECHATOUT,
            ChatMessageType.MODPRIVATECHAT}) {
            assertEquals(type.name(), "Zezima", draw(type, "Zezima"));
        }
    }

    @Test
    public void nothingOutsideTheBadgedChannelsIsTouched() {
        for (ChatMessageType type : ChatMessageType.values()) {
            switch (type) {
                case CLAN_CHAT:
                case CLAN_GUEST_CHAT:
                case CLAN_GIM_CHAT:
                case FRIENDSCHAT:
                case PUBLICCHAT:
                    continue;
                default:
                    assertEquals(type.name(), "Zezima", draw(type, "Zezima"));
            }
        }
    }

    @Test
    public void theSplitPrivateChatPaneIsNeverTouched() {
        // It only ever holds DMs; the flag is checked before the type.
        assertEquals("Zezima", draw(ChatMessageType.CLAN_CHAT, "Zezima", "Zezima", true));
    }

    @Test
    public void theMessageNodeIsNeverRenamed() {
        // MessageNode.setName rebuilds the sender identity the game's friend
        // and ignore checks read. Renaming a node is what made DMs vanish.
        for (ChatMessageType type : ChatMessageType.values()) {
            draw(type, "Zezima");
        }
        assertTrue("renamed: " + renamed, renamed.isEmpty());
    }

    @Test
    public void publicChatOnlyWhenOptedIn() {
        assertEquals("Zezima", draw(ChatMessageType.PUBLICCHAT, "Zezima"));
        state(config).put("eventTeamIndicatorsPublicChat", true);
        assertEquals("<img=42><col=cc3333>Zezima</col>", draw(ChatMessageType.PUBLICCHAT, "Zezima"));
    }

    @Test
    public void playersOffTheRosterAreLeftAlone() {
        assertEquals("Woox", draw(ChatMessageType.CLAN_CHAT, "Woox"));
    }

    @Test
    public void discordBridgeLinesAreNotBadged() {
        String sender = "Zezima " + ChatMessageUtil.DISCORD_SENDER_MARKER;
        assertEquals(sender, draw(ChatMessageType.CLAN_CHAT, sender));
    }

    @Test
    public void offBadgesNothing() {
        state(config).put("eventTeamIndicators", TeamIndicatorStyle.OFF);
        assertEquals("Zezima", draw(ChatMessageType.CLAN_CHAT, "Zezima"));
    }

    @Test
    public void theSenderIsMatchedByTheNodeNotTheDrawnName() {
        // Another plugin may have decorated the drawn name already; the node
        // still carries the name the server sent, U+00A0 and all.
        assertEquals("<img=42><col=cc3333><img=5>Beast Owned</col>",
            draw(ChatMessageType.CLAN_CHAT, "Beast\u00A0Owned", "<img=5>Beast Owned", false));
    }

    @Test
    public void otherCallbacksAreIgnored() {
        // No stacks are stubbed at all; touching them would throw.
        ScriptCallbackEvent event = new ScriptCallbackEvent();
        event.setEventName("privChatUsername");
        service.onScriptCallbackEvent(event);
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
