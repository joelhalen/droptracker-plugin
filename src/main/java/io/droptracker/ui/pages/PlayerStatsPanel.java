package io.droptracker.ui.pages;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import javax.inject.Inject;

import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.ui.components.LeaderboardComponents;
import io.droptracker.ui.components.PanelElements;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.api.PlayerSearchResult;
import io.droptracker.models.api.TopPlayersResult;
import io.droptracker.models.submissions.RecentSubmission;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

public class PlayerStatsPanel {

	@Inject
	private Client client;

	@Inject
	private DropTrackerConfig config;
	

	@Inject
	private DropTrackerApi api;

	@Inject
	private DropTrackerPlugin plugin;

	private ItemManager itemManager;
	
	// UI components that we need to update
	private JPanel mainPanel;
	private JPanel contentPanel;
	private JTextField searchField;
	private JPanel leaderboardPlaceholder;

	public PlayerStatsPanel(Client client, DropTrackerPlugin plugin, DropTrackerConfig config, DropTrackerApi api, ItemManager itemManager) {
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.api = api;
		this.itemManager = itemManager;
	}

	public JPanel create() {
		mainPanel = new JPanel();
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		
		// Header section with title and search using LeaderboardComponents
		LeaderboardComponents.HeaderResult headerResult = LeaderboardComponents.createHeaderPanel(
			"DropTracker - Players", 
			"Search for a player", 
			() -> performPlayerSearch("")
		);
		searchField = headerResult.searchField;
		
		// Content panel that will change based on state
		contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		contentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Show default state
		showDefaultState();
		
		// Add components to main panel - match GroupPanel structure
		mainPanel.add(headerResult.panel);
		mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		mainPanel.add(contentPanel);
		mainPanel.add(Box.createVerticalGlue());
		
		return mainPanel;
	}
	

	
	private void showDefaultState() {
		contentPanel.removeAll();
		
		// Create center panel for instructions and leaderboard
		JPanel defaultPanel = new JPanel();
		defaultPanel.setLayout(new BoxLayout(defaultPanel, BoxLayout.Y_AXIS));
		defaultPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		defaultPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
		
		// Add some spacing
		defaultPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		
		// Instructions text
		JLabel instructionLabel = new JLabel("Search for a player by name above");
		instructionLabel.setFont(FontManager.getRunescapeFont());
		instructionLabel.setForeground(Color.LIGHT_GRAY);
		instructionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		instructionLabel.setHorizontalAlignment(JLabel.CENTER);
		
		defaultPanel.add(instructionLabel);
		defaultPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		
		// Get current player and add button if logged in
		String playerName = (client.getLocalPlayer() != null) ? client.getLocalPlayer().getName() : null;
		
		if (playerName != null && !"Not logged in".equals(playerName)) {
			// Button to view current player stats
			JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
			buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
			buttonPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
			buttonPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
			buttonPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 30));
			
			JButton viewStatsButton = new JButton("⚡ View My Stats (" + playerName + ")");
			viewStatsButton.setFont(FontManager.getRunescapeSmallFont());
			viewStatsButton.setPreferredSize(new Dimension(200, 30));
			viewStatsButton.setToolTipText("View your DropTracker statistics");
			viewStatsButton.addActionListener(e -> performPlayerSearch(playerName));
			
			buttonPanel.add(viewStatsButton);
			defaultPanel.add(buttonPanel);
			defaultPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		}
		
		if (config.useApi()) {
			// Create placeholder for leaderboard (same pattern as GroupPanel)
			leaderboardPlaceholder = LeaderboardComponents.createLoadingPlaceholder("Loading top players...");
			defaultPanel.add(leaderboardPlaceholder);
			
			// Start loading data
			obtainPlayerLeaderboardData();
		}
		
		defaultPanel.add(Box.createVerticalGlue());
		
		contentPanel.add(defaultPanel);
		contentPanel.revalidate();
		contentPanel.repaint();
	}

	// New method to obtain player leaderboard data (similar to GroupPanel)
	private void obtainPlayerLeaderboardData() {
		LeaderboardComponents.loadLeaderboardAsync(
			leaderboardPlaceholder,
			() -> {
				try {
					return api.getTopPlayers();
				} catch (Exception e) {
					System.err.println("Failed to get top players: " + e.getMessage());
					// Fallback to demo data for testing
					return createDemoTopPlayersResult();
				}
			},
			this::showPlayerLeaderboard
		);
	}

	// New method to show player leaderboard using LeaderboardComponents
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

	// Create demo data for testing (same as before)
	private TopPlayersResult createDemoTopPlayersResult() {
		TopPlayersResult demo = new TopPlayersResult();
		List<TopPlayersResult.TopPlayer> players = new ArrayList<>();
		
		players.add(new TopPlayersResult.TopPlayer("Woox", 1, "2.1B"));
		players.add(new TopPlayersResult.TopPlayer("Lynx Titan", 2, "1.8B"));
		players.add(new TopPlayersResult.TopPlayer("Zezima", 3, "1.2B"));
		players.add(new TopPlayersResult.TopPlayer("B0aty", 4, "890M"));
		players.add(new TopPlayersResult.TopPlayer("Framed", 5, "650M"));
		
		demo.setPlayers(players);
		return demo;
	}
	
	public void performPlayerSearch(String searchQuery) {
		String toSearch;
		if (searchQuery.isEmpty()) {
			if (searchField != null && searchField.getText() != null && !searchField.getText().isEmpty()) {
				toSearch = searchField.getText().trim();
			} else {
				if (plugin.getLocalPlayerName() != null && !plugin.getLocalPlayerName().isEmpty()) {
					toSearch = plugin.getLocalPlayerName();
				} else {
					return;
				}
			}
		} else {
			toSearch = searchQuery;
		}
		
		
		// Show loading message
		contentPanel.removeAll();
		JLabel loadingLabel = new JLabel("Searching for player...");
		loadingLabel.setFont(FontManager.getRunescapeFont());
		loadingLabel.setForeground(Color.LIGHT_GRAY);
		loadingLabel.setHorizontalAlignment(JLabel.CENTER);
		contentPanel.add(loadingLabel);
		contentPanel.revalidate();
		contentPanel.repaint();
		
		// Perform search in background
		CompletableFuture.supplyAsync(() -> {
			try {
				PlayerSearchResult playerResult = api.lookupPlayerNew(toSearch);
				return playerResult;
			} catch (Exception e) {
				System.err.println("Failed to search for player: " + e.getMessage());
				return null;
			}
		}).thenAccept(playerResult -> {
			SwingUtilities.invokeLater(() -> {
				if (playerResult != null) {
					showPlayerDetails(playerResult);
				} else {
					showSearchError("Player '" + toSearch + "' not found. API search failed.");
				}
			});
		});
	}
	
	private void showSearchError(String message) {
		contentPanel.removeAll();
		
		JPanel errorPanel = LeaderboardComponents.createErrorPanel(message, () -> {
			searchField.setText("");
			showDefaultState();
		});
		
		contentPanel.add(errorPanel);
		contentPanel.revalidate();
		contentPanel.repaint();
	}
	private void showPlayerDetails(PlayerSearchResult playerResult) {
		contentPanel.removeAll();
		
		// Match GroupPanel structure exactly
		JPanel playerInfoPanel = new JPanel();
		playerInfoPanel.setLayout(new BoxLayout(playerInfoPanel, BoxLayout.Y_AXIS));
		playerInfoPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		playerInfoPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		
		// Player header panel - like groupHeaderPanel
		JPanel playerHeaderPanel = new JPanel(new BorderLayout(10, 0));
		playerHeaderPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		playerHeaderPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 60));
		playerHeaderPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 60));
		playerHeaderPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Player name and description
		JPanel playerNamePanel = new JPanel();
		playerNamePanel.setLayout(new BoxLayout(playerNamePanel, BoxLayout.Y_AXIS));
		playerNamePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		
		JLabel playerNameLabel = new JLabel(playerResult.getPlayerName());
		playerNameLabel.setFont(FontManager.getRunescapeBoldFont());
		playerNameLabel.setForeground(Color.WHITE);
		playerNameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		String statusText = playerResult.isRegistered() ? "Registered Player" : "Unregistered Player";
		JLabel playerDescLabel = new JLabel(statusText);
		playerDescLabel.setFont(FontManager.getRunescapeSmallFont());
		playerDescLabel.setForeground(playerResult.isRegistered() ? Color.GREEN : Color.ORANGE);
		playerDescLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		playerNamePanel.add(playerNameLabel);
		playerNamePanel.add(Box.createRigidArea(new Dimension(0, 5)));
		playerNamePanel.add(playerDescLabel);
		
		// Add groups information if available
		if (playerResult.getGroups() != null && !playerResult.getGroups().isEmpty()) {
			JPanel groupsPanel = createGroupsPanel(playerResult.getGroups());
			playerNamePanel.add(groupsPanel);
		}
		
		// Clear button for closing the search result
		JButton clearButton = LeaderboardComponents.createClearButton(() -> {
			searchField.setText("");
			showDefaultState();
		});

		playerHeaderPanel.add(playerNamePanel, BorderLayout.CENTER);
		playerHeaderPanel.add(clearButton, BorderLayout.EAST);
		
		// Stats panel - exactly like GroupPanel statsPanel
		JPanel statsPanel = new JPanel(new GridLayout(2, 2, 5, 5));
		statsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		statsPanel.setBorder(new EmptyBorder(10, 0, 10, 0));
		statsPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 100));
		statsPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 100));
		statsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Format numbers nicely
		String globalRankFormatted = "#" + playerResult.getGlobalRank();
		
		// Format top NPC information
		
		JPanel totalLootBox = PanelElements.createStatBox("Total Loot", playerResult.getTotalLoot() + " GP");
		JPanel globalRankBox = PanelElements.createStatBox("Global Rank", globalRankFormatted);
		
		statsPanel.add(totalLootBox);
		statsPanel.add(globalRankBox);
		
		// Action buttons - exactly like GroupPanel actionPanel
		JPanel actionPanel = new JPanel();
		actionPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 10, 0));
		actionPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		actionPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 40));
		actionPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 40));
		actionPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JButton refreshButton = new JButton("Refresh Stats");
		refreshButton.setMargin(new Insets(0, 5, 0, 5));
		refreshButton.addActionListener(e -> {
			performPlayerSearch(playerResult.getPlayerName());
		});
		
		JButton viewProfileButton = new JButton("⇱ View Profile");
		viewProfileButton.setFont(FontManager.getRunescapeSmallFont());
		viewProfileButton.setMargin(new Insets(0, 5, 0, 5));
		viewProfileButton.addActionListener(e -> {
			if (playerResult.getDropTrackerPlayerId() != null) {
				LinkBrowser.browse("https://www.droptracker.io/players/" + playerResult.getDropTrackerPlayerId() + "/view");
			} else {
				LinkBrowser.browse("https://www.droptracker.io/players/" + playerResult.getPlayerName() + "/view");
			}
		});
		
		actionPanel.add(refreshButton);
		actionPanel.add(viewProfileButton);
		
		// Add components exactly like GroupPanel
		playerInfoPanel.add(playerHeaderPanel);
		playerInfoPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		playerInfoPanel.add(statsPanel);
		playerInfoPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		
		// Add recent submissions panel if available
		if (playerResult.getRecentSubmissions() != null && !playerResult.getRecentSubmissions().isEmpty()) {
			List<RecentSubmission> recentSubmissions = playerResult.getRecentSubmissions();
			playerInfoPanel.add(PanelElements.createRecentSubmissionPanel(recentSubmissions, itemManager, client, false));
			playerInfoPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		} else {
			// Create a placeholder panel that matches the exact dimensions of createRecentSubmissionPanel
			JPanel noSubmissionsContainer = new JPanel();
			noSubmissionsContainer.setLayout(new BoxLayout(noSubmissionsContainer, BoxLayout.Y_AXIS));
			noSubmissionsContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			noSubmissionsContainer.setBorder(new EmptyBorder(10, 0, 10, 0));
			noSubmissionsContainer.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 120)); // Match original
			noSubmissionsContainer.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 120));
			noSubmissionsContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
			
			// Title panel to match original structure
			JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
			titlePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			titlePanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 20));
			titlePanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 20));
			
			JLabel title = new JLabel("Recent Submissions");
			title.setFont(FontManager.getRunescapeSmallFont());
			title.setForeground(Color.WHITE);
			titlePanel.add(title);
			
			// Content panel to match original structure
			JPanel contentWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
			contentWrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			contentWrapper.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 80));
			contentWrapper.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 80));
			
			JLabel noSubmissionsLabel = new JLabel("No recent submissions available");
			noSubmissionsLabel.setFont(FontManager.getRunescapeSmallFont());
			noSubmissionsLabel.setForeground(Color.LIGHT_GRAY);
			noSubmissionsLabel.setHorizontalAlignment(JLabel.CENTER);
			contentWrapper.add(noSubmissionsLabel);
			
			// Assemble the container exactly like the original
			noSubmissionsContainer.add(titlePanel);
			noSubmissionsContainer.add(Box.createRigidArea(new Dimension(0, 5))); // Match original spacing
			noSubmissionsContainer.add(contentWrapper);
			
			playerInfoPanel.add(noSubmissionsContainer);
			playerInfoPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		}
		
		playerInfoPanel.add(actionPanel);
		
		contentPanel.add(playerInfoPanel);
		contentPanel.revalidate();
		contentPanel.repaint();
	}
	
	private JPanel createGroupsPanel(List<PlayerSearchResult.PlayerGroup> groups) {
		if (groups == null || groups.isEmpty()) {
			return new JPanel(); // Return empty panel if no groups
		}
		
		JPanel groupsContainer = new JPanel();
		groupsContainer.setLayout(new BoxLayout(groupsContainer, BoxLayout.Y_AXIS));
		groupsContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		groupsContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Groups header
		JLabel groupsHeaderLabel = new JLabel("Groups:");
		groupsHeaderLabel.setFont(FontManager.getRunescapeSmallFont());
		groupsHeaderLabel.setForeground(Color.LIGHT_GRAY);
		groupsHeaderLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		groupsContainer.add(groupsHeaderLabel);
		
		// Add each group as a separate label for proper scaling
		for (PlayerSearchResult.PlayerGroup group : groups) {
			StringBuilder groupText = new StringBuilder("• ");
			groupText.append(group.getName());
			if (group.getMembers() != null) {
				groupText.append(" (").append(group.getMembers()).append(" members)");
			}
			
			JLabel groupLabel = new JLabel(groupText.toString());
			groupLabel.setFont(FontManager.getRunescapeSmallFont());
			groupLabel.setForeground(Color.LIGHT_GRAY);
			groupLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			
			groupsContainer.add(groupLabel);
		}
		
		return groupsContainer;
	}

}
