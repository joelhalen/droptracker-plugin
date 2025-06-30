package io.droptracker.ui.pages;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Map;
import java.awt.Graphics;
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
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.StrokeBorder;
import javax.swing.JLayeredPane;
import javax.swing.ImageIcon;
import javax.swing.event.DocumentListener;

import javax.inject.Inject;

import io.droptracker.DropTrackerConfig;
import io.droptracker.ui.PanelElements;
import io.droptracker.util.ChatMessageUtil;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.api.GroupSearchResult;
import io.droptracker.models.api.TopGroupResult;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

public class GroupPanel {

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

	private ItemManager itemManager;

	private int currentGroupId = 2; // Track group ID instead of URL
	
	// UI components that we need to update
	private JPanel mainPanel;
	private JPanel contentPanel;
	private JTextField searchField;

	// Add field for tracking leaderboard placeholder
	private JPanel leaderboardPlaceholder;

	public GroupPanel(Client client, ClientThread clientThread, DropTrackerConfig config, ChatMessageUtil chatMessageUtil, DropTrackerApi api, ItemManager itemManager) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.chatMessageUtil = chatMessageUtil;
		this.api = api;
		this.itemManager = itemManager;
	}

	public JPanel create() {
		mainPanel = new JPanel();
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		
		// Header section with title and search
		JPanel headerPanel = createHeaderPanel();
		
		// Content panel that will change based on state - fix alignment
		contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		contentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Show default state
		showDefaultState();
		
		// Add components to main panel - match PlayerStatsPanel structure
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
		
		JLabel groupTitle = new JLabel("DropTracker - Groups");
		groupTitle.setFont(FontManager.getRunescapeBoldFont());
		groupTitle.setForeground(Color.WHITE);
		groupTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
		groupTitle.setHorizontalAlignment(JLabel.CENTER);
		groupTitle.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
		groupTitle.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
		
		// Search field for groups - ORIGINAL structure
		JPanel searchPanel = new JPanel(new BorderLayout(5, 0));
		searchPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchPanel.setBorder(new StrokeBorder(new BasicStroke(1), ColorScheme.DARKER_GRAY_HOVER_COLOR));
		searchPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
		searchPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));
		searchPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));
		searchPanel.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));
		
		searchField = new JTextField();
		searchField.setBorder(new StrokeBorder(new BasicStroke(1), ColorScheme.DARKER_GRAY_HOVER_COLOR));
		searchField.setToolTipText("Search for a group");
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
		searchButton.addActionListener(e -> performGroupSearch());
		
		// ORIGINAL simple layout
		searchPanel.add(searchField, BorderLayout.CENTER);
		searchPanel.add(searchButton, BorderLayout.EAST);
		
		titleAndSearchPanel.add(groupTitle);
		titleAndSearchPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		titleAndSearchPanel.add(searchPanel);
		
		// Add the panel to the center of the BorderLayout
		headerPanel.add(titleAndSearchPanel, BorderLayout.CENTER);
		
		return headerPanel;
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
		defaultPanel.add(Box.createRigidArea(new Dimension(0, 15)));
		
		if (config.useApi()) {
			// Create placeholder for leaderboard
			leaderboardPlaceholder = new JPanel();
			leaderboardPlaceholder.setLayout(new BoxLayout(leaderboardPlaceholder, BoxLayout.Y_AXIS));
			leaderboardPlaceholder.setBackground(ColorScheme.DARK_GRAY_COLOR);
			leaderboardPlaceholder.setAlignmentX(Component.CENTER_ALIGNMENT);
			
			JLabel loadingLabel = new JLabel("Loading top groups...");
			loadingLabel.setFont(FontManager.getRunescapeSmallFont());
			loadingLabel.setForeground(Color.LIGHT_GRAY);
			loadingLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			loadingLabel.setHorizontalAlignment(JLabel.CENTER);
			
			leaderboardPlaceholder.add(loadingLabel);
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
		JPanel leaderboardPanel = new JPanel();
		leaderboardPanel.setLayout(new BoxLayout(leaderboardPanel, BoxLayout.Y_AXIS));
		leaderboardPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		leaderboardPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
		
		// Title for the leaderboard
		JLabel leaderboardTitle = new JLabel("Top Groups");
		leaderboardTitle.setFont(FontManager.getRunescapeBoldFont());
		leaderboardTitle.setForeground(Color.WHITE);
		leaderboardTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
		leaderboardTitle.setHorizontalAlignment(JLabel.CENTER);
		
		// Create table container with border similar to other panels
		JPanel tableContainer = new JPanel();
		tableContainer.setLayout(new BoxLayout(tableContainer, BoxLayout.Y_AXIS));
		tableContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		tableContainer.setBorder(new EmptyBorder(10, 10, 10, 10));
		tableContainer.setAlignmentX(Component.CENTER_ALIGNMENT);
		tableContainer.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 200));
		
		// Create table header
		JPanel headerRow = new JPanel(new BorderLayout(5, 0));
		headerRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerRow.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
		headerRow.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
		
		JLabel rankHeader = new JLabel("Rank");
		rankHeader.setFont(FontManager.getRunescapeBoldFont());
		rankHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		rankHeader.setHorizontalAlignment(JLabel.LEFT);
		rankHeader.setPreferredSize(new Dimension(40, 25));
		
		// Create sub-panel for name and loot columns
		JPanel nameAndLootHeader = new JPanel(new BorderLayout(10, 0));
		nameAndLootHeader.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		
		JLabel nameHeader = new JLabel("Name");
		nameHeader.setFont(FontManager.getRunescapeBoldFont());
		nameHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		nameHeader.setHorizontalAlignment(JLabel.LEFT);
		
		JLabel lootHeader = new JLabel("Loot");
		lootHeader.setFont(FontManager.getRunescapeBoldFont());
		lootHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		lootHeader.setHorizontalAlignment(JLabel.RIGHT);
		lootHeader.setPreferredSize(new Dimension(50, 25));
		
		nameAndLootHeader.add(nameHeader, BorderLayout.CENTER);
		nameAndLootHeader.add(lootHeader, BorderLayout.EAST);
		
		headerRow.add(rankHeader, BorderLayout.WEST);
		headerRow.add(nameAndLootHeader, BorderLayout.CENTER);
		
		// Create data rows
		JPanel dataContainer = new JPanel();
		dataContainer.setLayout(new BoxLayout(dataContainer, BoxLayout.Y_AXIS));
		dataContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		dataContainer.setAlignmentX(Component.CENTER_ALIGNMENT);
		
		// Display top groups from API response
		if (leaderboardData != null && leaderboardData.getGroups() != null) {
			int displayRank = 1;
			for (TopGroupResult.TopGroup groupData : leaderboardData.getGroups()) {
				if (displayRank > 5) break; // Only show top 5
				
				JPanel dataRow = new JPanel(new BorderLayout(5, 0));
				dataRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				dataRow.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
				dataRow.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
				
				// Rank - use API rank if available, otherwise use display position
				Integer apiRank = groupData.getRank();
				int rankToShow = (apiRank != null) ? apiRank : displayRank;
				
				JLabel rankLabel = new JLabel("#" + rankToShow);
				rankLabel.setFont(FontManager.getRunescapeSmallFont());
				rankLabel.setForeground(rankToShow <= 3 ? ColorScheme.PROGRESS_COMPLETE_COLOR : Color.WHITE);
				rankLabel.setHorizontalAlignment(JLabel.LEFT);
				rankLabel.setPreferredSize(new Dimension(40, 25));
				
				// Create sub-panel for name and loot columns
				JPanel nameAndLootData = new JPanel(new BorderLayout(10, 0));
				nameAndLootData.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				
				// Group Name from API
				final String groupName = groupData.getGroupName() == null || groupData.getGroupName().trim().isEmpty() 
					? "Unknown Group" 
					: groupData.getGroupName();
				
				JButton nameButton = new JButton("<html>" + groupName + "&nbsp;&nbsp;<img src='https://www.droptracker.io/img/external-8px-g.png'></html>");
				nameButton.setFont(FontManager.getRunescapeSmallFont());
				nameButton.setForeground(Color.WHITE);
				nameButton.setHorizontalAlignment(JLabel.LEFT);
				nameButton.setBorder(null);
				nameButton.addActionListener(e -> {
					
					CompletableFuture.supplyAsync(() -> {	
						try{
							showLoadingState();
							GroupSearchResult groupResult = api.searchGroup(groupName);
							if (groupResult != null) {
								showGroupDetails(groupResult);
							}
							return null;
						} catch (Exception ex) {
							System.err.println("Failed to search for group: " + ex.getMessage());
							return null;
						}
					});
				});
				
				// Total Loot from API
				String totalLoot = groupData.getTotalLoot();
				if (totalLoot == null || totalLoot.trim().isEmpty()) {
					totalLoot = "0 GP";
				}
				
				JLabel lootLabel = new JLabel(totalLoot);
				lootLabel.setFont(FontManager.getRunescapeSmallFont());
				lootLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
				lootLabel.setHorizontalAlignment(JLabel.RIGHT);
				lootLabel.setPreferredSize(new Dimension(50, 25));
				
				nameAndLootData.add(nameButton, BorderLayout.CENTER);
				nameAndLootData.add(lootLabel, BorderLayout.EAST);
				
				dataRow.add(rankLabel, BorderLayout.WEST);
				dataRow.add(nameAndLootData, BorderLayout.CENTER);
				
				dataContainer.add(dataRow);
				if (displayRank < 5) {
					dataContainer.add(Box.createRigidArea(new Dimension(0, 3))); // Small spacing between rows
				}
				
				displayRank++;
			}
		} else {
			// Fallback if no data
			JLabel noDataLabel = new JLabel("No leaderboard data available");
			noDataLabel.setFont(FontManager.getRunescapeSmallFont());
			noDataLabel.setForeground(Color.LIGHT_GRAY);
			noDataLabel.setHorizontalAlignment(JLabel.CENTER);
			noDataLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			dataContainer.add(noDataLabel);
		}
		
		// Assemble the table
		tableContainer.add(headerRow);
		tableContainer.add(Box.createRigidArea(new Dimension(0, 5))); // Spacing after header
		tableContainer.add(dataContainer);
		
		// Add to main panel
		leaderboardPanel.add(leaderboardTitle);
		leaderboardPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		leaderboardPanel.add(tableContainer);
		
		return leaderboardPanel;
	}

	private void obtainLeaderboardData() {
		CompletableFuture.supplyAsync(() -> {
			try {
				TopGroupResult groupResult = api.getTopGroups();
				if (groupResult != null) {
					// Pre-build the leaderboard panel off the EDT to avoid latency
					return showLeaderboard(groupResult);
				}
			} catch (Exception e) {
				System.err.println("Failed to get top groups: " + e.getMessage());
			}
			return null;
		}).thenAccept(leaderboardPanel -> {
			SwingUtilities.invokeLater(() -> {
				if (leaderboardPanel != null && leaderboardPlaceholder != null) {
					// Get the parent panel (defaultPanel)
					Container parent = leaderboardPlaceholder.getParent();
					if (parent != null) {
						// Find the index of the placeholder
						int index = -1;
						for (int i = 0; i < parent.getComponentCount(); i++) {
							if (parent.getComponent(i) == leaderboardPlaceholder) {
								index = i;
								break;
							}
						}
						
						if (index != -1) {
							// Remove placeholder and add the real leaderboard at the same position
							parent.remove(index);
							parent.add(leaderboardPanel, index);
							parent.revalidate();
							parent.repaint();
						}
					}
				}
			});
		});
	}
	
	private void performGroupSearch() {
		String searchQuery = searchField.getText().trim();
		
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
		// topPlayerBox.addMouseListener(new MouseAdapter() {
		// 	@Override
		// 	public void mouseClicked(MouseEvent e) {
		// 		searchField.setText(groupResult.getGroupTopPlayer());
		// 		performGroupSearch();
		// 	}
		// }); TODO -- send to player stats panel for this player
		
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
		List<GroupSearchResult.RecentSubmission> recentSubmissions = groupResult.getRecentSubmissions();
		System.out.println("Recent submissions: " + recentSubmissions.size());
		System.out.println("First player: " + recentSubmissions.get(0).getPlayerName());
		groupInfoPanel.add(createRecentSubmissionPanel(recentSubmissions, itemManager));
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
	private void loadGroupIcon(JLabel iconLabel, String urlString) {
		if (urlString == null || urlString.isEmpty()) {
			return; // nothing to load
		}

		CompletableFuture.supplyAsync(() -> {
			try {
				BufferedImage img = ImageIO.read(new URL(urlString));
				if (img == null) {
					return null;
				}
				Image scaled = img.getScaledInstance(50, 50, Image.SCALE_SMOOTH);
				return new ImageIcon(scaled);
			} catch (IOException e) {
				System.err.println("Failed to load group icon: " + e.getMessage());
				return null;
			}
		}).thenAccept(icon -> {
			if (icon != null) {
				SwingUtilities.invokeLater(() -> iconLabel.setIcon(icon));
			}
		});
	}

	private JPanel createRecentSubmissionPanel(List<GroupSearchResult.RecentSubmission> recentSubmissions,
			ItemManager itemManager) {
		System.out.println("Creating recent submission panel...");
		
		// Main container with title and submissions
		JPanel container = new JPanel();
		container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
		container.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		container.setBorder(new EmptyBorder(10, 0, 10, 0));
		container.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 120)); // Adjusted height
		container.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 120));
		container.setAlignmentX(Component.LEFT_ALIGNMENT); // Keep consistent with parent
		
		// Title panel to ensure centering
		JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		titlePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		titlePanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 20));
		titlePanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 20));
		
		JLabel title = new JLabel("Recent Submissions");
		title.setFont(FontManager.getRunescapeSmallFont());
		title.setForeground(Color.WHITE);
		titlePanel.add(title);
		
		// Submissions panel - use FlowLayout wrapper to center the GridBagLayout
		JPanel submissionWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		submissionWrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		submissionWrapper.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 80));
		submissionWrapper.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 80));
		
		JPanel panel = new JPanel();
		panel.setLayout(new GridBagLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 80)); 
		
		submissionWrapper.add(updateRecentSubmissionPanel(panel, recentSubmissions, itemManager));

		// Add components to container
		container.add(titlePanel);
		container.add(Box.createRigidArea(new Dimension(0, 5))); // Small gap between title and submissions
		container.add(submissionWrapper);
		
		return container;
	}

	private JPanel updateRecentSubmissionPanel(JPanel panel, List<GroupSearchResult.RecentSubmission> recentSubmissions, ItemManager itemManager) {
		panel.removeAll();
		
		// Debug logging
		System.out.println("Starting updateRecentSubmissionPanel with " + recentSubmissions.size() + " submissions");
		System.out.println("ItemManager is null: " + (itemManager == null));

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.NONE;
		c.anchor = GridBagConstraints.CENTER;
		c.weightx = 0.2; // Equal weight for 5 columns
		c.weighty = 0.5; // Equal weight for 2 rows
		c.gridx = 0;
		c.gridy = 0;	
		c.insets = new Insets(1, 1, 1, 1); // Padding around each icon

		int successfullyAdded = 0;
		final int ITEMS_PER_ROW = 5;
		final int MAX_ITEMS = 10;

		// Add each submission icon to the panel (limit to 10 items)
		for (int i = 0; i < Math.min(recentSubmissions.size(), MAX_ITEMS); i++) {
			GroupSearchResult.RecentSubmission submission = recentSubmissions.get(i);
			System.out.println("Processing submission " + i + ": type=" + submission.getSubmissionType() + 
				", player=" + submission.getPlayerName());
			
			try {
				JLabel iconContainer = null;
				
				if (submission.getSubmissionType().equalsIgnoreCase("drop")) {
					// Handle drops
					Integer itemId = submission.getDropItemId();
					Integer quantity = submission.getDropQuantity();
					
					System.out.println(submission.toString());
					
					if (itemId != null && quantity != null && itemManager != null) {
						final AsyncBufferedImage originalImage = itemManager.getImage(itemId, quantity, quantity > 1);
						final float alpha = (quantity > 0 ? 1.0f : 0.5f);
						
						// Create a scaled version of the image for initial display
						BufferedImage scaledImage = new BufferedImage(28, 28, BufferedImage.TYPE_INT_ARGB);
						BufferedImage opaque = ImageUtil.alphaOffset(scaledImage, alpha);		

						final JLabel dropContainer = PanelElements.createStyledIconContainer();
						dropContainer.setToolTipText(buildSubmissionTooltip(submission));
						dropContainer.setIcon(new ImageIcon(opaque));
						iconContainer = dropContainer;

						originalImage.onLoaded(() -> {
							// Scale the loaded image to 16x16
							Image scaled = originalImage.getScaledInstance(28, 28, Image.SCALE_SMOOTH);
							BufferedImage scaledBuffered = new BufferedImage(28, 28, BufferedImage.TYPE_INT_ARGB);
							Graphics g = scaledBuffered.getGraphics();
							g.drawImage(scaled, 0, 0, null);
							g.dispose(); // Clean up graphics resources
							
							BufferedImage finalImage = ImageUtil.alphaOffset(scaledBuffered, alpha);
							dropContainer.setIcon(new ImageIcon(finalImage));
							dropContainer.revalidate();
							dropContainer.repaint();
						});
						
						System.out.println("  Successfully added drop icon");
					} else {
						System.out.println("  Skipped drop - missing data or null itemManager");
					}
				} else if (submission.getSubmissionType().equalsIgnoreCase("clog")) {
					// Handle collection log items
					Integer itemId = submission.getClogItemId();
					
					System.out.println("  Clog - itemId=" + itemId);
					
					if (itemId != null && itemManager != null) {
						final AsyncBufferedImage originalImage = itemManager.getImage(itemId, 1, false);
						final float alpha = 1.0f;
						
						// Create a scaled version of the image for initial display
						BufferedImage scaledImage = new BufferedImage(28, 28, BufferedImage.TYPE_INT_ARGB);
						BufferedImage opaque = ImageUtil.alphaOffset(scaledImage, alpha);

						final JLabel clogContainer = PanelElements.createStyledIconContainer();
						clogContainer.setToolTipText(buildSubmissionTooltip(submission));
						clogContainer.setIcon(new ImageIcon(opaque));
						iconContainer = clogContainer;

						originalImage.onLoaded(() -> {
							// Scale the loaded image to 16x16
							Image scaled = originalImage.getScaledInstance(28, 28, Image.SCALE_SMOOTH);
							BufferedImage scaledBuffered = new BufferedImage(28, 28, BufferedImage.TYPE_INT_ARGB);
							Graphics g = scaledBuffered.getGraphics();
							g.drawImage(scaled, 0, 0, null);
							g.dispose(); // Clean up graphics resources
							
							BufferedImage finalImage = ImageUtil.alphaOffset(scaledBuffered, alpha);
							clogContainer.setIcon(new ImageIcon(finalImage));
							clogContainer.revalidate();
							clogContainer.repaint();
						});
						
						System.out.println("  Successfully added clog icon");
					} else {
						System.out.println("  Skipped clog - missing data or null itemManager");
					}
				} else if (submission.getSubmissionType().equalsIgnoreCase("pb")) {
					// Handle personal best submissions with image URL
					String imageUrl = submission.getImageUrl();
					System.out.println("  PB - imageUrl=" + imageUrl);
					
					if (imageUrl != null && !imageUrl.isEmpty()) {
						final JLabel pbContainer = PanelElements.createStyledIconContainer();
						pbContainer.setToolTipText(buildSubmissionTooltip(submission));
						pbContainer.setText("PB");
						pbContainer.setFont(FontManager.getRunescapeSmallFont());
						pbContainer.setForeground(Color.WHITE);
						iconContainer = pbContainer;
						
						// Load image asynchronously
						CompletableFuture.supplyAsync(() -> {
							try {
								BufferedImage image = ImageIO.read(new URL(imageUrl));
								if (image != null) {
									Image scaled = image.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
									return new ImageIcon(scaled);
								}
							} catch (IOException e) {
								System.err.println("Failed to load PB image: " + e.getMessage());
							}
							return null;
						}).thenAccept(imageIcon -> {
							if (imageIcon != null) {
								SwingUtilities.invokeLater(() -> {
									pbContainer.setText(""); // Remove text
									pbContainer.setIcon(imageIcon);
									pbContainer.revalidate();
									pbContainer.repaint();
								});
							}
						});
						
						System.out.println("  Successfully added PB placeholder");
					} else {
						System.out.println("  Skipped PB - no image URL");
					}
				} else {
					System.out.println("  Unknown submission type: " + submission.getSubmissionType());
				}
				
				// Add the icon container if it was created successfully
				if (iconContainer != null) {
					panel.add(iconContainer, c);
					successfullyAdded++;
					
					// Move to next position
					c.gridx++;
					if (c.gridx >= ITEMS_PER_ROW) {
						c.gridx = 0;
						c.gridy++;
					}
				}
			} catch (Exception e) {
				System.err.println("Error processing submission " + i + ": " + e.getMessage());
				e.printStackTrace();
			}
		}
		
		System.out.println("Successfully added " + successfullyAdded + " icons to panel");
		
		// If no icons were added, show a message
		if (successfullyAdded == 0) {
			JLabel debugLabel = new JLabel("No recent submissions to display");
			debugLabel.setForeground(Color.LIGHT_GRAY);
			debugLabel.setFont(FontManager.getRunescapeSmallFont());
			c.gridx = 0;
			c.gridy = 0;
			c.gridwidth = ITEMS_PER_ROW;
			c.gridheight = 2;
			panel.add(debugLabel, c);
		}
		
		panel.revalidate();
		panel.repaint();
		return panel;	
	}
	
	

	private String buildSubmissionTooltip(GroupSearchResult.RecentSubmission submission) {
		try {
			String tooltip = "<html>";
			if (submission.getSubmissionType().equalsIgnoreCase("pb")) {
				String pbTime = submission.getPbTime();
				tooltip += "<b>" + pbTime + "</b> at " + submission.getSourceName() + "<br>" +
				submission.getPlayerName() + " - new personal best!<br>" + "<br>" +
				"<i>" + submission.timeSinceReceived() + "</i>";
			} else if (submission.getSubmissionType().equalsIgnoreCase("drop")) {
				String itemName = submission.getDropItemName();
				Integer quantity = submission.getDropQuantity();
				tooltip += "<b>" + itemName + "</b><br>" +
					submission.getPlayerName() + "<br>" +
					"from: <i>" + submission.getSourceName() + "</i><br>" +
					"<i>" + submission.timeSinceReceived() + "</i>";
			} else if (submission.getSubmissionType().equalsIgnoreCase("clog")) {
				String itemName = submission.getClogItemName();
				tooltip += submission.getPlayerName() + " - New Collection Log:<br>" +
					"<b>" + itemName + "</b><br>" +
					"<i>from: " + submission.getSourceName() + "</i><br>" +
					"<i>" + submission.timeSinceReceived() + "</i>";
			}
			tooltip += "</html>";
			return tooltip;
		} catch (Exception e) {
			System.err.println("Error building tooltip: " + e.getMessage());
		}
		return submission.getPlayerName() + " - " + submission.getSubmissionType() + " - " + submission.getSourceName();
	}
}
