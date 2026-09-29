package io.droptracker.ui.overlays.popup;

import io.droptracker.service.EventNotificationService.Toast;
import io.droptracker.ui.DropTrackerTheme;

import java.awt.Color;
import java.awt.Composite;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.function.IntFunction;
import java.util.List;

/** The original HUD nudge: a compact framed card hanging beneath the HUD. */
class ClassicNudgeRenderer implements PopupRenderer {
    private static final int PAD = 8;
    private static final int ICON = 20;
    /** Three cards of unbounded body used to be able to run off the screen. */
    private static final int MAX_BODY_LINES = 3;
    private static final long FADE_MS = 1000;

    private static final Color EDGE_DARK = new Color(0x0e, 0x0b, 0x07);
    private static final Color BG = new Color(0x15, 0x11, 0x0c, 242);

    private final IntFunction<BufferedImage> sprites;

    ClassicNudgeRenderer(IntFunction<BufferedImage> sprites) {
        this.sprites = sprites;
    }

    @Override
    public int preferredWidth() {
        return 200;
    }

    @Override
    public int gap() {
        return 4;
    }

    @Override
    public int draw(Graphics2D g, Toast toast, int x, int top, int width, long now) {
        Color accent = accentFor(toast);
        FontMetrics titleFm = g.getFontMetrics(PopupPaint.bold());
        FontMetrics smallFm = g.getFontMetrics(PopupPaint.small());

        int textLeft = PAD;
        Integer iconId = PopupPaint.iconOf(toast);
        BufferedImage icon = iconId != null ? sprites.apply(iconId) : null;
        if (icon != null) {
            textLeft += ICON + 6;
        }
        int textWidth = width - textLeft - PAD;
        List<String> bodyLines = PopupPaint.wrap(toast.getBody(), smallFm, textWidth, MAX_BODY_LINES);
        int height = 6 + titleFm.getHeight() + bodyLines.size() * smallFm.getHeight() + 6;
        if (icon != null) {
            height = Math.max(height, ICON + 12);
        }

        Composite previous = g.getComposite();
        PopupPaint.setAlpha(g, PopupPaint.fadeOut(toast, now, FADE_MS));

        g.setColor(BG);
        g.fillRect(x + 1, top + 1, width - 2, height - 2);
        g.setColor(EDGE_DARK);
        g.drawRect(x, top, width - 1, height - 1);
        g.setColor(accent);
        g.drawRect(x + 1, top + 1, width - 3, height - 3);

        if (icon != null) {
            g.drawImage(icon, x + PAD, top + (height - ICON) / 2, ICON, ICON, null);
        }
        g.setFont(PopupPaint.bold());
        int titleY = top + 6 + titleFm.getAscent();
        PopupPaint.shadowed(g, PopupPaint.ellipsize(toast.getTitle(), titleFm, textWidth),
            x + textLeft, titleY, accent);
        g.setFont(PopupPaint.small());
        int lineY = titleY + smallFm.getHeight();
        for (String line : bodyLines) {
            PopupPaint.shadowed(g, line, x + textLeft, lineY, DropTrackerTheme.TEXT);
            lineY += smallFm.getHeight();
        }

        g.setComposite(previous);
        return height;
    }

    /** By importance tier: the tile-finishing drop and the 50-KC tick must not look alike. */
    private static Color accentFor(Toast toast) {
        switch (toast.getPriority()) {
            case HIGH:
                return DropTrackerTheme.GOLD_BRIGHT;
            case LOW:
                return DropTrackerTheme.STONE;
            default:
                return DropTrackerTheme.BRONZE;
        }
    }
}
