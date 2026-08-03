package io.droptracker.events;

import net.runelite.api.Client;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the chat-message regex patterns and {@link PbHandler#parseMessage}
 * filtering, exposed via package-private {@code @VisibleForTesting} accessors.
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

    // --- parseMessage: raid target-time announcements (issue #35) ---

    /**
     * parseMessage resolves raid team sizes through {@link Client} varbits, so give
     * the handler a stub client whose getters all return zero/null defaults.
     */
    private static PbHandler newHandler() {
        PbHandler handler = new PbHandler();
        handler.client = (Client) Proxy.newProxyInstance(
                Client.class.getClassLoader(),
                new Class<?>[]{Client.class},
                (proxy, method, args) -> {
                    Class<?> returnType = method.getReturnType();
                    if (returnType == int.class) return 0;
                    if (returnType == long.class) return 0L;
                    if (returnType == boolean.class) return false;
                    return null;
                });
        return handler;
    }

    @Test
    public void parseMessageIgnoresFailedTargetTimeAnnouncement() {
        PbHandler handler = newHandler();

        // In-game the target-time announcement arrives after the completion line.
        assertTrue(handler.parseMessage(
                "Tombs of Amascut total completion time: 41:37.20 (new personal best)").isPresent());

        // The announcement contains a parseable time (40:00) that is unrelated to the
        // player's PB; it must yield no KillData that could overwrite the 41:37.20.
        assertFalse(handler.parseMessage(
                "Your party failed to beat the overall target time of 40:00.").isPresent());
    }

    @Test
    public void parseMessageIgnoresBeatenTargetTimeAnnouncement() {
        PbHandler handler = newHandler();
        assertFalse(handler.parseMessage(
                "Your party beat the overall target time of 25:00!").isPresent());
    }

    @Test
    public void parseMessageStillParsesToaCompletionTime() {
        PbHandler handler = newHandler();
        Optional<PbHandler.KillData> result = handler.parseMessage(
                "Tombs of Amascut total completion time: 41:37.20 (new personal best)");
        assertTrue(result.isPresent());
        assertEquals("Tombs of Amascut", result.get().boss);
        assertEquals(Duration.ofMinutes(41).plusSeconds(37).plusMillis(200), result.get().time);
        assertTrue(result.get().isPersonalBest);
    }
}
