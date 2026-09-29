package io.droptracker.ui.overlays.popup;

import io.droptracker.models.api.EventNotification;
import io.droptracker.service.EventNotificationService.Toast;

import java.awt.Color;
import java.awt.Composite;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.function.IntFunction;
import java.util.List;

/**
 * The original stand-alone pop-up: a small rounded card, title over up to
 * {@value #MAX_BODY_LINES} lines of body text, framed by importance tier.
 */
class ClassicCardRenderer implements PopupRenderer {
    /** Ceiling on card height: three cards must never eat the top of the screen. */
    private static final int MAX_BODY_LINES = 3;
    private static final int WIDTH = 280;
    private static final int PADDING = 8;
    private static final int ICON_SIZE = 24;
    private static final int RAIL_WIDTH = 3;
    private static final long FADE_MS = 1000;

    private static final Color BACKGROUND = new Color(0x15, 0x11, 0x0c, 230);
    private static final Color BORDER = new Color(0x7a, 0x5a, 0x32, 255);
    private static final Color BORDER_HIGH = new Color(0xff, 0xd9, 0x66, 255);
    private static final Color BORDER_LOW = new Color(0x5a, 0x5a, 0x52, 255);
    private static final Color TITLE = new Color(0xff, 0xb8, 0x3f);
    private static final Color TITLE_HIGH = new Color(0xff, 0xd9, 0x66);
    private static final Color TITLE_LOW = new Color(0xd8, 0xc9, 0xa3);
    private static final Color BODY = new Color(0xef, 0xe6, 0xd2);

    private final IntFunction<BufferedImage> sprites;

    ClassicCardRenderer(IntFunction<BufferedImage> sprites) {
        this.sprites = sprites;
    }

    @Override
    public int preferredWidth() {
        return WIDTH;
    }

    @Override
    public int draw(Graphics2D g, Toast toast, int x, int top, int width, long now) {
        boolean important = toast.getPriority() == EventNotification.Priority.HIGH;
        FontMetrics titleFm = g.getFontMetrics(PopupPaint.bold());
        FontMetrics bodyFm = g.getFontMetrics(PopupPaint.small());

        int textLeft = PADDING + (important ? RAIL_WIDTH : 0);
        Integer iconId = PopupPaint.iconOf(toast);
        BufferedImage icon = iconId != null ? sprites.apply(iconId) : null;
        if (icon != null) {
            textLeft += ICON_SIZE + PADDING;
        }
        int textWidth = width - textLeft - PADDING;
        List<String> bodyLines = PopupPaint.wrap(toast.getBody(), bodyFm, textWidth, MAX_BODY_LINES);
        int height = PADDING + titleFm.getHeight() + bodyLines.size() * bodyFm.getHeight() + PADDING;
        height = Math.max(height, icon != null ? ICON_SIZE + 2 * PADDING : 0);

        Composite previous = g.getComposite();
        PopupPaint.setAlpha(g, PopupPaint.fadeOut(toast, now, FADE_MS));

        g.setColor(BACKGROUND);
        g.fillRoundRect(x, top, width, height, 8, 8);
        g.setColor(borderFor(toast));
        g.drawRoundRect(x, top, width - 1, height - 1, 8, 8);
        if (important) {
            // Accent rail: the one card you should look at reads as such even
            // out of the corner of your eye.
            g.fillRect(x + 1, top + 2, RAIL_WIDTH, height - 4);
        }
        if (icon != null) {
            g.drawImage(icon, x + PADDING + (important ? RAIL_WIDTH : 0),
                top + (height - ICON_SIZE) / 2, ICON_SIZE, ICON_SIZE, null);
        }

        int textY = top + PADDING + titleFm.getAscent();
        g.setFont(PopupPaint.bold());
        g.setColor(titleFor(toast));
        g.drawString(PopupPaint.ellipsize(toast.getTitle(), titleFm, textWidth), x + textLeft, textY);

        g.setFont(PopupPaint.small());
        g.setColor(BODY);
        int lineY = textY + bodyFm.getHeight();
        for (String line : bodyLines) {
            g.drawString(line, x + textLeft, lineY);
            lineY += bodyFm.getHeight();
        }

        g.setComposite(previous);
        return height;
    }

    private static Color borderFor(Toast toast) {
        switch (toast.getPriority()) {
            case HIGH:
                return BORDER_HIGH;
            case LOW:
                return BORDER_LOW;
            default:
                return BORDER;
        }
    }

    private static Color titleFor(Toast toast) {
        switch (toast.getPriority()) {
            case HIGH:
                return TITLE_HIGH;
            case LOW:
                return TITLE_LOW;
            default:
                return TITLE;
        }
    }
}
