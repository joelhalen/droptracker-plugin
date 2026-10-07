package io.droptracker.ui.pages;

import lombok.RequiredArgsConstructor;
import io.droptracker.api.*;
import io.droptracker.DropTrackerConfig;
import io.droptracker.ui.*;
import io.droptracker.ui.components.*;
import net.runelite.api.Client;
import net.runelite.client.ui.*;
import net.runelite.client.util.LinkBrowser;

import javax.annotation.Nullable;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

@RequiredArgsConstructor
public class HomePanel {
    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final Client client;
    private final DropTrackerPanel panel;
    private final DeathMessageApi deathMessageApi;

    // Store references for dynamic updates
    private JPanel homePanel;
    private @Nullable JPanel playerButtonRow;
    private int playerButtonIndex = -1; // Tracks where to insert the button

    public JPanel create() {
        homePanel = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);
        homePanel.setBorder(new EmptyBorder(5, 0, 5, 0));

        // Set maximum width to prevent expansion
        homePanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

        // When the API is disabled, lead with a call-to-action explaining what it unlocks.
        if (!config.useApi()) {
            homePanel.add(createApiCtaCard());
            homePanel.add(DropTrackerTheme.gap(8));
        }

        final JPanel welcomeMessagePanel = PanelElements.createCollapsiblePanel("Welcome to the DropTracker", PanelElements.getLatestWelcomeContent(api), true);

        final JPanel patchNotesPanel = PanelElements.createCollapsiblePanel("News / Updates", PanelElements.getLatestUpdateContent(config, api), false);

        homePanel.add(welcomeMessagePanel);
        homePanel.add(DropTrackerTheme.gap(8));

        // Global lootboard button row — a 1-cell grid so the button fills the
        // panel's full width instead of hugging its text.
        JButton viewGlobalButton = PanelElements.createLootboardButton("Global Lootboard", "Click to view the global lootboard", () -> PanelElements.showLootboardForGroup(client, 2));
        viewGlobalButton.setMargin(new Insets(3, 3, 3, 3));
        viewGlobalButton.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel buttonPanel = new JPanel(new GridLayout(1, 1, 5, 0));
        buttonPanel.setBackground(DropTrackerTheme.SURFACE_0);
        buttonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttonPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 30));
        buttonPanel.add(viewGlobalButton);
        homePanel.add(buttonPanel);

        // Note: playerButtonIndex will be set to the next component index
        playerButtonIndex = homePanel.getComponentCount();

        // Initialize player button (will add if config is available)
        updatePlayerButton();

        homePanel.add(DropTrackerTheme.gap(8));
        homePanel.add(patchNotesPanel);
        homePanel.add(DropTrackerTheme.gap(8));
        homePanel.add(createQuickLinks());
        // Collect leftover vertical space here instead of stretching the cards.
        homePanel.add(Box.createVerticalGlue());

        return homePanel;
    }

    /**
     * Prominent card shown when the API integration is disabled, explaining what
     * enabling it adds. The rest of the plugin (Discord webhook notifications) still
     * works without it.
     */
    private JPanel createApiCtaCard() {
        JPanel card = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
        card.setBorder(DropTrackerTheme.accentCardBorder(10, 10, 10, 10));
        card.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 170));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = DropTrackerTheme.label("Unlock the full DropTracker", FontManager.getRunescapeBoldFont(), DropTrackerTheme.GOLD);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextArea body = new JTextArea(
            "Enable \"Use API Connections\" in the plugin settings to unlock:\n"
            + "- Live top player & group leaderboards\n"
            + "- Player and group lookups\n"
            + "- Submission tracking with retries\n"
            + "- Participation in clan events");
        body.setWrapStyleWord(true);
        body.setLineWrap(true);
        body.setOpaque(false);
        body.setEditable(false);
        body.setFocusable(false);
        body.setForeground(DropTrackerTheme.TEXT_MUTED);
        body.setFont(FontManager.getRunescapeSmallFont());
        body.setBorder(new EmptyBorder(5, 0, 5, 0));
        body.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton learnMore = new JButton("Learn more");
        DropTrackerTheme.styleButton(learnMore);
        learnMore.setToolTipText("Read about the DropTracker API on the wiki");
        learnMore.setAlignmentX(Component.LEFT_ALIGNMENT);
        learnMore.addActionListener(e -> LinkBrowser.browse(DropTrackerUrls.web("wiki").toString()));

        card.add(title);
        card.add(body);
        card.add(learnMore);

        return card;
    }

    /** 2x2 grid of quick links: wiki, discord, suggestions, bug reports. */
    private JPanel createQuickLinks() {
        JPanel quickAccessPanel = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);
        quickAccessPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel topButtonRow = new JPanel(new GridLayout(1, 2, 5, 0));
        topButtonRow.setBackground(DropTrackerTheme.SURFACE_0);

        JPanel bottomButtonRow = new JPanel(new GridLayout(1, 2, 5, 0));
        bottomButtonRow.setBackground(DropTrackerTheme.SURFACE_0);

        topButtonRow.add(linkButton("Read the Wiki", DropTrackerUrls.web("wiki").toString()));
        topButtonRow.add(linkButton("Join Discord", DropTrackerUrls.web("discord").toString()));
        bottomButtonRow.add(linkButton("Suggest Features", DropTrackerUrls.web("forums", "suggestions").toString()));
        bottomButtonRow.add(linkButton("Report a Bug", DropTrackerUrls.web("forums", "bug-reports").toString()));

        quickAccessPanel.add(topButtonRow);
        quickAccessPanel.add(DropTrackerTheme.gap(8));
        quickAccessPanel.add(bottomButtonRow);

        quickAccessPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 68));
        return quickAccessPanel;
    }

    private JButton linkButton(String text, String url) {
        JButton button = new JButton(text);
        DropTrackerTheme.styleButton(button);
        button.addActionListener(e -> LinkBrowser.browse(url));
        button.setMargin(new Insets(0, 0, 0, 0));
        return button;
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

        // Add player button if the API is on and an account has been seen
        if (config.useApi() && config.lastAccountName() != null && !config.lastAccountName().isEmpty()) {
            playerButtonRow = new JPanel(new GridLayout(1, 2, 5, 0));
            playerButtonRow.setBackground(DropTrackerTheme.SURFACE_0);
            playerButtonRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            playerButtonRow.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 30));

            JButton playerStatsButton = new JButton("View your Stats");
            DropTrackerTheme.styleButton(playerStatsButton);
            playerStatsButton.addActionListener(e -> {
                panel.selectPanel("player");
                panel.updatePlayerPanel(config.lastAccountName());
            });
            playerStatsButton.setMargin(new Insets(0, 0, 0, 0));

            // The line the account's clans post when it dies (members' own
            // death messages); the same messages as the website and Discord.
            JButton deathMessageButton = new JButton("Death message");
            DropTrackerTheme.styleButton(deathMessageButton);
            deathMessageButton.setToolTipText("Write what your clan sees when you die");
            deathMessageButton.addActionListener(e -> DeathMessageDialog.open(client, config, deathMessageApi));
            deathMessageButton.setMargin(new Insets(0, 0, 0, 0));

            playerButtonRow.add(playerStatsButton);
            playerButtonRow.add(deathMessageButton);

            // Insert at the correct position (after the global lootboard button)
            homePanel.add(playerButtonRow, playerButtonIndex);
        }

        // Refresh the panel
        homePanel.revalidate();
        homePanel.repaint();
    }
}
