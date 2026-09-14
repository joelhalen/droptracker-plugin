package io.droptracker.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The plugin's mirror of the server's death message rules. These are the same
 * cases the server (tests/unit/test_member_messages.py) and the website
 * (apps/web/test/member-messages.test.ts) pin, with the same wording, so a rule
 * changed in one place fails in the others.
 */
public class DeathMessageRulesTest {

    private static final String MENTIONS = "Messages can't mention people, roles or channels, or use custom emoji.";
    private static final String LINKS = "Messages can't contain links.";
    private static final String HEADINGS = "Messages can't start with a heading or a quote.";
    private static final String ONE_LINE = "Each message has to be a single line of text.";

    @Test
    public void limitsMatchTheServer() {
        assertEquals(5, DeathMessageRules.MAX_MESSAGES);
        assertEquals(150, DeathMessageRules.MAX_LENGTH);
    }

    @Test
    public void normalizingTrimsDropsBlanksAndDuplicatesAndLowercasesPlaceholders() {
        assertEquals(
            Collections.singletonList("{player_name} forgot to pray"),
            DeathMessageRules.normalize(Arrays.asList(
                "  {player_name} forgot to pray  ", "", "   ", null, "{player_name} forgot to pray")));
        assertEquals(
            Collections.singletonList("{player_name} vs {killer}"),
            DeathMessageRules.normalize(Collections.singletonList("{Player_Name} vs {KILLER}")));
    }

    @Test
    public void atMostFiveMessagesBlankRowsNotCounted() {
        assertTrue(DeathMessageRules.listIssue(Arrays.asList("a", "b", "c", "d", "e", "f"))
            .contains("at most 5 messages"));
        assertNull(DeathMessageRules.listIssue(Arrays.asList("a", "b", "c", "d", "e", "", " ")));
    }

    @Test
    public void lengthIsCountedInCharacters() {
        assertEquals("Each message can be at most 150 characters.",
            DeathMessageRules.issue(repeat("x", 151)));
        assertNull(DeathMessageRules.issue(repeat("x", 150)));
        // 150 emoji: 300 UTF-16 units, 150 characters to the server.
        assertNull(DeathMessageRules.issue(repeat("💀", 150)));
    }

    @Test
    public void noMentionsOrDiscordEntities() {
        for (String text : Arrays.asList(
            "@everyone rip", "@here rip", "<@123> rip", "<@!123> rip", "<@&456> rip",
            "<#789> rip", "<:skull:123456> rip", "<a:dance:123456> rip",
            "</settings:123456> rip", "<t:1700000000:R> rip")) {
            assertEquals(text, MENTIONS, DeathMessageRules.issue(text));
        }
    }

    @Test
    public void noLinks() {
        for (String text : Arrays.asList(
            "see https://example.com", "http://x.y", "www.example.org rip",
            "join discord.gg/abcdef", "discord.com/invite/abc", "free gp at scam.xyz",
            "[click me](https://example.com)")) {
            assertEquals(text, LINKS, DeathMessageRules.issue(text));
        }
    }

    @Test
    public void noHeadingsOrQuotes() {
        for (String text : Arrays.asList("# {player_name} died", "## big", "-# small", "> quoted", ">>> quoted")) {
            assertEquals(text, HEADINGS, DeathMessageRules.issue(text));
        }
    }

    @Test
    public void singleLine() {
        for (String text : Arrays.asList("line one\nline two", "tab\tseparated", "sep\u2028arated")) {
            assertEquals(text, ONE_LINE, DeathMessageRules.issue(text));
        }
    }

    @Test
    public void unknownPlaceholdersAreNamed() {
        String issue = DeathMessageRules.issue("{player_name} {video_url} {image_url}");
        assertTrue(issue, issue.startsWith("Unknown placeholders {image_url}, {video_url}."));
        assertTrue(issue.contains("{killer}"));
        assertTrue(DeathMessageRules.issue("{nope}").startsWith("Unknown placeholder {nope}."));
    }

    @Test
    public void ordinaryMessagesPass() {
        for (String text : Arrays.asList(
            "{player_name} lost {value_lost} (kept {value_kept}) to a level {killer_combat_level} {killer}",
            "{player_name} died at {location}",
            "{player_name} vs {source} in {region_name}",
            "Mr. Mordaut got {player_name} again — 4.2M gone, e.g. everything",
            "{player_name} **really** thought that was ||safe||")) {
            assertNull(text, DeathMessageRules.issue(text));
        }
    }

    @Test
    public void previewFillsSamplesTheAccountNameAndAliases() {
        Map<String, String> samples = new HashMap<>();
        samples.put("{killer}", "Vorkath");
        samples.put("{location}", "Ungael");
        assertEquals("Iron Ron fed Vorkath at Ungael",
            DeathMessageRules.preview("{player_name} fed {killer} at {region_name} {video_url}", samples, "Iron Ron"));
        assertEquals("Vorkath again", DeathMessageRules.preview("{Source} again", samples, "Ron"));
        // A sample holding a regex replacement token must come out literally.
        samples.put("{value_lost}", "$1.2M");
        assertEquals("Ron lost $1.2M", DeathMessageRules.preview("{player_name} lost {value_lost}", samples, "Ron"));
    }

    @Test
    public void tokensAreTheServersInOrder() {
        List<String> expected = Arrays.asList(
            "{player_name}", "{killer}", "{location}", "{value_lost}", "{value_kept}", "{killer_combat_level}");
        assertEquals(expected, DeathMessageRules.TOKENS);
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
