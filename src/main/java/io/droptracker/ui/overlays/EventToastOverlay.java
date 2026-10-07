package io.droptracker.ui.overlays;

import io.droptracker.DropTrackerConfig;
import io.droptracker.service.EventNotificationService;
import io.droptracker.service.EventNotificationService.Toast;
import io.droptracker.ui.overlays.popup.*;
import net.runelite.client.ui.overlay.*;

import javax.inject.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Transient event pop-ups, stacked top-center: slim banners for "Chat + text
 * pop-ups", showcase panels for "Enhanced display" (or whichever style a
 * {@code ::dtpopup} preview pinned), fading out at the end of their
 * priority-dependent lifetime. Movable like any overlay (hold Alt to drag).
 */
@Singleton
public class EventToastOverlay extends Overlay {
    private final DropTrackerConfig config;
    private final EventNotificationService service;
    private final PopupRenderers renderers;

    @Inject
    public EventToastOverlay(DropTrackerConfig config, EventNotificationService service,
                             PopupRenderers renderers) {
        this.config = config;
        this.service = service;
        this.renderers = renderers;
        setPosition(OverlayPosition.TOP_CENTER);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.eventNotifications() || !config.eventDisplayMode().popupsEnabled()) {
            service.getToasts().clear();
            return null;
        }
        long now = System.currentTimeMillis();
        List<Toast> visible = new ArrayList<>();
        List<PopupRenderer> painters = new ArrayList<>();
        int width = 0;
        Iterator<Toast> iterator = service.getToasts().iterator();
        while (iterator.hasNext()) {
            Toast toast = iterator.next();
            if (toast.expired(now)) {
                iterator.remove();
                continue;
            }
            PopupRenderer renderer = renderers.popup(toast.getPopupStyle() != null
                ? toast.getPopupStyle() : config.eventDisplayMode().popupStyle());
            if (visible.size() < renderer.maxVisible()) {
                visible.add(toast);
                painters.add(renderer);
                width = Math.max(width, renderer.preferredWidth());
            }
        }
        if (visible.isEmpty()) {
            return null;
        }

        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int y = 0;
        for (int i = 0; i < visible.size(); i++) {
            PopupRenderer renderer = painters.get(i);
            if (i > 0) {
                y += renderer.gap();
            }
            int cardWidth = renderer.preferredWidth();
            y += renderer.draw(graphics, visible.get(i), (width - cardWidth) / 2, y, cardWidth, now);
        }
        return new Dimension(width, y);
    }
}
