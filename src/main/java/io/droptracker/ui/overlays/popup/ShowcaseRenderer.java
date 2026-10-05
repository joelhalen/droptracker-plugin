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
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * The showcase panel, after the Collection Log Popup Enhanced plugin's
 * layout: a small orange caption, a big headline, a progress line, up to
 * four "label / value" stats in the corners and the icon in a framed box
 * hanging off the bottom edge between them. The panel folds open, the icon
 * pops in after it, and big moments get one light sweep across the face.
 * Each kind of news gets its own tinted panel and frame, like rarity tiers.
 */
class ShowcaseRenderer implements PopupRenderer {
    static final int WIDTH = 330;
    private static final int PAD = 12;
    private static final int BOX = 54;

    private static final long FOLD_MS = 320;
    private static final long ICON_DELAY_MS = 220;
    private static final long ICON_POP_MS = 340;
    private static final long BAR_DELAY_MS = 260;
    private static final long BAR_FILL_MS = 700;
    private static final long SWEEP_DELAY_MS = 380;
    private static final long SWEEP_MS = 900;
    private static final long FADE_MS = 600;

    private final PopupIcons icons;

    ShowcaseRenderer(PopupIcons icons) {
        this.icons = icons;
    }

    @Override
    public int preferredWidth() {
        return WIDTH;
    }

    @Override
    public int gap() {
        return 8;
    }

    @Override
    public int maxVisible() {
        return 2;
    }

    @Override
    public int draw(Graphics2D g, Toast toast, int x, int top, int w, long now) {
        EventPopupCard card = PopupPaint.cardOf(toast);
        long age = now - toast.getCreatedAt();
        Color accent = PopupPaint.accent(card.getKind());

        /* ---- fonts and metrics ---- */
        Font captionFont = PopupPaint.bold();
        Font labelFont = PopupPaint.small();
        Font valueFont = PopupPaint.bold();
        Font detailFont = PopupPaint.small();
        FontMetrics captionFm = g.getFontMetrics(captionFont);
        FontMetrics labelFm = g.getFontMetrics(labelFont);
        FontMetrics valueFm = g.getFontMetrics(valueFont);
        FontMetrics detailFm = g.getFontMetrics(detailFont);

        int innerWidth = w - PAD * 2;
        String moreText = toast.getMoreCount() > 0 ? "+" + toast.getMoreCount() + " more" : null;
        String note = card.getNote();
        // The caption is centred; keep it clear of whichever corner is wider.
        int corner = Math.max(moreText != null ? detailFm.stringWidth(moreText) : 0,
            note != null ? detailFm.stringWidth(note) : 0);
        int captionRoom = innerWidth - (corner > 0 ? 2 * (corner + 6) : 0);
        String caption = PopupPaint.ellipsize(card.getCaption(), captionFm, captionRoom);
        Font headlineFont = PopupPaint.fitBold(g, card.getHeadline(), innerWidth, 25f, 16f);
        FontMetrics headlineFm = g.getFontMetrics(headlineFont);
        String headline = PopupPaint.ellipsize(card.getHeadline(), headlineFm, innerWidth);
        List<String> detail = card.getDetail() != null
            ? PopupPaint.wrap(card.getDetail(), detailFm, innerWidth,
                card.getKind() == EventPopupCard.Kind.DIGEST ? 3 : 1)
            : Collections.emptyList();

        BufferedImage sprite = icons.forToast(toast);
        boolean stats = card.hasStats();
        boolean secondRow = card.hasSecondRow();
        int statWidth = (w - BOX) / 2 - PAD - 6;

        /* ---- measure ---- */
        int captionY = top + 6 + captionFm.getAscent();
        int headlineY = captionY + captionFm.getDescent() + 1 + headlineFm.getAscent();
        int y = headlineY + headlineFm.getDescent();
        int detailY = y + detailFm.getAscent();
        y += detail.size() * detailFm.getHeight();
        int barY = y + 6;
        int barH = 3;
        y = barY + barH;
        int rowH = labelFm.getAscent() + labelFm.getDescent() + valueFm.getAscent() + valueFm.getDescent();
        int statsTop = y + 6;
        if (stats) {
            y = statsTop + rowH + (secondRow ? 4 + rowH : 0);
        }
        int panelBottom = y + 8;
        int panelH = panelBottom - top;
        // Hang the icon off the bottom edge, but never over the bar.
        int boxTop = Math.max(barY + barH + 5, panelBottom - BOX + BOX * 2 / 5);
        int total = Math.max(panelH, boxTop + BOX - top);

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
        g.setFont(detailFont);
        int cornerY = top + 7 + detailFm.getAscent();
        if (note != null) {
            PopupPaint.shadowed(g, note, x + PAD - 3, cornerY, PopupPaint.TEXT_MUTED);
        }
        if (moreText != null) {
            PopupPaint.shadowedRight(g, moreText, x + w - PAD + 3, cornerY, accent);
        }
        g.setFont(headlineFont);
        PopupPaint.shadowedCentered(g, headline, x + w / 2, headlineY,
            PopupPaint.mix(accent, Color.WHITE, 0.12f));
        g.setFont(detailFont);
        int lineY = detailY;
        for (String line : detail) {
            PopupPaint.shadowedCentered(g, line, x + w / 2, lineY, PopupPaint.TEXT_MUTED);
            lineY += detailFm.getHeight();
        }

        paintBar(g, card, x, w, barY, barH, accent, age);

        if (stats) {
            int row1 = statsTop;
            int row2 = statsTop + rowH + 4;
            paintStat(g, card.getLeftLabel(), card.getLeftValue(), x + PAD, row1, statWidth,
                false, labelFont, valueFont);
            paintStat(g, card.getRightLabel(), card.getRightValue(), x + w - PAD, row1, statWidth,
                true, labelFont, valueFont);
            paintStat(g, card.getLeft2Label(), card.getLeft2Value(), x + PAD, row2, statWidth,
                false, labelFont, valueFont);
            paintStat(g, card.getRight2Label(), card.getRight2Value(), x + w - PAD, row2, statWidth,
                true, labelFont, valueFont);
        }

        if (toast.getPriority() == EventNotification.Priority.HIGH) {
            paintSweep(g, x, top, w, panelH, age);
        }
        g.setTransform(previousTransform);
        g.setClip(previousClip);

        /* ---- icon: pops in once the panel is open ---- */
        float t = PopupPaint.phase(age, ICON_DELAY_MS, ICON_POP_MS);
        if (t > 0f) {
            float scale = 0.4f + 0.6f * PopupPaint.easeOutBack(t, 1.9f);
            PopupPaint.setAlpha(g, fade * Math.min(1f, t * 2f));
            int boxX = x + (w - BOX) / 2;
            double cx = boxX + BOX / 2.0;
            double cy = boxTop + BOX / 2.0;
            g.translate(cx, cy);
            g.scale(scale, scale);
            g.translate(-cx, -cy);
            paintIconBox(g, boxX, boxTop, BOX, accent);
            if (sprite != null) {
                PopupPaint.drawSprite(g, sprite, boxX + 6, boxTop + 6, BOX - 12);
            } else {
                // No item or task icon: the kind's emblem, so the box never sits empty.
                int glyph = BOX * 11 / 20;
                PopupGlyphs.draw(g, card.getKind(), boxX + (BOX - glyph) / 2, boxTop + (BOX - glyph) / 2,
                    glyph, accent);
            }
            g.setTransform(previousTransform);
        }

        g.setComposite(previousComposite);
        return total;
    }

    /** One "label / value" corner; {@code right} anchors it to {@code anchorX} from the right. */
    private static void paintStat(Graphics2D g, @Nullable String label, @Nullable String value,
                                  int anchorX, int rowTop, int width, boolean right,
                                  Font labelFont, Font valueFont) {
        if (value == null) {
            return;
        }
        FontMetrics labelFm = g.getFontMetrics(labelFont);
        FontMetrics valueFm = g.getFontMetrics(valueFont);
        int labelY = rowTop + labelFm.getAscent();
        int valueY = labelY + labelFm.getDescent() + valueFm.getAscent();
        g.setFont(labelFont);
        String l = PopupPaint.ellipsize(label, labelFm, width);
        g.setFont(valueFont);
        String v = PopupPaint.ellipsize(value, valueFm, width);
        g.setFont(labelFont);
        if (right) {
            PopupPaint.shadowedRight(g, l, anchorX, labelY, PopupPaint.TEXT_MUTED);
        } else {
            PopupPaint.shadowed(g, l, anchorX, labelY, PopupPaint.TEXT_MUTED);
        }
        g.setFont(valueFont);
        if (right) {
            PopupPaint.shadowedRight(g, v, anchorX, valueY, PopupPaint.VALUE);
        } else {
            PopupPaint.shadowed(g, v, anchorX, valueY, PopupPaint.VALUE);
        }
    }

    /* ---------------- pieces ---------------- */

    private void paintPanel(Graphics2D g, int x, int y, int w, int h, Color accent) {
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
        Color fill = accent;
        g.setColor(PopupPaint.mix(accent, PopupPaint.INK, 0.65f));
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
}
