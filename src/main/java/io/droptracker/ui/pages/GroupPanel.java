package io.droptracker.ui.pages;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.BasicStroke;
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

import javax.inject.Inject;

import io.droptracker.DropTrackerConfig;
import io.droptracker.ui.PanelElements;
import io.droptracker.util.ChatMessageUtil;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

public class GroupPanel {

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DropTrackerConfig config;

	@Inject
	private ChatMessageUtil chatMessageUtil;

    public GroupPanel(Client client, ClientThread clientThread, DropTrackerConfig config, ChatMessageUtil chatMessageUtil) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.chatMessageUtil = chatMessageUtil;
	}

    public JPanel create() {
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
		searchPanel.setBorder(new StrokeBorder(new BasicStroke(1), ColorScheme.DARKER_GRAY_HOVER_COLOR));
		searchPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 30));
		
		JTextField searchField = new JTextField();
		searchField.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 100, 40));
		searchField.setBorder(new StrokeBorder(new BasicStroke(1), ColorScheme.DARKER_GRAY_HOVER_COLOR));
		searchField.setToolTipText("Search for a group");
		
		JButton searchButton = new JButton("Search");
		searchButton.setPreferredSize(new Dimension(70, 40));
		searchButton.setMargin(new Insets(5, 5, 5, 5));
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
		JPanel membersBox = PanelElements.createStatBox("Members", "0");
		JPanel rankBox = PanelElements.createStatBox("Global Rank", "N/A");
		JPanel lootBox = PanelElements.createStatBox("Monthly Loot", "0 GP");
		JPanel topPlayerBox = PanelElements.createStatBox("Top Player", "None");
		
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
		JPanel member1 = PanelElements.createMemberRow("Player1", "1,234,567 GP", "1");
		member1.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));
		member1.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));
		member1.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JPanel member2 = PanelElements.createMemberRow("Player2", "987,654 GP", "2");
		member2.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));
		member2.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));
		member2.setAlignmentX(Component.LEFT_ALIGNMENT);
		
		JPanel member3 = PanelElements.createMemberRow("Player3", "567,890 GP", "3");
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
		
		// Remove the scroll pane wrapping - just return the main panel
		return groupPanel;
	}

}
