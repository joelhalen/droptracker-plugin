package io.droptracker.util;

import net.runelite.client.util.ColorUtil;
import org.junit.Test;

import java.awt.Color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Event chat accents must stay readable on both chatbox styles. */
public class ChatAccentContrastTest {
    /** The event palette (EventNotificationService's HEX_* accents). */
    private static final String[] ACCENTS = {
        "#6fbf73", "#7fe08a", "#ffd966", "#ff8c42", "#e05c4d", "#ffb83f", "#d8c9a3", "#8f8778"
    };
    /** The opaque chatbox's parchment, sampled mid-texture. */
    private static final Color PARCHMENT = new Color(0xcf, 0xbd, 0x97);

    private static double contrast(Color a, Color b) {
        double la = ChatMessageUtil.luminance(a);
        double lb = ChatMessageUtil.luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    @Test
    public void opaqueAccentsMeetAaOnParchment() {
        for (String hex : ACCENTS) {
            Color accent = ColorUtil.fromHex(hex);
            Color body = ChatMessageUtil.chatAccent(accent, true);
            Color strong = ChatMessageUtil.chatEmphasis(accent, true);
            assertTrue(hex + " body " + contrast(body, PARCHMENT), contrast(body, PARCHMENT) >= 4.5);
            assertTrue(hex + " emphasis", contrast(strong, PARCHMENT) > contrast(body, PARCHMENT));
        }
    }

    @Test
    public void transparentAccentsStayBright() {
        for (String hex : ACCENTS) {
            Color body = ChatMessageUtil.chatAccent(ColorUtil.fromHex(hex), false);
            assertTrue(hex, ChatMessageUtil.luminance(body) >= 0.30);
        }
        // Already-bright accents pass through untouched.
        assertEquals(ColorUtil.fromHex("#ffd966"), ChatMessageUtil.chatAccent(ColorUtil.fromHex("#ffd966"), false));
    }

    @Test
    public void formattedLineCarriesTheChatboxColours() {
        String line = ChatMessageUtil.formatEventLine("Autumn Bingo", "Iron Eagles", "COMPLETE", "#6fbf73",
            "Dragon warhammer", "Zezima received Dragon warhammer", true);
        String body = ColorUtil.colorToHexCode(ChatMessageUtil.chatAccent(ColorUtil.fromHex("#6fbf73"), true));
        assertTrue(line, line.contains(body));
        for (String hex : ACCENTS) {
            Color o = ChatMessageUtil.chatAccent(ColorUtil.fromHex(hex), true);
            Color t = ChatMessageUtil.chatAccent(ColorUtil.fromHex(hex), false);
            System.out.println(hex + " opaque=" + ColorUtil.toHexColor(o) + " transparent=" + ColorUtil.toHexColor(t)
                + " emphasis=" + ColorUtil.toHexColor(ChatMessageUtil.chatEmphasis(ColorUtil.fromHex(hex), true)));
        }
    }
}
