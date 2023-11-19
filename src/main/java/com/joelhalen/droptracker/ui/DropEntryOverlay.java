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
        setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT);
        this.client = client;
        this.config = config;
    }
    @Override
    public Dimension render(Graphics2D graphics)
    {
        panelComponent.getChildren().clear();
        String overlayTitle = "DropTracker";
        String overlayDesc = "You have a drop waiting to be submitted in the side panel!";

        panelComponent.getChildren().add(TitleComponent.builder()
                .text(overlayTitle)
                .color(Color.GREEN)
                .build());

        panelComponent.setPreferredSize(new Dimension(
                graphics.getFontMetrics().stringWidth(overlayDesc) + 30,
                0));

        panelComponent.getChildren().add(TitleComponent.builder()
                .text(overlayDesc)
                .build());


        return panelComponent.render(graphics);
    }
}
