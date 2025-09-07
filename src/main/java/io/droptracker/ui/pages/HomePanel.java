package io.droptracker.ui.pages;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.ui.DropTrackerPanel;
import io.droptracker.ui.components.PanelElements;
import net.runelite.api.Client;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

import javax.swing.*;
import java.awt.*;

public class HomePanel {
    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final Client client;
    private final DropTrackerPanel panel;

    // Store references for dynamic updates
    private JPanel homePanel;
    private JPanel playerButtonRow;
    private int playerButtonIndex = -1; // Tracks where to insert the button

    public HomePanel(DropTrackerConfig config, DropTrackerApi api, Client client, DropTrackerPanel panel) {
        this.config = config;
        this.api = api;
        this.client = client;
        this.panel = panel;
    }

    public JPanel create() {
        homePanel = new JPanel();
        homePanel.setLayout(new BoxLayout(homePanel, BoxLayout.Y_AXIS));
        homePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Set maximum width to prevent expansion
        homePanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

        final JPanel welcomeMessagePanel = PanelElements.createCollapsiblePanel("Welcome to the DropTracker", PanelElements.getLatestWelcomeContent(api), true);
        // Patch notes panel (collapsible)
        welcomeMessagePanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
        welcomeMessagePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        final JPanel patchNotesPanel = PanelElements.createCollapsiblePanel("News / Updates", PanelElements.getLatestUpdateContent(config, api), false);
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


        // Create the global board button
        // Create button to view lootboard

        // Add buttons to the top row with proper sizing
        JButton guideButton = new JButton("Read the Wiki");
        guideButton.setFocusPainted(false);
        guideButton.setFont(FontManager.getRunescapeSmallFont());
        guideButton.addActionListener(e -> LinkBrowser.browse("https://www.droptracker.io/wiki"));
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
        suggestButton.addActionListener(e -> LinkBrowser.browse("https://www.droptracker.io/forums/suggestions"));
        // Prevent text truncation
        suggestButton.setMargin(new Insets(0, 0, 0, 0));

        JButton bugReportButton = new JButton("Report a Bug");
        bugReportButton.setFocusPainted(false);
        bugReportButton.setFont(FontManager.getRunescapeSmallFont());
        bugReportButton.addActionListener(e -> LinkBrowser.browse("https://www.droptracker.io/forums/bug-reports"));
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
        JPanel trackDropsPanel = PanelElements.createFeaturePanel("Track Your Drops",
                "Automatically record and analyze all your valuable drops from almost anywhere in the game.");
        trackDropsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel discordPanel = PanelElements.createFeaturePanel("Discord Integration",
                "Share your achievements with friends via customizable Discord webhooks.");
        discordPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel pbPanel = PanelElements.createFeaturePanel("Personal Bests",
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

        JButton viewGlobalButton = PanelElements.createLootboardButton("Global Lootboard", "Click to view the global lootboard", () -> PanelElements.showLootboardForGroup(client, 2));

        viewGlobalButton.setMargin(new Insets(3, 3, 3, 3));
        viewGlobalButton.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel buttonPanel = new JPanel(new BorderLayout(10, 0));
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
        buttonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttonPanel.add(viewGlobalButton);

        homePanel.add(buttonPanel);

        // Note: playerButtonIndex will be set to the next component index
        playerButtonIndex = homePanel.getComponentCount();

        // Initialize player button (will add if config is available)
        updatePlayerButton();

        homePanel.add(Box.createRigidArea(new Dimension(0, 8)));
        homePanel.add(patchNotesPanel);
        homePanel.add(Box.createRigidArea(new Dimension(0, 8)));
        homePanel.add(quickAccessPanel);
        homePanel.add(Box.createRigidArea(new Dimension(0, 8)));
        homePanel.add(featurePanelsContainer);

        return homePanel;
    }

    /**
     * Updates the player button based on current config state.
     * Can be called dynamically when the config changes.
     */
    public void updatePlayerButton() {
        if (homePanel == null) {
            return; // Panel not initialized yet
        }

        // Remove existing player button if it exists
        if (playerButtonRow != null) {
            homePanel.remove(playerButtonRow);
            playerButtonRow = null;
        }

        // Add player button if config is available
        if (config.lastAccountName() != null && !config.lastAccountName().isEmpty()) {
            playerButtonRow = new JPanel(new GridLayout(1, 2, 5, 0));
            playerButtonRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
            playerButtonRow.setAlignmentX(Component.LEFT_ALIGNMENT);

            JButton playerStatsButton = new JButton("View your Stats");
            playerStatsButton.setFocusPainted(false);
            playerStatsButton.setFont(FontManager.getRunescapeSmallFont());
            playerStatsButton.addActionListener(e -> {
                panel.selectPanel("players");
                panel.updatePlayerPanel(config.lastAccountName());
            });
            playerStatsButton.setMargin(new Insets(0, 0, 0, 0));

            playerButtonRow.add(playerStatsButton);

            // Insert at the correct position (after the global lootboard button)
            homePanel.add(playerButtonRow, playerButtonIndex);
        }

        // Refresh the panel
        homePanel.revalidate();
        homePanel.repaint();
    }
}
