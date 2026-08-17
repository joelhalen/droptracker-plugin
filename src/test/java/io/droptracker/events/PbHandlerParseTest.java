package io.droptracker.events;

import net.runelite.client.util.Text;
import org.junit.Test;

import java.time.Duration;
import java.util.regex.Matcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the chat-message regex patterns in {@link PbHandler}, exposed via
 * package-private {@code @VisibleForTesting} accessors.
 */
public class PbHandlerParseTest {

    // --- BOSS_COUNT_PATTERN ---

    @Test
    public void bossCountMatchesKillCountMessage() {
        Matcher m = PbHandler.bossCountPattern().matcher("Your Zulrah kill count is: 1,234");
        assertTrue(m.find());
        assertEquals("Zulrah", m.group("key"));
        assertEquals("kill", m.group("type"));
        assertEquals("1,234", m.group("value"));
    }

    @Test
    public void bossCountMatchesMultiWordBossName() {
        Matcher m = PbHandler.bossCountPattern().matcher("Your Grotesque Guardians kill count is: 500");
        assertTrue(m.find());
        assertEquals("Grotesque Guardians", m.group("key"));
        assertEquals("500", m.group("value"));
    }

    @Test
    public void bossCountMatchesChestAndSuccessTypes() {
        Matcher chest = PbHandler.bossCountPattern().matcher("Your Barrows chest count is: 42");
        assertTrue(chest.find());
        assertEquals("Barrows", chest.group("key"));
        assertEquals("chest", chest.group("type"));

        Matcher success = PbHandler.bossCountPattern().matcher("Your Wintertodt success count is: 300");
        assertTrue(success.find());
        assertEquals("Wintertodt", success.group("key"));
        assertEquals("success", success.group("type"));
    }

    @Test
    public void bossCountMatchesNamesWithApostrophesAndColons() {
        Matcher m = PbHandler.bossCountPattern().matcher("Your Vet'ion kill count is: 69");
        assertTrue(m.find());
        assertEquals("Vet'ion", m.group("key"));
    }

    @Test
    public void bossCountDoesNotMatchUnrelatedMessages() {
        assertFalse(PbHandler.bossCountPattern().matcher("You have a funny feeling...").find());
        assertFalse(PbHandler.bossCountPattern().matcher("Your heriblore level is now 78.").find());
    }

    // --- SECONDARY_BOSS_PATTERN ---

    @Test
    public void secondaryPatternMatchesCompletedCountMessage() {
        Matcher m = PbHandler.secondaryBossPattern().matcher("Your completed Theatre of Blood count is: 5");
        assertTrue(m.find());
        assertEquals("completed", m.group("type"));
        assertEquals("Theatre of Blood", m.group("key"));
        assertEquals("5", m.group("value"));
    }

    @Test
    public void secondaryPatternMatchesSubduedCountMessage() {
        Matcher m = PbHandler.secondaryBossPattern().matcher("Your subdued Wintertodt count is: 10");
        assertTrue(m.find());
        assertEquals("subdued", m.group("type"));
        assertEquals("Wintertodt", m.group("key"));
        assertEquals("10", m.group("value"));
    }

    // --- TEAM_SIZE_PATTERN ---

    @Test
    public void teamSizeMatchesSolo() {
        Matcher m = PbHandler.teamSizePattern().matcher("Team size: Solo");
        assertTrue(m.find());
        assertEquals("Solo", m.group("size"));
    }

    @Test
    public void teamSizeMatchesNumericPlayers() {
        Matcher m = PbHandler.teamSizePattern().matcher("Team size: 3 players");
        assertTrue(m.find());
        assertEquals("3", m.group("size"));

        Matcher single = PbHandler.teamSizePattern().matcher("Team size: 1 player");
        assertTrue(single.find());
        assertEquals("1", single.group("size"));
    }

    @Test
    public void teamSizeDoesNotMatchUnrelatedText() {
        assertFalse(PbHandler.teamSizePattern().matcher("Fight duration: 1:23").find());
    }

    // --- TIME_WITH_PB_PATTERN ---

    @Test
    public void timePatternExtractsDurationAndNewPbIndicator() {
        Matcher m = PbHandler.timeWithPbPattern().matcher("Fight duration: 1:23.40 (new personal best)");
        assertTrue(m.find());
        assertEquals("1:23.40", m.group("duration"));
        assertEquals("(new personal best)", m.group("pbIndicator"));
        assertNull(m.group("pbtime"));
    }

    @Test
    public void timePatternExtractsDurationAndExistingPersonalBest() {
        Matcher m = PbHandler.timeWithPbPattern().matcher("Duration: 45:12 Personal best: 40:00");
        assertTrue(m.find());
        assertEquals("45:12", m.group("duration"));
        assertEquals("40:00", m.group("pbtime"));
        assertNull(m.group("pbIndicator"));
    }

    @Test
    public void timePatternHandlesHourLongDurations() {
        Matcher m = PbHandler.timeWithPbPattern().matcher("Overall time: 1:23:45.60 (new personal best)");
        assertTrue(m.find());
        assertEquals("1:23:45.60", m.group("duration"));
        assertEquals("(new personal best)", m.group("pbIndicator"));
    }

    @Test
    public void timePatternWithoutPbInfoLeavesGroupsNull() {
        Matcher m = PbHandler.timeWithPbPattern().matcher("Fight duration: 2:15.");
        assertTrue(m.find());
        assertEquals("2:15", m.group("duration"));
        assertNull(m.group("pbtime"));
        assertNull(m.group("pbIndicator"));
    }

    // --- selectTimeLine ---
    //
    // Raid messages taken verbatim from RuneLite's ChatCommandsPluginTest,
    // run through the plugin's own sanitising.

    /** Mirrors {@code SubmissionManager.sanitize}. */
    private static String sanitize(String message) {
        return Text.removeTags(message.replace("<br>", "\n")).replace('\u00A0', ' ').trim();
    }

    private static Matcher timeMatcherFor(String message) {
        String line = PbHandler.selectTimeLine(sanitize(message));
        assertNotNull("expected a usable time line in: " + message, line);
        Matcher m = PbHandler.timeWithPbPattern().matcher(line);
        assertTrue(m.find());
        return m;
    }

    private static final String TOB_PB_MESSAGE =
        "Wave 'The Final Challenge' (Normal Mode) complete!<br>" +
            "Duration: <col=ff0000>2:42.0</col><br>" +
            "Theatre of Blood completion time: <col=ff0000>17:00.20</col> (new personal best)";

    private static final String TOB_NO_PB_MESSAGE =
        "Wave 'The Final Challenge' (Normal Mode) complete!<br>" +
            "Duration: <col=ff0000>2:42</col><br>" +
            "Theatre of Blood completion time: <col=ff0000>17:00</col>. Personal best: 13:52.80";

    private static final String TOB_TOTAL_MESSAGE =
        "Theatre of Blood total completion time: <col=ff0000>24:40.20</col>. Personal best: 20:45.00";

    private static final String TOA_PB_MESSAGE =
        "Challenge complete: The Wardens. Duration: <col=ef1020>8:30</col><br>" +
            "Tombs of Amascut challenge completion time: <col=ef1020>8:31</col> (new personal best)";

    private static final String TOA_TOTAL_MESSAGE =
        "Tombs of Amascut total completion time: <col=ef1020>0:01</col> (new personal best)";

    @Test
    public void tobTakesRaidCompletionTimeOverTheFinalWaveDuration() {
        Matcher m = timeMatcherFor(TOB_PB_MESSAGE);
        // Not 2:42.0, the Verzik room duration that shares this message.
        assertEquals("17:00.20", m.group("duration"));
        assertEquals("(new personal best)", m.group("pbIndicator"));
        assertNull(m.group("pbtime"));
    }

    @Test
    public void tobKeepsTheNewPersonalBestSuffixThatSitsLinesAwayFromTheFirstTime() {
        Matcher m = timeMatcherFor(TOB_PB_MESSAGE);
        assertNotNull("a genuine PB must not be reported as a non-PB", m.group("pbIndicator"));
        assertEquals(Duration.ofMinutes(17).plusMillis(200), PbHandler.parseTime(m.group("duration")));
    }

    @Test
    public void tobNonPbKillReportsCompletionTimeAndStandingBest() {
        Matcher m = timeMatcherFor(TOB_NO_PB_MESSAGE);
        assertEquals("17:00", m.group("duration"));
        assertEquals("13:52.80", m.group("pbtime"));
        assertNull(m.group("pbIndicator"));
    }

    @Test
    public void tobWallClockTotalIsNotAKillTimeAtAll() {
        // Null keeps updateKillData from overwriting the raid time.
        assertNull(PbHandler.selectTimeLine(sanitize(TOB_TOTAL_MESSAGE)));
    }

    @Test
    public void toaTakesChallengeCompletionTimeOverTheWardensDuration() {
        Matcher m = timeMatcherFor(TOA_PB_MESSAGE);
        assertEquals("8:31", m.group("duration"));
        assertEquals("(new personal best)", m.group("pbIndicator"));
    }

    @Test
    public void toaWallClockTotalIsNotAKillTimeAtAll() {
        assertNull(PbHandler.selectTimeLine(sanitize(TOA_TOTAL_MESSAGE)));
    }

    @Test
    public void coxRaidCompletionIsUnaffected() {
        Matcher m = timeMatcherFor(
            "<col=ef20ff>Congratulations - your raid is complete!</col><br>" +
                "Team size: <col=ff0000>4 players</col> Duration:</col> <col=ff0000>37:04.20</col> " +
                "Personal best: </col><col=ff0000>32:26.40</col>");
        assertEquals("37:04.20", m.group("duration"));
        assertEquals("32:26.40", m.group("pbtime"));
    }

    @Test
    public void singleLineKillTimesAreUnaffected() {
        Matcher pb = timeMatcherFor("Fight duration: <col=ff0000>1:23.40</col> (new personal best)");
        assertEquals("1:23.40", pb.group("duration"));
        assertEquals("(new personal best)", pb.group("pbIndicator"));

        Matcher standing = timeMatcherFor(
            "Fight duration: <col=ff0000>2:30</col>. Personal best: 2:15.60");
        assertEquals("2:30", standing.group("duration"));
        assertEquals("2:15.60", standing.group("pbtime"));

        Matcher gauntlet = timeMatcherFor(
            "Challenge duration: <col=ff0000>10:30</col>. Personal best: 9:45");
        assertEquals("10:30", gauntlet.group("duration"));
    }

    /**
     * The shape that poisoned ~47 ToB rows between 2026-08-04 and 08-17: a
     * non-PB raid, where the completion line carries no PB marker so nothing
     * returned early and the fallback took Verzik's room split as the raid
     * time. 5:34.20 is not a Theatre of Blood time; it is roughly a third of
     * the world record.
     */
    private static final String TOB_NON_PB_TOTAL_HOLDS_THE_BEST =
        "Wave 'The Final Challenge' (Normal Mode) complete!<br>" +
            "Duration: <col=ff0000>5:34.20</col><br>" +
            "Theatre of Blood completion time: <col=ff0000>18:54.60</col><br>" +
            "Theatre of Blood total completion time: <col=ff0000>21:42.60</col>. Personal best: 20:45.00";

    /** A room that was itself a PB, on a raid that was not. */
    private static final String TOB_ROOM_PB_ONLY =
        "Wave 'The Final Challenge' (Normal Mode) complete!<br>" +
            "Duration: <col=ff0000>4:53.80</col> (new personal best)<br>" +
            "Theatre of Blood completion time: <col=ff0000>19:14.40</col>";

    @Test
    public void tobNonPbRaidTakesTheCompletionTimeNotTheVerzikSplit() {
        Matcher m = timeMatcherFor(TOB_NON_PB_TOTAL_HOLDS_THE_BEST);
        assertEquals("18:54.60", m.group("duration"));
        assertNull("the room split must not be reported as the raid time", m.group("pbIndicator"));
        assertEquals(Duration.ofMinutes(18).plusSeconds(54).plusMillis(600),
            PbHandler.parseTime(m.group("duration")));
    }

    @Test
    public void tobRoomPersonalBestDoesNotHijackTheRaidTime() {
        // The only (new personal best) in this message sits on the room line,
        // so a "prefer the PB-flagged line" rule alone would submit 4:53.80.
        Matcher m = timeMatcherFor(TOB_ROOM_PB_ONLY);
        assertEquals("19:14.40", m.group("duration"));
    }

    @Test
    public void withoutAnyPbInfoTheFirstTimedLineStillWins() {
        // Unchanged fallback: what a single scan of the whole message returned.
        Matcher m = timeMatcherFor("Wave 'The Final Challenge' complete!<br>" +
            "Duration: <col=ff0000>2:42.0</col><br>Something else: <col=ff0000>9:99</col>");
        assertEquals("2:42.0", m.group("duration"));
    }

    @Test
    public void messagesWithNoTimeYieldNothing() {
        assertNull(PbHandler.selectTimeLine("Your completed Theatre of Blood count is: 73."));
        assertNull(PbHandler.selectTimeLine(""));
        assertNull(PbHandler.selectTimeLine(null));
    }

    // === raid team-size bracketing ===

    @Test
    public void rosterSizeBeatsDecayedCompletionVarbits() {
        // Orbs already read "just me" at loot-chest time; the roster still
        // knows the party.
        assertEquals("5", PbHandler.formatRaidTeamSize(5, 1));
    }

    @Test
    public void varbitsRemainTheFallbackWithoutARoster() {
        assertEquals("4", PbHandler.formatRaidTeamSize(0, 4));
        assertEquals("Solo", PbHandler.formatRaidTeamSize(0, 1));
    }

    @Test
    public void agreeingSourcesKeepTheirBracket() {
        assertEquals("Solo", PbHandler.formatRaidTeamSize(1, 1));
        assertEquals("2", PbHandler.formatRaidTeamSize(2, 2));
    }

    @Test
    public void liveVarbitsCanExceedAStaleRoster() {
        // The max wins in both directions.
        assertEquals("5", PbHandler.formatRaidTeamSize(3, 5));
    }

    @Test
    public void noSourcesPreservesTheLegacyZeroBracket() {
        assertEquals("0", PbHandler.formatRaidTeamSize(0, 0));
    }
}
