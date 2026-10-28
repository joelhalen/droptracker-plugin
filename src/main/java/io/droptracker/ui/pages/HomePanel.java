package io.droptracker.ui.pages;

import lombok.RequiredArgsConstructor;
import io.droptracker.api.*;
import io.droptracker.DropTrackerConfig;
import io.droptracker.ui.*;
import io.droptracker.ui.admin.GroupAdminWindow;
import io.droptracker.ui.components.*;
import io.droptracker.models.api.GroupAdmin;
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
    private final GroupAdminApi groupAdminApi;

    // Store references for dynamic updates
    private JPanel homePanel;
    private @Nullable JPanel playerButtonRow;
    private int playerButtonIndex = -1; // Tracks where to insert the button
    /** Holds the "Group admin" card; empty (and so invisible) for most players. */
    private final JPanel adminSlot = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);
    private long adminCardHash = -1;

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

        adminSlot.setAlignmentX(Component.LEFT_ALIGNMENT);
        homePanel.add(adminSlot);

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
            "Turn on \"Use API\" (settings, under Advanced) to unlock:\n"
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

        JButton learnMore = DropTrackerTheme.button("Learn more");
        learnMore.setToolTipText("Read about the DropTracker API");
        learnMore.setAlignmentX(Component.LEFT_ALIGNMENT);
        learnMore.addActionListener(e -> LinkBrowser.browse(DropTrackerUrls.web("docs", "runelite-plugin").toString()));

        card.add(title);
        card.add(body);
        card.add(learnMore);

        return card;
    }

    /** 2x2 grid of quick links: docs, discord, suggestions, bug reports. */
    private JPanel createQuickLinks() {
        JPanel quickAccessPanel = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);
        quickAccessPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel topButtonRow = new JPanel(new GridLayout(1, 2, 5, 0));
        topButtonRow.setBackground(DropTrackerTheme.SURFACE_0);

        JPanel bottomButtonRow = new JPanel(new GridLayout(1, 2, 5, 0));
        bottomButtonRow.setBackground(DropTrackerTheme.SURFACE_0);

        topButtonRow.add(linkButton("Read the Docs", DropTrackerUrls.web("docs").toString()));
        topButtonRow.add(linkButton("Join Discord", DropTrackerUrls.web("discord").toString()));
        bottomButtonRow.add(linkButton("Suggest Features", DropTrackerUrls.web("suggestions").toString()));
        bottomButtonRow.add(linkButton("Report a Bug", DropTrackerUrls.web("tickets").toString()));

        quickAccessPanel.add(topButtonRow);
        quickAccessPanel.add(DropTrackerTheme.gap(8));
        quickAccessPanel.add(bottomButtonRow);

        quickAccessPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 68));
        return quickAccessPanel;
    }

    private JButton linkButton(String text, String url) {
        JButton button = DropTrackerTheme.button(text);
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
            playerButtonRow = new JPanel(new GridLayout(1, 1));
            playerButtonRow.setBackground(DropTrackerTheme.SURFACE_0);
            playerButtonRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            playerButtonRow.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 30));

            JButton playerStatsButton = DropTrackerTheme.button("View your Stats");
            playerStatsButton.addActionListener(e -> {
                panel.selectPanel("player");
                panel.updatePlayerPanel(config.lastAccountName());
            });
            playerStatsButton.setMargin(new Insets(0, 0, 0, 0));

            playerButtonRow.add(playerStatsButton);

            // Insert at the correct position (after the global lootboard button)
            homePanel.add(playerButtonRow, playerButtonIndex);
        }

        refreshAdminCard();

        // Refresh the panel
        homePanel.revalidate();
        homePanel.repaint();
    }

    /**
     * Asks once per account which groups it may manage, and shows a small card
     * for them. Players who manage nothing never see it.
     */
    private void refreshAdminCard() {
        long hash = client.getAccountHash();
        String name = config.lastAccountName();
        if (!config.useApi() || hash == -1 || name == null || name.isEmpty()) {
            adminSlot.removeAll();
            adminCardHash = -1;
            return;
        }
        if (hash == adminCardHash) {
            return;
        }
        adminCardHash = hash;
        groupAdminApi.groups(name, hash, result -> {
            adminSlot.removeAll();
            if (result.value != null && !result.value.getGroups().isEmpty() && hash == adminCardHash) {
                adminSlot.add(DropTrackerTheme.gap(8));
                adminSlot.add(createAdminCard(result.value.getGroups()));
            }
            adminSlot.revalidate();
            adminSlot.repaint();
        });
    }

    private JPanel createAdminCard(java.util.List<GroupAdmin.Group> groups) {
        JPanel card = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
        card.setBorder(DropTrackerTheme.accentCardBorder(8, 8, 8, 8));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

        JLabel title = DropTrackerTheme.label("Group admin", FontManager.getRunescapeBoldFont(), DropTrackerTheme.GOLD);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(title);
        JLabel hint = DropTrackerTheme.label("Manage your group in-game.",
            FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(hint);

        for (GroupAdmin.Group group : groups.subList(0, Math.min(groups.size(), 4))) {
            card.add(DropTrackerTheme.gap(6));
            JPanel row = new JPanel(new BorderLayout(6, 0));
            row.setBackground(DropTrackerTheme.SURFACE_2);
            row.setBorder(new EmptyBorder(4, 6, 4, 4));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 30));

            JPanel names = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_2);
            names.add(DropTrackerTheme.label(group.getName(), FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT));
            boolean owner = "owner".equals(group.getRole());
            names.add(DropTrackerTheme.label(owner ? "Owner" : "Admin", FontManager.getRunescapeSmallFont(),
                owner ? DropTrackerTheme.GOLD : DropTrackerTheme.TEXT_MUTED));
            row.add(names, BorderLayout.CENTER);

            JButton manage = DropTrackerTheme.button("Manage");
            manage.addActionListener(e -> GroupAdminWindow.open(client, config, groupAdminApi, groups, group.getId()));
            row.add(manage, BorderLayout.EAST);
            card.add(row);
        }
        return card;
    }
}
