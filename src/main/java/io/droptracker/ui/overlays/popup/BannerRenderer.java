package io.droptracker.ui.overlays.popup;

import io.droptracker.models.EventPopupCard;
import io.droptracker.service.EventNotificationService.Toast;

import java.awt.Color;
import java.awt.Composite;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * A slim banner: the icon on a colour block at the left, caption (and who
 * did it) over the headline, the one number that matters on the right, and a thin line along
 * the bottom that shows task progress or, failing that, the time left on
 * screen. Slides down into place and fades out. The least screen for the most
 * information, for players who want pop-ups but not panels.
 */
class BannerRenderer implements PopupRenderer {
    private static final int WIDTH = 340;
    private static final int HEIGHT = 40;
    private static final int BLOCK = 40;
    private static final long SLIDE_MS = 240;
    private static final long FADE_MS = 500;

    private final PopupIcons icons;

    BannerRenderer(PopupIcons icons) {
        this.icons = icons;
    }

    @Override
    public int preferredWidth() {
        return WIDTH;
    }

    @Override
    public int gap() {
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
        g.translate(0, Math.round((slide - 1f) * HEIGHT * 0.6f));

        /* ---- body ---- */
        g.setPaint(new GradientPaint(x, 0, PopupPaint.alpha(PopupPaint.mix(accent, PopupPaint.INK, 0.72f), 240),
            x + w, 0, PopupPaint.alpha(PopupPaint.INK, 225)));
        g.fillRect(x, top, w, HEIGHT);
        g.setColor(PopupPaint.INK);
        g.drawRect(x, top, w - 1, HEIGHT - 1);
        g.setColor(accent);
        g.drawLine(x + 1, top + 1, x + w - 2, top + 1);

        /* ---- icon block ---- */
        g.setColor(PopupPaint.mix(accent, PopupPaint.INK, 0.55f));
        g.fillRect(x + 1, top + 2, BLOCK - 1, HEIGHT - 3);
        g.setColor(accent);
        g.drawLine(x + BLOCK, top + 2, x + BLOCK, top + HEIGHT - 2);
        BufferedImage sprite = icons.forToast(toast);
        if (sprite != null) {
            PopupPaint.drawSprite(g, sprite, x + 4, top + 5, BLOCK - 8);
        } else {
            // No item: the kind's emblem, so the block never sits empty.
            PopupGlyphs.draw(g, card.getKind(), x + 9, top + 9, BLOCK - 18,
                PopupPaint.mix(accent, Color.WHITE, 0.25f));
        }

        /* ---- right column ---- */
        FontMetrics smallFm = g.getFontMetrics(PopupPaint.small());
        FontMetrics boldFm = g.getFontMetrics(PopupPaint.bold());
        int right = x + w - 8;
        String value = card.getRightValue();
        String label = card.getRightLabel() != null ? trimColon(card.getRightLabel()) : null;
        if (value == null && toast.getMoreCount() > 0) {
            value = "+" + toast.getMoreCount();
            label = "more";
        } else if (toast.getMoreCount() > 0) {
            label = (label != null ? label + "  " : "") + "+" + toast.getMoreCount() + " more";
        }
        int valueWidth = value != null ? Math.min(boldFm.stringWidth(value), 110) : 0;
        int labelWidth = label != null ? Math.min(smallFm.stringWidth(label), 110) : 0;
        int column = Math.max(valueWidth, labelWidth);

        /* ---- text ---- */
        int textX = x + BLOCK + 8;
        int textWidth = right - (column > 0 ? column + 10 : 0) - textX;
        g.setFont(PopupPaint.small());
        int captionY = top + 4 + smallFm.getAscent();
        String captionText = PopupPaint.ellipsize(card.getCaption().toUpperCase(Locale.ROOT),
            smallFm, textWidth);
        PopupPaint.shadowed(g, captionText, textX, captionY, accent);
        // Who did it, after the caption when it fits: "TASK COMPLETE - Zezima".
        String who = card.getLeftValue();
        int whoX = textX + smallFm.stringWidth(captionText) + 6;
        int whoRoom = textX + textWidth - whoX;
        if (who != null && whoRoom > smallFm.stringWidth("- ") + 20) {
            PopupPaint.shadowed(g, PopupPaint.ellipsize("- " + who, smallFm, whoRoom), whoX, captionY,
                PopupPaint.TEXT_MUTED);
        }
        g.setFont(PopupPaint.bold());
        int headlineY = top + HEIGHT - 8 - boldFm.getDescent();
        PopupPaint.shadowed(g, PopupPaint.ellipsize(card.getHeadline(), boldFm, textWidth),
            textX, headlineY, PopupPaint.TEXT);

        if (label != null) {
            g.setFont(PopupPaint.small());
            PopupPaint.shadowedRight(g, PopupPaint.ellipsize(label, smallFm, 110), right, captionY,
                PopupPaint.TEXT_MUTED);
        }
        if (value != null) {
            g.setFont(PopupPaint.bold());
            PopupPaint.shadowedRight(g, PopupPaint.ellipsize(value, boldFm, 110), right, headlineY,
                PopupPaint.VALUE);
        }

        /* ---- bottom line: progress, else time left ---- */
        int lineX = x + BLOCK + 1;
        int lineW = w - BLOCK - 2;
        int lineY = top + HEIGHT - 3;
        g.setColor(PopupPaint.alpha(PopupPaint.INK, 200));
        g.fillRect(lineX, lineY, lineW, 2);
        float fraction = card.fraction();
        if (fraction >= 0f) {
            float grow = PopupPaint.easeOutCubic(PopupPaint.phase(age, 150, 600));
            g.setColor(accent);
            g.fillRect(lineX, lineY, Math.round(lineW * fraction * grow), 2);
        } else {
            float left = PopupPaint.clamp01(toast.remainingMs(now) / (float) toast.lifetimeMs());
            g.setColor(PopupPaint.alpha(accent, 150));
            g.fillRect(lineX, lineY, Math.round(lineW * left), 2);
        }

        g.setTransform(previousTransform);
        g.setComposite(previousComposite);
        return HEIGHT;
    }

    private static String trimColon(String label) {
        return label.endsWith(":") ? label.substring(0, label.length() - 1) : label;
    }
}
