package io.droptracker.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * What a member's own death message may contain, checked as they type.
 *
 * <p>The server is the authority (disc {@code db/member_messages.py}) and
 * refuses anything that breaks these rules with the same wording; this mirror
 * only exists so the editor can say what is wrong before a save round-trip.
 * The website carries the same mirror, and all three pin the same cases in
 * their tests — change a rule in all three places or the editors will
 * disagree with the server about what can be saved.
 */
public final class DeathMessageRules {

    public static final int MAX_MESSAGES = 5;
    public static final int MAX_LENGTH = 150;

    /** Placeholders a member may use, in display order. */
    public static final List<String> TOKENS = Collections.unmodifiableList(Arrays.asList(
        "{player_name}", "{killer}", "{location}", "{value_lost}", "{value_kept}", "{killer_combat_level}"));

    /** Also accepted, so a line copied from a group's own death messages works. */
    private static final Map<String, String> ALIASES = Map.of(
        "{source}", "{killer}",
        "{region_name}", "{location}");

    private static final Set<String> ALLOWED;

    static {
        Set<String> allowed = new LinkedHashSet<>(TOKENS);
        allowed.addAll(ALIASES.keySet());
        ALLOWED = Collections.unmodifiableSet(allowed);
    }

    private static final Pattern TOKEN = Pattern.compile("\\{[a-z0-9_]+\\}", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISCORD_ENTITY = Pattern.compile(
        "@everyone|@here|<@[&!]?\\d+>|<#\\d+>|<a?:\\w+:\\d+>|</[^<>:\\n]+:\\d+>|<t:-?\\d+(?::[a-z])?>",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile(
        "https?://\\S*|www\\.\\S*|\\bdiscord(?:app)?\\.(?:gg|com/invite)\\S*"
            + "|\\b[a-z0-9][a-z0-9-]*\\.(?:com|net|org|gg|io|co|me|xyz|tv|ly|link|site|app|dev|info|ru|uk|us)\\b\\S*",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK_MARKDOWN = Pattern.compile("^\\s*(?:#{1,3}\\s|-#\\s|>>>|>\\s)");
    /** Control characters plus the Unicode line and paragraph separators. */
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1f\\x7f\\u2028\\u2029]");
    private static final Pattern SPACES = Pattern.compile("\\s{2,}");

    private DeathMessageRules() {
    }

    /** Why one trimmed message cannot be saved, or null. Same wording as the server. */
    @Nullable
    public static String issue(String text) {
        if (text.codePointCount(0, text.length()) > MAX_LENGTH) {
            return "Each message can be at most " + MAX_LENGTH + " characters.";
        }
        if (CONTROL.matcher(text).find()) {
            return "Each message has to be a single line of text.";
        }
        if (DISCORD_ENTITY.matcher(text).find()) {
            return "Messages can't mention people, roles or channels, or use custom emoji.";
        }
        if (LINK.matcher(text).find()) {
            return "Messages can't contain links.";
        }
        if (BLOCK_MARKDOWN.matcher(text).find()) {
            return "Messages can't start with a heading or a quote.";
        }
        Set<String> unknown = new TreeSet<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            if (!ALLOWED.contains(m.group().toLowerCase(Locale.ROOT))) {
                unknown.add(m.group());
            }
        }
        if (!unknown.isEmpty()) {
            return "Unknown placeholder" + (unknown.size() > 1 ? "s " : " ") + String.join(", ", unknown)
                + ". You can use " + String.join(", ", TOKENS) + ".";
        }
        return null;
    }

    /**
     * What the server will store: each row trimmed, blank rows and exact
     * duplicates dropped, placeholder names lowercased.
     */
    public static List<String> normalize(List<String> rows) {
        List<String> out = new ArrayList<>();
        for (String raw : rows) {
            if (raw == null) {
                continue;
            }
            String text = lowercaseTokens(raw.trim());
            if (!text.isEmpty() && !out.contains(text)) {
                out.add(text);
            }
        }
        return out;
    }

    /** The first problem with the whole list, or null when it can be saved. */
    @Nullable
    public static String listIssue(List<String> rows) {
        List<String> cleaned = normalize(rows);
        for (String message : cleaned) {
            String issue = issue(message);
            if (issue != null) {
                return issue;
            }
        }
        if (cleaned.size() > MAX_MESSAGES) {
            return "You can save at most " + MAX_MESSAGES + " messages.";
        }
        return null;
    }

    /**
     * A template filled with sample values the way the bot fills a real death:
     * {@code {player_name}} becomes the account's name, aliases read their
     * canonical placeholder's sample, and anything else is left out.
     */
    public static String preview(String template, Map<String, String> samples, String playerName) {
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String token = m.group().toLowerCase(Locale.ROOT);
            String canonical = ALIASES.getOrDefault(token, token);
            String value;
            if ("{player_name}".equals(canonical)) {
                value = playerName == null ? "" : playerName;
            } else {
                value = samples.getOrDefault(canonical, "");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return SPACES.matcher(out.toString()).replaceAll(" ").trim();
    }

    private static String lowercaseTokens(String text) {
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(m.group().toLowerCase(Locale.ROOT)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
