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
    private volatile String teamName;
    public volatile JSONObject currentTask;
    private JSONObject lootStatistics;

    @Inject
    private DropTrackerOverlay(Client client, final DropTrackerPlugin plugin, final DropTrackerApi api, DropTrackerPluginConfig config)
    {
        super(plugin);
        setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT);
        setPriority(OverlayPriority.LOW);
        setMovable(true);
        this.plugin = plugin;
        this.api = api;
        this.config = config;
        this.client = client;
    }
    @Override
    public Dimension render(Graphics2D graphics) {
        panelComponent.getChildren().clear();
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("Event")
                .color(Color.GREEN)
                .build());

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastApiCallTime > 10000) {
            System.out.println("API Call...");
            lastApiCallTime = currentTime;
            executorService.submit(() -> {
                try {
                    String playerName = plugin.getLocalPlayerName();
                    String authKey = config.authKey();
                    api.fetchCurrentTask(playerName, authKey);
                    this.teamName = api.getTeamName();
                    this.currentTask = api.getCurrentTask();
                    this.lootStatistics = api.fetchLootStatistics(client.getLocalPlayer().getName(),
                            String.valueOf(plugin.getClanDiscordServerID(config.serverId())),
                            config.authKey());
                    lastApiCallTime = currentTime;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

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
            Integer quantity = this.currentTask.optInt("current_progress", 1);
            Integer leftForCompletion = this.currentTask.optInt("required_quantity",1);
            if (this.currentTask.optBoolean("per") == true) {
                // Implement logic to count team members and multiply
            }
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Progress: ")
                    .right(String.valueOf(quantity + "/" + leftForCompletion))
                    .build());
        } else {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Error loading event data.")
                    .build());
        }

        if (lootStatistics != null) {
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("DropTracker")
                    .color(Color.GREEN)
                    .build());
            long overallTotalAll = lootStatistics.optLong("overall_total_all", 0);
            long overallTotalServer = lootStatistics.optLong("overall_month_server", 0);
            long monthlyTotalPlayer = lootStatistics.optLong("monthly_total_player", 0);
            long overallTotalPlayer = lootStatistics.optLong("overall_total_player", 0);
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

