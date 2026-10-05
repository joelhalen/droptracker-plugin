package io.droptracker.ui.overlays.popup;

import io.droptracker.models.EventPopupCard;
import io.droptracker.models.api.EventNotification;
import io.droptracker.service.EventNotificationService.Toast;
import net.runelite.client.ui.FontManager;

import javax.annotation.Nullable;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared painting helpers for the pop-up renderers: palette, fonts, easing,
 * text fitting and sprite drawing. Everything here runs on the client thread
 * inside an overlay's render, so it stays allocation-light: derived fonts are
 * cached and nothing touches the ItemManager directly.
 */
final class PopupPaint {
    /** The game's own orange, as on the collection log caption. */
    static final Color OSRS_ORANGE = new Color(0xff, 0x98, 0x1f);
    static final Color INK = new Color(0x0b, 0x09, 0x06);
    static final Color TEXT = new Color(0xef, 0xe6, 0xd2);
    static final Color TEXT_MUTED = new Color(0xc8, 0xb8, 0x9a);
    static final Color VALUE = new Color(0xff, 0xd2, 0x4a);

    private static final Map<Float, Font> BOLD_SIZES = new HashMap<>();

    private PopupPaint() {
    }

    /* ---------------- palette ---------------- */

    /** One accent per kind of news, in the spirit of rarity tiers. */
    static Color accent(EventPopupCard.Kind kind) {
        switch (kind) {
            case PROGRESS:
                return new Color(0xa8, 0xa8, 0x9e);
            case COMPLETE:
                return new Color(0x5f, 0xc8, 0x6a);
            case TILE:
                return new Color(0xe8, 0xc5, 0x47);
            case LINE:
                return new Color(0xff, 0x8c, 0x42);
            case BLACKOUT:
                return new Color(0xe8, 0x3f, 0x6f);
            case LEAD:
                return new Color(0xb0, 0x6a, 0xf0);
            case STARTED:
                return new Color(0x3d, 0x9b, 0xe9);
            case ENDED:
                return new Color(0xd8, 0xc9, 0xa3);
            case BOARD:
                return new Color(0x3f, 0xc9, 0xb6);
            case ROLL:
                return new Color(0xff, 0xb8, 0x3f);
            case DIGEST:
            default:
                return new Color(0x8f, 0xa7, 0xc7);
        }
    }

    /** Linear blend: t = 0 is {@code a}, t = 1 is {@code b}. */
    static Color mix(Color a, Color b, float t) {
        float u = 1f - t;
        return new Color(
            Math.round(a.getRed() * u + b.getRed() * t),
            Math.round(a.getGreen() * u + b.getGreen() * t),
            Math.round(a.getBlue() * u + b.getBlue() * t));
    }

    static Color alpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    /* ---------------- content ---------------- */

    /**
     * The toast's structured card; toasts built without one (nothing today,
     * but the constructor allows it) get a card from their title and body.
     */
    static EventPopupCard cardOf(Toast toast) {
        if (toast.getCard() != null) {
            return toast.getCard();
        }
        EventPopupCard.Kind kind = toast.getPriority() == EventNotification.Priority.HIGH
            ? EventPopupCard.Kind.TILE
            : toast.getPriority() == EventNotification.Priority.LOW
                ? EventPopupCard.Kind.PROGRESS : EventPopupCard.Kind.COMPLETE;
        return new EventPopupCard(kind, toast.getTitle(), toast.getBody());
    }

    @Nullable
    static Integer iconOf(Toast toast) {
        Integer id = toast.getIconItemId();
        return id != null && id > 0 ? id : null;
    }

    /* ---------------- fonts ---------------- */

    static Font bold() {
        return FontManager.getRunescapeBoldFont();
    }

    static Font plain() {
        return FontManager.getRunescapeFont();
    }

    static Font small() {
        return FontManager.getRunescapeSmallFont();
    }

    /** The RuneScape bold face at another size; cached, deriveFont isn't free. */
    static Font bold(float size) {
        Font font = BOLD_SIZES.get(size);
        if (font == null) {
            font = FontManager.getRunescapeBoldFont().deriveFont(size);
            BOLD_SIZES.put(size, font);
        }
        return font;
    }

    /* ---------------- timing ---------------- */

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 0..1 progress through a window starting {@code delay} ms after birth. */
    static float phase(long ageMs, long delay, long duration) {
        return clamp01((ageMs - delay) / (float) duration);
    }

    static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /** Overshoots past 1 then settles; {@code s} sets how far. */
    static float easeOutBack(float t, float s) {
        float u = t - 1f;
        return 1f + (s + 1f) * u * u * u + s * u * u;
    }

    /** 1 while the toast is fresh, falling to 0 over its last {@code fadeMs}. */
    static float fadeOut(Toast toast, long now, long fadeMs) {
        long remaining = toast.remainingMs(now);
        return remaining < fadeMs ? clamp01(remaining / (float) fadeMs) : 1f;
    }

    static void setAlpha(Graphics2D g, float alpha) {
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clamp01(alpha)));
    }

    /* ---------------- text ---------------- */

    static void shadowed(Graphics2D g, String text, int x, int y, Color color) {
        g.setColor(Color.BLACK);
        g.drawString(text, x + 1, y + 1);
        g.setColor(color);
        g.drawString(text, x, y);
    }

    static void shadowedCentered(Graphics2D g, String text, int centerX, int y, Color color) {
        int width = g.getFontMetrics().stringWidth(text);
        shadowed(g, text, centerX - width / 2, y, color);
    }

    static void shadowedRight(Graphics2D g, String text, int rightX, int y, Color color) {
        int width = g.getFontMetrics().stringWidth(text);
        shadowed(g, text, rightX - width, y, color);
    }

    static String ellipsize(@Nullable String text, FontMetrics fm, int width) {
        if (text == null) {
            return "";
        }
        if (fm.stringWidth(text) <= width) {
            return text;
        }
        String out = text;
        while (out.length() > 1 && fm.stringWidth(out + "…") > width) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }

    /** Word-wrap into at most {@code maxLines}; the last line is ellipsized if anything is dropped. */
    static List<String> wrap(@Nullable String text, FontMetrics fm, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        StringBuilder current = new StringBuilder();
        boolean clipped = false;
        for (String word : text.split(" ")) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (fm.stringWidth(candidate) <= width || current.length() == 0) {
                current = new StringBuilder(candidate);
            } else if (lines.size() == maxLines - 1) {
                clipped = true;
                break;
            } else {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
        }
        if (current.length() > 0 && lines.size() < maxLines) {
            lines.add(current.toString());
        }
        if (!lines.isEmpty()) {
            int last = lines.size() - 1;
            String tail = lines.get(last);
            if (clipped) {
                tail += "…";
            }
            lines.set(last, ellipsize(tail, fm, width));
        }
        return lines;
    }

    /**
     * The biggest bold size from {@code max} down to {@code min} at which the
     * text fits {@code width}; the caller ellipsizes if even {@code min} won't.
     */
    static Font fitBold(Graphics2D g, String text, int width, float max, float min) {
        for (float size = max; size > min; size -= 1f) {
            Font font = bold(size);
            if (g.getFontMetrics(font).stringWidth(text) <= width) {
                return font;
            }
        }
        return bold(min);
    }

    /* ---------------- sprites ---------------- */

    /**
     * Draws an item sprite fitted into a {@code box}-sized square, centred,
     * aspect kept (sprites are 36x32). Scales up as well as down, smoothly.
     */
    static void drawSprite(Graphics2D g, BufferedImage sprite, int x, int y, int box) {
        int w = Math.max(sprite.getWidth(), 1);
        int h = Math.max(sprite.getHeight(), 1);
        float scale = Math.min((float) box / w, (float) box / h);
        int nw = Math.max(Math.round(w * scale), 1);
        int nh = Math.max(Math.round(h * scale), 1);
        Object previous = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(sprite, x + (box - nw) / 2, y + (box - nh) / 2, nw, nh, null);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            previous != null ? previous : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    }
}
