package io.droptracker.ui;

import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;

import javax.inject.Inject;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextArea;

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


public class DropTrackerPanelNew extends PluginPanel
{
	private static final String DONATION_LINK = "https://www.buymeacoffee.com/dillydill123";
	private static final String WIKI_LINK = "https://www.droptracker.io/wiki";
	private static final String SUGGESTION_LINK = "https://www.droptracker.io/forums/suggestions";
	
	private static final ImageIcon COLLAPSED_ICON;
	private static final ImageIcon EXPANDED_ICON;
	
	static {
		// Load original images
		Image collapsedImg = ImageUtil.loadImageResource(DropTrackerPlugin.class, "util/collapse.png");
		Image expandedImg = ImageUtil.loadImageResource(DropTrackerPlugin.class, "util/expand.png");
		
		// Resize to appropriate dimensions
		Image collapsedResized = collapsedImg.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
		Image expandedResized = expandedImg.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
		
		// Create icons from resized images
		COLLAPSED_ICON = new ImageIcon(collapsedResized);
		EXPANDED_ICON = new ImageIcon(expandedResized);
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
		
		contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		
		// Header
		final JLabel welcomeText = new JLabel("DropTracker v" + plugin.pluginVersion);
		welcomeText.setFont(FontManager.getRunescapeBoldFont());
		welcomeText.setHorizontalAlignment(JLabel.CENTER);
		welcomeText.setForeground(Color.WHITE);

		final JPanel welcomePanel = new JPanel(new BorderLayout());
		welcomePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		welcomePanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		welcomePanel.add(welcomeText, BorderLayout.NORTH);

		// Patch notes panel (collapsible)
		final JPanel patchNotesPanel = createCollapsiblePanel("News/Updates", getLatestUpdateContent());
		
		// Add stats panel if API is enabled
		JPanel statsPanel = null;
		if (config.useApi()) {
			statsPanel = createStatsPanel();
		}
		
		// Quick access buttons in a grid (without subtexts)
		JPanel quickAccessPanel = new JPanel(new GridLayout(1, 2, 5, 0));
		quickAccessPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		
		JButton guideButton = new JButton("Docs");
		guideButton.setFocusPainted(false);
		guideButton.setFont(FontManager.getRunescapeSmallFont());
		guideButton.addActionListener(e -> LinkBrowser.browse(WIKI_LINK));
		
		JButton suggestButton = new JButton("Suggestions");
		suggestButton.setFocusPainted(false);
		suggestButton.setFont(FontManager.getRunescapeSmallFont());
		suggestButton.addActionListener(e -> LinkBrowser.browse(SUGGESTION_LINK));
		
		quickAccessPanel.add(guideButton);
		quickAccessPanel.add(suggestButton);
		
		// Feature panels
		JPanel trackDropsPanel = createFeaturePanel("Track Your Progress", 
			"Automatically track and submit your drops / achievements to our Discord bot");
		
		JPanel discordPanel = createFeaturePanel("Compete with your Clan", 
			"See how you rank up against other members of your clan or the global leaderboards");
		
		JPanel pbPanel = createFeaturePanel("Personal Bests", 
			"Rank within personal best leaderboards with all players using our plugin");
		
		// Add all panels to content panel with proper spacing
		contentPanel.add(welcomePanel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 8)));
		contentPanel.add(patchNotesPanel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 8)));
		
		// Add stats panel right after patch notes if enabled
		if (statsPanel != null) {
			contentPanel.add(statsPanel);
			contentPanel.add(Box.createRigidArea(new Dimension(0, 8)));
		}
		
		contentPanel.add(quickAccessPanel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 8)));
		contentPanel.add(trackDropsPanel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 8)));
		contentPanel.add(discordPanel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 8)));
		contentPanel.add(pbPanel);

		add(contentPanel, BorderLayout.NORTH);
		revalidate();
		repaint();
	}
	
	private JPanel createCollapsiblePanel(String title, JPanel content) {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(10, 10, 10, 10));
		
		JPanel headerPanel = new JPanel(new BorderLayout());
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		
		JLabel titleLabel = new JLabel(title);
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setForeground(Color.WHITE);
		
		JLabel toggleIcon = new JLabel(EXPANDED_ICON);
		
		headerPanel.add(titleLabel, BorderLayout.WEST);
		headerPanel.add(toggleIcon, BorderLayout.EAST);
		
		// Store content panel for toggling
		patchNotesContent = content;
		
		// Add click listener for collapsing/expanding
		headerPanel.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				patchNotesCollapsed = !patchNotesCollapsed;
				toggleIcon.setIcon(patchNotesCollapsed ? COLLAPSED_ICON : EXPANDED_ICON);
				patchNotesContent.setVisible(!patchNotesCollapsed);
				panel.revalidate();
				panel.repaint();
			}
		});
		
		panel.add(headerPanel);
		panel.add(getJSeparator(ColorScheme.LIGHT_GRAY_COLOR));
		panel.add(content);
		
		return panel;
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

		JTextArea textArea = new JTextArea();
		textArea.setText(updateText);
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
		
		JPanel contentPanel = new JPanel();
		contentPanel.setLayout(new BorderLayout());
		contentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		contentPanel.add(textArea, BorderLayout.CENTER);
		
		return contentPanel;
	}
	
	private JPanel createFeaturePanel(String title, String description) {
		JPanel panel = new JPanel();
		panel.setLayout(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(10, 10, 10, 10));
		
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
		descArea.setBorder(new EmptyBorder(5, 5, 5, 5));
		
		panel.add(titleLabel, BorderLayout.NORTH);
		panel.add(descArea, BorderLayout.CENTER);
		
		return panel;
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
					// Only refresh if logged in
					// Add code to refresh stats from API
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
	
	// Method to update stats if you implement that feature
	public void updateStats(Map<String, Object> stats) {
		// Implementation to update the stats panel with data from your API
	}
}