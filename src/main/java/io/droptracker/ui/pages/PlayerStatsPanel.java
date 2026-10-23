package io.droptracker.ui.pages;

import io.droptracker.api.*;
import io.droptracker.*;
import io.droptracker.models.api.*;
import io.droptracker.service.PlayerModelService;
import io.droptracker.ui.components.*;
import io.droptracker.ui.DropTrackerTheme;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;

import javax.swing.*;
import java.awt.*;

public class PlayerStatsPanel extends SearchPage {
    private final DropTrackerPlugin plugin;
    private final PlayerModelService playerModelService;
    /** The player the detail card shows, for its Refresh button. */
    private String lastPlayer;

    public PlayerStatsPanel(Client client, DropTrackerPlugin plugin, DropTrackerConfig config, DropTrackerApi api, ItemManager itemManager, PlayerModelService playerModelService) {
        super(client, config, api, itemManager);
        this.plugin = plugin;
        this.playerModelService = playerModelService;
    }

    public JPanel create() {
        return create("DropTracker - Players", "Search for a player", () -> performPlayerSearch(""));
    }

    @Override
    protected void showDefaultState() {
        contentPanel.removeAll();
        JPanel defaultPanel = defaultPanel();
        defaultPanel.add(DropTrackerTheme.gap(5));
        defaultPanel.add(instructions("Search for a player by name above"));
        defaultPanel.add(DropTrackerTheme.gap(5));

        // Get current player and add button if logged in
        String playerName = config.lastAccountName();

        if (playerName != null && !"Not logged in".equals(playerName)) {
            // Button to view current player stats
            JButton viewStatsButton = new JButton("View My Stats (" + playerName + ")");
            DropTrackerTheme.styleButton(viewStatsButton);
            viewStatsButton.setPreferredSize(new Dimension(200, 30));
            viewStatsButton.setToolTipText("View your DropTracker statistics");
            viewStatsButton.addActionListener(e -> performPlayerSearch(playerName));
            defaultPanel.add(buttonRow(viewStatsButton, 5));

            if (config.useApi()) {
                defaultPanel.add(buttonRow(createSendModelButton(), 5));
            }
            defaultPanel.add(DropTrackerTheme.gap(10));
        }

        if (config.useApi()) {
            leaderboardPlaceholder = StateViews.loading("Loading top players…");
            defaultPanel.add(leaderboardPlaceholder);
            LeaderboardComponents.loadLeaderboardAsync(leaderboardPlaceholder, () -> {
                try {
                    return api.getTopPlayers();
                } catch (Exception e) {
                    return null;
                }
            }, this::showPlayerLeaderboard);
        }

        defaultPanel.add(Box.createVerticalGlue());
        show(defaultPanel);
    }

    private JPanel showPlayerLeaderboard(PanelCard.Top top) {
        // Search for this player when clicked
        return LeaderboardComponents.createLeaderboardTable("Top Players", "Player", top.rows(),
            row -> performPlayerSearch(row.getName()));
    }

    public void performPlayerSearch(String searchQuery) {
        String toSearch;
        if (searchQuery.isEmpty()) {
            if (searchField != null && searchField.getText() != null && !searchField.getText().isEmpty()) {
                toSearch = searchField.getText().trim();
            } else if (plugin.getLocalPlayerName() != null && !plugin.getLocalPlayerName().isEmpty()) {
                toSearch = plugin.getLocalPlayerName();
            } else {
                return;
            }
        } else {
            toSearch = searchQuery;
        }

        runSearch("Searching for player…", () -> api.searchCard("/player_search", toSearch), card -> {
            lastPlayer = card.getTitle();
            showCard(card);
        }, "Player '" + toSearch + "' was not found.");
    }

    @Override
    protected void onCardAction(String type, String value) {
        if ("refresh".equals(type) && lastPlayer != null) {
            performPlayerSearch(lastPlayer);
        }
    }

    /**
     * The "Send Player Model" button: captures the character as it looks right
     * now and pins that model to the player's droptracker.io profile. All
     * feedback happens on the button itself — it narrates the send and shows
     * the outcome, then returns to normal so it can be used again.
     */
    private JButton createSendModelButton() {
        final String idleLabel = "Send Player Model";
        JButton sendModelButton = new JButton(idleLabel);
        DropTrackerTheme.styleButton(sendModelButton);
        sendModelButton.setPreferredSize(new Dimension(200, 30));
        sendModelButton.setToolTipText("<html>Uploads your character (and pet) exactly as they look right now<br/>"
                + "and shows that model on your droptracker.io profile.<br/>"
                + "Stand still with your favourite gear on, then click.</html>");
        sendModelButton.addActionListener(e -> {
            sendModelButton.setEnabled(false);
            sendModelButton.setText("Sending model…");
            playerModelService.sendCurrentModel((ok, message) -> {
                // Callback already arrives on the EDT.
                sendModelButton.setText(ok ? "Model sent!" : "Failed to send");
                sendModelButton.setToolTipText(message);
                Timer restore = new Timer(4000, ev -> {
                    sendModelButton.setText(idleLabel);
                    sendModelButton.setEnabled(true);
                });
                restore.setRepeats(false);
                restore.start();
            });
        });
        return sendModelButton;
    }
}
