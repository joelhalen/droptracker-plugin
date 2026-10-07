package io.droptracker.ui.pages;

import io.droptracker.api.*;
import io.droptracker.*;
import io.droptracker.models.api.*;
import io.droptracker.service.PlayerModelService;
import io.droptracker.ui.components.*;
import io.droptracker.ui.DropTrackerTheme;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;

public class PlayerStatsPanel extends SearchPage {
    private final DropTrackerPlugin plugin;
    private final PlayerModelService playerModelService;

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

    private JPanel showPlayerLeaderboard(TopPlayersResult leaderboardData) {
        return LeaderboardComponents.createLeaderboardTable(
                "Top Players",
                "Player",
                leaderboardData != null ? leaderboardData.getPlayers() : null,
                new LeaderboardComponents.LeaderboardItemRenderer<TopPlayersResult.TopPlayer>() {
                    @Override
                    public String getName(TopPlayersResult.TopPlayer player) {
                        return player.getPlayerName() != null ? player.getPlayerName() : "Unknown Player";
                    }

                    @Override
                    public String getLootValue(TopPlayersResult.TopPlayer player) {
                        String loot = player.getTotalLoot();
                        return (loot != null && !loot.trim().isEmpty()) ? loot : "0 GP";
                    }

                    @Override
                    public Integer getRank(TopPlayersResult.TopPlayer player) {
                        return player.getRank();
                    }

                    @Override
                    public void onItemClick(TopPlayersResult.TopPlayer player) {
                        // Search for this player when clicked
                        performPlayerSearch(player.getPlayerName());
                    }
                }
        );
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

        runSearch("Searching for player…", () -> api.lookupPlayer(toSearch), this::showPlayerDetails,
            "Player '" + toSearch + "' was not found.");
    }

    private void showPlayerDetails(PlayerSearchResult playerResult) {
        contentPanel.removeAll();
        JPanel playerNamePanel = nameColumn(playerResult.getPlayerName());

        String statusText = playerResult.isRegistered() ? "Registered Player" : "Not registered!";
        JLabel playerDescLabel = DropTrackerTheme.label(statusText, FontManager.getRunescapeSmallFont(),
            playerResult.isRegistered() ? DropTrackerTheme.GREEN : DropTrackerTheme.EMBER);
        playerDescLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (!playerResult.isRegistered()) {
            playerDescLabel.setToolTipText("<html>This account has not been claimed on DropTracker.<br/>"
                + "Claim your in-game name in our Discord to register.</html>");
        }

        playerNamePanel.add(DropTrackerTheme.gap(5));
        playerNamePanel.add(playerDescLabel);

        // Add groups information if available
        if (playerResult.getGroups() != null && !playerResult.getGroups().isEmpty()) {
            playerNamePanel.add(createGroupsPanel(playerResult.getGroups()));
        }

        JPanel playerPointsBox = PanelElements.createStatBox("Lifetime Points", playerResult.getPoints() + " pts");
        JPanel statsPanel = statsGrid(
            PanelElements.createStatBox("Total Loot", playerResult.getTotalLoot() + " GP"),
            PanelElements.createStatBox("Global Rank", "#" + playerResult.getGlobalRank()),
            playerPointsBox);

        playerPointsBox.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                LinkBrowser.browse(DropTrackerUrls.web("wiki", "points").toString());
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                playerPointsBox.setBackground(DropTrackerTheme.SURFACE_3);
                playerPointsBox.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                playerPointsBox.setBackground(DropTrackerTheme.SURFACE_2);
                playerPointsBox.setCursor(Cursor.getDefaultCursor());
            }
        });
        playerPointsBox.setToolTipText("View more info about points (click to open wiki)");

        JPanel actionPanel = actionRow();

        JButton refreshButton = actionButton("Refresh Stats");
        refreshButton.addActionListener(e -> performPlayerSearch(playerResult.getPlayerName()));

        JButton viewProfileButton = actionButton("View Profile");
        viewProfileButton.setIcon(PanelElements.getExternalLinkIcon());
        viewProfileButton.addActionListener(e -> {
            if (playerResult.getDropTrackerPlayerId() != null) {
                LinkBrowser.browse(DropTrackerUrls.web("players", String.valueOf(playerResult.getDropTrackerPlayerId()), "view").toString());
            } else {
                LinkBrowser.browse(DropTrackerUrls.web("players", playerResult.getPlayerName(), "view").toString());
            }
        });

        actionPanel.add(refreshButton);
        actionPanel.add(viewProfileButton);

        showCard(detailHeader(null, playerNamePanel), statsPanel,
            recent(playerResult.getRecentSubmissions(), false, "No recent submissions available"), actionPanel);
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

    private JPanel createGroupsPanel(List<PlayerSearchResult.PlayerGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return new JPanel(); // Return empty panel if no groups
        }

        JPanel groupsContainer = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
        groupsContainer.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel groupsHeaderLabel = DropTrackerTheme.label("Groups:", FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
        groupsHeaderLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        groupsContainer.add(groupsHeaderLabel);

        // Add each group as a separate label for proper scaling
        for (PlayerSearchResult.PlayerGroup group : groups) {
            StringBuilder groupText = new StringBuilder("- ");
            groupText.append(group.getName());
            if (group.getMembers() != null) {
                groupText.append(" (").append(group.getMembers()).append(" members)");
            }

            JLabel groupLabel = DropTrackerTheme.label(groupText.toString(), FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
            groupLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

            groupsContainer.add(groupLabel);
        }

        return groupsContainer;
    }

}
