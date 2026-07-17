package io.droptracker.ui.overlays;

import io.droptracker.DropTrackerConfig;
import io.droptracker.models.EventHudDetail;
import io.droptracker.models.api.EventState;
import io.droptracker.service.EventNotificationService;
import io.droptracker.util.RemoteImageCache;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.ProgressBarComponent;
import net.runelite.client.ui.overlay.components.SplitComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * The "Enhanced display" HUD: a movable overlay (hold Alt to drag — standard
 * RuneLite overlay behavior) showing the pinned event's focus task, progress
 * and — in Detailed mode — team standing. Data comes from the
 * {@code /event_state} snapshot kept by {@link EventNotificationService};
 * which event shows is the "Show on HUD" pick in the Events side-panel tab.
 */
@Singleton
public class EventHudOverlay extends OverlayPanel {
    private static final int PANEL_WIDTH = 190;
    private static final int ICON_SIZE = 26;

    private static final Color GOLD = new Color(0xff, 0xb8, 0x3f);
    private static final Color TEXT = new Color(0xef, 0xe6, 0xd2);
    private static final Color MUTED = new Color(0xd8, 0xc9, 0xa3);
    private static final Color BAR_BG = new Color(0x3a, 0x2f, 0x20);
    private static final Color BAR_FG = new Color(0x6f, 0xbf, 0x73);

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
        panelComponent.setPreferredSize(new Dimension(PANEL_WIDTH, 0));
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
        panelComponent.getChildren().clear();

        panelComponent.getChildren().add(TitleComponent.builder()
            .text(entry.getEvent().getName())
            .color(GOLD)
            .build());

        if (detailed && entry.getTeam() != null) {
            EventState.TeamInfo team = entry.getTeam();
            Color teamColor = parseColor(team.getColor(), TEXT);
            String standing = team.getRank() != null
                ? ordinal(team.getRank()) + " of " + team.getTeamCount()
                : team.getScore() + " pts";
            panelComponent.getChildren().add(LineComponent.builder()
                .left(team.getName()).leftColor(teamColor)
                .right(standing).rightColor(MUTED)
                .build());
            if (team.getRank() != null) {
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("Score").leftColor(MUTED)
                    .right(String.valueOf(team.getScore())).rightColor(TEXT)
                    .build());
            }
        }

        EventState.FocusTask task = entry.getFocusTask();
        if (task != null) {
            LineComponent taskLine = LineComponent.builder()
                .left(task.getLabel()).leftColor(TEXT)
                .build();
            BufferedImage icon = taskIcon(task);
            if (icon != null) {
                panelComponent.getChildren().add(SplitComponent.builder()
                    .first(new ImageComponent(scaled(icon)))
                    .second(taskLine)
                    .orientation(net.runelite.client.ui.overlay.components.ComponentOrientation.HORIZONTAL)
                    .gap(new java.awt.Point(6, 0))
                    .build());
            } else {
                panelComponent.getChildren().add(taskLine);
            }
            if (task.getNeed() > 1 || task.getHave() > 0) {
                ProgressBarComponent bar = new ProgressBarComponent();
                bar.setBackgroundColor(BAR_BG);
                bar.setForegroundColor(BAR_FG);
                bar.setMaximum(Math.max(task.getNeed(), 1));
                bar.setValue(Math.min(task.getHave(), task.getNeed()));
                bar.setLabelDisplayMode(ProgressBarComponent.LabelDisplayMode.TEXT_ONLY);
                bar.setCenterLabel(task.getHave() + " / " + task.getNeed());
                panelComponent.getChildren().add(bar);
            }
        } else if ("awaiting_roll".equals(entry.getBoardStatus())) {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Roll the dice!").leftColor(GOLD)
                .build());
        } else if (entry.getTasksTotal() > 0) {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Tasks").leftColor(MUTED)
                .right(entry.getTasksCompleted() + " / " + entry.getTasksTotal())
                .rightColor(TEXT)
                .build());
        }

        return super.render(graphics);
    }

    private BufferedImage taskIcon(EventState.FocusTask task) {
        if (task.getIconItemId() != null && task.getIconItemId() > 0) {
            return itemManager.getImage(task.getIconItemId());
        }
        return remoteImages.get(task.getIconUrl(), null);
    }

    private static BufferedImage scaled(BufferedImage source) {
        if (source.getWidth() <= ICON_SIZE && source.getHeight() <= ICON_SIZE) {
            return source;
        }
        BufferedImage out = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, ICON_SIZE, ICON_SIZE, null);
        g.dispose();
        return out;
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
