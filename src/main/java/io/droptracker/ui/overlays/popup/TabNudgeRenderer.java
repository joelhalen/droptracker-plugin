package io.droptracker.ui.overlays.popup;

import io.droptracker.models.EventPopupCard;
import io.droptracker.service.EventNotificationService.Toast;
import io.droptracker.ui.DropTrackerTheme;

import java.awt.Color;
import java.awt.Composite;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.function.IntFunction;

/**
 * A drawer that unrolls out of the bottom of the HUD: same bronze frame, no
 * gap, so the update reads as part of the HUD rather than a second window.
 * A rail in the kind's colour, the icon in a sunken slot, caption and the
 * key number on one line, the headline under it, and a thin progress bar
 * when the task has one.
 */
class TabNudgeRenderer implements PopupRenderer {
    private static final int PAD = 7;
    private static final int SLOT = 26;
    private static final int RAIL = 3;
    private static final long UNROLL_MS = 220;
    private static final long FADE_MS = 700;

    private static final Color EDGE_DARK = new Color(0x0e, 0x0b, 0x07);
    private static final Color BG = new Color(0x1c, 0x16, 0x0f, 242);
    private static final Color SLOT_BG = new Color(0x0e, 0x0b, 0x07, 220);

    private final IntFunction<BufferedImage> sprites;

    TabNudgeRenderer(IntFunction<BufferedImage> sprites) {
        this.sprites = sprites;
    }

    @Override
    public int preferredWidth() {
        return 200;
    }

    @Override
    public int gap() {
        return 0; // butts against the HUD (or the tab above) as one piece
    }

    @Override
    public int draw(Graphics2D g, Toast toast, int x, int top, int w, long now) {
        EventPopupCard card = PopupPaint.cardOf(toast);
        Color accent = PopupPaint.accent(card.getKind());
        long age = now - toast.getCreatedAt();
        FontMetrics smallFm = g.getFontMetrics(PopupPaint.small());
        FontMetrics boldFm = g.getFontMetrics(PopupPaint.bold());
        float fraction = card.fraction();

        int height = 5 + smallFm.getHeight() + boldFm.getHeight() + (fraction >= 0f ? 6 : 0) + 4;
        height = Math.max(height, SLOT + 10);
        int reveal = Math.max(1, Math.round(height * PopupPaint.easeOutCubic(
            PopupPaint.phase(age, 0, UNROLL_MS))));

        Composite previousComposite = g.getComposite();
        Shape previousClip = g.getClip();
        PopupPaint.setAlpha(g, PopupPaint.fadeOut(toast, now, FADE_MS));
        g.clipRect(x, top, w, reveal);

        /* ---- frame: continues the HUD's sides and closes the bottom ---- */
        g.setColor(BG);
        g.fillRect(x + 1, top, w - 2, height - 1);
        g.setColor(EDGE_DARK);
        g.drawLine(x, top, x, top + height - 1);
        g.drawLine(x + w - 1, top, x + w - 1, top + height - 1);
        g.drawLine(x, top + height - 1, x + w - 1, top + height - 1);
        g.setColor(DropTrackerTheme.BRONZE);
        g.drawLine(x + 1, top, x + 1, top + height - 2);
        g.drawLine(x + w - 2, top, x + w - 2, top + height - 2);
        g.drawLine(x + 1, top + height - 2, x + w - 2, top + height - 2);
        // Seam: a darker stitch where the tab meets whatever is above it.
        g.setColor(PopupPaint.alpha(EDGE_DARK, 160));
        g.drawLine(x + 2, top, x + w - 3, top);
        g.setColor(accent);
        g.fillRect(x + 2, top + 1, RAIL, height - 3);

        /* ---- icon slot ---- */
        int slotX = x + 2 + RAIL + 4;
        int slotY = top + (height - SLOT) / 2;
        Integer iconId = PopupPaint.iconOf(toast);
        BufferedImage sprite = iconId != null ? sprites.apply(iconId) : null;
        g.setColor(SLOT_BG);
        g.fillRect(slotX, slotY, SLOT, SLOT);
        g.setColor(PopupPaint.mix(DropTrackerTheme.BRONZE, EDGE_DARK, 0.3f));
        g.drawRect(slotX, slotY, SLOT - 1, SLOT - 1);
        if (sprite != null) {
            PopupPaint.drawSprite(g, sprite, slotX + 2, slotY + 2, SLOT - 4);
        } else {
            PopupGlyphs.draw(g, card.getKind(), slotX + 5, slotY + 5, SLOT - 10, accent);
        }
        int textX = slotX + SLOT + 6;
        int right = x + w - PAD - 1;
        int textWidth = right - textX;

        /* ---- caption + value ---- */
        String value = card.getRightValue();
        if (toast.getMoreCount() > 0) {
            value = (value != null ? value + " " : "") + "+" + toast.getMoreCount();
        }
        g.setFont(PopupPaint.small());
        int captionY = top + 5 + smallFm.getAscent();
        int valueWidth = 0;
        if (value != null) {
            String fitted = PopupPaint.ellipsize(value, smallFm, textWidth / 2);
            valueWidth = smallFm.stringWidth(fitted) + 6;
            PopupPaint.shadowedRight(g, fitted, right, captionY, PopupPaint.VALUE);
        }
        PopupPaint.shadowed(g, PopupPaint.ellipsize(card.getCaption(), smallFm, textWidth - valueWidth),
            textX, captionY, accent);

        /* ---- headline ---- */
        g.setFont(PopupPaint.bold());
        int headlineY = captionY + smallFm.getDescent() + boldFm.getAscent();
        PopupPaint.shadowed(g, PopupPaint.ellipsize(card.getHeadline(), boldFm, textWidth),
            textX, headlineY, PopupPaint.TEXT);

        /* ---- progress ---- */
        if (fraction >= 0f) {
            int barY = headlineY + boldFm.getDescent() + 2;
            g.setColor(EDGE_DARK);
            g.fillRect(textX, barY, textWidth, 4);
            float grow = PopupPaint.easeOutCubic(PopupPaint.phase(age, UNROLL_MS, 600));
            g.setColor(accent);
            g.fillRect(textX, barY, Math.round(textWidth * fraction * grow), 4);
        }

        g.setClip(previousClip);
        g.setComposite(previousComposite);
        return reveal;
    }
}
