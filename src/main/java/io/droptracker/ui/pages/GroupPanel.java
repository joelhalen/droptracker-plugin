package io.droptracker.ui.pages;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.CompletableFuture;
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
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.StrokeBorder;
import javax.swing.JLayeredPane;
import javax.swing.ImageIcon;

import javax.inject.Inject;

import io.droptracker.DropTrackerConfig;
import io.droptracker.ui.PanelElements;
import io.droptracker.util.ChatMessageUtil;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.api.GroupSearchResult;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
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

	private int currentGroupId = 2; // Track group ID instead of URL
	
	// UI components that we need to update
	private JPanel mainPanel;
	private JPanel contentPanel;
	private JTextField searchField;

	public GroupPanel(Client client, ClientThread clientThread, DropTrackerConfig config, ChatMessageUtil chatMessageUtil, DropTrackerApi api) {
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
		// Ensure main panel takes full width
		mainPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		mainPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 0)); // Height will grow as needed
		mainPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		mainPanel.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH, 0));
		
		// Header section with title and search
		JPanel headerPanel = createHeaderPanel();
		
		// Content panel that will change based on state
		contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		contentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		contentPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 0));
		contentPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		
		// Show default state (Create a Group button)
		showDefaultState();
		
		// Add components to main panel
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
		
		// Search field for groups - fix alignment in BoxLayout
		JPanel searchPanel = new JPanel(new BorderLayout(5, 0));
		searchPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchPanel.setBorder(new StrokeBorder(new BasicStroke(1), ColorScheme.DARKER_GRAY_HOVER_COLOR));
		// Use proper alignment for BoxLayout
		searchPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
		// Set width to match parent container exactly
		searchPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35)); // Account for header padding
		searchPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));
		searchPanel.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));
		
		searchField = new JTextField();
		searchField.setBorder(new StrokeBorder(new BasicStroke(1), ColorScheme.DARKER_GRAY_HOVER_COLOR));
		searchField.setToolTipText("Search for a group");
		// Fix text alignment and positioning
		searchField.setHorizontalAlignment(JTextField.LEFT);
		searchField.setMargin(new Insets(5, 8, 5, 8)); // Add proper padding inside the field
		searchField.setFont(FontManager.getRunescapeSmallFont()); // Ensure consistent font
		// Ensure the text field expands properly
		searchField.setPreferredSize(new Dimension(200, 35)); // Minimum reasonable width
		searchField.setMinimumSize(new Dimension(100, 35)); // Absolute minimum
		
		JButton searchButton = new JButton("Search");
		searchButton.setPreferredSize(new Dimension(70, 35)); // Slightly smaller button
		searchButton.setMaximumSize(new Dimension(70, 35));
		searchButton.setMinimumSize(new Dimension(70, 35));
		searchButton.setMargin(new Insets(5, 5, 5, 5));
		searchButton.addActionListener(e -> performGroupSearch());
		
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
		
		// Add some spacing
		defaultPanel.add(Box.createRigidArea(new Dimension(0, 25)));
		
		// Instructions text
		JLabel instructionLabel = new JLabel("Search for a group above");
		instructionLabel.setFont(FontManager.getRunescapeFont());
		instructionLabel.setForeground(Color.LIGHT_GRAY);
		instructionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		instructionLabel.setHorizontalAlignment(JLabel.CENTER);
		
		// Create button panel
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
		buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttonPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
		
		// Create Group button (same style as View Lootboard)
		JButton createGroupButton = new JButton("⚡ Create a Group");
		createGroupButton.setFont(FontManager.getRunescapeSmallFont());
		createGroupButton.setPreferredSize(new Dimension(150, 30));
		createGroupButton.setToolTipText("Click to visit the group creation documentation");
		
		// Add click listener to open the URL
		createGroupButton.addActionListener(e -> openCreateGroupPage());
		
		buttonPanel.add(createGroupButton);
		
		defaultPanel.add(instructionLabel);
		defaultPanel.add(Box.createRigidArea(new Dimension(0, 20)));
		defaultPanel.add(buttonPanel);
		defaultPanel.add(Box.createVerticalGlue());
		
		contentPanel.add(defaultPanel);
		contentPanel.revalidate();
		contentPanel.repaint();
	}
	
	private void performGroupSearch() {
		String searchQuery = searchField.getText().trim();
		
		if (searchQuery.isEmpty()) {
			JOptionPane.showMessageDialog(mainPanel, "Please enter a group name to search for.");
			return;
		}
		
		System.out.println("Searching for group: " + searchQuery);
		
		// Show loading message
		contentPanel.removeAll();
		JLabel loadingLabel = new JLabel("Searching for group...");
		loadingLabel.setFont(FontManager.getRunescapeFont());
		loadingLabel.setForeground(Color.LIGHT_GRAY);
		loadingLabel.setHorizontalAlignment(JLabel.CENTER);
		contentPanel.add(loadingLabel);
		contentPanel.revalidate();
		contentPanel.repaint();
		
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
		
		// Create main container using JLayeredPane for floating close button
		JLayeredPane layeredContainer = new JLayeredPane();
		layeredContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		layeredContainer.setOpaque(true);
		layeredContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Group info section - this will be the main content
		JPanel groupInfoPanel = new JPanel();
		groupInfoPanel.setLayout(new BoxLayout(groupInfoPanel, BoxLayout.Y_AXIS));
		groupInfoPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		groupInfoPanel.setBorder(new EmptyBorder(10, 10, 10, 10)); // Full padding since close button won't interfere
		groupInfoPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Create floating close button
		JButton closeButton = new JButton("×");
		closeButton.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 14));
		closeButton.setForeground(Color.LIGHT_GRAY);
		closeButton.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		closeButton.setBorder(new StrokeBorder(new BasicStroke(1), ColorScheme.MEDIUM_GRAY_COLOR));
		closeButton.setSize(22, 22); // Use setSize for absolute positioning
		closeButton.setMargin(new Insets(0, 0, 0, 0));
		closeButton.setToolTipText("Close group details");
		closeButton.setFocusPainted(false);
		
		// Add hover effect
		closeButton.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseEntered(MouseEvent e) {
				closeButton.setBackground(ColorScheme.BRAND_ORANGE);
				closeButton.setForeground(Color.WHITE);
			}
			
			@Override
			public void mouseExited(MouseEvent e) {
				closeButton.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				closeButton.setForeground(Color.LIGHT_GRAY);
			}
		});
		
		// Add click action
		closeButton.addActionListener(e -> {
			searchField.setText("");
			showDefaultState();
		});

		// Group icon and name
		JPanel groupHeaderPanel = new JPanel(new BorderLayout(10, 0));
		groupHeaderPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		groupHeaderPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 60)); // Account for groupInfoPanel padding (10px each side)
		groupHeaderPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 60));
		groupHeaderPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Placeholder for group icon (gray square) while real image loads
		BufferedImage placeholderImg = new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB);
		JLabel groupIcon = new JLabel(new ImageIcon(placeholderImg));
		groupIcon.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		groupIcon.setPreferredSize(new Dimension(50, 50));
		groupIcon.setMaximumSize(new Dimension(50, 50));
		groupIcon.setMinimumSize(new Dimension(50, 50));
		
		// Asynchronously load and scale the real icon
		loadGroupIcon(groupIcon, groupResult.getGroupImageUrl());
		
		JPanel groupNamePanel = new JPanel();
		groupNamePanel.setLayout(new BoxLayout(groupNamePanel, BoxLayout.Y_AXIS));
		groupNamePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		
		JLabel groupNameLabel = new JLabel(groupResult.getGroupName());
		groupNameLabel.setFont(FontManager.getRunescapeBoldFont());
		groupNameLabel.setForeground(Color.WHITE);
		groupNameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Group description with proper text wrapping
		String fullDescription = groupResult.getGroupDescription();

		JTextArea groupDescArea = new JTextArea();
		groupDescArea.setWrapStyleWord(true);
		groupDescArea.setLineWrap(true);
		groupDescArea.setOpaque(false);
		groupDescArea.setEditable(false);
		groupDescArea.setFocusable(false);
		groupDescArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		groupDescArea.setForeground(Color.LIGHT_GRAY);
		groupDescArea.setFont(FontManager.getRunescapeSmallFont());
		groupDescArea.setAlignmentX(Component.LEFT_ALIGNMENT);
		groupDescArea.setMargin(new Insets(0, 0, 5, 0));

		// Calculate available space (total width minus groupInfo padding, icon and spacing)
		int availableWidth = PluginPanel.PANEL_WIDTH - 20 - 50 - 10; // panel width - groupInfo padding - icon - spacing
		int maxHeight = 50; // Allow for about 3-4 lines

		// Set the text and handle truncation
		setTruncatedText(groupDescArea, fullDescription, availableWidth, maxHeight);

		// Set final size after text is set
		groupDescArea.setPreferredSize(new Dimension(availableWidth, groupDescArea.getPreferredSize().height));
		groupDescArea.setMaximumSize(new Dimension(availableWidth, maxHeight));
		
		groupNamePanel.add(groupNameLabel);
		groupNamePanel.add(Box.createRigidArea(new Dimension(0, 3))); // Small spacing
		
		groupNamePanel.add(groupDescArea);
		
		groupHeaderPanel.add(groupIcon, BorderLayout.WEST);
		groupHeaderPanel.add(groupNamePanel, BorderLayout.CENTER);
		
		// Group stats in a table-like format
		JPanel statsPanel = new JPanel(new GridLayout(2, 2, 5, 5));
		statsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		statsPanel.setBorder(new EmptyBorder(10, 0, 10, 0));
		statsPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 100)); // Account for groupInfoPanel padding
		statsPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 100));
		statsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Create stat boxes with fixed sizes
		JPanel membersBox = PanelElements.createStatBox("Members", String.valueOf(groupResult.getGroupStats().getTotalMembers()));
		JPanel rankBox = PanelElements.createStatBox("Global Rank", "#" + groupResult.getGroupStats().getGlobalRank());
		JPanel lootBox = PanelElements.createStatBox("Monthly Loot", groupResult.getGroupStats().getMonthlyLoot() + " GP");
		JPanel topPlayerBox = PanelElements.createStatBox("Top Player", groupResult.getGroupTopPlayer());
		
		statsPanel.add(membersBox);
		statsPanel.add(rankBox);
		statsPanel.add(lootBox);
		statsPanel.add(topPlayerBox);
		
		// Add all sections to the group info panel
		groupInfoPanel.add(groupHeaderPanel);
		groupInfoPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		groupInfoPanel.add(statsPanel);
		groupInfoPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		
		// Create horizontal button panel for Discord and Lootboard buttons
		JPanel buttonPanel = new JPanel(new BorderLayout(10, 0));
		buttonPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		buttonPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 40));
		buttonPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 40));
		
		// Add Discord Link button if present
		if (groupResult.getPublicDiscordLink() != null && !groupResult.getPublicDiscordLink().isEmpty()) {
			JButton joinButton = new JButton("⇱ Discord");
			joinButton.setPreferredSize(new Dimension(40, 20));
			joinButton.setMargin(new Insets(5, 5, 5, 5));
			joinButton.addActionListener(e -> LinkBrowser.browse(groupResult.getPublicDiscordLink()));
			buttonPanel.add(joinButton, BorderLayout.WEST);
		}
		
		// Add View Lootboard button (always present)
		JButton viewLootboardButton = new JButton("⇱ View Lootboard");
		viewLootboardButton.setFont(FontManager.getRunescapeSmallFont());
		viewLootboardButton.setPreferredSize(new Dimension(180, 20));
		viewLootboardButton.setToolTipText("Click to view the group's lootboard in full size");
		viewLootboardButton.addActionListener(e -> PanelElements.showLootboardForGroup(client, currentGroupId));
		
		// Position the lootboard button based on whether Discord button exists
		if (groupResult.getPublicDiscordLink() != null && !groupResult.getPublicDiscordLink().isEmpty()) {
			buttonPanel.add(viewLootboardButton, BorderLayout.CENTER);
		} else {
			buttonPanel.add(viewLootboardButton, BorderLayout.WEST);
		}
		
		// Add the button panel to the main layout
		groupInfoPanel.add(buttonPanel);
		
		// Calculate sizes after content is added
		groupInfoPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, groupInfoPanel.getPreferredSize().height));
		
		// Set bounds for layered pane components
		groupInfoPanel.setBounds(0, 0, PluginPanel.PANEL_WIDTH, groupInfoPanel.getPreferredSize().height);
		closeButton.setLocation(PluginPanel.PANEL_WIDTH - 32, 10); // 10px from top, 10px from right edge (22 + 10)
		
		// Set the layered container size
		layeredContainer.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, groupInfoPanel.getPreferredSize().height));
		layeredContainer.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, groupInfoPanel.getPreferredSize().height));
		
		// Add components to layered pane
		layeredContainer.add(groupInfoPanel, JLayeredPane.DEFAULT_LAYER);
		layeredContainer.add(closeButton, JLayeredPane.PALETTE_LAYER);
		
		// Add the layered container to the content panel
		contentPanel.add(layeredContainer);
		contentPanel.revalidate();
		contentPanel.repaint();
	}
	
	private void openCreateGroupPage() {
		try {
			LinkBrowser.browse("https://www.droptracker.io/wiki/create-group");
		} catch (Exception e) {
			System.err.println("Failed to open browser: " + e.getMessage());
			// Fallback: copy URL to clipboard or show message
			JOptionPane.showMessageDialog(mainPanel, 
				"Could not open browser. Please visit:\nhttps://droptracker.io/wiki/create-group", 
				"Create Group Guide", 
				JOptionPane.INFORMATION_MESSAGE);
		}
	}

	private void setTruncatedText(JTextArea textArea, String fullText, int maxWidth, int maxHeight) {
		// First, configure the text area with the full text to see how much space it needs
		textArea.setText(fullText);
		textArea.setSize(new Dimension(maxWidth, Integer.MAX_VALUE));
		
		// Force the text area to calculate its layout
		textArea.getDocument().putProperty("i18n", Boolean.FALSE);
		textArea.revalidate();
		
		// Get the actual height needed for the full text
		int actualHeight = textArea.getPreferredSize().height;
		
		// If it fits, we're done
		if (actualHeight <= maxHeight) {
			textArea.setToolTipText(null);
			return;
		}
		
		// Text is too long, need to truncate
		// Calculate how many lines we can fit
		FontMetrics fm = textArea.getFontMetrics(textArea.getFont());
		int lineHeight = fm.getHeight();
		int maxLines = Math.max(1, maxHeight / lineHeight);
		
		// Split into words and build text line by line
		String[] words = fullText.split("\\s+");
		StringBuilder result = new StringBuilder();
		StringBuilder currentLine = new StringBuilder();
		int linesUsed = 1;
		
		for (String word : words) {
			// Test if adding this word would make the line too long
			String testLine = currentLine.length() > 0 ? currentLine + " " + word : word;
			int lineWidth = fm.stringWidth(testLine);
			
			if (lineWidth > maxWidth && currentLine.length() > 0) {
				// Line would be too long, move to next line
				if (result.length() > 0) result.append(" ");
				result.append(currentLine);
				currentLine = new StringBuilder(word);
				linesUsed++;
				
				// Check if we've used all available lines
				if (linesUsed > maxLines) {
					// Need to truncate - remove some words to make room for "..."
					String truncated = result.toString();
					String ellipsis = "...";
					
					// Keep removing words until "..." fits on the last line
					while (truncated.length() > 0) {
						int lastSpace = truncated.lastIndexOf(' ');
						if (lastSpace == -1) break;
						
						String testTruncated = truncated.substring(0, lastSpace) + " " + ellipsis;
						
						// Test if this fits
						textArea.setText(testTruncated);
						textArea.setSize(new Dimension(maxWidth, Integer.MAX_VALUE));
						
						if (textArea.getPreferredSize().height <= maxHeight) {
							textArea.setText(testTruncated);
							textArea.setToolTipText("<html><div style='width: 300px;'>" + 
												   fullText.replace("\n", "<br>") + "</div></html>");
							return;
						}
						
						truncated = truncated.substring(0, lastSpace);
					}
					
					// Fallback
					textArea.setText(truncated + " " + ellipsis);
					textArea.setToolTipText("<html><div style='width: 300px;'>" + 
										   fullText.replace("\n", "<br>") + "</div></html>");
					return;
				}
			} else {
				currentLine.append(currentLine.length() > 0 ? " " : "").append(word);
			}
		}
		
		// Add the last line
		if (currentLine.length() > 0) {
			if (result.length() > 0) result.append(" ");
			result.append(currentLine);
		}
		
		// Set the final text
		textArea.setText(result.toString());
		textArea.setToolTipText(null);
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
}
