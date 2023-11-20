package com.joelhalen.droptracker.ui;

import com.joelhalen.droptracker.DropTrackerPluginConfig;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;
public class DropEntryOverlay extends Overlay {
    private final PanelComponent panelComponent = new PanelComponent();
    private final Client client;
    private final DropTrackerPluginConfig config;

    @Inject
    private DropEntryOverlay(Client client, DropTrackerPluginConfig config)
    {
        setPosition(OverlayPosition.TOP_CENTER);
        this.client = client;
        this.config = config;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        panelComponent.getChildren().clear();
        String overlayTitle = "DropTracker";
        String overlayDesc = "You have a drop waiting to be submitted in the side panel!";
        TitleComponent titleComponent = TitleComponent.builder()
                .text(overlayTitle)
                .color(Color.GREEN)
                .build();
        TitleComponent descComponent = TitleComponent.builder()
                .text(overlayDesc)
                .build();
        panelComponent.getChildren().add(titleComponent);
        panelComponent.getChildren().add(descComponent);
        int maxWidth = Math.max(
                graphics.getFontMetrics().stringWidth(overlayTitle),
                graphics.getFontMetrics().stringWidth(overlayDesc)
        );
        int padding = 30;
        maxWidth += padding;

        int preferredHeight = graphics.getFontMetrics().stringWidth(overlayDesc) + 30;
        panelComponent.setPreferredSize(new Dimension(maxWidth, preferredHeight));

        return panelComponent.render(graphics);
    }
}
