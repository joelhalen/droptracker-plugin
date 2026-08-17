package io.droptracker.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Fails when panel code renders a character RuneLite's fonts cannot draw.
 * <p>
 * {@code FontManager}'s three fonts map ~247 codepoints and stop at U+2122.
 * They only ever displayed symbols above that by falling back to a system
 * font, and that fallback chain does not cover them on macOS — where the
 * panel showed missing-glyph boxes instead (reported 2026-08). Rather than
 * depend on a per-platform fallback we cannot test from CI, panel text stays
 * inside the fonts' own coverage and shapes are painted (see PanelIcons).
 * <p>
 * If this fails: paint the shape in {@code PanelIcons} instead of typing the
 * character, or pick one of the {@link #IN_FONT_EXTRAS} below.
 */
public class PanelGlyphCoverageTest {

    /**
     * Non-Latin-1 characters verified present in runescape.ttf,
     * runescape_small.ttf and runescape_bold.ttf.
     */
    private static final Set<Character> IN_FONT_EXTRAS = Set.of(
        '…', // …  horizontal ellipsis
        '—', // —  em dash
        '€', // €  euro sign
        '™'  // ™  trade mark
    );

    /** Matches a Java string literal, honouring escapes. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\n]*");

    @Test
    public void panelStringsStayInsideTheBundledFontsCoverage() throws IOException {
        Path uiRoot = Paths.get("src/main/java/io/droptracker/ui");
        assertTrue("expected the UI sources at " + uiRoot.toAbsolutePath()
            + " — run this from the project root", Files.isDirectory(uiRoot));

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(uiRoot)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                collectOffenders(file, offenders);
            }
        }

        assertTrue("panel text uses characters RuneLite's fonts cannot render "
            + "(they tofu on macOS) — paint them via PanelIcons instead:\n  "
            + String.join("\n  ", offenders), offenders.isEmpty());
    }

    private static void collectOffenders(Path file, List<String> offenders) throws IOException {
        // Comments are not rendered, so strip them before looking at literals.
        String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        source = BLOCK_COMMENT.matcher(source).replaceAll("");
        source = LINE_COMMENT.matcher(source).replaceAll("");

        Matcher literal = STRING_LITERAL.matcher(source);
        while (literal.find()) {
            String value = literal.group(1);
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 0x100 || IN_FONT_EXTRAS.contains(c)) {
                    continue;
                }
                offenders.add(String.format("%s: U+%04X '%c' in \"%s\"",
                    file.getFileName(), (int) c, c, value));
            }
        }
    }
}
