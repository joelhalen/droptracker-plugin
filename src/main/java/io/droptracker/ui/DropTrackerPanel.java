package io.droptracker.ui;

import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.service.SubmissionManager;

import javax.inject.Inject;
import javax.swing.*;

import io.droptracker.api.DropTrackerApi;
import io.droptracker.service.EventNotificationService;
import io.droptracker.ui.components.PanelElements;
import io.droptracker.ui.pages.ActivityPanel;
import io.droptracker.ui.pages.EventsPanel;
import io.droptracker.ui.pages.GroupPanel;
import io.droptracker.ui.pages.HomePanel;
import io.droptracker.ui.pages.PlayerStatsPanel;
import io.droptracker.util.ItemIDSearch;
import io.droptracker.util.RemoteImageCache;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import okhttp3.OkHttpClient;

import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.util.Map;

@Slf4j
public class DropTrackerPanel extends PluginPanel implements DropTrackerApi.PanelDataLoadedCallback {

	private static final ImageIcon LOGO_GIF;

	static {
		Image logoGif = ImageUtil.loadImageResource(DropTrackerPlugin.class, "brand/droptracker-small.gif");
		Image logoResized = logoGif.getScaledInstance(44, 44, Image.SCALE_DEFAULT);
		LOGO_GIF = new ImageIcon(logoResized);
	}

	/** Connection state shown as a dot + label in the persistent header. */
	public enum ConnectionState {
		CONNECTED("Connected", DropTrackerTheme.GREEN),
		CONNECTING("Connecting…", DropTrackerTheme.EMBER),
		OFFLINE("Offline", DropTrackerTheme.RED),
		DISABLED("API off", DropTrackerTheme.STONE);

		private final String label;
		private final Color color;

		ConnectionState(String label, Color color) {
			this.label = label;
			this.color = color;
		}
	}

	private final DropTrackerPlugin plugin;
	private final DropTrackerApi api;
	private final OkHttpClient httpClient;
	private JPanel headerPanel;

	@Inject
	private Client client;
	@Inject
	private ItemManager itemManager;
	@Inject
	private DropTrackerConfig config;
	@Inject
	private SubmissionManager submissionManager;
	@Inject
	private EventNotificationService eventNotificationService;
	@Inject
	private RemoteImageCache remoteImageCache;
	@Inject
	private ItemIDSearch itemIDSearch;

	private PlayerStatsPanel statsPanel;
	private GroupPanel groupPanel;
	private ActivityPanel activityPanel;
	private HomePanel homePanel;
	private EventsPanel eventsPanel;
	private JTabbedPane tabbedPane;

	// The actual component instances added as tabs
	private JPanel homeComponent;
	private JPanel activityComponent;
	private JPanel playerComponent;
	private JPanel groupComponent;
	private JPanel eventsComponent;

	private JLabel statusDotLabel;
	private JLabel statusTextLabel;

	@Inject
	public DropTrackerPanel(DropTrackerConfig config, DropTrackerApi api, DropTrackerPlugin plugin, Client client, OkHttpClient httpClient) {
		this.config = config;
		this.api = api;
		this.plugin = plugin;
		this.client = client;
		this.httpClient = httpClient;
		// Static UI helpers fetch images through the shared client; hand it over once here.
		PanelElements.setHttpClient(httpClient);

		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(6, 6, 6, 6));
		setBackground(DropTrackerTheme.SURFACE_0);

		// init() will be called by the plugin - don't call it here to avoid double initialization
	}

	public void init() {
		// Stop any timer owned by a previous incarnation of the Activity tab
		if (activityPanel != null) {
			activityPanel.cleanup();
			activityPanel = null;
		}
		removeAll();

		setLayout(new BorderLayout());
		headerPanel = buildHeader();

		tabbedPane = new JTabbedPane();
		tabbedPane.setBackground(DropTrackerTheme.SURFACE_0);
		tabbedPane.setForeground(DropTrackerTheme.TEXT);
		tabbedPane.setFont(FontManager.getRunescapeSmallFont());

		homePanel = new HomePanel(config, api, client, this);
		homeComponent = homePanel.create();

		if (config.useApi()) {
			activityPanel = new ActivityPanel(config, api, submissionManager, this);
			activityComponent = activityPanel.create();
			statsPanel = new PlayerStatsPanel(client, plugin, config, api, itemManager);
			playerComponent = statsPanel.create();
			groupPanel = new GroupPanel(client, config, api, itemManager, this, httpClient);
			groupComponent = groupPanel.create();
			eventsPanel = new EventsPanel(config, api, eventNotificationService,
				client, itemManager, remoteImageCache, itemIDSearch);
			eventsComponent = eventsPanel.create();
			eventNotificationService.setOnStateUpdated(() -> {
				if (eventsPanel != null) {
					eventsPanel.onUpdated();
				}
			});

			tabbedPane.addTab("Home", homeComponent);
			tabbedPane.addTab("Activity", activityComponent);
			tabbedPane.addTab("Events", eventsComponent);
			tabbedPane.addTab("Player", playerComponent);
			tabbedPane.addTab("Group", groupComponent);
		} else {
			activityComponent = null;
			playerComponent = null;
			groupComponent = null;
			eventsComponent = null;
			eventsPanel = null;
			eventNotificationService.setOnStateUpdated(null);
			tabbedPane.addTab("Home", homeComponent);
		}

		add(headerPanel, BorderLayout.NORTH);
		add(tabbedPane, BorderLayout.CENTER);
		tabbedPane.setSelectedComponent(homeComponent);

		// Populate the connection indicator once everything is wired up
		if (config.useApi() && activityPanel != null) {
			activityPanel.updateStatusLabel();
		} else {
			updateConnectionState(ConnectionState.DISABLED);
		}

		revalidate();
		repaint();
	}

	/**
	 * Compact persistent header: logo, name + version, tracked account, and a single
	 * connection dot + label, plus the refresh affordance.
	 */
	private JPanel buildHeader() {
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(DropTrackerTheme.SURFACE_1);
		header.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, DropTrackerTheme.SURFACE_3),
			new EmptyBorder(6, 8, 6, 8)));

		// Row 1: logo | title + version | refresh
		JPanel topRow = new JPanel(new BorderLayout(8, 0));
		topRow.setBackground(DropTrackerTheme.SURFACE_1);
		topRow.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 48));

		JLabel logoLabel = new JLabel(LOGO_GIF);
		topRow.add(logoLabel, BorderLayout.WEST);

		JPanel titleCol = new JPanel();
		titleCol.setLayout(new BoxLayout(titleCol, BoxLayout.Y_AXIS));
		titleCol.setBackground(DropTrackerTheme.SURFACE_1);

		JLabel titleLabel = new JLabel("DropTracker");
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setForeground(DropTrackerTheme.TEXT);
		titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel versionLabel = new JLabel("v" + plugin.pluginVersion);
		versionLabel.setFont(FontManager.getRunescapeSmallFont());
		versionLabel.setForeground(DropTrackerTheme.TEXT_MUTED);
		versionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		titleCol.add(Box.createVerticalGlue());
		titleCol.add(titleLabel);
		titleCol.add(versionLabel);
		titleCol.add(Box.createVerticalGlue());
		topRow.add(titleCol, BorderLayout.CENTER);

		JButton docsButton = new JButton("?");
		DropTrackerTheme.styleButton(docsButton);
		docsButton.setToolTipText("Open the DropTracker documentation");
		docsButton.setPreferredSize(new Dimension(26, 26));
		docsButton.setMargin(new java.awt.Insets(0, 0, 0, 0));
		docsButton.addActionListener(e -> LinkBrowser.browse("https://www.droptracker.io/docs"));

		JButton refreshButton = new JButton("↻");
		DropTrackerTheme.styleButton(refreshButton);
		refreshButton.setToolTipText("Refresh panel");
		refreshButton.setPreferredSize(new Dimension(26, 26));
		refreshButton.setMargin(new java.awt.Insets(0, 0, 0, 0));
		refreshButton.addActionListener(e -> init());

		JPanel refreshWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		refreshWrap.setBackground(DropTrackerTheme.SURFACE_1);
		refreshWrap.add(docsButton);
		refreshWrap.add(refreshButton);
		topRow.add(refreshWrap, BorderLayout.EAST);

		header.add(topRow);
		header.add(Box.createRigidArea(new Dimension(0, 4)));

		// Row 2: tracked account + connection status on one compact line
		JPanel statusRow = new JPanel(new BorderLayout(4, 0));
		statusRow.setBackground(DropTrackerTheme.SURFACE_1);
		statusRow.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 18));

		JLabel accountLabel = new JLabel();
		accountLabel.setFont(FontManager.getRunescapeSmallFont());
		String lastAccount = config.lastAccountName();
		if (lastAccount != null && !lastAccount.isEmpty()) {
			accountLabel.setText(lastAccount);
			accountLabel.setForeground(DropTrackerTheme.TEXT_MUTED);
			accountLabel.setToolTipText("Currently tracked account");
		} else {
			accountLabel.setText("You are not registered!");
			accountLabel.setForeground(DropTrackerTheme.EMBER);
			accountLabel.setToolTipText("<html>Claim your in-game name in our Discord to register.<br/>"
				+ "Join via the <b>Join Discord</b> button on the Home tab.</html>");
		}
		statusRow.add(accountLabel, BorderLayout.WEST);

		JPanel connectionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
		connectionPanel.setBackground(DropTrackerTheme.SURFACE_1);
		statusDotLabel = DropTrackerTheme.statusDot(DropTrackerTheme.STONE);
		statusTextLabel = new JLabel("…");
		statusTextLabel.setFont(FontManager.getRunescapeSmallFont());
		statusTextLabel.setForeground(DropTrackerTheme.TEXT_MUTED);
		connectionPanel.add(statusDotLabel);
		connectionPanel.add(statusTextLabel);
		statusRow.add(connectionPanel, BorderLayout.EAST);

		header.add(statusRow);

		return header;
	}

	public void deinit() {
		removeAll();
		if (activityPanel != null) {
			activityPanel.cleanup();
		}
		eventNotificationService.setOnStateUpdated(null);
		eventsPanel = null;
	}

	public void selectPanel(String panelToSelect) {
		if (tabbedPane == null) {
			return;
		}

		switch (panelToSelect.toLowerCase()) {
			case "home":
			case "welcome":
				if (homeComponent != null) {
					tabbedPane.setSelectedComponent(homeComponent);
				}
				break;
			case "player":
			case "players":
			case "stats":
				if (playerComponent != null && config.useApi()) {
					tabbedPane.setSelectedComponent(playerComponent);
				}
				break;
			case "group":
			case "groups":
				if (groupComponent != null && config.useApi()) {
					tabbedPane.setSelectedComponent(groupComponent);
				}
				break;
			// "api" kept for backwards compatibility: that tab is now "Activity"
			case "api":
			case "activity":
				if (activityComponent != null && config.useApi()) {
					tabbedPane.setSelectedComponent(activityComponent);
				}
				break;
			default:
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
		if (groupPanel != null && groupToLoad != null && !groupToLoad.trim().isEmpty()) {
			groupPanel.performGroupSearch(groupToLoad);
		}
	}

	// Update the Activity tab with the current session's valid submissions
	public void updateSentSubmissions() {
		if (activityPanel != null) {
			activityPanel.refresh();
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

	/**
	 * Updates the connection dot + label in the persistent header.
	 */
	public void updateConnectionState(ConnectionState state) {
		if (statusDotLabel == null || statusTextLabel == null || state == null) {
			return;
		}
		statusDotLabel.setForeground(state.color);
		statusTextLabel.setText(state.label);
		statusTextLabel.setForeground(state.color);
		if (headerPanel != null) {
			headerPanel.revalidate();
			headerPanel.repaint();
		}
	}

	@Override
	public void onDataLoaded(Map<String, Object> data) {
		updatePlayerPanel();
		updateGroupPanel("");
	}
}
