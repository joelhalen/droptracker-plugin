package io.droptracker.ui;

import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.service.SubmissionManager;

import javax.inject.Inject;
import javax.swing.*;

import io.droptracker.api.DropTrackerApi;
import io.droptracker.ui.pages.GroupPanel;
import io.droptracker.ui.pages.HomePanel;
import io.droptracker.ui.pages.ApiPanel;
import io.droptracker.ui.pages.PlayerStatsPanel;

import net.runelite.api.Client;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.ImageUtil;

import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Image;
import java.util.Map;
import java.awt.FlowLayout;
import java.awt.Component;

public class DropTrackerPanelNew extends PluginPanel implements DropTrackerApi.PanelDataLoadedCallback {

	private static final ImageIcon LOGO_GIF;


	static {
		Image logoGif = ImageUtil.loadImageResource(DropTrackerPlugin.class, "brand/droptracker-small.gif");
		Image logoResized = logoGif.getScaledInstance(100, 100, Image.SCALE_SMOOTH);
		LOGO_GIF = new ImageIcon(logoResized);
	}

	private final DropTrackerPlugin plugin;
	private final DropTrackerApi api;
	private JPanel headerPanel;

	@Inject
	private Client client;
	@Inject
	private ItemManager itemManager;
	@Inject
	private DropTrackerConfig config;
	@Inject
	private SubmissionManager submissionManager;
	
	private PlayerStatsPanel statsPanel;
	private GroupPanel groupPanel;
	private ApiPanel apiPanel;
	private HomePanel homePanel;
	private JTabbedPane tabbedPane;
	
	// Add references to the actual panel components added to tabs
	private JPanel welcomePanel;
	private JPanel apiInfoPanel;
	private JPanel playerStatsPanel;
	private JPanel groupStatsPanel;

	@Inject
	public DropTrackerPanelNew(DropTrackerConfig config, DropTrackerApi api, DropTrackerPlugin plugin, Client client) {
		this.config = config;
		this.api = api;
		this.plugin = plugin;
		this.client = client;
		
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// init() will be called by the plugin - don't call it here to avoid double initialization
	}

	public void init() {
		removeAll();

		// Main layout
		setLayout(new BorderLayout());
		headerPanel = new JPanel(new BorderLayout());

		addHeaderElements();

		// Create tabbed pane
		tabbedPane = new JTabbedPane();
		tabbedPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabbedPane.setForeground(Color.WHITE);

		// Home tab
		homePanel = new HomePanel(config, api, client, this);
		welcomePanel = homePanel.create();	// Store reference




		// Tabs for users with API enabled
		if (config.useApi()) {
			// API Info tab
			apiPanel = new ApiPanel(config, api, submissionManager);
			apiInfoPanel = apiPanel.create();   // Store reference
			statsPanel = new PlayerStatsPanel(client, plugin, config, api, itemManager);
			groupPanel = new GroupPanel(client, config, api, itemManager, this);
			playerStatsPanel = statsPanel.create(); // Store reference
			groupStatsPanel = groupPanel.create();   // Store reference
			tabbedPane.addTab("API", apiInfoPanel); // added first to place it at the top
			tabbedPane.addTab("Players", playerStatsPanel);
			tabbedPane.addTab("Groups", groupStatsPanel);
		}

		// Welcome tab
		tabbedPane.addTab("Welcome", welcomePanel);


		// Add components to main panel
		add(headerPanel, BorderLayout.NORTH);
		add(tabbedPane, BorderLayout.CENTER);
		tabbedPane.setSelectedComponent(welcomePanel);

		revalidate();
		repaint();
	}

	private void addHeaderElements() {
		// Header panel (stays constant across tabs)
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		// Create a vertical panel for just the title
		JPanel titlePanel = new JPanel();
		titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));
		titlePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Main title
		JLabel welcomeText = new JLabel("DropTracker");
		welcomeText.setFont(FontManager.getRunescapeBoldFont());
		welcomeText.setAlignmentX(Component.CENTER_ALIGNMENT);
		welcomeText.setForeground(Color.WHITE);

		// Add only the title to title panel
		titlePanel.add(welcomeText);

		// Create a subtle info panel for version and API status
		JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
		infoPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel versionText = new JLabel("v" + plugin.pluginVersion);
		versionText.setFont(FontManager.getRunescapeSmallFont());
		versionText.setForeground(ColorScheme.LIGHT_GRAY_COLOR); // More subtle color

		JLabel separatorText = new JLabel("•"); // Bullet separator
		separatorText.setFont(FontManager.getRunescapeSmallFont());
		separatorText.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		// Create API status label
		String apiStatus = config.useApi() ? "ENABLED" : "DISABLED";
		JLabel apiStatusText = new JLabel("API: " + apiStatus);
		apiStatusText.setFont(FontManager.getRunescapeSmallFont());
		apiStatusText.setForeground(config.useApi() ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
		

		JButton refreshButton = new JButton("↻ Refresh Panel");
		refreshButton.setFont(FontManager.getRunescapeSmallFont());
		refreshButton.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		refreshButton.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		refreshButton.setBorder(new EmptyBorder(5, 8, 5, 8));
		refreshButton.setPreferredSize(new Dimension(100, 30));
		refreshButton.setMaximumSize(new Dimension(100, 30));


		// Add info components to info panel
		infoPanel.add(versionText);
		infoPanel.add(separatorText);
		infoPanel.add(apiStatusText);
		
		// Logo panel with logo and version info below it
		JPanel logoPanel = new JPanel();
		logoPanel.setLayout(new BoxLayout(logoPanel, BoxLayout.Y_AXIS));
		logoPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		
		JLabel logoLabel = new JLabel(LOGO_GIF);
		logoLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		logoPanel.add(logoLabel);
		logoPanel.add(Box.createRigidArea(new Dimension(0, 5))); // Small spacing between logo and info
		logoPanel.add(infoPanel);

		// Add to header panel
		headerPanel.add(titlePanel, BorderLayout.NORTH);
		headerPanel.add(Box.createRigidArea(new Dimension(0, 10)), BorderLayout.CENTER); // Spacing
		headerPanel.add(logoPanel, BorderLayout.SOUTH);
	}
	
	public void deinit() {
		removeAll();
		
	}

	public void selectPanel(String panelToSelect) {
		if (tabbedPane == null) {
			System.out.println("TabbedPane is null, cannot select panel");
			return;
		}
		
		switch (panelToSelect.toLowerCase()) {
			case "home":
			case "welcome":
				if (welcomePanel != null) {
					tabbedPane.setSelectedComponent(welcomePanel);
					System.out.println("Selected Welcome tab");
				} else {
					System.out.println("Welcome panel is null");
				}
				break;
				
			case "players":
			case "stats":
				if (playerStatsPanel != null && config.useApi()) {
					tabbedPane.setSelectedComponent(playerStatsPanel);
					System.out.println("Selected Players tab");
				} else {
					System.out.println("Players panel is null or API disabled");
				}
				break;
				
			case "groups":
				if (groupStatsPanel != null && config.useApi()) {
					tabbedPane.setSelectedComponent(groupStatsPanel);
					System.out.println("Selected Groups tab");
				} else {
					System.out.println("Groups panel is null or API disabled");
				}
				break;
				
			case "api":
				if (apiInfoPanel != null) {
					tabbedPane.setSelectedComponent(apiInfoPanel);
					System.out.println("Selected Info tab");
				} else {
					System.out.println("Info panel is null");
				}
				break;
				
			default:
				System.out.println("Unknown panel: " + panelToSelect);
				break;
		}
	}

	// Allow searched for player by name (Perhaps for right-click lookups later, etc)
	public void updatePlayerPanel(String playerToLoad) {
		if (statsPanel != null) {
			statsPanel.performPlayerSearch(playerToLoad);
		}
	}

	// Update the player panel with the current player's name
	public void updatePlayerPanel() {
		if (statsPanel != null) {
			statsPanel.performPlayerSearch("");
		}
	}

	// Update the group panel from sources other than direct searches
	public void updateGroupPanel(String groupToLoad) {
		if (groupPanel != null) {
			groupPanel.performGroupSearch("");
		}
	}


	// Update the API panel with the current session's valid submissions
	public void updateSentSubmissions() {
		if (apiPanel != null) {
			apiPanel.refresh();
		}
	}

	/**
	 * Updates the player button on the home panel if a local name becomes available after initial render
	 */
	public void updateHomePlayerButton() {
		if (homePanel != null) {
			homePanel.updatePlayerButton();
		}
	}

	@Override
	public void onDataLoaded(Map<String, Object> data) {
		updatePlayerPanel();
		updateGroupPanel("");
	}
}