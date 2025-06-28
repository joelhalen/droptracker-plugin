package io.droptracker.ui.pages;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.border.StrokeBorder;

import com.google.inject.Inject;

import io.droptracker.ui.PanelElements;
import io.droptracker.util.ChatMessageUtil;
import io.droptracker.DropTrackerConfig;

import java.awt.BasicStroke;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.callback.ClientThread;
import net.runelite.api.Client;

public class PlayerStatsPanel {
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DropTrackerConfig config;

	@Inject
	private ChatMessageUtil chatMessageUtil;

	public PlayerStatsPanel(Client client, ClientThread clientThread, DropTrackerConfig config, ChatMessageUtil chatMessageUtil) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.chatMessageUtil = chatMessageUtil;
	}

	public JPanel create() {
		JPanel statsPanel = new JPanel();
		statsPanel.setLayout(new BoxLayout(statsPanel, BoxLayout.Y_AXIS));
		statsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Header section with title and search
		JPanel headerPanel = new JPanel();
		headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		headerPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 100));

		JLabel statsTitle = new JLabel("DropTracker - Players");
		statsTitle.setFont(FontManager.getRunescapeBoldFont());
		statsTitle.setForeground(Color.WHITE);
		statsTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

		// Search field for players
		JPanel searchPanel = new JPanel(new BorderLayout(5, 0));
		searchPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchPanel.setBorder(new StrokeBorder(new BasicStroke(1), ColorScheme.DARKER_GRAY_HOVER_COLOR));
		searchPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 30));

		JTextField searchField = new JTextField();
		searchField.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 100, 40));
		searchField.setBorder(new StrokeBorder(new BasicStroke(1), ColorScheme.DARKER_GRAY_HOVER_COLOR));
		searchField.setToolTipText("Search for a player");

		JButton searchButton = new JButton("Search");
		searchButton.setPreferredSize(new Dimension(70, 40));
		searchButton.setMargin(new Insets(5, 5, 5, 5));
		searchButton.addActionListener(e -> {
			// Will be implemented later to search for players
			JOptionPane.showMessageDialog(null, "Player search will be implemented soon!");
		});

		searchPanel.add(searchField, BorderLayout.CENTER);
		searchPanel.add(searchButton, BorderLayout.EAST);

		headerPanel.add(statsTitle);
		headerPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		headerPanel.add(searchPanel);

		// Player info section
		JPanel playerInfoPanel = new JPanel();
		playerInfoPanel.setLayout(new BoxLayout(playerInfoPanel, BoxLayout.Y_AXIS));
		playerInfoPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		playerInfoPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		// Player header panel (no icon needed)
		JPanel playerHeaderPanel = new JPanel(new BorderLayout(10, 0));
		playerHeaderPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		playerHeaderPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 60));
		playerHeaderPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 60));
		playerHeaderPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel playerNamePanel = new JPanel();
		playerNamePanel.setLayout(new BoxLayout(playerNamePanel, BoxLayout.Y_AXIS));
		playerNamePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Get current player name or show placeholder
		String playerName = (client.getLocalPlayer() != null) ? client.getLocalPlayer().getName() : "Not logged in";

		JLabel playerNameLabel = new JLabel(playerName);
		playerNameLabel.setFont(FontManager.getRunescapeBoldFont());
		playerNameLabel.setForeground(Color.WHITE);
		playerNameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel playerDescLabel = new JLabel("Your Statistics");
		playerDescLabel.setFont(FontManager.getRunescapeSmallFont());
		playerDescLabel.setForeground(Color.LIGHT_GRAY);
		playerDescLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		playerNamePanel.add(playerNameLabel);
		playerNamePanel.add(playerDescLabel);

		playerHeaderPanel.add(playerNamePanel, BorderLayout.CENTER);

		// Player stats in a table-like format - fixed layout (2x2 grid)
		JPanel playerStatsPanel = new JPanel(new GridLayout(2, 2, 5, 5));
		playerStatsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		playerStatsPanel.setBorder(new EmptyBorder(10, 0, 10, 0));
		playerStatsPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 100));
		playerStatsPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 100));
		playerStatsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Create stat boxes with fixed sizes
		JPanel totalLootBox = PanelElements.createStatBox("Total Loot", "Loading...");
		JPanel globalRankBox = PanelElements.createStatBox("Global Rank", "Loading...");
		JPanel topNpcBox = PanelElements.createStatBox("Top NPC", "Loading...");
		JPanel bestPbBox = PanelElements.createStatBox("Best PB Rank", "Loading...");

		playerStatsPanel.add(totalLootBox);
		playerStatsPanel.add(globalRankBox);
		playerStatsPanel.add(topNpcBox);
		playerStatsPanel.add(bestPbBox);

		// Top NPCs section (similar to members section in groups)
		JPanel topNpcsPanel = new JPanel();
		topNpcsPanel.setLayout(new BoxLayout(topNpcsPanel, BoxLayout.Y_AXIS));
		topNpcsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		topNpcsPanel.setBorder(new EmptyBorder(10, 0, 10, 0));
		topNpcsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		topNpcsPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 200));
		topNpcsPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 200));

		JLabel topNpcsTitle = new JLabel("Top NPCs by Loot Value");
		topNpcsTitle.setFont(FontManager.getRunescapeBoldFont());
		topNpcsTitle.setForeground(Color.WHITE);
		topNpcsTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel npcsList = new JPanel();
		npcsList.setLayout(new BoxLayout(npcsList, BoxLayout.Y_AXIS));
		npcsList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		npcsList.setAlignmentX(Component.LEFT_ALIGNMENT);
		npcsList.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 150));
		npcsList.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 150));

		// Add placeholder NPCs with fixed widths
		JPanel npc1 = PanelElements.createNpcRow("Vorkath", "12,345,678 GP", "1");
		npc1.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));
		npc1.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));
		npc1.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel npc2 = PanelElements.createNpcRow("Zulrah", "8,765,432 GP", "2");
		npc2.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));
		npc2.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));
		npc2.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel npc3 = PanelElements.createNpcRow("Hydra", "5,432,109 GP", "3");
		npc3.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));
		npc3.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));
		npc3.setAlignmentX(Component.LEFT_ALIGNMENT);

		npcsList.add(npc1);
		npcsList.add(Box.createRigidArea(new Dimension(0, 5)));
		npcsList.add(npc2);
		npcsList.add(Box.createRigidArea(new Dimension(0, 5)));
		npcsList.add(npc3);

		topNpcsPanel.add(topNpcsTitle);
		topNpcsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		topNpcsPanel.add(npcsList);

		// Action buttons
		JPanel actionPanel = new JPanel();
		actionPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 10, 0));
		actionPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		actionPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 40));
		actionPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 40));
		actionPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JButton refreshButton = new JButton("Refresh Stats");
		refreshButton.setMargin(new Insets(0, 5, 0, 5));
		refreshButton.addActionListener(e -> {
			clientThread.invokeLater(() -> {
				if (client.getGameState().getState() >= 30) {
					if (!config.useApi()) {
						chatMessageUtil.sendChatMessage(
								"You must have API connections enabled in the DropTracker plugin configuration in order to refresh your statistics!");
					} else {
						// load panel data here
						JOptionPane.showMessageDialog(null, "Stats refreshed! (Implementation pending)");
					}
				}
			});
		});

		JButton viewProfileButton = new JButton("View Profile");
		viewProfileButton.setMargin(new Insets(0, 5, 0, 5));
		viewProfileButton.addActionListener(e -> {
			if (config.useApi()) {
				// TODO: Need to get the player's ID properly or add a XF endpoint to properly
				// link the name
				LinkBrowser.browse("https://www.droptracker.io/players/" + playerName + "/view");
			} else {
				JOptionPane.showMessageDialog(null, "API must be enabled to view your profile online!");
			}
		});

		actionPanel.add(refreshButton);
		actionPanel.add(viewProfileButton);

		// Add all sections to the player info panel
		playerInfoPanel.add(playerHeaderPanel);
		playerInfoPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		playerInfoPanel.add(playerStatsPanel);
		playerInfoPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		playerInfoPanel.add(topNpcsPanel);
		playerInfoPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		playerInfoPanel.add(actionPanel);

		// Add all components to the main panel
		statsPanel.add(headerPanel);
		statsPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		statsPanel.add(playerInfoPanel);
		statsPanel.add(Box.createVerticalGlue());

		// Remove the scroll pane wrapping - just return the main panel
		return statsPanel;
	}
}
