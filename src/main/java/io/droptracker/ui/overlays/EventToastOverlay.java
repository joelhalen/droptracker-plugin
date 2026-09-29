package io.droptracker.ui.overlays;

import io.droptracker.DropTrackerConfig;
import io.droptracker.service.EventNotificationService;
import io.droptracker.service.EventNotificationService.Toast;
import io.droptracker.ui.overlays.popup.PopupRenderer;
import io.droptracker.ui.overlays.popup.PopupRenderers;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Transient event pop-ups ("Chat + text pop-ups" / "Enhanced display"):
 * cards stacked top-center, each painted by the renderer for its style (the
 * configured {@code eventPopupStyle}, or the one a {@code ::dtpopup} preview
 * pinned), fading out at the end of their priority-dependent lifetime.
 *
 * Stands down while the Enhanced Display HUD is painting: the HUD then draws
 * the same queue as nudges anchored beneath itself (one movable object for
 * the user, not two), and this overlay resumes the moment the HUD stops.
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
        if (service.hudOwnsToasts()) {
            return null; // the HUD is rendering the queue as nudges beneath itself
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
                ? toast.getPopupStyle() : config.eventPopupStyle());
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
