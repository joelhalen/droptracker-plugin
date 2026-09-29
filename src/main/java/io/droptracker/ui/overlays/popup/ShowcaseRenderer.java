package io.droptracker.ui.overlays.popup;

import io.droptracker.models.EventPopupCard;
import io.droptracker.models.api.EventNotification;
import io.droptracker.service.EventNotificationService.Toast;

import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.function.IntFunction;
import java.util.Collections;
import java.util.List;

/**
 * The showcase panel, after the Collection Log Popup Enhanced plugin's
 * layout: a small orange caption, a big headline, a progress line, two
 * "label / value" stat corners and the item in a framed box hanging off the
 * bottom edge. The panel folds open, the icon pops in after it, and big
 * moments get one light sweep across the face.
 *
 * <p>Two skins share the geometry. {@code stone} paints the game's brown
 * interface panel and keeps colour to the caption; otherwise each kind of
 * news gets its own tinted panel and frame, like rarity tiers. The same
 * class draws the HUD-width "mini" variant: sizes step down with the width.
 */
class ShowcaseRenderer implements PopupRenderer {
    private static final long FOLD_MS = 320;
    private static final long ICON_DELAY_MS = 220;
    private static final long ICON_POP_MS = 340;
    private static final long BAR_DELAY_MS = 260;
    private static final long BAR_FILL_MS = 700;
    private static final long SWEEP_DELAY_MS = 380;
    private static final long SWEEP_MS = 900;
    private static final long FADE_MS = 600;

    /* Stone skin. */
    private static final Color STONE_TOP = new Color(0x4a, 0x40, 0x31);
    private static final Color STONE_BOTTOM = new Color(0x38, 0x30, 0x24);
    private static final Color STONE_FRAME = new Color(0x62, 0x55, 0x43);
    private static final Color STONE_LIGHT = new Color(0x7d, 0x6d, 0x55);
    private static final Color STONE_DARK = new Color(0x24, 0x1e, 0x16);

    private final IntFunction<BufferedImage> sprites;
    private final boolean stone;
    private final int width;

    ShowcaseRenderer(IntFunction<BufferedImage> sprites, boolean stone, int width) {
        this.sprites = sprites;
        this.stone = stone;
        this.width = width;
    }

    @Override
    public int preferredWidth() {
        return width;
    }

    @Override
    public int gap() {
        return mini() ? 5 : 8;
    }

    @Override
    public int maxVisible() {
        return 2;
    }

    private boolean mini() {
        return width < 260;
    }

    @Override
    public int draw(Graphics2D g, Toast toast, int x, int top, int w, long now) {
        EventPopupCard card = PopupPaint.cardOf(toast);
        boolean mini = w < 260;
        long age = now - toast.getCreatedAt();
        Color accent = PopupPaint.accent(card.getKind());

        /* ---- fonts and metrics ---- */
        int pad = mini ? 7 : 12;
        Font captionFont = mini ? PopupPaint.small() : PopupPaint.bold();
        Font labelFont = mini ? PopupPaint.small() : PopupPaint.plain();
        Font valueFont = mini ? PopupPaint.plain() : PopupPaint.bold();
        Font detailFont = PopupPaint.small();
        FontMetrics captionFm = g.getFontMetrics(captionFont);
        FontMetrics labelFm = g.getFontMetrics(labelFont);
        FontMetrics valueFm = g.getFontMetrics(valueFont);
        FontMetrics detailFm = g.getFontMetrics(detailFont);

        int innerWidth = w - pad * 2;
        String moreText = toast.getMoreCount() > 0 ? "+" + toast.getMoreCount() + " more" : null;
        // The caption is centred; keep it clear of the "+N more" badge.
        int captionRoom = innerWidth - (moreText != null ? 2 * (detailFm.stringWidth(moreText) + 4) : 0);
        String caption = PopupPaint.ellipsize(card.getCaption(), captionFm, captionRoom);
        Font headlineFont = PopupPaint.fitBold(g, card.getHeadline(), innerWidth,
            mini ? 17f : 25f, mini ? 13f : 16f);
        FontMetrics headlineFm = g.getFontMetrics(headlineFont);
        String headline = PopupPaint.ellipsize(card.getHeadline(), headlineFm, innerWidth);
        List<String> detail = card.getDetail() != null
            ? PopupPaint.wrap(card.getDetail(), detailFm, innerWidth,
                card.getKind() == EventPopupCard.Kind.DIGEST ? 3 : 1)
            : Collections.emptyList();

        Integer iconId = PopupPaint.iconOf(toast);
        BufferedImage sprite = iconId != null ? sprites.apply(iconId) : null;
        // Always a box: kinds without an item show their emblem in it.
        int box = mini ? 36 : 54;
        boolean stats = card.getLeftValue() != null || card.getRightValue() != null;
        int statWidth = (w - box) / 2 - pad - 4;

        /* ---- measure ---- */
        int captionY = top + (mini ? 4 : 6) + captionFm.getAscent();
        int headlineY = captionY + captionFm.getDescent() + (mini ? 0 : 1) + headlineFm.getAscent();
        int y = headlineY + headlineFm.getDescent();
        int detailY = y + detailFm.getAscent();
        y += detail.size() * detailFm.getHeight();
        int barY = y + (mini ? 4 : 6);
        int barH = mini ? 2 : 3;
        y = barY + barH;
        int labelY = 0;
        int valueY = 0;
        if (stats) {
            labelY = y + (mini ? 4 : 7) + labelFm.getAscent();
            valueY = labelY + labelFm.getDescent() + valueFm.getAscent();
            y = valueY + valueFm.getDescent();
        }
        int panelBottom = y + (mini ? 5 : 8);
        int panelH = panelBottom - top;
        int boxTop = 0;
        int total = panelH;
        if (box > 0) {
            // Hang the icon off the bottom edge, but never over the bar.
            boxTop = Math.max(barY + barH + 5, panelBottom - box + box * 2 / 5);
            total = Math.max(panelH, boxTop + box - top);
        }

        Composite previousComposite = g.getComposite();
        AffineTransform previousTransform = g.getTransform();
        Shape previousClip = g.getClip();
        float fade = PopupPaint.fadeOut(toast, now, FADE_MS);

        /* ---- panel: folds open around its middle ---- */
        float fold = PopupPaint.easeOutBack(PopupPaint.phase(age, 0, FOLD_MS), 1.2f);
        float foldIn = PopupPaint.phase(age, 0, FOLD_MS / 2);
        PopupPaint.setAlpha(g, fade * foldIn);
        int midY = top + panelH / 2;
        g.translate(0, midY);
        g.scale(1.0, Math.max(fold, 0.01f));
        g.translate(0, -midY);

        paintPanel(g, x, top, w, panelH, accent);

        g.setFont(captionFont);
        PopupPaint.shadowedCentered(g, caption, x + w / 2, captionY, PopupPaint.OSRS_ORANGE);
        if (moreText != null) {
            g.setFont(detailFont);
            PopupPaint.shadowedRight(g, moreText, x + w - pad, top + (mini ? 4 : 6) + detailFm.getAscent(),
                stone ? PopupPaint.TEXT_MUTED : accent);
        }
        g.setFont(headlineFont);
        PopupPaint.shadowedCentered(g, headline, x + w / 2, headlineY, headlineColor(accent, toast));
        g.setFont(detailFont);
        int lineY = detailY;
        for (String line : detail) {
            PopupPaint.shadowedCentered(g, line, x + w / 2, lineY, PopupPaint.TEXT_MUTED);
            lineY += detailFm.getHeight();
        }

        paintBar(g, card, x, w, barY, barH, accent, age);

        if (stats) {
            if (card.getLeftValue() != null) {
                g.setFont(labelFont);
                PopupPaint.shadowed(g, PopupPaint.ellipsize(card.getLeftLabel(), labelFm, statWidth),
                    x + pad, labelY, PopupPaint.TEXT);
                g.setFont(valueFont);
                PopupPaint.shadowed(g, PopupPaint.ellipsize(card.getLeftValue(), valueFm, statWidth),
                    x + pad, valueY, PopupPaint.VALUE);
            }
            if (card.getRightValue() != null) {
                g.setFont(labelFont);
                PopupPaint.shadowedRight(g, PopupPaint.ellipsize(card.getRightLabel(), labelFm, statWidth),
                    x + w - pad, labelY, PopupPaint.TEXT);
                g.setFont(valueFont);
                PopupPaint.shadowedRight(g, PopupPaint.ellipsize(card.getRightValue(), valueFm, statWidth),
                    x + w - pad, valueY, PopupPaint.VALUE);
            }
        }

        if (toast.getPriority() == EventNotification.Priority.HIGH) {
            paintSweep(g, x, top, w, panelH, age);
        }
        g.setTransform(previousTransform);
        g.setClip(previousClip);

        /* ---- icon: pops in once the panel is open ---- */
        if (box > 0) {
            float t = PopupPaint.phase(age, ICON_DELAY_MS, ICON_POP_MS);
            if (t > 0f) {
                float scale = 0.4f + 0.6f * PopupPaint.easeOutBack(t, 1.9f);
                PopupPaint.setAlpha(g, fade * Math.min(1f, t * 2f));
                int boxX = x + (w - box) / 2;
                double cx = boxX + box / 2.0;
                double cy = boxTop + box / 2.0;
                g.translate(cx, cy);
                g.scale(scale, scale);
                g.translate(-cx, -cy);
                paintIconBox(g, boxX, boxTop, box, accent);
                if (sprite != null) {
                    int inset = mini ? 4 : 5;
                    PopupPaint.drawSprite(g, sprite, boxX + inset, boxTop + inset, box - inset * 2);
                } else {
                    int glyph = box * 11 / 20;
                    PopupGlyphs.draw(g, card.getKind(), boxX + (box - glyph) / 2, boxTop + (box - glyph) / 2,
                        glyph, stone ? PopupPaint.OSRS_ORANGE : accent);
                }
                g.setTransform(previousTransform);
            }
        }

        g.setComposite(previousComposite);
        return total;
    }

    /* ---------------- pieces ---------------- */

    private void paintPanel(Graphics2D g, int x, int y, int w, int h, Color accent) {
        if (stone) {
            g.setPaint(new GradientPaint(0, y, STONE_TOP, 0, y + h, STONE_BOTTOM));
            g.fillRect(x, y, w, h);
            g.setColor(PopupPaint.INK);
            g.drawRect(x, y, w - 1, h - 1);
            g.setColor(STONE_FRAME);
            g.drawRect(x + 1, y + 1, w - 3, h - 3);
            g.drawRect(x + 2, y + 2, w - 5, h - 5);
            // Bevel: light top-left, dark bottom-right, as game panels have.
            g.setColor(STONE_LIGHT);
            g.drawLine(x + 1, y + 1, x + w - 2, y + 1);
            g.drawLine(x + 1, y + 1, x + 1, y + h - 2);
            g.setColor(STONE_DARK);
            g.drawRect(x + 3, y + 3, w - 7, h - 7);
            paintCorners(g, x + 1, y + 1, w - 2, h - 2, 3, STONE_LIGHT);
            return;
        }
        Color face = PopupPaint.mix(accent, PopupPaint.INK, 0.80f);
        Color faceLow = PopupPaint.mix(accent, PopupPaint.INK, 0.87f);
        g.setPaint(new GradientPaint(0, y, PopupPaint.alpha(face, 242), 0, y + h,
            PopupPaint.alpha(faceLow, 242)));
        g.fillRect(x, y, w, h);
        g.setColor(PopupPaint.INK);
        g.drawRect(x, y, w - 1, h - 1);
        g.setColor(accent);
        g.drawRect(x + 1, y + 1, w - 3, h - 3);
        g.drawRect(x + 2, y + 2, w - 5, h - 5);
        g.setColor(PopupPaint.mix(accent, PopupPaint.INK, 0.55f));
        g.drawRect(x + 3, y + 3, w - 7, h - 7);
    }

    /** The progress line: task progress when the card has it, else a plain rule. */
    private void paintBar(Graphics2D g, EventPopupCard card, int x, int w, int y, int h,
                          Color accent, long age) {
        int inset = Math.round(w * 0.06f);
        int left = x + inset;
        int barW = w - inset * 2;
        Color fill = stone ? PopupPaint.TEXT : accent;
        g.setColor(stone ? STONE_DARK : PopupPaint.mix(accent, PopupPaint.INK, 0.65f));
        g.fillRect(left, y, barW, h);
        float fraction = card.fraction();
        if (fraction < 0f) {
            fraction = 1f; // no progress to show: a full rule, drawn in
        }
        float grow = PopupPaint.easeOutCubic(PopupPaint.phase(age, BAR_DELAY_MS, BAR_FILL_MS));
        int filled = Math.round(barW * fraction * grow);
        g.setColor(card.fraction() < 0f ? PopupPaint.alpha(fill, 150) : fill);
        g.fillRect(left, y, filled, h);
    }

    private void paintIconBox(Graphics2D g, int x, int y, int box, Color accent) {
        if (stone) {
            g.setColor(STONE_BOTTOM);
            g.fillRect(x, y, box, box);
            g.setColor(PopupPaint.INK);
            g.drawRect(x, y, box - 1, box - 1);
            g.setColor(STONE_FRAME);
            g.drawRect(x + 1, y + 1, box - 3, box - 3);
            g.drawRect(x + 2, y + 2, box - 5, box - 5);
            g.setColor(STONE_DARK);
            g.drawRect(x + 3, y + 3, box - 7, box - 7);
            return;
        }
        g.setColor(PopupPaint.mix(accent, PopupPaint.INK, 0.84f));
        g.fillRect(x, y, box, box);
        g.setColor(PopupPaint.INK);
        g.drawRect(x, y, box - 1, box - 1);
        g.setColor(accent);
        g.drawRect(x + 1, y + 1, box - 3, box - 3);
        g.drawRect(x + 2, y + 2, box - 5, box - 5);
        paintCorners(g, x, y, box, box, box >= 44 ? 6 : 4, accent);
    }

    /** Stepped corner ornaments, drawn inward from each corner. */
    private static void paintCorners(Graphics2D g, int x, int y, int w, int h, int size, Color color) {
        g.setColor(color);
        int s = size;
        g.fillRect(x + 3, y + 3, s, 2);
        g.fillRect(x + 3, y + 3, 2, s);
        g.fillRect(x + w - 3 - s, y + 3, s, 2);
        g.fillRect(x + w - 5, y + 3, 2, s);
        g.fillRect(x + 3, y + h - 5, s, 2);
        g.fillRect(x + 3, y + h - 3 - s, 2, s);
        g.fillRect(x + w - 3 - s, y + h - 5, s, 2);
        g.fillRect(x + w - 5, y + h - 3 - s, 2, s);
    }

    /** One soft light band across the face, for the moments worth a second look. */
    private static void paintSweep(Graphics2D g, int x, int y, int w, int h, long age) {
        float t = PopupPaint.phase(age, SWEEP_DELAY_MS, SWEEP_MS);
        if (t <= 0f || t >= 1f) {
            return;
        }
        int band = 36;
        int bx = x - band * 2 + Math.round((w + band * 3) * t);
        g.clipRect(x + 3, y + 3, w - 6, h - 6);
        Polygon shape = new Polygon(
            new int[]{bx, bx + band, bx + band - h / 2, bx - h / 2},
            new int[]{y, y, y + h, y + h}, 4);
        g.setColor(new Color(255, 255, 255, 38));
        g.fill(shape);
    }

    private Color headlineColor(Color accent, Toast toast) {
        if (stone) {
            return toast.getPriority() == EventNotification.Priority.HIGH
                ? new Color(0xff, 0xf2, 0xc4) : Color.WHITE;
        }
        return PopupPaint.mix(accent, Color.WHITE, 0.12f);
    }
}
