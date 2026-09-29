package io.droptracker.ui.overlays.popup;

import io.droptracker.models.EventPopupCard;
import io.droptracker.service.EventNotificationService.Toast;

import java.awt.Color;
import java.awt.Composite;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.function.IntFunction;

/**
 * One slim line per update: a colour rail, a small icon, the headline and
 * the key number. For players who want to know something happened without
 * anything covering the game. Slides in from the right.
 */
class CompactNudgeRenderer implements PopupRenderer {
    private static final int HEIGHT = 20;
    private static final int ICON = 16;
    private static final long SLIDE_MS = 200;
    private static final long FADE_MS = 500;

    private static final Color BG = new Color(0x0e, 0x0b, 0x07, 215);

    private final IntFunction<BufferedImage> sprites;

    CompactNudgeRenderer(IntFunction<BufferedImage> sprites) {
        this.sprites = sprites;
    }

    @Override
    public int preferredWidth() {
        return 200;
    }

    @Override
    public int gap() {
        return 2;
    }

    @Override
    public int maxVisible() {
        return 4;
    }

    @Override
    public int draw(Graphics2D g, Toast toast, int x, int top, int w, long now) {
        EventPopupCard card = PopupPaint.cardOf(toast);
        Color accent = PopupPaint.accent(card.getKind());
        long age = now - toast.getCreatedAt();

        Composite previousComposite = g.getComposite();
        AffineTransform previousTransform = g.getTransform();
        float slide = PopupPaint.easeOutCubic(PopupPaint.phase(age, 0, SLIDE_MS));
        PopupPaint.setAlpha(g, PopupPaint.fadeOut(toast, now, FADE_MS) * slide);
        g.translate(Math.round((1f - slide) * 24f), 0);

        g.setColor(BG);
        g.fillRect(x, top, w, HEIGHT);
        g.setColor(accent);
        g.fillRect(x, top, 3, HEIGHT);
        g.setColor(PopupPaint.alpha(accent, 90));
        g.drawLine(x + 3, top + HEIGHT - 1, x + w - 1, top + HEIGHT - 1);

        int textX = x + 7;
        Integer iconId = PopupPaint.iconOf(toast);
        BufferedImage sprite = iconId != null ? sprites.apply(iconId) : null;
        if (sprite != null) {
            PopupPaint.drawSprite(g, sprite, textX, top + (HEIGHT - ICON) / 2, ICON);
        } else {
            PopupGlyphs.draw(g, card.getKind(), textX + 2, top + (HEIGHT - ICON) / 2 + 2, ICON - 4, accent);
        }
        textX += ICON + 4;

        FontMetrics fm = g.getFontMetrics(PopupPaint.small());
        g.setFont(PopupPaint.small());
        int baseline = top + (HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
        int right = x + w - 5;
        String value = card.getRightValue();
        if (toast.getMoreCount() > 0) {
            value = (value != null ? value + " " : "") + "+" + toast.getMoreCount();
        }
        if (value != null) {
            value = PopupPaint.ellipsize(value, fm, 70);
            PopupPaint.shadowedRight(g, value, right, baseline, PopupPaint.VALUE);
            right -= fm.stringWidth(value) + 6;
        }
        // "Tile complete: Bandos set" with the caption in the kind's colour.
        String caption = card.getCaption();
        if (!caption.endsWith("!") && !caption.endsWith(":")) {
            caption += ":";
        }
        int room = right - textX;
        if (fm.stringWidth(caption) + 30 < room) {
            PopupPaint.shadowed(g, caption, textX, baseline, accent);
            textX += fm.stringWidth(caption) + 4;
        }
        PopupPaint.shadowed(g, PopupPaint.ellipsize(card.getHeadline(), fm, right - textX),
            textX, baseline, PopupPaint.TEXT);

        g.setTransform(previousTransform);
        g.setComposite(previousComposite);
        return HEIGHT;
    }
}
