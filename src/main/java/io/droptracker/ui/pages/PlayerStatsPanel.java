package io.droptracker.ui.pages;

import io.droptracker.api.*;
import io.droptracker.*;
import io.droptracker.models.api.*;
import io.droptracker.ui.components.*;
import io.droptracker.ui.DropTrackerTheme;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;

import javax.swing.*;
import java.awt.*;

public class PlayerStatsPanel extends SearchPage {
    private final DropTrackerPlugin plugin;
    /** The player the detail card shows, for its Refresh button. */
    private String lastPlayer;

    public PlayerStatsPanel(Client client, DropTrackerPlugin plugin, DropTrackerConfig config, DropTrackerApi api, ItemManager itemManager) {
        super(client, config, api, itemManager);
        this.plugin = plugin;
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
            JButton viewStatsButton = DropTrackerTheme.button("View My Stats (" + playerName + ")");
            viewStatsButton.setPreferredSize(new Dimension(200, 30));
            viewStatsButton.setToolTipText("View your DropTracker statistics");
            viewStatsButton.addActionListener(e -> performPlayerSearch(playerName));
            defaultPanel.add(buttonRow(viewStatsButton, 5));
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
}
