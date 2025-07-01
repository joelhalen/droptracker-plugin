package io.droptracker.ui.pages;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.io.IOException;
import javax.imageio.ImageIO;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.ImageIcon;

import javax.inject.Inject;

import io.droptracker.DropTrackerConfig;
import io.droptracker.ui.DropTrackerPanelNew;
import io.droptracker.ui.components.LeaderboardComponents;
import io.droptracker.ui.components.PanelElements;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.api.GroupSearchResult;
import io.droptracker.models.api.TopGroupResult;
import io.droptracker.models.submissions.RecentSubmission;
import net.runelite.api.Client;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.LinkBrowser;

public class GroupPanel {

	@Inject
	private Client client;

	@Inject
	private DropTrackerConfig config;


	@Inject
	private DropTrackerPanelNew panel;

	@Inject
	private DropTrackerApi api;

	private ItemManager itemManager;

	private int currentGroupId = 2; // Track group ID instead of URL
	
	// UI components that we need to update
	private JPanel mainPanel;
	private JPanel contentPanel;
	private JTextField searchField;

	// Add field for tracking leaderboard placeholder
	private JPanel leaderboardPlaceholder;

	public GroupPanel(Client client, DropTrackerConfig config, DropTrackerApi api, ItemManager itemManager, DropTrackerPanelNew panel) {
		this.client = client;
		this.config = config;
		this.api = api;
		this.itemManager = itemManager;
		this.panel = panel;
	}

	public JPanel create() {
		mainPanel = new JPanel();
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		
		// Header section with title and search using LeaderboardComponents
		LeaderboardComponents.HeaderResult headerResult = LeaderboardComponents.createHeaderPanel(
			"DropTracker - Groups", 
			"Search for a group", 
			() -> performGroupSearch("")
		);
		searchField = headerResult.searchField;
		
		// Content panel that will change based on state - fix alignment
		contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		contentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Show default state
		showDefaultState();
		
		// Add components to main panel - match PlayerStatsPanel structure
		mainPanel.add(headerResult.panel);
		mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		mainPanel.add(contentPanel);
		mainPanel.add(Box.createVerticalGlue());
		
		return mainPanel;
	}
	

	
	private void showDefaultState() {
		contentPanel.removeAll();
		
		// Create center panel for the button
		JPanel defaultPanel = new JPanel();
		defaultPanel.setLayout(new BoxLayout(defaultPanel, BoxLayout.Y_AXIS));
		defaultPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		defaultPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
		
		// Instructions text
		JLabel instructionLabel = new JLabel("Search for a group by name above");
		instructionLabel.setFont(FontManager.getRunescapeFont());
		instructionLabel.setForeground(Color.LIGHT_GRAY);
		instructionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		instructionLabel.setHorizontalAlignment(JLabel.CENTER);

		
		JButton createGroupButton = PanelElements.createExternalLinkButton("⚡ Create a Group", "Click to visit the group creation documentation", false, () -> openCreateGroupPage());
		
		JButton groupPageButton = PanelElements.createExternalLinkButton("View All Groups", "Click to visit the group page", true, () -> openGroupPage());

		
		// Panel for first button - centered horizontally
		JPanel createButtonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		createButtonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		createButtonPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
		createButtonPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		createButtonPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 30));
		createButtonPanel.add(createGroupButton);
		
		// Panel for second button - centered horizontally  
		JPanel groupButtonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		groupButtonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		groupButtonPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
		groupButtonPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		groupButtonPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 30));
		groupButtonPanel.add(groupPageButton);
		
		defaultPanel.add(instructionLabel);
		defaultPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		
		if (config.useApi()) {
			// Create placeholder for leaderboard using LeaderboardComponents
			leaderboardPlaceholder = LeaderboardComponents.createLoadingPlaceholder("Loading top groups...");
			defaultPanel.add(leaderboardPlaceholder);
			defaultPanel.add(Box.createRigidArea(new Dimension(0, 10)));
			
			// Start loading data
			obtainLeaderboardData();
		}
		
		defaultPanel.add(createButtonPanel);
		defaultPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		defaultPanel.add(groupButtonPanel);
		defaultPanel.add(Box.createVerticalGlue());
		
		contentPanel.add(defaultPanel);
		contentPanel.revalidate();
		contentPanel.repaint();
	}

	private JPanel showLeaderboard(TopGroupResult leaderboardData) {
		return LeaderboardComponents.createLeaderboardTable(
			"Top Groups",
			"Name",
			leaderboardData != null ? leaderboardData.getGroups() : null,
			new LeaderboardComponents.LeaderboardItemRenderer<TopGroupResult.TopGroup>() {
				@Override
				public String getName(TopGroupResult.TopGroup group) {
					return group.getGroupName() != null && !group.getGroupName().trim().isEmpty() 
						? group.getGroupName() 
						: "Unknown Group";
				}

				@Override
				public String getLootValue(TopGroupResult.TopGroup group) {
					String loot = group.getTotalLoot();
					return (loot != null && !loot.trim().isEmpty()) ? loot : "0 GP";
				}

				@Override
				public Integer getRank(TopGroupResult.TopGroup group) {
					return group.getRank();
				}

				@Override
				public void onItemClick(TopGroupResult.TopGroup group) {
					// Search for this group when clicked
					CompletableFuture.supplyAsync(() -> {	
						try{
							showLoadingState();
							GroupSearchResult groupResult = api.searchGroup(group.getGroupName());
							if (groupResult != null) {
								showGroupDetails(groupResult);
							}
							return null;
						} catch (Exception ex) {
							System.err.println("Failed to search for group: " + ex.getMessage());
							return null;
						}
					});
				}
			}
		);
	}

	private void obtainLeaderboardData() {
		LeaderboardComponents.loadLeaderboardAsync(
			leaderboardPlaceholder,
			() -> {
				try {
					return api.getTopGroups();
				} catch (Exception e) {
					System.err.println("Failed to get top groups: " + e.getMessage());
					return null;
				}
			},
			this::showLeaderboard
		);
	}
	
	public void performGroupSearch(String directQuery) {
		String searchQuery;
		if (directQuery.equalsIgnoreCase("")) {
			searchQuery = searchField.getText().trim();
		} else {
			searchQuery = directQuery;
		}
		
		if (searchQuery.isEmpty()) {
			JOptionPane.showMessageDialog(contentPanel, "Please enter a group name to search for.");
			return;
		}
		
		System.out.println("Searching for group: " + searchQuery);
		
		// Show loading message
		showLoadingState();
		
		// Perform search in background
		CompletableFuture.supplyAsync(() -> {
			try {
				GroupSearchResult groupResult = api.searchGroup(searchQuery);
				return groupResult;
			} catch (Exception e) {
				System.err.println("Failed to search for group: " + e.getMessage());
				return null;
			}
		}).thenAccept(groupResult -> {
			SwingUtilities.invokeLater(() -> {
				if (groupResult != null) {
					showGroupDetails(groupResult);
					// Load the lootboard image when group is found
					if (groupResult.getGroupDropTrackerId() != null) {
						currentGroupId = groupResult.getGroupDropTrackerId();
						PanelElements.loadLootboardForGroup(currentGroupId);
					}
					PanelElements.cachedGroupName = groupResult.getGroupName();
				} else {
					// Fallback to demo data for testing
					if (searchQuery.equalsIgnoreCase("test") || searchQuery.equalsIgnoreCase("demo")) {
						showGroupDetails(createDemoGroupResult());
					} else {
						showSearchError("Group '" + searchQuery + "' not found. API search failed.");
					}
				}
			});
		});
	}

	private void showLoadingState() {
		contentPanel.removeAll();
		JLabel loadingLabel = new JLabel("Loading...");
		loadingLabel.setFont(FontManager.getRunescapeFont());
		loadingLabel.setForeground(Color.LIGHT_GRAY);
		loadingLabel.setHorizontalAlignment(JLabel.CENTER);
		contentPanel.add(loadingLabel);
		contentPanel.revalidate();
		contentPanel.repaint();
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
	
	private GroupSearchResult createDemoGroupResult() {
		// Create demo data for testing
		GroupSearchResult demo = new GroupSearchResult();
		demo.setGroupName("Demo Group");
		demo.setGroupDescription("This is a demo group that shows how the group panel works. You can see member statistics, lootboards, and other group information here.");
		//demo.setGroupDropTrackerId(2);
		demo.setGroupDropTrackerId(7);
		currentGroupId = 7;
		
		// Create demo stats
		GroupSearchResult.GroupStats stats = new GroupSearchResult.GroupStats();
		stats.setTotalMembers(5);
		stats.setGlobalRank("127");
		stats.setMonthlyLoot("15.2M"); // 15.2M GP
		demo.setGroupStats(stats);
		demo.setGroupTopPlayer("DemoPlayer");
		
		return demo;
	}
	
	private void showGroupDetails(GroupSearchResult groupResult) {
		contentPanel.removeAll();
		
		// Match PlayerStatsPanel structure exactly - no custom borders
		JPanel groupInfoPanel = new JPanel();
		groupInfoPanel.setLayout(new BoxLayout(groupInfoPanel, BoxLayout.Y_AXIS));
		groupInfoPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		groupInfoPanel.setBorder(new EmptyBorder(10, 10, 10, 10)); // Same as playerInfoPanel
		
		// Group header panel - exactly like playerHeaderPanel with clear button
		JPanel groupHeaderPanel = new JPanel(new BorderLayout(10, 0));
		groupHeaderPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		groupHeaderPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 60));
		groupHeaderPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 60));
		groupHeaderPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Group icon
		BufferedImage placeholderImg = new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB);
		JLabel groupIcon = new JLabel(new ImageIcon(placeholderImg));
		groupIcon.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		groupIcon.setPreferredSize(new Dimension(50, 50));
		groupIcon.setMaximumSize(new Dimension(50, 50));
		groupIcon.setMinimumSize(new Dimension(50, 50));
		loadGroupIcon(groupIcon, groupResult.getGroupImageUrl());
		
		// Group name and description
		JPanel groupNamePanel = new JPanel();
		groupNamePanel.setLayout(new BoxLayout(groupNamePanel, BoxLayout.Y_AXIS));
		groupNamePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		
		JLabel groupNameLabel = new JLabel(groupResult.getGroupName());
		groupNameLabel.setFont(FontManager.getRunescapeBoldFont());
		groupNameLabel.setForeground(Color.WHITE);
		groupNameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JLabel groupDescLabel = new JLabel("<html>" + groupResult.getGroupDescription() + "</html>");
		groupDescLabel.setFont(FontManager.getRunescapeSmallFont());
		groupDescLabel.setForeground(Color.LIGHT_GRAY);
		groupDescLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		groupNamePanel.add(groupNameLabel);
		groupNamePanel.add(groupDescLabel);
		
		// Clear button for closing the search result
		JButton clearButton = LeaderboardComponents.createClearButton(() -> {
			searchField.setText("");
			showDefaultState();
		});

		groupHeaderPanel.add(groupIcon, BorderLayout.WEST);
		groupHeaderPanel.add(groupNamePanel, BorderLayout.CENTER);	
		groupHeaderPanel.add(clearButton, BorderLayout.EAST);
		
		// Stats panel - exactly like playerStatsPanel
		JPanel statsPanel = new JPanel(new GridLayout(2, 2, 5, 5));
		statsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		statsPanel.setBorder(new EmptyBorder(10, 0, 10, 0)); // Same as playerStatsPanel
		statsPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 100));
		statsPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 100));
		statsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JPanel membersBox = PanelElements.createStatBox("Members", String.valueOf(groupResult.getGroupStats().getTotalMembers()));
		JPanel rankBox = PanelElements.createStatBox("Global Rank", "#" + groupResult.getGroupStats().getGlobalRank());
		JPanel lootBox = PanelElements.createStatBox("Monthly Loot", groupResult.getGroupStats().getMonthlyLoot() + " GP");
		JPanel topPlayerBox = PanelElements.createStatBox("Top Player", groupResult.getGroupTopPlayer());
		topPlayerBox.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		topPlayerBox.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				panel.selectPanel("players");
				String topPlayer = groupResult.getGroupTopPlayer().split("\\(")[0].trim();
				panel.updatePlayerPanel(topPlayer);
			}
		});
		
		statsPanel.add(membersBox);
		statsPanel.add(rankBox);
		statsPanel.add(lootBox);
		statsPanel.add(topPlayerBox);
		
		// Action buttons - exactly like actionPanel
		JPanel actionPanel = new JPanel();
		actionPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 10, 0));
		actionPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		actionPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 40));
		actionPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 40));
		actionPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		if (groupResult.getPublicDiscordLink() != null && !groupResult.getPublicDiscordLink().isEmpty()) {
			JButton joinButton = new JButton("Discord");
			joinButton.setMargin(new Insets(0, 5, 0, 5));
			joinButton.addActionListener(e -> LinkBrowser.browse(groupResult.getPublicDiscordLink()));
			actionPanel.add(joinButton);
		}
		
		JButton viewLootboardButton = PanelElements.createLootboardButton("View Lootboard", "Click to view the lootboard", () -> PanelElements.showLootboardForGroup(client, currentGroupId));
		actionPanel.add(viewLootboardButton);
		
		// Add components exactly like PlayerStatsPanel
		groupInfoPanel.add(groupHeaderPanel);
		groupInfoPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		groupInfoPanel.add(statsPanel);
		groupInfoPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		// Get recent submission data to draw 
		List<RecentSubmission> recentSubmissions = groupResult.getRecentSubmissions();
		if (recentSubmissions != null && !recentSubmissions.isEmpty()) {
			System.out.println("Recent submissions: " + recentSubmissions.size());
			System.out.println("First player: " + recentSubmissions.get(0).getPlayerName());
			groupInfoPanel.add(PanelElements.createRecentSubmissionPanel(recentSubmissions, itemManager, client, true));
		} else {
			System.out.println("No recent submissions found for this group");
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
			
			groupInfoPanel.add(noSubmissionsContainer);
		}
		groupInfoPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		groupInfoPanel.add(actionPanel);
		
		contentPanel.add(groupInfoPanel);
		contentPanel.revalidate();
		contentPanel.repaint();
	}
	
	private void openGroupPage() {
		try {
			LinkBrowser.browse("https://www.droptracker.io/groups");
		} catch (Exception e) {
			System.err.println("Failed to open browser: " + e.getMessage());
			// Fallback: copy URL to clipboard or show message
			JOptionPane.showMessageDialog(contentPanel, 
				"Could not open browser. Please visit:\nhttps://www.droptracker.io/groups", 
				"Group Page", 
				JOptionPane.INFORMATION_MESSAGE);
		}
	}

	private void openCreateGroupPage() {
		try {
			LinkBrowser.browse("https://www.droptracker.io/wiki/create-group");
		} catch (Exception e) {
			System.err.println("Failed to open browser: " + e.getMessage());
			// Fallback: copy URL to clipboard or show message
			JOptionPane.showMessageDialog(contentPanel, 
				"Could not open browser. Please visit:\nhttps://droptracker.io/wiki/create-group", 
				"Create Group Guide", 
				JOptionPane.INFORMATION_MESSAGE);
		}
	}

	/**
	 * Downloads the group icon from the provided URL, scales it to 50×50, then swaps it into the given label.
	 * This runs off the EDT to avoid blocking the UI.
	 */
	private void loadGroupIcon(JLabel iconLabel, String inputString) {
		if (inputString == null || inputString.trim().isEmpty()) {
			System.out.println("Group icon URL is null or empty, skipping icon load");
			return; // nothing to load
		}
		
		// we can't load gifs in swing panels natively, so we swap for a png alternative hoping it exists
		String urlString = inputString.replace(".gif", ".png");
		
		// Validate URL format
		try {
			new URL(urlString); // This will throw if URL is malformed
		} catch (Exception e) {
			System.err.println("Invalid group icon URL format: " + urlString + " - " + e.getMessage());
			return;
		}

		CompletableFuture.supplyAsync(() -> {
			try {
				System.out.println("Attempting to load group icon from: " + urlString);
				BufferedImage img = ImageIO.read(new URL(urlString));
				if (img == null) {
					System.err.println("ImageIO.read returned null for URL: " + urlString);
					return null;
				}
				Image scaled = img.getScaledInstance(50, 50, Image.SCALE_SMOOTH);
				System.out.println("Successfully loaded and scaled group icon");
				return new ImageIcon(scaled);
			} catch (IOException e) {
				System.err.println("Failed to load group icon from URL: " + urlString + " - " + e.getMessage());
				// Try the original URL if the .png replacement failed
				if (urlString.endsWith(".png") && !inputString.endsWith(".png")) {
					try {
						System.out.println("Attempting to load original URL: " + inputString);
						BufferedImage img = ImageIO.read(new URL(inputString));
						if (img != null) {
							Image scaled = img.getScaledInstance(50, 50, Image.SCALE_SMOOTH);
							System.out.println("Successfully loaded original group icon");
							return new ImageIcon(scaled);
						}
					} catch (IOException ex) {
						System.err.println("Failed to load original group icon: " + ex.getMessage());
					}
				}
				return null;
			} catch (Exception e) {
				System.err.println("Unexpected error loading group icon: " + e.getMessage());
				return null;
			}
		}).thenAccept(icon -> {
			if (icon != null) {
				SwingUtilities.invokeLater(() -> iconLabel.setIcon(icon));
			} else {
				System.out.println("No icon loaded, keeping placeholder");
			}
		});
	}

	
}
