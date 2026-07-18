package io.droptracker.ui.overlays;

import io.droptracker.DropTrackerConfig;
import io.droptracker.models.EventHudDetail;
import io.droptracker.models.api.EventState;
import io.droptracker.service.EventNotificationService;
import io.droptracker.ui.DropTrackerTheme;
import io.droptracker.util.RemoteImageCache;
import io.droptracker.util.ValueFormat;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The "Enhanced display" HUD, painted in the style of the game's collection
 * log popup: layered parchment card with a bronze frame, a title strip, the
 * team standing and the tracked task with a framed progress bar. Movable
 * (hold Alt to drag — standard RuneLite overlay behavior). Data comes from
 * the {@code /event_state} snapshot in {@link EventNotificationService};
 * which event shows is the "Show on HUD" pick in the Events tab, and which
 * task shows honors the user's tracked-task pick (Events tab) before the
 * server's focus choice.
 *
 * While this HUD paints, incoming event pop-ups render as compact nudges
 * anchored directly beneath the card (following wherever the user dragged
 * it), and the stand-alone {@link EventToastOverlay} stands down — the user
 * positions one object, never two.
 */
@Singleton
public class EventHudOverlay extends Overlay {
    private static final int WIDTH = 200;
    private static final int PAD = 8;
    private static final int ICON_SIZE = 26;
    private static final int BAR_HEIGHT = 13;

    /* Frame palette (collection-log-popup inspired). */
    private static final Color EDGE_DARK = new Color(0x0e, 0x0b, 0x07);
    private static final Color FRAME_BRONZE = DropTrackerTheme.BRONZE;
    private static final Color BG_TOP = new Color(0x2c, 0x23, 0x18, 242);
    private static final Color BG_BOTTOM = new Color(0x15, 0x11, 0x0c, 242);
    private static final Color TITLE_STRIP = new Color(0x0e, 0x0b, 0x07, 130);
    private static final Color BAR_FILL = new Color(0x2e, 0x5c, 0x33);
    private static final Color BAR_EDGE = DropTrackerTheme.GREEN;

    private final DropTrackerConfig config;
    private final EventNotificationService service;
    private final ItemManager itemManager;
    private final RemoteImageCache remoteImages;

    @Inject
    public EventHudOverlay(DropTrackerConfig config, EventNotificationService service,
                           ItemManager itemManager, RemoteImageCache remoteImages) {
        this.config = config;
        this.service = service;
        this.itemManager = itemManager;
        this.remoteImages = remoteImages;
        setPosition(OverlayPosition.TOP_LEFT);
        setResizable(false);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.eventNotifications() || !config.eventDisplayMode().hudEnabled()) {
            return null;
        }
        EventState.Entry entry = service.hudEntry();
        if (entry == null || entry.getEvent() == null) {
            return null;
        }
        boolean detailed = config.eventHudDetail() == EventHudDetail.DETAILED;
        EventNotificationService.DisplayTask task = service.displayTask(entry);
        boolean awaitingRoll = "awaiting_roll".equals(entry.getBoardStatus());

        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font titleFont = FontManager.getRunescapeBoldFont();
        Font smallFont = FontManager.getRunescapeSmallFont();
        FontMetrics titleFm = graphics.getFontMetrics(titleFont);
        FontMetrics smallFm = graphics.getFontMetrics(smallFont);

        int innerWidth = WIDTH - PAD * 2;
        String title = truncateToWidth(entry.getEvent().getName(), titleFm, innerWidth);

        // Task label lines (max 2), leaving room for the icon column.
        BufferedImage icon = task != null ? taskIcon(task) : null;
        int labelWidth = innerWidth - (icon != null ? ICON_SIZE + 6 : 0);
        List<String> taskLines = task != null
            ? wrap(taskLabel(task), smallFm, labelWidth, 2) : new ArrayList<>();
        boolean hasBar = task != null && (task.need > 1 || task.have > 0);

        /* ---- measure ---- */
        int titleStripH = titleFm.getHeight() + 6;
        int height = titleStripH;
        if (detailed && entry.getTeam() != null) {
            height += 4 + smallFm.getHeight();
            if (entry.getTeam().getRank() != null) {
                height += smallFm.getHeight();
            }
        }
        if (awaitingRoll) {
            height += 6 + titleFm.getHeight();
        } else if (task != null) {
            int textBlock = taskLines.size() * smallFm.getHeight();
            int rowH = Math.max(icon != null ? ICON_SIZE : 0, textBlock);
            height += 6 + rowH;
            if (hasBar) {
                height += 4 + BAR_HEIGHT;
            }
        } else if (entry.getTasksTotal() > 0) {
            height += 6 + smallFm.getHeight();
        }
        height += PAD;

        /* ---- frame ---- */
        graphics.setPaint(new GradientPaint(0, 0, BG_TOP, 0, height, BG_BOTTOM));
        graphics.fillRect(1, 1, WIDTH - 2, height - 2);
        graphics.setColor(EDGE_DARK);
        graphics.setStroke(new BasicStroke(1));
        graphics.drawRect(0, 0, WIDTH - 1, height - 1);
        graphics.setColor(FRAME_BRONZE);
        graphics.drawRect(1, 1, WIDTH - 3, height - 3);

        /* ---- title strip ---- */
        graphics.setColor(TITLE_STRIP);
        graphics.fillRect(2, 2, WIDTH - 4, titleStripH - 2);
        graphics.setColor(FRAME_BRONZE);
        graphics.drawLine(2, titleStripH, WIDTH - 3, titleStripH);
        graphics.setFont(titleFont);
        int titleX = (WIDTH - titleFm.stringWidth(title)) / 2;
        int titleY = 3 + titleFm.getAscent();
        graphics.setColor(Color.BLACK);
        graphics.drawString(title, titleX + 1, titleY + 1);
        graphics.setColor(DropTrackerTheme.GOLD);
        graphics.drawString(title, titleX, titleY);

        int y = titleStripH + 4;

        /* ---- team standing (Detailed) ---- */
        if (detailed && entry.getTeam() != null) {
            EventState.TeamInfo team = entry.getTeam();
            Color teamColor = parseColor(team.getColor(), DropTrackerTheme.TEXT);
            graphics.setFont(smallFont);

            int swatch = 8;
            int swatchY = y + (smallFm.getHeight() - swatch) / 2;
            graphics.setColor(teamColor);
            graphics.fillRect(PAD, swatchY, swatch, swatch);
            graphics.setColor(EDGE_DARK);
            graphics.drawRect(PAD, swatchY, swatch, swatch);

            String standing = team.getRank() != null
                ? ordinal(team.getRank()) + " of " + team.getTeamCount()
                : ValueFormat.commas(team.getScore()) + " pts";
            String teamName = truncateToWidth(team.getName(), smallFm,
                innerWidth - swatch - 6 - smallFm.stringWidth(standing) - 4);
            int textY = y + smallFm.getAscent();
            shadowed(graphics, teamName, PAD + swatch + 6, textY, teamColor);
            shadowed(graphics, standing, WIDTH - PAD - smallFm.stringWidth(standing),
                textY, DropTrackerTheme.TEXT_MUTED);
            y += smallFm.getHeight();

            if (team.getRank() != null) {
                String score = ValueFormat.commas(team.getScore());
                textY = y + smallFm.getAscent();
                shadowed(graphics, "Score", PAD, textY, DropTrackerTheme.TEXT_MUTED);
                shadowed(graphics, score, WIDTH - PAD - smallFm.stringWidth(score),
                    textY, DropTrackerTheme.GOLD_BRIGHT);
                y += smallFm.getHeight();
            }
        }

        /* ---- task / roll prompt ---- */
        if (awaitingRoll) {
            y += 6;
            graphics.setFont(titleFont);
            String roll = "Roll the dice!";
            int rollX = (WIDTH - titleFm.stringWidth(roll)) / 2;
            shadowed(graphics, roll, rollX, y + titleFm.getAscent(), DropTrackerTheme.GOLD_BRIGHT);
        } else if (task != null) {
            y += 6;
            int textBlock = taskLines.size() * smallFm.getHeight();
            int rowH = Math.max(icon != null ? ICON_SIZE : 0, textBlock);
            int textX = PAD;
            if (icon != null) {
                graphics.drawImage(scaled(icon), PAD, y + (rowH - ICON_SIZE) / 2, null);
                textX += ICON_SIZE + 6;
            }
            graphics.setFont(smallFont);
            int lineY = y + (rowH - textBlock) / 2 + smallFm.getAscent();
            for (String line : taskLines) {
                shadowed(graphics, line, textX, lineY,
                    task.tracked ? DropTrackerTheme.GOLD_BRIGHT : DropTrackerTheme.TEXT);
                lineY += smallFm.getHeight();
            }
            y += rowH;

            if (hasBar) {
                y += 4;
                drawProgressBar(graphics, smallFm, PAD, y, innerWidth, task.have, task.need);
                y += BAR_HEIGHT;
            }
        } else if (entry.getTasksTotal() > 0) {
            y += 6;
            graphics.setFont(smallFont);
            String done = entry.getTasksCompleted() + " / " + entry.getTasksTotal();
            shadowed(graphics, "Tasks", PAD, y + smallFm.getAscent(), DropTrackerTheme.TEXT_MUTED);
            shadowed(graphics, done, WIDTH - PAD - smallFm.stringWidth(done),
                y + smallFm.getAscent(), DropTrackerTheme.TEXT);
        }

        // This frame painted: own the pop-up queue (EventToastOverlay stands
        // down) and render it as nudges hanging off the card's bottom edge.
        service.markHudRendered();
        int totalHeight = height;
        if (config.eventDisplayMode().popupsEnabled()) {
            totalHeight = renderNudges(graphics, height);
        }
        return new Dimension(WIDTH, totalHeight);
    }

    /* ===================== pop-up nudges ===================== */

    private static final int MAX_NUDGES = 3;
    private static final int NUDGE_GAP = 4;
    private static final int NUDGE_ICON = 20;
    private static final long NUDGE_FADE_MS = 1000;

    /** Draws the pending toasts as compact HUD-styled cards below the HUD
     *  (starting at {@code hudHeight}); returns the new total height. */
    private int renderNudges(Graphics2D g, int hudHeight) {
        long now = System.currentTimeMillis();
        List<EventNotificationService.Toast> visible = new ArrayList<>(MAX_NUDGES);
        Iterator<EventNotificationService.Toast> iterator = service.getToasts().iterator();
        while (iterator.hasNext()) {
            EventNotificationService.Toast toast = iterator.next();
            if (toast.expired(now)) {
                iterator.remove();
            } else if (visible.size() < MAX_NUDGES) {
                visible.add(toast);
            }
        }
        int y = hudHeight;
        for (EventNotificationService.Toast toast : visible) {
            y += NUDGE_GAP;
            y += drawNudge(g, toast, y, now);
        }
        return y;
    }

    private int drawNudge(Graphics2D g, EventNotificationService.Toast toast, int top, long now) {
        long remaining = EventNotificationService.Toast.LIFETIME_MS - (now - toast.getCreatedAt());
        float alpha = remaining < NUDGE_FADE_MS
            ? Math.max(remaining / (float) NUDGE_FADE_MS, 0f) : 1f;

        Font titleFont = FontManager.getRunescapeBoldFont();
        Font smallFont = FontManager.getRunescapeSmallFont();
        FontMetrics titleFm = g.getFontMetrics(titleFont);
        FontMetrics smallFm = g.getFontMetrics(smallFont);

        int textLeft = PAD;
        BufferedImage icon = null;
        if (toast.getIconItemId() != null && toast.getIconItemId() > 0) {
            icon = itemManager.getImage(toast.getIconItemId());
            if (icon != null) {
                textLeft += NUDGE_ICON + 6;
            }
        }
        int textWidth = WIDTH - textLeft - PAD;
        List<String> bodyLines = wrap(toast.getBody(), smallFm, textWidth, 2);
        int height = 6 + titleFm.getHeight() + bodyLines.size() * smallFm.getHeight() + 6;
        if (icon != null) {
            height = Math.max(height, NUDGE_ICON + 12);
        }

        java.awt.Composite previous = g.getComposite();
        g.setComposite(java.awt.AlphaComposite.getInstance(
            java.awt.AlphaComposite.SRC_OVER, alpha));

        g.setColor(BG_BOTTOM);
        g.fillRect(1, top + 1, WIDTH - 2, height - 2);
        g.setColor(EDGE_DARK);
        g.drawRect(0, top, WIDTH - 1, height - 1);
        g.setColor(FRAME_BRONZE);
        g.drawRect(1, top + 1, WIDTH - 3, height - 3);

        if (icon != null) {
            g.drawImage(icon, PAD, top + (height - NUDGE_ICON) / 2,
                NUDGE_ICON, NUDGE_ICON, null);
        }
        g.setFont(titleFont);
        int titleY = top + 6 + titleFm.getAscent();
        shadowed(g, truncateToWidth(toast.getTitle(), titleFm, textWidth),
            textLeft, titleY, DropTrackerTheme.GOLD);
        g.setFont(smallFont);
        int lineY = titleY + smallFm.getHeight();
        for (String line : bodyLines) {
            shadowed(g, line, textLeft, lineY, DropTrackerTheme.TEXT);
            lineY += smallFm.getHeight();
        }

        g.setComposite(previous);
        return height;
    }

    /* ===================== painting helpers ===================== */

    private void drawProgressBar(Graphics2D g, FontMetrics fm, int x, int y,
                                 int width, long have, long need) {
        long max = Math.max(need, 1);
        long value = Math.min(Math.max(have, 0), max);
        g.setColor(new Color(0x0a, 0x08, 0x05));
        g.fillRect(x, y, width, BAR_HEIGHT);
        int fill = (int) Math.round((double) value / max * (width - 2));
        g.setColor(BAR_FILL);
        g.fillRect(x + 1, y + 1, fill, BAR_HEIGHT - 2);
        g.setColor(BAR_EDGE);
        g.fillRect(x + 1, y + 1, fill, 2);
        g.setColor(FRAME_BRONZE);
        g.drawRect(x, y, width - 1, BAR_HEIGHT - 1);

        String text = ValueFormat.progress(have, need);
        int tx = x + (width - fm.stringWidth(text)) / 2;
        int ty = y + (BAR_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
        g.setColor(Color.BLACK);
        g.drawString(text, tx + 1, ty + 1);
        g.setColor(DropTrackerTheme.TEXT);
        g.drawString(text, tx, ty);
    }

    private void shadowed(Graphics2D g, String text, int x, int y, Color color) {
        g.setColor(Color.BLACK);
        g.drawString(text, x + 1, y + 1);
        g.setColor(color);
        g.drawString(text, x, y);
    }

    private String taskLabel(EventNotificationService.DisplayTask task) {
        return (task.tracked ? "★ " : "") + (task.label != null ? task.label : "");
    }

    @Nullable
    private BufferedImage taskIcon(EventNotificationService.DisplayTask task) {
        if (task.iconItemId != null && task.iconItemId > 0) {
            return itemManager.getImage(task.iconItemId);
        }
        return remoteImages.get(task.iconUrl, null);
    }

    private static BufferedImage scaled(BufferedImage source) {
        if (source.getWidth() <= ICON_SIZE && source.getHeight() <= ICON_SIZE) {
            return source;
        }
        // Fit within the slot, centered, aspect ratio preserved (item sprites
        // are 36x32 — a straight square scale visibly squishes them).
        int w = Math.max(source.getWidth(), 1);
        int h = Math.max(source.getHeight(), 1);
        float scale = Math.min((float) ICON_SIZE / w, (float) ICON_SIZE / h);
        int nw = Math.max(Math.round(w * scale), 1);
        int nh = Math.max(Math.round(h * scale), 1);
        BufferedImage out = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, (ICON_SIZE - nw) / 2, (ICON_SIZE - nh) / 2, nw, nh, null);
        g.dispose();
        return out;
    }

    /* ===================== text helpers ===================== */

    private static String truncateToWidth(String text, FontMetrics fm, int width) {
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

    /** Word-wrap into at most maxLines lines; the last line is ellipsized. */
    private static List<String> wrap(String text, FontMetrics fm, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(candidate) <= width || line.length() == 0) {
                line = new StringBuilder(candidate);
            } else {
                lines.add(line.toString());
                line = new StringBuilder(word);
                if (lines.size() == maxLines - 1) {
                    break;
                }
            }
        }
        if (line.length() > 0 && lines.size() < maxLines) {
            lines.add(line.toString());
        }
        // If the text didn't fully fit, ellipsize the final line.
        String joined = String.join(" ", lines);
        if (!joined.equals(text) && !lines.isEmpty()) {
            int last = lines.size() - 1;
            lines.set(last, truncateToWidth(lines.get(last) + "…", fm, width));
        }
        return lines;
    }

    private static Color parseColor(String hex, Color fallback) {
        if (hex == null || hex.isEmpty()) {
            return fallback;
        }
        try {
            return Color.decode(hex);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String ordinal(int n) {
        if (n % 100 >= 11 && n % 100 <= 13) {
            return n + "th";
        }
        switch (n % 10) {
            case 1: return n + "st";
            case 2: return n + "nd";
            case 3: return n + "rd";
            default: return n + "th";
        }
    }
}
