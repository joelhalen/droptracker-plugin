package io.droptracker.ui;

import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;

import javax.inject.Inject;
import javax.swing.*;

import io.droptracker.api.DropTrackerApi;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.awt.FlowLayout;
import java.awt.Component;
import java.awt.Insets;


public class DropTrackerPanelNew extends PluginPanel implements DropTrackerApi.PanelDataLoadedCallback
{
	private static final String PATREON_LINK = "https://patreon.com/droptracker";
	private static final String WIKI_LINK = "https://www.droptracker.io/wiki";
	private static final String SUGGESTION_LINK = "https://www.droptracker.io/forums/suggestions";
	private static final String BUG_REPORT_LINK = "https://www.droptracker.io/forums/bug-reports/";
	
	private static final ImageIcon COLLAPSED_ICON;
	private static final ImageIcon EXPANDED_ICON;

	private static final ImageIcon LOGO_GIF;
	
	static {
		// Load original images
		Image collapsedImg = ImageUtil.loadImageResource(DropTrackerPlugin.class, "util/collapse.png");
		Image expandedImg = ImageUtil.loadImageResource(DropTrackerPlugin.class, "util/expand.png");

		Image logoGif = ImageUtil.loadImageResource(DropTrackerPlugin.class, "droptracker-small.gif");
		
		// Resize to appropriate dimensions
		Image collapsedResized = collapsedImg.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
		Image expandedResized = expandedImg.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
		Image logoResized = logoGif.getScaledInstance(128, 128, Image.SCALE_SMOOTH);
		// Create icons from resized images
		COLLAPSED_ICON = new ImageIcon(collapsedResized);
		EXPANDED_ICON = new ImageIcon(expandedResized);
		LOGO_GIF = new ImageIcon(logoResized);
	}
	
	private final DropTrackerPlugin plugin;
	private JPanel contentPanel;
	private final DropTrackerApi api;
	private JPanel patchNotesContent;
	private boolean patchNotesCollapsed = false;
	
	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;

	@Inject
	private DropTrackerConfig config;

	@Inject
	public DropTrackerPanelNew(DropTrackerConfig config, DropTrackerApi api, DropTrackerPlugin plugin, Client client) {
		this.config = config;
		this.api = api;
		this.plugin = plugin;
		this.client = client;
		
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		
		init();
	}
	
	public void init()
	{
		removeAll();
		
		// Main layout
		setLayout(new BorderLayout());
		
		// Header panel (stays constant across tabs)
		JPanel headerPanel = new JPanel(new BorderLayout());
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		
		JLabel welcomeText = new JLabel("DropTracker v" + plugin.pluginVersion);
		JPanel logoPanel = new JPanel(new BorderLayout());
		JLabel logoLabel = new JLabel(LOGO_GIF);
		logoLabel.setHorizontalAlignment(SwingConstants.CENTER);
		logoPanel.add(logoLabel, BorderLayout.CENTER);
		welcomeText.setFont(FontManager.getRunescapeBoldFont());
		welcomeText.setHorizontalAlignment(JLabel.CENTER);
		welcomeText.setForeground(Color.WHITE);
		headerPanel.add(welcomeText, BorderLayout.NORTH);
		headerPanel.add(logoPanel, BorderLayout.SOUTH);
		
		// Create tabbed pane
		JTabbedPane tabbedPane = new JTabbedPane();
		tabbedPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabbedPane.setForeground(Color.WHITE);
		
		// Home tab
		JPanel homePanel = createHomePanel();
		tabbedPane.addTab("Welcome", homePanel);
		
		// Stats tab
		if (config.useApi()) {
			JPanel statsPanel = createStatsPanel();
			tabbedPane.addTab("Players", statsPanel);
			JPanel groupPanel = createGroupPanel();
			tabbedPane.addTab("Groups", groupPanel);
		}
		
		// API Info tab
		JPanel infoPanel = createAPIPanel();
		tabbedPane.addTab("Info", infoPanel);
		
		// Settings tab

		
		// Add components to main panel
		add(headerPanel, BorderLayout.NORTH);
		add(tabbedPane, BorderLayout.CENTER);
		
		revalidate();
		repaint();
	}
	
	private JPanel createHomePanel() {
		JPanel homePanel = new JPanel();
		homePanel.setLayout(new BoxLayout(homePanel, BoxLayout.Y_AXIS));
		homePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		
		// Set maximum width to prevent expansion
		homePanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		final JPanel welcomeMessagePanel = createCollapsiblePanel("Welcome to the DropTracker", getLatestWelcomeContent(), true);
		// Patch notes panel (collapsible)
		welcomeMessagePanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		welcomeMessagePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		final JPanel patchNotesPanel = createCollapsiblePanel("News / Updates", getLatestUpdateContent(), false);
		patchNotesPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		patchNotesPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Quick access buttons in a grid (without subtexts)
		JPanel quickAccessPanel = new JPanel();
		quickAccessPanel.setLayout(new BoxLayout(quickAccessPanel, BoxLayout.Y_AXIS));
		quickAccessPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		quickAccessPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Create two separate panels for each row of buttons
		JPanel topButtonRow = new JPanel(new GridLayout(1, 2, 5, 0));
		topButtonRow.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel bottomButtonRow = new JPanel(new GridLayout(1, 2, 5, 0));
		bottomButtonRow.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Add buttons to the top row with proper sizing
		JButton guideButton = new JButton("Read the Wiki");
		guideButton.setFocusPainted(false);
		guideButton.setFont(FontManager.getRunescapeSmallFont());
		guideButton.addActionListener(e -> LinkBrowser.browse(WIKI_LINK));
		// Prevent text truncation
		guideButton.setMargin(new Insets(0, 0, 0, 0));

		JButton discordButton = new JButton("Join Discord");
		discordButton.setFocusPainted(false);
		discordButton.setFont(FontManager.getRunescapeSmallFont());
		discordButton.addActionListener(e -> LinkBrowser.browse("https://www.droptracker.io/discord"));
		// Prevent text truncation
		discordButton.setMargin(new Insets(0, 0, 0, 0));

		topButtonRow.add(guideButton);
		topButtonRow.add(discordButton);

		// Add buttons to the bottom row with proper sizing
		JButton suggestButton = new JButton("Suggest Features");
		suggestButton.setFocusPainted(false);
		suggestButton.setFont(FontManager.getRunescapeSmallFont());
		suggestButton.addActionListener(e -> LinkBrowser.browse(SUGGESTION_LINK));
		// Prevent text truncation
		suggestButton.setMargin(new Insets(0, 0, 0, 0));

		JButton bugReportButton = new JButton("Report a Bug");
		bugReportButton.setFocusPainted(false);
		bugReportButton.setFont(FontManager.getRunescapeSmallFont());
		bugReportButton.addActionListener(e -> LinkBrowser.browse(BUG_REPORT_LINK));
		// Prevent text truncation
		bugReportButton.setMargin(new Insets(0, 0, 0, 0));

		bottomButtonRow.add(suggestButton);
		bottomButtonRow.add(bugReportButton);

		// Add rows to the main panel with a rigid area between them for spacing
		quickAccessPanel.add(topButtonRow);
		quickAccessPanel.add(Box.createRigidArea(new Dimension(0, 8))); // 8px gap between rows
		quickAccessPanel.add(bottomButtonRow);

		// Set maximum size to prevent expansion
		quickAccessPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 68)); // Adjusted for the gap
		
		// Create a rigid container for feature panels to prevent expansion
		JPanel featurePanelsContainer = new JPanel();
		featurePanelsContainer.setLayout(new BoxLayout(featurePanelsContainer, BoxLayout.Y_AXIS));
		featurePanelsContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		featurePanelsContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Feature panels with fixed heights
		JPanel trackDropsPanel = createFeaturePanel("Track Your Drops", 
			"Automatically record and analyze all your valuable drops from bosses, raids, and PvP.");
		trackDropsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JPanel discordPanel = createFeaturePanel("Discord Integration", 
			"Share your achievements with friends via customizable Discord webhooks.");
		discordPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JPanel pbPanel = createFeaturePanel("Personal Bests", 
			"Track and compare your boss kill times and personal records.");
		pbPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Add feature panels to the container with fixed spacing
		featurePanelsContainer.add(trackDropsPanel);
		featurePanelsContainer.add(Box.createRigidArea(new Dimension(0, 8)));
		featurePanelsContainer.add(discordPanel);
		featurePanelsContainer.add(Box.createRigidArea(new Dimension(0, 8)));
		featurePanelsContainer.add(pbPanel);
		
		// Add all panels to home panel with proper spacing
		homePanel.add(welcomeMessagePanel);
		homePanel.add(Box.createRigidArea(new Dimension(0, 8)));
		homePanel.add(patchNotesPanel);
		homePanel.add(Box.createRigidArea(new Dimension(0, 8)));
		homePanel.add(quickAccessPanel);
		homePanel.add(Box.createRigidArea(new Dimension(0, 8)));
		homePanel.add(featurePanelsContainer);
		
		return homePanel;
	}
	
	private JPanel createCollapsiblePanel(String title, JPanel content, boolean isUnderlined) {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(10, 10, 10, 10));
		
		JPanel headerPanel = new JPanel(new BorderLayout());
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		
		// Create title with optional underline
		JLabel titleLabel;
		if (isUnderlined) {
			titleLabel = new JLabel("<html><u>" + title + "</u></html>");
		} else {
			titleLabel = new JLabel(title);
		}
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setForeground(Color.WHITE);
		
		JLabel toggleIcon = new JLabel(EXPANDED_ICON);
		
		headerPanel.add(titleLabel, BorderLayout.WEST);
		headerPanel.add(toggleIcon, BorderLayout.EAST);
		
		// Create a final reference to the content for use in the listener
		final JPanel contentRef = content;
		final boolean[] isCollapsed = {false};
		
		// Add click listener for collapsing/expanding
		headerPanel.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				isCollapsed[0] = !isCollapsed[0];
				toggleIcon.setIcon(isCollapsed[0] ? COLLAPSED_ICON : EXPANDED_ICON);
				contentRef.setVisible(!isCollapsed[0]);
				
				// Force fixed height when collapsed
				if (isCollapsed[0]) {
					panel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, headerPanel.getPreferredSize().height + 20));
					panel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, headerPanel.getPreferredSize().height + 20));
					panel.revalidate();
					panel.repaint();
				} else {
					panel.setPreferredSize(null);
					panel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
					panel.revalidate();
					panel.repaint();
				}
			}
		});

		panel.add(headerPanel);
		panel.add(getJSeparator(ColorScheme.LIGHT_GRAY_COLOR));
		panel.add(content);
		
		return panel;
	}

	private JPanel getLatestWelcomeContent() {
		String welcomeText;
		if (config.useApi()) {
			welcomeText = api.getLatestWelcomeString();
		} else {
			welcomeText = "<html>An <b>all-in-one</b> loot and achievement tracking system.<br />" +
					"New here? You can click <ul>View Guide</ul> below for help!<br />" +
					"By simply using our plugin, your in-game progress is automatically tracked by our database." +
					"</html>";
		}
		JTextArea textArea = collapsibleSubText(welcomeText);

		JPanel contentPanel = new JPanel();
		contentPanel.setLayout(new BorderLayout());
		contentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		contentPanel.add(textArea, BorderLayout.CENTER);

		return contentPanel;

	}

	private JPanel getLatestUpdateContent() {
		String updateText;
		if (config.useApi()) {
			updateText = api.getLatestUpdateString();
		} else {
			updateText = "• Implemented support for tracking Personal Bests from a POH adventure log.\n\n" +
						  "• Added pet collection submissions when adventure logs are opened.\n\n" +
						  "• Fixed various personal best tracking bugs.\n\n" +
						  "• A new side panel & stats functionality";
		}

		JTextArea textArea = collapsibleSubText(updateText);
		
		JPanel contentPanel = new JPanel();
		contentPanel.setLayout(new BorderLayout());
		contentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		contentPanel.add(textArea, BorderLayout.CENTER);
		
		return contentPanel;
	}

	private JTextArea collapsibleSubText(String inputString) {
		JTextArea textArea = new JTextArea();
		textArea.setText(inputString);
		textArea.setWrapStyleWord(true);
		textArea.setLineWrap(true);
		textArea.setOpaque(false);
		textArea.setEditable(false);
		textArea.setFocusable(false);
		textArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		textArea.setForeground(Color.LIGHT_GRAY);
		Font textAreaFont = FontManager.getRunescapeSmallFont();
		textArea.setFont(textAreaFont);
		textArea.setBorder(new EmptyBorder(5, 5, 5, 5));

		return textArea;
	}
	
	private JPanel createFeaturePanel(String title, String description) {
		// Create a panel with fixed dimensions
		JPanel panel = new JPanel();
		panel.setLayout(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(10, 10, 10, 10));
		
		// Fixed height for the entire panel
		Dimension panelSize = new Dimension(PluginPanel.PANEL_WIDTH, 90);
		panel.setPreferredSize(panelSize);
		panel.setMinimumSize(panelSize);
		panel.setMaximumSize(panelSize);
		
		JLabel titleLabel = new JLabel(title);
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setForeground(Color.WHITE);
		
		JTextArea descArea = new JTextArea(description);
		descArea.setWrapStyleWord(true);
		descArea.setLineWrap(true);
		descArea.setOpaque(false);
		descArea.setEditable(false);
		descArea.setFocusable(false);
		descArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		descArea.setForeground(Color.LIGHT_GRAY);
		descArea.setFont(FontManager.getRunescapeSmallFont());
		descArea.setBorder(new EmptyBorder(5, 0, 0, 0));
		
		panel.add(titleLabel, BorderLayout.NORTH);
		panel.add(descArea, BorderLayout.CENTER);
		
		return panel;
	}

	private JPanel createAPIPanel() {
		JPanel apiPanel = new JPanel();
		apiPanel.setLayout(new BoxLayout(apiPanel, BoxLayout.Y_AXIS));
		apiPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		apiPanel.setBorder(new EmptyBorder(0, 0, 0, 0));
		
		// Create a panel for the title with proper alignment
		JPanel titlePanel = new JPanel();
		titlePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		titlePanel.setLayout(new FlowLayout(FlowLayout.CENTER, 0, 0));
		titlePanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		titlePanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 40));
		titlePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JLabel apiTitle = new JLabel("API Features");
		apiTitle.setFont(FontManager.getRunescapeBoldFont());
		apiTitle.setForeground(Color.WHITE);
		
		titlePanel.add(apiTitle);
		
		// API status panel
		JPanel statusPanel = new JPanel(new BorderLayout());
		statusPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		statusPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		statusPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 40));
		statusPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JLabel statusLabel = new JLabel("API Status: " + (config.useApi() ? "Enabled" : "Disabled"));
		statusLabel.setForeground(config.useApi() ? Color.GREEN : Color.RED);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusPanel.add(statusLabel, BorderLayout.CENTER);
		
		// API features list
		JPanel featuresPanel = new JPanel();
		featuresPanel.setLayout(new BoxLayout(featuresPanel, BoxLayout.Y_AXIS));
		featuresPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		featuresPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		featuresPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		featuresPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JLabel featuresTitle = new JLabel("With the API enabled, you can:");
		featuresTitle.setFont(FontManager.getRunescapeSmallFont());
		featuresTitle.setForeground(Color.WHITE);
		featuresTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JPanel featuresList = new JPanel();
		featuresList.setLayout(new BoxLayout(featuresList, BoxLayout.Y_AXIS));
		featuresList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		featuresList.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, Integer.MAX_VALUE));
		featuresList.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		String[] features = {
			"• Track all your drops across multiple accounts",
			"• View detailed statistics about your drops",
			"• Compare your drop rates with community averages",
			"• Access your drop history from anywhere",
			"• Share your collection log progress with friends"
		};
		
		for (String feature : features) {
			JLabel featureLabel = new JLabel(feature);
			featureLabel.setFont(FontManager.getRunescapeSmallFont());
			featureLabel.setForeground(Color.LIGHT_GRAY);
			featureLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			featuresList.add(featureLabel);
			featuresList.add(Box.createRigidArea(new Dimension(0, 5)));
		}
		
		featuresPanel.add(featuresTitle);
		featuresPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		featuresPanel.add(featuresList);
		
		// API setup instructions
		JPanel setupPanel = new JPanel();
		setupPanel.setLayout(new BoxLayout(setupPanel, BoxLayout.Y_AXIS));
		setupPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setupPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		setupPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		setupPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JLabel setupTitle = new JLabel("How to Enable the API");
		setupTitle.setFont(FontManager.getRunescapeSmallFont());
		setupTitle.setForeground(Color.WHITE);
		setupTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JTextArea setupInstructions = new JTextArea(
			"1. Go to the Settings tab\n" +
			"2. Check 'Enable API Integration'\n" +
			"3. Enter your API key (get one at droptracker.io)\n" +
			"4. Click Save"
		);
		setupInstructions.setWrapStyleWord(true);
		setupInstructions.setLineWrap(true);
		setupInstructions.setOpaque(false);
		setupInstructions.setEditable(false);
		setupInstructions.setFocusable(false);
		setupInstructions.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setupInstructions.setForeground(Color.LIGHT_GRAY);
		setupInstructions.setFont(FontManager.getRunescapeSmallFont());
		setupInstructions.setAlignmentX(Component.LEFT_ALIGNMENT);
		setupInstructions.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, Integer.MAX_VALUE));
		
		setupPanel.add(setupTitle);
		setupPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		setupPanel.add(setupInstructions);
		
		// Button to get API key
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
		buttonPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttonPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 40));
		buttonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JButton getApiKeyButton = new JButton("Get API Key");
		getApiKeyButton.addActionListener(e -> LinkBrowser.browse("https://www.droptracker.io/account/api"));
		buttonPanel.add(getApiKeyButton);
		
		// Add all components to the API panel
		apiPanel.add(titlePanel);
		apiPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		apiPanel.add(statusPanel);
		apiPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		apiPanel.add(featuresPanel);
		apiPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		apiPanel.add(setupPanel);
		apiPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		apiPanel.add(buttonPanel);
		apiPanel.add(Box.createVerticalGlue());
		
		// Wrap in scroll pane with proper insets
		JScrollPane scrollPane = new JScrollPane(apiPanel);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		
		JPanel wrapperPanel = new JPanel(new BorderLayout());
		wrapperPanel.add(scrollPane, BorderLayout.CENTER);
		return wrapperPanel;
	}

	private JPanel createGroupPanel() {
		JPanel groupPanel = new JPanel();
		groupPanel.setLayout(new BoxLayout(groupPanel, BoxLayout.Y_AXIS));
		groupPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		
		// Header section with title and search
		JPanel headerPanel = new JPanel();
		headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		headerPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 100));
		
		JLabel groupTitle = new JLabel("DropTracker - Groups");
		groupTitle.setFont(FontManager.getRunescapeBoldFont());
		groupTitle.setForeground(Color.WHITE);
		groupTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
		
		// Search field for groups
		JPanel searchPanel = new JPanel(new BorderLayout(5, 0));
		searchPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchPanel.setBorder(new EmptyBorder(10, 0, 5, 0));
		searchPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 30));
		
		JTextField searchField = new JTextField();
		searchField.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 100, 30));
		searchField.setToolTipText("Search for a group");
		
		JButton searchButton = new JButton("Search");
		searchButton.setPreferredSize(new Dimension(70, 30));
		searchButton.setMargin(new Insets(0, 0, 0, 0));
		searchButton.addActionListener(e -> {
			// Will be implemented later to search for groups
			JOptionPane.showMessageDialog(null, "Group search will be implemented soon!");
		});
		
		searchPanel.add(searchField, BorderLayout.CENTER);
		searchPanel.add(searchButton, BorderLayout.EAST);
		
		headerPanel.add(groupTitle);
		headerPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		headerPanel.add(searchPanel);
		
		// Group info section
		JPanel groupInfoPanel = new JPanel();
		groupInfoPanel.setLayout(new BoxLayout(groupInfoPanel, BoxLayout.Y_AXIS));
		groupInfoPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		groupInfoPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		groupInfoPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Group icon and name
		JPanel groupHeaderPanel = new JPanel(new BorderLayout(10, 0));
		groupHeaderPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		groupHeaderPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 60));
		groupHeaderPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 60));
		groupHeaderPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Placeholder for group icon (would be replaced with actual icon)
		JPanel iconPlaceholder = new JPanel();
		iconPlaceholder.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		iconPlaceholder.setPreferredSize(new Dimension(50, 50));
		
		JPanel groupNamePanel = new JPanel();
		groupNamePanel.setLayout(new BoxLayout(groupNamePanel, BoxLayout.Y_AXIS));
		groupNamePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		
		JLabel groupNameLabel = new JLabel("Group Name");
		groupNameLabel.setFont(FontManager.getRunescapeBoldFont());
		groupNameLabel.setForeground(Color.WHITE);
		groupNameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JLabel groupDescLabel = new JLabel("Group description goes here...");
		groupDescLabel.setFont(FontManager.getRunescapeSmallFont());
		groupDescLabel.setForeground(Color.LIGHT_GRAY);
		groupDescLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		groupNamePanel.add(groupNameLabel);
		groupNamePanel.add(groupDescLabel);
		
		groupHeaderPanel.add(iconPlaceholder, BorderLayout.WEST);
		groupHeaderPanel.add(groupNamePanel, BorderLayout.CENTER);
		
		// Group stats in a table-like format - fixed layout
		JPanel statsPanel = new JPanel(new GridLayout(2, 2, 5, 5));
		statsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		statsPanel.setBorder(new EmptyBorder(10, 0, 10, 0));
		statsPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 100));
		statsPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 100));
		statsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		// Create stat boxes with fixed sizes
		JPanel membersBox = createStatBox("Members", "0");
		JPanel rankBox = createStatBox("Global Rank", "N/A");
		JPanel lootBox = createStatBox("Monthly Loot", "0 GP");
		JPanel topPlayerBox = createStatBox("Top Player", "None");
		
		statsPanel.add(membersBox);
		statsPanel.add(rankBox);
		statsPanel.add(lootBox);
		statsPanel.add(topPlayerBox);
		
		// Group members section
		JPanel membersPanel = new JPanel();
		membersPanel.setLayout(new BoxLayout(membersPanel, BoxLayout.Y_AXIS));
		membersPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		membersPanel.setBorder(new EmptyBorder(10, 0, 10, 0));
		membersPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		membersPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 200));
		membersPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 200));
		
		JLabel membersTitle = new JLabel("Top Members");
		membersTitle.setFont(FontManager.getRunescapeBoldFont());
		membersTitle.setForeground(Color.WHITE);
		membersTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JPanel membersList = new JPanel();
		membersList.setLayout(new BoxLayout(membersList, BoxLayout.Y_AXIS));
		membersList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		membersList.setAlignmentX(Component.LEFT_ALIGNMENT);
		membersList.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 150));
		membersList.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 150));
		
		// Add placeholder members with fixed widths
		JPanel member1 = createMemberRow("Player1", "1,234,567 GP", "1");
		member1.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));
		member1.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));
		member1.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JPanel member2 = createMemberRow("Player2", "987,654 GP", "2");
		member2.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));
		member2.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));
		member2.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JPanel member3 = createMemberRow("Player3", "567,890 GP", "3");
		member3.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));
		member3.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));
		member3.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		membersList.add(member1);
		membersList.add(Box.createRigidArea(new Dimension(0, 5)));
		membersList.add(member2);
		membersList.add(Box.createRigidArea(new Dimension(0, 5)));
		membersList.add(member3);
		
		membersPanel.add(membersTitle);
		membersPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		membersPanel.add(membersList);
		
		// Action buttons
		JPanel actionPanel = new JPanel();
		actionPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 10, 0));
		actionPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		actionPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 40));
		actionPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 40));
		actionPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JButton joinButton = new JButton("Join Group");
		joinButton.setMargin(new Insets(0, 5, 0, 5));
		joinButton.addActionListener(e -> {
			JOptionPane.showMessageDialog(null, "Group joining will be implemented soon!");
		});
		
		JButton createButton = new JButton("Create Group");
		createButton.setMargin(new Insets(0, 5, 0, 5));
		createButton.addActionListener(e -> {
			JOptionPane.showMessageDialog(null, "Group creation will be implemented soon!");
		});
		
		actionPanel.add(joinButton);
		actionPanel.add(createButton);
		
		// Add all sections to the group info panel
		groupInfoPanel.add(groupHeaderPanel);
		groupInfoPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		groupInfoPanel.add(statsPanel);
		groupInfoPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		groupInfoPanel.add(membersPanel);
		groupInfoPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		groupInfoPanel.add(actionPanel);
		
		// Add all components to the main panel
		groupPanel.add(headerPanel);
		groupPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		groupPanel.add(groupInfoPanel);
		groupPanel.add(Box.createVerticalGlue());
		
		// Wrap in a scroll pane
		JScrollPane scrollPane = new JScrollPane(groupPanel);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		
		JPanel wrapperPanel = new JPanel(new BorderLayout());
		wrapperPanel.add(scrollPane, BorderLayout.CENTER);
		return wrapperPanel;
	}

	// Helper method to create a stat box with fixed size
	private JPanel createStatBox(String label, String value) {
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		box.setBorder(new EmptyBorder(5, 5, 5, 5));
		
		JLabel valueLabel = new JLabel(value);
		valueLabel.setFont(FontManager.getRunescapeBoldFont());
		valueLabel.setForeground(Color.WHITE);
		valueLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		
		JLabel nameLabel = new JLabel(label);
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		nameLabel.setForeground(Color.LIGHT_GRAY);
		nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		
		box.add(valueLabel);
		box.add(nameLabel);
		
		return box;
	}

	// Helper method to create a member row with fixed size
	private JPanel createMemberRow(String name, String loot, String rank) {
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		row.setBorder(new EmptyBorder(5, 5, 5, 5));
		
		JLabel rankLabel = new JLabel("#" + rank);
		rankLabel.setFont(FontManager.getRunescapeSmallFont());
		rankLabel.setForeground(Color.YELLOW);
		
		JLabel nameLabel = new JLabel(name);
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		nameLabel.setForeground(Color.WHITE);
		
		JLabel lootLabel = new JLabel(loot);
		lootLabel.setFont(FontManager.getRunescapeSmallFont());
		lootLabel.setForeground(Color.GREEN);
		
		row.add(rankLabel, BorderLayout.WEST);
		row.add(nameLabel, BorderLayout.CENTER);
		row.add(lootLabel, BorderLayout.EAST);
		
		return row;
	}

	private JPanel createStatsPanel() {
		JPanel statsPanel = new JPanel();
		statsPanel.setLayout(new BoxLayout(statsPanel, BoxLayout.Y_AXIS));
		statsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		statsPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		
		// Create a panel for the title to center it properly
		JPanel titlePanel = new JPanel(new BorderLayout());
		titlePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		
		JLabel statsTitle = new JLabel("Your Statistics");
		statsTitle.setFont(FontManager.getRunescapeBoldFont());
		statsTitle.setHorizontalAlignment(JLabel.CENTER);
		statsTitle.setForeground(Color.WHITE);
		
		titlePanel.add(statsTitle, BorderLayout.CENTER);
		
		JPanel statsContent = new JPanel(new GridLayout(0, 1, 0, 5));
		statsContent.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		
		// Add placeholder stats
		statsContent.add(createStatRow("Total Loot Tracked:", "Loading..."));
		statsContent.add(createStatRow("Global Rank:", "Loading..."));
		statsContent.add(createStatRow("Top NPC:", "Loading..."));
		statsContent.add(createStatRow("Best Ranked PB:", "Loading..."));
		
		// Create a panel for the button to center it
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
		buttonPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		
		JButton refreshButton = new JButton("Refresh Stats");
		refreshButton.addActionListener(e -> {
			clientThread.invokeLater(() -> {
				if (client.getGameState().getState() >= 30) {
					if(!config.useApi()) {
						plugin.sendChatMessage("You must have API connections enabled in the DropTracker plugin configuration in order to refresh your statistics!");
					} else {
						// load panel data here
						return;
					}
				}
			});
		});
		
		buttonPanel.add(refreshButton);
		
		statsPanel.add(titlePanel);
		statsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		statsPanel.add(getJSeparator(ColorScheme.LIGHT_GRAY_COLOR));
		statsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		statsPanel.add(statsContent);
		statsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		statsPanel.add(buttonPanel);
		
		return statsPanel;
	}
	
	private JPanel createStatRow(String label, String value) {
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		
		JLabel statLabel = new JLabel(label);
		statLabel.setFont(FontManager.getRunescapeSmallFont());
		statLabel.setForeground(Color.WHITE);
		
		JLabel statValue = new JLabel(value);
		statValue.setFont(FontManager.getRunescapeSmallFont());
		statValue.setForeground(Color.YELLOW);
		
		row.add(statLabel, BorderLayout.WEST);
		row.add(statValue, BorderLayout.EAST);
		
		return row;
	}

	private JSeparator getJSeparator(Color color)
	{
		JSeparator sep = new JSeparator();
		sep.setBackground(color);
		sep.setForeground(color);
		return sep;
	}
	
	public void deinit()
	{
		removeAll();
	}

	public void updateStats(Map<String, Object> stats) {
		// Update the player stats panel using the data retrieved from the API
	}

	public void updateGroup(Map<String, Object> data) {
		// Update the Group panel using the data retrieved from the API
	}

	@Override
	public void onDataLoaded(Map<String, Object> data) {
		updateStats(data);
		updateGroup(data);
	}
}