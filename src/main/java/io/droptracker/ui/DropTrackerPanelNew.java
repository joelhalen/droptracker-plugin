package io.droptracker.ui;

import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.util.ChatMessageUtil;

import javax.inject.Inject;
import javax.swing.*;

import io.droptracker.api.DropTrackerApi;
import io.droptracker.ui.pages.GroupPanel;
import io.droptracker.ui.pages.HomePanel;
import io.droptracker.ui.pages.InfoPanel;
import io.droptracker.ui.pages.PlayerStatsPanel;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
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
	private static final String PATREON_LINK = "https://patreon.com/droptracker";
	private static final String WIKI_LINK = "https://www.droptracker.io/wiki";
	private static final String SUGGESTION_LINK = "https://www.droptracker.io/forums/suggestions";
	private static final String BUG_REPORT_LINK = "https://www.droptracker.io/forums/bug-reports/";

	private static final ImageIcon LOGO_GIF;

	@Inject
	private ChatMessageUtil chatMessageUtil;

	static {
		Image logoGif = ImageUtil.loadImageResource(DropTrackerPlugin.class, "brand/droptracker-small.gif");
		Image logoResized = logoGif.getScaledInstance(100, 100, Image.SCALE_SMOOTH);
		LOGO_GIF = new ImageIcon(logoResized);
	}

	private final DropTrackerPlugin plugin;
	private JPanel contentPanel;
	private final DropTrackerApi api;
	private JPanel patchNotesContent;
	private boolean patchNotesCollapsed = false;
	private JPanel headerPanel;

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private ItemManager itemManager;
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

		// init() will be called by the plugin - don't call it here to avoid double initialization
	}

	public void init() {
		removeAll();

		// Main layout
		setLayout(new BorderLayout());
		headerPanel = new JPanel(new BorderLayout());

		addHeaderElements();

		// Create tabbed pane
		JTabbedPane tabbedPane = new JTabbedPane();
		tabbedPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabbedPane.setForeground(Color.WHITE);

		// Home tab
		HomePanel homePanel = new HomePanel(config, api, client);
		InfoPanel infoPanel2 = new InfoPanel(config, api);
		JPanel welcomePanel = homePanel.create();	
		JPanel apiInfoPanel = infoPanel2.create();

		// Stats tab
		if (config.useApi()) {
			PlayerStatsPanel statsPanel = new PlayerStatsPanel(client, clientThread, config, chatMessageUtil, api);
			GroupPanel groupPanel = new GroupPanel(client, clientThread, config, chatMessageUtil, api, itemManager);
			JPanel playerStatsPanel = statsPanel.create();
			JPanel groupStatsPanel = groupPanel.create();
			tabbedPane.addTab("Players", playerStatsPanel);
			tabbedPane.addTab("Groups", groupStatsPanel);
		}

		// API Info tab
		tabbedPane.addTab("Welcome", welcomePanel);
		tabbedPane.addTab("Info", apiInfoPanel);

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