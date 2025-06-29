package io.droptracker.ui.pages;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.concurrent.CompletableFuture;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.StrokeBorder;

import javax.inject.Inject;

import io.droptracker.DropTrackerConfig;
import io.droptracker.ui.PanelElements;
import io.droptracker.util.ChatMessageUtil;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.api.PlayerSearchResult;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

public class PlayerStatsPanel {

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DropTrackerConfig config;
	
	@Inject
	private ChatMessageUtil chatMessageUtil;

	@Inject
	private DropTrackerApi api;

	private int currentPlayerId = -1;
	
	// UI components that we need to update
	private JPanel mainPanel;
	private JPanel contentPanel;
	private JTextField searchField;

	public PlayerStatsPanel(Client client, ClientThread clientThread, DropTrackerConfig config, ChatMessageUtil chatMessageUtil, DropTrackerApi api) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.chatMessageUtil = chatMessageUtil;
		this.api = api;
	}

	public JPanel create() {
		mainPanel = new JPanel();
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		
		// Header section with title and search
		JPanel headerPanel = createHeaderPanel();
		
		// Content panel that will change based on state
		contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		contentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Show default state
		showDefaultState();
		
		// Add components to main panel - match GroupPanel structure
		mainPanel.add(headerPanel);
		mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		mainPanel.add(contentPanel);
		mainPanel.add(Box.createVerticalGlue());
		
		return mainPanel;
	}
	
	private JPanel createHeaderPanel() {
		JPanel headerPanel = new JPanel(new BorderLayout());
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		headerPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 90));
		headerPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 90));
		headerPanel.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH, 90));
		
		// Create a vertical panel for title and search
		JPanel titleAndSearchPanel = new JPanel();
		titleAndSearchPanel.setLayout(new BoxLayout(titleAndSearchPanel, BoxLayout.Y_AXIS));
		titleAndSearchPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		
		JLabel playerTitle = new JLabel("DropTracker - Players");
		playerTitle.setFont(FontManager.getRunescapeBoldFont());
		playerTitle.setForeground(Color.WHITE);
		playerTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
		playerTitle.setHorizontalAlignment(JLabel.CENTER);
		playerTitle.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
		playerTitle.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
		
		// Search field for players
		JPanel searchPanel = new JPanel(new BorderLayout(5, 0));
		searchPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchPanel.setBorder(new StrokeBorder(new BasicStroke(1), ColorScheme.DARKER_GRAY_HOVER_COLOR));
		searchPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
		searchPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));
		searchPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));
		searchPanel.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));
		
		searchField = new JTextField();
		searchField.setBorder(new StrokeBorder(new BasicStroke(1), ColorScheme.DARKER_GRAY_HOVER_COLOR));
		searchField.setToolTipText("Search for a player");
		searchField.setHorizontalAlignment(JTextField.LEFT);
		searchField.setMargin(new Insets(5, 8, 5, 8));
		searchField.setFont(FontManager.getRunescapeSmallFont());
		searchField.setPreferredSize(new Dimension(200, 35));
		searchField.setMinimumSize(new Dimension(100, 35));
		
		JButton searchButton = new JButton("Search");
		searchButton.setPreferredSize(new Dimension(70, 35));
		searchButton.setMaximumSize(new Dimension(70, 35));
		searchButton.setMinimumSize(new Dimension(70, 35));
		searchButton.setMargin(new Insets(5, 5, 5, 5));
		searchButton.addActionListener(e -> performPlayerSearch());
		
		searchPanel.add(searchField, BorderLayout.CENTER);
		searchPanel.add(searchButton, BorderLayout.EAST);
		
		titleAndSearchPanel.add(playerTitle);
		titleAndSearchPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		titleAndSearchPanel.add(searchPanel);
		
		// Add the panel to the center of the BorderLayout
		headerPanel.add(titleAndSearchPanel, BorderLayout.CENTER);
		
		return headerPanel;
	}
	
	private void showDefaultState() {
		contentPanel.removeAll();
		
		// Create center panel for current player info
		JPanel defaultPanel = new JPanel();
		defaultPanel.setLayout(new BoxLayout(defaultPanel, BoxLayout.Y_AXIS));
		defaultPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		defaultPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
		
		// Add some spacing
		defaultPanel.add(Box.createRigidArea(new Dimension(0, 25)));
		
		// Get current player or show message
		String playerName = (client.getLocalPlayer() != null) ? client.getLocalPlayer().getName() : "Not logged in";
		
		if (!"Not logged in".equals(playerName)) {
			// Show current player info
			JLabel currentPlayerLabel = new JLabel("Current Player: " + playerName);
			currentPlayerLabel.setFont(FontManager.getRunescapeBoldFont());
			currentPlayerLabel.setForeground(Color.WHITE);
			currentPlayerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			currentPlayerLabel.setHorizontalAlignment(JLabel.CENTER);
			
			JLabel instructionLabel = new JLabel("Search for other players above");
			instructionLabel.setFont(FontManager.getRunescapeSmallFont());
			instructionLabel.setForeground(Color.LIGHT_GRAY);
			instructionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			instructionLabel.setHorizontalAlignment(JLabel.CENTER);
			
			// Button to view current player stats
			JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
			buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
			buttonPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
			
			JButton viewStatsButton = new JButton("⚡ View My Stats");
			viewStatsButton.setFont(FontManager.getRunescapeSmallFont());
			viewStatsButton.setPreferredSize(new Dimension(150, 30));
			viewStatsButton.setToolTipText("View your DropTracker statistics");
			viewStatsButton.addActionListener(e -> performPlayerSearch(playerName));
			
			buttonPanel.add(viewStatsButton);
			
			defaultPanel.add(currentPlayerLabel);
			defaultPanel.add(Box.createRigidArea(new Dimension(0, 10)));
			defaultPanel.add(instructionLabel);
			defaultPanel.add(Box.createRigidArea(new Dimension(0, 20)));
			defaultPanel.add(buttonPanel);
		} else {
			// Instructions text for not logged in
			JLabel instructionLabel = new JLabel("Log in to see your stats");
			instructionLabel.setFont(FontManager.getRunescapeFont());
			instructionLabel.setForeground(Color.LIGHT_GRAY);
			instructionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			instructionLabel.setHorizontalAlignment(JLabel.CENTER);
			
			JLabel searchLabel = new JLabel("Or search for a player above");
			searchLabel.setFont(FontManager.getRunescapeSmallFont());
			searchLabel.setForeground(Color.LIGHT_GRAY);
			searchLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			searchLabel.setHorizontalAlignment(JLabel.CENTER);
			
			defaultPanel.add(instructionLabel);
			defaultPanel.add(Box.createRigidArea(new Dimension(0, 10)));
			defaultPanel.add(searchLabel);
		}
		
		defaultPanel.add(Box.createVerticalGlue());
		
		contentPanel.add(defaultPanel);
		contentPanel.revalidate();
		contentPanel.repaint();
	}
	
	private void performPlayerSearch() {
		String searchQuery = searchField.getText().trim();
		performPlayerSearch(searchQuery);
	}
	
	private void performPlayerSearch(String searchQuery) {
		if (searchQuery.isEmpty()) {
			JOptionPane.showMessageDialog(contentPanel, "Please enter a player name to search for.");
			return;
		}
		
		System.out.println("Searching for player: " + searchQuery);
		
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
				PlayerSearchResult playerResult = api.lookupPlayerNew(searchQuery);
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
					// Fallback to demo data for testing
					if (searchQuery.equalsIgnoreCase("test") || searchQuery.equalsIgnoreCase("demo")) {
						showPlayerDetails(createDemoPlayerResult());
					} else {
						showSearchError("Player '" + searchQuery + "' not found. API search failed.");
					}
				}
			});
		});
	}
	
	private void showSearchError(String message) {
		contentPanel.removeAll();
		
		JPanel errorPanel = new JPanel();
		errorPanel.setLayout(new BoxLayout(errorPanel, BoxLayout.Y_AXIS));
		errorPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		errorPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
		
		errorPanel.add(Box.createRigidArea(new Dimension(0, 50)));
		
		JLabel errorLabel = new JLabel(message);
		errorLabel.setFont(FontManager.getRunescapeFont());
		errorLabel.setForeground(Color.RED);
		errorLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		errorLabel.setHorizontalAlignment(JLabel.CENTER);
		
		JButton backButton = new JButton("Back to Search");
		backButton.setAlignmentX(Component.CENTER_ALIGNMENT);
		backButton.addActionListener(e -> {
			searchField.setText("");
			showDefaultState();
		});
		
		errorPanel.add(errorLabel);
		errorPanel.add(Box.createRigidArea(new Dimension(0, 20)));
		errorPanel.add(backButton);
		errorPanel.add(Box.createVerticalGlue());
		
		contentPanel.add(errorPanel);
		contentPanel.revalidate();
		contentPanel.repaint();
	}
	
	private PlayerSearchResult createDemoPlayerResult() {
		// Create demo data for testing
		PlayerSearchResult demo = new PlayerSearchResult();
		demo.setPlayerName("Demo Player");
		demo.setDropTrackerPlayerId(123);
		demo.setRegistered(true);
		demo.setTotalLoot(50000000L); // 50M GP
		demo.setGlobalRank(456);
		demo.setTopNpc("Vorkath");
		demo.setBestPbRank(12);
		
		// Create demo stats
		PlayerSearchResult.PlayerStats stats = new PlayerSearchResult.PlayerStats();
		stats.setTotalSubmissions(125);
		stats.setTotalLootValue(50000000L);
		stats.setAverageDropValue(400000L);
		stats.setBestDropValue(5000000L);
		stats.setFavoriteBoss("Vorkath");
		stats.setDaysActive(45);
		demo.setPlayerStats(stats);
		
		return demo;
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
		playerNamePanel.add(playerDescLabel);
		
		// Clear button for closing the search result
		JButton clearButton = new JButton("×");
		clearButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
		clearButton.setForeground(Color.LIGHT_GRAY);
		clearButton.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		clearButton.setBorder(new EmptyBorder(5, 8, 5, 8));
		clearButton.setPreferredSize(new Dimension(30, 30));
		clearButton.setMaximumSize(new Dimension(30, 30));
		clearButton.setMinimumSize(new Dimension(30, 30));
		clearButton.setToolTipText("Clear search");
		clearButton.setOpaque(false);
		clearButton.setContentAreaFilled(false);
		clearButton.addActionListener(e -> {
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
		String totalLootFormatted = String.format("%,.0f", (double)playerResult.getTotalLoot()) + " GP";
		String globalRankFormatted = "#" + playerResult.getGlobalRank();
		String bestPbRankFormatted = playerResult.getBestPbRank() != null ? "#" + playerResult.getBestPbRank() : "N/A";
		
		JPanel totalLootBox = PanelElements.createStatBox("Total Loot", totalLootFormatted);
		JPanel globalRankBox = PanelElements.createStatBox("Global Rank", globalRankFormatted);
		JPanel topNpcBox = PanelElements.createStatBox("Top NPC", playerResult.getTopNpc() != null ? playerResult.getTopNpc() : "N/A");
		JPanel bestPbBox = PanelElements.createStatBox("Best PB Rank", bestPbRankFormatted);
		
		statsPanel.add(totalLootBox);
		statsPanel.add(globalRankBox);
		statsPanel.add(topNpcBox);
		statsPanel.add(bestPbBox);
		
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
		playerInfoPanel.add(actionPanel);
		
		contentPanel.add(playerInfoPanel);
		contentPanel.revalidate();
		contentPanel.repaint();
	}
}
