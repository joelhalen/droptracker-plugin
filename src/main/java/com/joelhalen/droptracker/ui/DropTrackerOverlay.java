package com.joelhalen.droptracker.ui;

import com.joelhalen.droptracker.DropTrackerPlugin;
import com.joelhalen.droptracker.DropTrackerPluginConfig;
import com.joelhalen.droptracker.api.DropTrackerApi;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import org.json.JSONObject;

import javax.inject.Inject;
import java.awt.*;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class DropTrackerOverlay extends OverlayPanel
{
    private final DropTrackerPlugin plugin;
    private final DropTrackerApi api;
    @Inject
    private Client client;
    private DropTrackerPluginConfig config;
    private long lastApiCallTime = 0;
    private long lastApiLootRefresh = 0;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final PanelComponent panelComponent = new PanelComponent();
    private String teamName;
    private JSONObject currentTask;
    private JSONObject lootStatistics;

    @Inject
    private DropTrackerOverlay(Client client, final DropTrackerPlugin plugin, final DropTrackerApi api, DropTrackerPluginConfig config)
    {
        setPosition(OverlayPosition.DYNAMIC);
        setMovable(true);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        this.plugin = plugin;
        this.api = api;
        this.config = config;
        this.client = client;
    }
    @Override
    public Dimension render(Graphics2D graphics) {
        panelComponent.getChildren().clear();
        String overlayTitle = "DropTracker";
        long currentTime = System.currentTimeMillis();
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("Event")
                .color(Color.GREEN)
                .build());
        // Only call the API if at least 10 seconds have passed since the last call
        if (currentTime - lastApiCallTime > 10000) {
            lastApiCallTime = currentTime;
            executorService.submit(() -> {
                try {
                    this.teamName = api.getTeamName();
                    this.currentTask = api.getCurrentTask();
                    String playerName = plugin.getLocalPlayerName();
                    String authKey = config.authKey();
                    api.fetchCurrentTask(playerName, authKey);
                    this.lootStatistics = api.fetchLootStatistics(client.getLocalPlayer().getName(), String.valueOf(plugin.getClanDiscordServerID(config.serverId())), config.authKey());
                    lastApiCallTime = currentTime;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
//        if (currentTime - lastApiLootRefresh > 10000) {
//            String playerName = plugin.getLocalPlayerName();
//            String authKey = config.authKey();
//            try {
//                api.fetchCurrentTask(playerName, authKey);
//                this.lootStatistics = api.fetchLootStatistics(client.getLocalPlayer().getName(), String.valueOf(plugin.getClanDiscordServerID(config.serverId())), config.authKey());
//            } catch (IOException e) {
//                throw new RuntimeException(e);
//            }
//
//        }


        if (teamName != null && currentTask != null) {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Team: ")
                    .right(teamName)
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Task: ")
                    .right(this.currentTask.optString("task", ""))
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Per member: ")
                    .right(String.valueOf(this.currentTask.optBoolean("per", false)))
                    .build());
            Integer quantity = this.currentTask.optInt("quantity", 1);;
            if(this.currentTask.optBoolean("per") == true) {
                quantity = this.currentTask.optInt("quantity", 1);
                /* Implement logic to count team members and multiply */
            }
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Total required: ")
                    .right(String.valueOf(quantity))
                    .build());
        } else {
        }
        if (lootStatistics != null) {
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text(overlayTitle)
                    .color(Color.GREEN)
                    .build());
            // Extract the values
            long overallTotalAll = lootStatistics.optLong("overall_total_all", 0);
            long overallTotalServer = lootStatistics.optLong("overall_month_server", 0);
            long monthlyTotalPlayer = lootStatistics.optLong("monthly_total_player", 0);
            long overallTotalPlayer = lootStatistics.optLong("overall_total_player", 0);

            // Add them to the panelComponent
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Overall Total (All): ")
                    .right(String.valueOf(overallTotalAll))
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left(plugin.getServerName(config.serverId()) + " (Month):")
                    .right(String.valueOf(overallTotalServer))
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Your total (Month): ")
                    .right(String.valueOf(monthlyTotalPlayer))
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("All-time: ")
                    .right(String.valueOf(overallTotalPlayer))
                    .build());
        } else {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Loot statistics not available.")
                    .build());
        }
        return panelComponent.render(graphics);
    }
}

