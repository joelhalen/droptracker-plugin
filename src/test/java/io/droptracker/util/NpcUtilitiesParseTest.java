package io.droptracker.util;

import org.junit.Test;

import java.util.regex.Matcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests the kill-count chat patterns used by {@link NpcUtilities#parseBoss}.
 * These drive KC attribution on every drop, so a broken group name silently
 * mis-tags submissions.
 */
public class NpcUtilitiesParseTest {

    @Test
    public void primaryMatchesKillCount() {
        Matcher m = NpcUtilities.PRIMARY_REGEX.matcher("Your Zulrah kill count is: 1,234");
        assertTrue(m.find());
        assertEquals("Zulrah", m.group("key"));
        assertEquals("kill", m.group("type"));
        assertEquals("1,234", m.group("value"));
    }

    @Test
    public void primaryMatchesMultiWordBoss() {
        Matcher m = NpcUtilities.PRIMARY_REGEX.matcher("Your Grotesque Guardians kill count is: 500");
        assertTrue(m.find());
        assertEquals("Grotesque Guardians", m.group("key"));
        assertEquals("500", m.group("value"));
    }

    @Test
    public void primaryMatchesChestAndCompletionAndSuccess() {
        Matcher chest = NpcUtilities.PRIMARY_REGEX.matcher("Your Barrows chest count is: 42");
        assertTrue(chest.find());
        assertEquals("Barrows", chest.group("key"));
        assertEquals("chest", chest.group("type"));

        Matcher completion = NpcUtilities.PRIMARY_REGEX.matcher("Your Gauntlet completion count is: 5");
        assertTrue(completion.find());
        assertEquals("completion", completion.group("type"));

        Matcher success = NpcUtilities.PRIMARY_REGEX.matcher("Your Wintertodt success count is: 300");
        assertTrue(success.find());
        assertEquals("success", success.group("type"));
    }

    @Test
    public void secondaryMatchesCompletedRaid() {
        Matcher m = NpcUtilities.SECONDARY_REGEX.matcher("Your completed Theatre of Blood count is: 5");
        assertTrue(m.find());
        assertEquals("completed", m.group("type"));
        assertEquals("Theatre of Blood", m.group("key"));
        assertEquals("5", m.group("value"));
    }

    @Test
    public void primaryDoesNotMatchUnrelatedMessages() {
        assertFalse(NpcUtilities.PRIMARY_REGEX.matcher("You have a funny feeling...").find());
        assertFalse(NpcUtilities.PRIMARY_REGEX.matcher("Your Attack level is now 70.").find());
    }

    // --- parsePrimaryBoss: only certain (boss,type) combinations are valid KC lines ---

    @Test
    public void primaryBossKeepsKillAndSuccessNamesVerbatim() {
        assertEquals("Zulrah", NpcUtilities.parsePrimaryBoss("Zulrah", "kill"));
        assertEquals("Wintertodt", NpcUtilities.parsePrimaryBoss("Wintertodt", "success"));
    }

    @Test
    public void primaryBossOnlyAcceptsBarrowsAndLunarChests() {
        assertEquals("Barrows", NpcUtilities.parsePrimaryBoss("Barrows", "chest"));
        assertEquals("Lunar chest", NpcUtilities.parsePrimaryBoss("Lunar", "chest"));
        // Any other "chest" source is not a tracked boss.
        assertNull(NpcUtilities.parsePrimaryBoss("Random", "chest"));
    }

    @Test
    public void primaryBossMapsGauntletCompletionToHunllef() {
        assertEquals("Crystalline Hunllef", NpcUtilities.parsePrimaryBoss("Gauntlet", "completion"));
        assertEquals("Corrupted Hunllef", NpcUtilities.parsePrimaryBoss("Corrupted Gauntlet", "completion"));
        assertNull(NpcUtilities.parsePrimaryBoss("Zulrah", "completion"));
    }

    // --- parseSecondary: raids + Wintertodt only ---

    @Test
    public void secondaryKeepsWintertodtAndRaids() {
        assertEquals("Wintertodt", NpcUtilities.parseSecondary("Wintertodt"));
        assertEquals("Theatre of Blood", NpcUtilities.parseSecondary("Theatre of Blood"));
        assertEquals("Theatre of Blood: Hard Mode",
                NpcUtilities.parseSecondary("Theatre of Blood: Hard Mode"));
    }

    @Test
    public void secondaryRejectsNonRaidNames() {
        assertNull(NpcUtilities.parseSecondary("Some Random Boss"));
    }
}
