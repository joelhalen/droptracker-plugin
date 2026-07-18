package io.droptracker.ui.overlays;

import io.droptracker.DropTrackerConfig;
import io.droptracker.service.EventNotificationService;
import io.droptracker.service.EventNotificationService.Toast;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Transient event pop-ups ("Chat + text pop-ups" / "Enhanced display"):
 * up to {@value #MAX_VISIBLE} small cards stacked top-center, fading out over
 * the last second of their {@link Toast#LIFETIME_MS} lifetime.
 *
 * Stands down while the Enhanced Display HUD is painting: the HUD then draws
 * the same queue as nudges anchored beneath itself (one movable object for
 * the user, not two), and this overlay resumes the moment the HUD stops.
 */
@Singleton
public class EventToastOverlay extends Overlay {
    private static final int MAX_VISIBLE = 3;
    private static final int WIDTH = 280;
    private static final int PADDING = 8;
    private static final int ICON_SIZE = 24;
    private static final int GAP = 6;
    private static final long FADE_MS = 1000;

    private static final Color BACKGROUND = new Color(0x15, 0x11, 0x0c, 230);
    private static final Color BORDER = new Color(0x7a, 0x5a, 0x32, 255);
    private static final Color TITLE = new Color(0xff, 0xb8, 0x3f);
    private static final Color BODY = new Color(0xef, 0xe6, 0xd2);

    private final DropTrackerConfig config;
    private final EventNotificationService service;
    private final ItemManager itemManager;

    @Inject
    public EventToastOverlay(DropTrackerConfig config, EventNotificationService service,
                             ItemManager itemManager) {
        this.config = config;
        this.service = service;
        this.itemManager = itemManager;
        setPosition(OverlayPosition.TOP_CENTER);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.eventNotifications() || !config.eventDisplayMode().popupsEnabled()) {
            service.getToasts().clear();
            return null;
        }
        if (service.hudOwnsToasts()) {
            return null; // the HUD is rendering the queue as nudges beneath itself
        }
        long now = System.currentTimeMillis();
        List<Toast> visible = new ArrayList<>(MAX_VISIBLE);
        Iterator<Toast> iterator = service.getToasts().iterator();
        while (iterator.hasNext()) {
            Toast toast = iterator.next();
            if (toast.expired(now)) {
                iterator.remove();
            } else if (visible.size() < MAX_VISIBLE) {
                visible.add(toast);
            }
        }
        if (visible.isEmpty()) {
            return null;
        }

        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int y = 0;
        for (Toast toast : visible) {
            y += drawToast(graphics, toast, y, now) + GAP;
        }
        return new Dimension(WIDTH, Math.max(y - GAP, 0));
    }

    private int drawToast(Graphics2D g, Toast toast, int top, long now) {
        long age = now - toast.getCreatedAt();
        long remaining = Toast.LIFETIME_MS - age;
        float alpha = remaining < FADE_MS ? Math.max(remaining / (float) FADE_MS, 0f) : 1f;

        FontMetrics titleFm = g.getFontMetrics(FontManager.getRunescapeBoldFont());
        FontMetrics bodyFm = g.getFontMetrics(FontManager.getRunescapeSmallFont());

        int textLeft = PADDING;
        BufferedImage icon = null;
        if (toast.getIconItemId() != null && toast.getIconItemId() > 0) {
            icon = itemManager.getImage(toast.getIconItemId());
            if (icon != null) {
                textLeft += ICON_SIZE + PADDING;
            }
        }
        int textWidth = WIDTH - textLeft - PADDING;
        List<String> bodyLines = wrap(toast.getBody(), bodyFm, textWidth, 3);
        int height = PADDING + titleFm.getHeight()
            + bodyLines.size() * bodyFm.getHeight() + PADDING;
        height = Math.max(height, icon != null ? ICON_SIZE + 2 * PADDING : 0);

        java.awt.Composite previous = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

        g.setColor(BACKGROUND);
        g.fillRoundRect(0, top, WIDTH, height, 8, 8);
        g.setColor(BORDER);
        g.drawRoundRect(0, top, WIDTH - 1, height - 1, 8, 8);

        if (icon != null) {
            g.drawImage(icon, PADDING, top + (height - ICON_SIZE) / 2,
                ICON_SIZE, ICON_SIZE, null);
        }

        int textY = top + PADDING + titleFm.getAscent();
        g.setFont(FontManager.getRunescapeBoldFont());
        g.setColor(TITLE);
        g.drawString(ellipsize(toast.getTitle(), titleFm, textWidth), textLeft, textY);

        g.setFont(FontManager.getRunescapeSmallFont());
        g.setColor(BODY);
        int lineY = textY + bodyFm.getHeight();
        for (String line : bodyLines) {
            g.drawString(line, textLeft, lineY);
            lineY += bodyFm.getHeight();
        }

        g.setComposite(previous);
        return height;
    }

    private static List<String> wrap(String text, FontMetrics fm, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (fm.stringWidth(candidate) <= width || current.length() == 0) {
                current = new StringBuilder(candidate);
            } else {
                lines.add(current.toString());
                current = new StringBuilder(word);
                if (lines.size() == maxLines - 1) {
                    break;
                }
            }
        }
        if (current.length() > 0 && lines.size() < maxLines) {
            lines.add(ellipsize(current.toString(), fm, width));
        }
        return lines;
    }

    private static String ellipsize(String text, FontMetrics fm, int width) {
        if (text == null) {
            return "";
        }
        if (fm.stringWidth(text) <= width) {
            return text;
        }
        String result = text;
        while (result.length() > 1 && fm.stringWidth(result + "…") > width) {
            result = result.substring(0, result.length() - 1);
        }
        return result + "…";
    }
}
