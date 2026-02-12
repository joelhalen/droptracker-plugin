package io.droptracker.ui.pages;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.api.GroupConfig;
import io.droptracker.models.submissions.SubmissionStatus;
import io.droptracker.models.submissions.ValidSubmission;
import io.droptracker.service.NearbyPlayerTracker;
import io.droptracker.service.SubmissionManager;
import io.droptracker.ui.DropTrackerPanel;
import io.droptracker.ui.components.PanelElements;
import io.droptracker.util.DurationAdapter;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ApiPanel {
    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final SubmissionManager submissionManager;
    private final NearbyPlayerTracker nearbyPlayerTracker;

    private JPanel apiPanel;
    private JPanel submissionsPanel;
    private JPanel statisticsPanel;
    private Timer statusUpdateTimer;
    private JPanel groupConfigPanel;
    private JPanel groupsContainerPanel;
    private DropTrackerPanel mainPanel;
    private JScrollPane groupsScrollPane;
    private final Map<String, Boolean> groupExpandStates = new HashMap<>();

    public ApiPanel(
        DropTrackerConfig config,
        DropTrackerApi api,
        SubmissionManager submissionManager,
        NearbyPlayerTracker nearbyPlayerTracker,
        DropTrackerPanel mainPanel
    ) {
        this.config = config;
        this.api = api;
        this.submissionManager = submissionManager;
        this.nearbyPlayerTracker = nearbyPlayerTracker;
        this.mainPanel = mainPanel;
    }

    public JPanel create() {
        apiPanel = new JPanel();
        apiPanel.setLayout(new BoxLayout(apiPanel, BoxLayout.Y_AXIS));
        apiPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        apiPanel.setBorder(new EmptyBorder(0, 0, 0, 0));

        JPanel configPanel = initializeConfigPanel();
        JPanel statisticsPanel = initializeStatisticsPanel();
        initializeSubmissionsPanel();

        apiPanel.add(statisticsPanel);
        apiPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        apiPanel.add(submissionsPanel);
        apiPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        apiPanel.add(initializeDebugButtonsPanel());
        apiPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        apiPanel.add(configPanel);
        apiPanel.add(Box.createVerticalGlue());

        updateStatusLabel();
        refreshStatistics();
        refreshSubmissions();
        refreshGroupConfigs();

        if (config.pollUpdates()) {
            statusUpdateTimer = new Timer(10000, e -> {
                updateStatusLabel();
                refreshStatistics();
                refreshGroupConfigs();
                if (config.useApi() && submissionManager.hasActiveSubmissions()) {
                    submissionManager.checkPendingStatuses();
                    refreshSubmissions();
                }
            });
            statusUpdateTimer.start();
        }

        JPanel wrapperPanel = new JPanel(new BorderLayout());
        wrapperPanel.add(apiPanel, BorderLayout.CENTER);
        return wrapperPanel;
    }

    private JPanel initializeDebugButtonsPanel() {
        JPanel debugPanel = new JPanel();
        debugPanel.setLayout(new BoxLayout(debugPanel, BoxLayout.Y_AXIS));
        debugPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        debugPanel.setBorder(new EmptyBorder(8, 10, 8, 10));
        debugPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 48));
        debugPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 56));
        debugPanel.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 40));

        JButton testNearbyPlayersButton = new JButton("Print Nearby Players");
        testNearbyPlayersButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        testNearbyPlayersButton.setFont(FontManager.getRunescapeSmallFont());
        testNearbyPlayersButton.setToolTipText("Print nearby player names to console.");
        testNearbyPlayersButton.addActionListener(e -> {
            if (nearbyPlayerTracker != null) {
                nearbyPlayerTracker.printNearbyPlayersToConsole(20);
            } else {
                System.out.println("[DropTracker] Nearby player tracker unavailable.");
            }
        });

        debugPanel.add(testNearbyPlayersButton);
        return debugPanel;
    }

    private void initializeSubmissionsPanel() {
        submissionsPanel = new JPanel();
        submissionsPanel.setLayout(new BoxLayout(submissionsPanel, BoxLayout.Y_AXIS));
        submissionsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        submissionsPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        submissionsPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 200));
        submissionsPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 300));
        submissionsPanel.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 50));

        JLabel submissionsTitle = new JLabel("Session Submissions");
        submissionsTitle.setFont(FontManager.getRunescapeBoldFont());
        submissionsTitle.setForeground(Color.WHITE);
        submissionsTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        submissionsPanel.add(submissionsTitle);
        submissionsPanel.add(Box.createRigidArea(new Dimension(0, 3)));

        JTextArea descriptionText = new JTextArea("");
        descriptionText.setWrapStyleWord(true);
        descriptionText.setLineWrap(true);
        descriptionText.setOpaque(false);
        descriptionText.setEditable(false);
        descriptionText.setFocusable(false);
        descriptionText.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        descriptionText.setForeground(Color.LIGHT_GRAY);
        descriptionText.setFont(FontManager.getRunescapeSmallFont());
        descriptionText.setBorder(new EmptyBorder(5, 5, 5, 5));
        submissionsPanel.add(descriptionText);
        submissionsPanel.add(Box.createRigidArea(new Dimension(0, 3)));
    }

    private JPanel initializeStatisticsPanel() {
        statisticsPanel = new JPanel();
        statisticsPanel.setLayout(new BoxLayout(statisticsPanel, BoxLayout.Y_AXIS));
        statisticsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        statisticsPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        statisticsPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 80));
        statisticsPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 100));
        statisticsPanel.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 60));

        JLabel statisticsTitle = new JLabel("Session Statistics");
        statisticsTitle.setFont(FontManager.getRunescapeBoldFont());
        statisticsTitle.setForeground(Color.WHITE);
        statisticsTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        statisticsPanel.add(statisticsTitle);
        statisticsPanel.add(Box.createRigidArea(new Dimension(0, 5)));

        JPanel statsContainer = createStreamlinedStatsDisplay();
        statisticsPanel.add(statsContainer);

        return statisticsPanel;
    }

    private JPanel createStreamlinedStatsDisplay() {
        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        container.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 50));

        // Derive stats from submission list
        int totalSubmissions = submissionManager.getTotalSubmissions();
        int notificationsSent = submissionManager.getNotificationsSent();
        int failedSubmissions = submissionManager.getFailedSubmissions();
        long totalValue = submissionManager.getTotalValue();

        JPanel row1 = createStatsRow(
            "Notified count:", String.valueOf(totalSubmissions), Color.CYAN,
            "Sent:", String.valueOf(notificationsSent), Color.GREEN
        );
        row1.setToolTipText("Total number of submissions you have sent, and number of those which have created Discord notifications");

        JPanel row2 = createStatsRow(
            "# Failed:", String.valueOf(failedSubmissions), Color.RED,
            "GP value:", formatValue(totalValue), Color.YELLOW
        );

        JPanel statusRow = createStatusRow();

        container.add(row1);
        container.add(Box.createRigidArea(new Dimension(0, 2)));
        container.add(row2);
        container.add(Box.createRigidArea(new Dimension(0, 3)));
        container.add(statusRow);

        return container;
    }

    private JPanel createStatsRow(String label1, String value1, Color color1,
                                  String label2, String value2, Color color2) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 16));
        row.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 16));

        JPanel stat1 = createCompactStat(label1, value1, color1);
        row.add(stat1);

        JLabel separator = new JLabel("|");
        separator.setForeground(Color.GRAY);
        separator.setFont(FontManager.getRunescapeSmallFont());
        row.add(separator);

        JPanel stat2 = createCompactStat(label2, value2, color2);
        row.add(stat2);

        return row;
    }

    private JPanel createCompactStat(String label, String value, Color valueColor) {
        JPanel stat = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        stat.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JLabel labelComponent = new JLabel(label);
        labelComponent.setFont(FontManager.getRunescapeSmallFont());
        labelComponent.setForeground(Color.LIGHT_GRAY);

        JLabel valueComponent = new JLabel(value);
        valueComponent.setFont(FontManager.getRunescapeSmallFont());
        valueComponent.setForeground(valueColor);

        stat.add(labelComponent);
        stat.add(valueComponent);

        return stat;
    }

    private JPanel createStatusRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 14));
        row.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 14));

        JLabel healthIcon = new JLabel("\u25CF");
        boolean healthy = true;
        healthIcon.setForeground(healthy ? Color.GREEN : Color.RED);
        healthIcon.setFont(FontManager.getRunescapeSmallFont());

        JLabel healthLabel = new JLabel("API Status");
        healthLabel.setFont(FontManager.getRunescapeSmallFont());
        healthLabel.setForeground(Color.LIGHT_GRAY);

        row.add(healthIcon);
        row.add(healthLabel);
        row.add(new JLabel(" "));

        return row;
    }

    private String formatValue(long value) {
        if (value == 0) return "0";

        if (value >= 1_000_000) {
            return String.format("%.1fM", value / 1_000_000.0);
        } else if (value >= 1_000) {
            return String.format("%.1fK", value / 1_000.0);
        } else {
            return String.valueOf(value);
        }
    }

    private JPanel initializeConfigPanel() {
        JPanel wrapperPanel = new JPanel();
        wrapperPanel.setLayout(new BoxLayout(wrapperPanel, BoxLayout.Y_AXIS));
        wrapperPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        wrapperPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 400));

        JPanel groupsContainer = new JPanel();
        groupsContainer.setLayout(new BoxLayout(groupsContainer, BoxLayout.Y_AXIS));
        groupsContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        buildGroupConfigPanels(groupsContainer);

        groupsScrollPane = new JScrollPane(groupsContainer);
        groupsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        groupsScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        groupsScrollPane.setBorder(null);
        groupsScrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupsScrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupsScrollPane.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 150));
        groupsScrollPane.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 150));

        wrapperPanel.add(groupsScrollPane);
        this.groupsContainerPanel = groupsContainer;
        groupConfigPanel = createMainCollapsiblePanel("Group Configurations", wrapperPanel);

        return groupConfigPanel;
    }

    private JPanel createMainCollapsiblePanel(String title, JPanel content) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        panel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 200));
        panel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 200));

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(FontManager.getRunescapeBoldFont());
        titleLabel.setForeground(Color.WHITE);

        ImageIcon expandedIcon = PanelElements.getExpandedIcon();
        ImageIcon collapsedIcon = PanelElements.getCollapsedIcon();

        final ImageIcon finalExpandedIcon = expandedIcon;
        final ImageIcon finalCollapsedIcon = collapsedIcon;
        final JLabel toggleIcon = new JLabel(expandedIcon);

        headerPanel.add(titleLabel, BorderLayout.WEST);
        headerPanel.add(toggleIcon, BorderLayout.EAST);

        final boolean[] isCollapsed = {false};

        headerPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                isCollapsed[0] = !isCollapsed[0];
                toggleIcon.setIcon(isCollapsed[0] ? finalCollapsedIcon : finalExpandedIcon);
                content.setVisible(!isCollapsed[0]);

                if (isCollapsed[0]) {
                    panel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, headerPanel.getPreferredSize().height + 20));
                    panel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, headerPanel.getPreferredSize().height + 20));
                } else {
                    panel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 200));
                    panel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 200));
                }

                panel.revalidate();
                panel.repaint();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                headerPanel.setCursor(new Cursor(Cursor.HAND_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                headerPanel.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
            }
        });

        JSeparator separator = new JSeparator();
        separator.setBackground(ColorScheme.LIGHT_GRAY_COLOR);
        separator.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        panel.add(headerPanel);
        panel.add(separator);
        panel.add(content);

        return panel;
    }

    private void buildGroupConfigPanels(JPanel parentPanel) {
        parentPanel.removeAll();

        List<GroupConfig> groupConfigs = api.getGroupConfigs();
        if (groupConfigs != null && groupConfigs.size() > 0) {
            for (GroupConfig groupConfig : groupConfigs) {
                JPanel groupContentPanel = createGroupConfigPanel(groupConfig);
                String groupTitle = groupConfig.getGroupName() + " (ID: " + groupConfig.getGroupId() + ")";
                String groupKey = String.valueOf(groupConfig.getGroupId());
                boolean isCollapsed = groupExpandStates.getOrDefault(groupKey, true);
                JPanel collapsibleGroup = createCompactCollapsiblePanel(groupTitle, groupContentPanel, groupKey, isCollapsed);
                parentPanel.add(collapsibleGroup);
                parentPanel.add(Box.createRigidArea(new Dimension(0, 2)));
            }
        } else {
            JPanel emptyPanel = new JPanel();
            emptyPanel.setLayout(new BoxLayout(emptyPanel, BoxLayout.Y_AXIS));
            emptyPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            emptyPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            emptyPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));

            JLabel noConfigsLabel = new JLabel("No group configurations loaded.");
            noConfigsLabel.setFont(FontManager.getRunescapeSmallFont());
            noConfigsLabel.setForeground(Color.GRAY);
            noConfigsLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            emptyPanel.add(Box.createVerticalGlue());
            emptyPanel.add(noConfigsLabel);
            emptyPanel.add(Box.createVerticalGlue());

            parentPanel.add(emptyPanel);
        }
    }

    private JPanel createCompactCollapsiblePanel(String title, JPanel content, String groupKey, boolean isCollapsed) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(2, 5, 2, 5));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        headerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        headerPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 18));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(FontManager.getRunescapeSmallFont());
        titleLabel.setForeground(Color.WHITE);

        ImageIcon expandedIcon = PanelElements.getExpandedIcon();
        ImageIcon collapsedIcon = PanelElements.getCollapsedIcon();
        final ImageIcon finalExpandedIcon = expandedIcon;
        final ImageIcon finalCollapsedIcon = collapsedIcon;
        final JLabel toggleIcon = new JLabel(isCollapsed ? collapsedIcon : expandedIcon);

        headerPanel.add(titleLabel, BorderLayout.WEST);
        headerPanel.add(toggleIcon, BorderLayout.EAST);

        content.setVisible(!isCollapsed);

        if (isCollapsed) {
            panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
            panel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 24));
        }

        final boolean[] isCollapsedRef = {isCollapsed};

        MouseAdapter clickListener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                isCollapsedRef[0] = !isCollapsedRef[0];
                toggleIcon.setIcon(isCollapsedRef[0] ? finalCollapsedIcon : finalExpandedIcon);
                content.setVisible(!isCollapsedRef[0]);
                groupExpandStates.put(groupKey, isCollapsedRef[0]);

                if (isCollapsedRef[0]) {
                    panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
                    panel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 24));
                } else {
                    panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
                    panel.setPreferredSize(null);
                }

                panel.revalidate();
                panel.repaint();
                if (panel.getParent() != null) {
                    panel.getParent().revalidate();
                    panel.getParent().repaint();
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                headerPanel.setCursor(new Cursor(Cursor.HAND_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                headerPanel.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
            }
        };

        headerPanel.addMouseListener(clickListener);

        JSeparator separator = new JSeparator();
        separator.setBackground(ColorScheme.LIGHT_GRAY_COLOR);
        separator.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));

        panel.add(headerPanel);
        panel.add(separator);
        panel.add(content);

        return panel;
    }

    private JPanel createGroupConfigPanel(GroupConfig groupConfig) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(2, 5, 2, 5));

        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        headerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        headerPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 70, 18));

        JLabel notificationsLabel = new JLabel("Notifications:");
        notificationsLabel.setFont(FontManager.getRunescapeSmallFont());
        notificationsLabel.setForeground(Color.WHITE);
        headerPanel.add(notificationsLabel);

        panel.add(headerPanel);
        panel.add(Box.createRigidArea(new Dimension(0, 2)));

        panel.add(createConfigRow("Drops", groupConfig.isSendDrops(), "Min: " + groupConfig.getMinimumDropValue()));
        panel.add(createConfigRow("CAs", groupConfig.isSendCAs(), "Min Tier: " + groupConfig.getMinimumCATier()));
        panel.add(createConfigRow("PBs", groupConfig.isSendPbs(), null));
        panel.add(createConfigRow("CLogs", groupConfig.isSendClogs(), null));
        panel.add(createConfigRow("Pets", groupConfig.isSendPets(), null));
        panel.add(createConfigRow("Quests", groupConfig.isSendQuests(), null));

        String levelText = groupConfig.isSendXP()
            ? "Levels (" + groupConfig.getMinimumLevel() + " min)"
            : "Levels";
        panel.add(createConfigRow(levelText, groupConfig.isSendXP(), null));
        return panel;
    }

    private JPanel createConfigRow(String label, boolean enabled, String details) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        row.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 70, 18));

        JLabel nameLabel = new JLabel(label + ":");
        nameLabel.setFont(FontManager.getRunescapeSmallFont());
        nameLabel.setForeground(Color.LIGHT_GRAY);
        row.add(nameLabel);

        JLabel statusLabel = new JLabel(enabled ? "On" : "Off");
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        statusLabel.setForeground(enabled ? Color.GREEN : Color.RED);
        row.add(statusLabel);

        if (details != null && !details.isEmpty()) {
            JLabel detailsLabel = new JLabel("(" + details + ")");
            detailsLabel.setFont(FontManager.getRunescapeSmallFont());
            detailsLabel.setForeground(Color.GRAY);
            row.add(detailsLabel);
        }

        return row;
    }

    public void updateStatusLabel() {
        if (!config.useApi()) {
            return;
        }

        long nowEpochSeconds = Instant.now().getEpochSecond();
        long lastEpochSeconds = api.lastCommunicationTime;
        long deltaSeconds = Math.max(0L, nowEpochSeconds - lastEpochSeconds);
        Duration duration = Duration.ofSeconds(deltaSeconds);

        String lastCommunicationTime = DurationAdapter.formatDuration(duration);
        String statusText;
        Color statusColor;

        if (duration.toSeconds() < 60) {
            statusText = "Last ping: " + lastCommunicationTime;
            statusColor = ColorScheme.PROGRESS_COMPLETE_COLOR;
        } else if (duration.toSeconds() < 300) {
            statusText = "Last ping: " + lastCommunicationTime;
            statusColor = Color.YELLOW;
        } else {
            statusText = "Disconnected (" + lastCommunicationTime + ")";
            statusColor = ColorScheme.PROGRESS_ERROR_COLOR;
        }

        if (mainPanel != null) {
            mainPanel.updateCommunicationStatus(statusText, statusColor);
        }
    }

    public void refreshSubmissions() {
        if (submissionManager != null) {
            List<ValidSubmission> validSubmissions = submissionManager.getValidSubmissions();
            if (validSubmissions != null) {
                updateSentSubmissions(validSubmissions);
            }
        }
    }

    public void updateSentSubmissions(List<ValidSubmission> validSubmissions) {
        if (submissionsPanel == null) {
            return;
        }

        Component[] components = submissionsPanel.getComponents();
        for (int i = components.length - 1; i >= 2; i--) {
            submissionsPanel.remove(i);
        }

        if (validSubmissions != null && validSubmissions.size() > 0) {
            List<ValidSubmission> pendingSubmissions = new ArrayList<>();
            List<ValidSubmission> processedSubmissions = new ArrayList<>();

            for (ValidSubmission submission : validSubmissions) {
                SubmissionStatus status = submission.getStatus();
                if (status == SubmissionStatus.PROCESSED) {
                    processedSubmissions.add(submission);
                } else {
                    pendingSubmissions.add(submission);
                }
            }

            for (ValidSubmission submission : pendingSubmissions) {
                JPanel submissionPanel = createSubmissionPanel(submission);
                submissionsPanel.add(submissionPanel);
                submissionsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
            }

            for (ValidSubmission submission : processedSubmissions) {
                JPanel compactPanel = createCompactSubmissionPanel(submission);
                submissionsPanel.add(compactPanel);
                submissionsPanel.add(Box.createRigidArea(new Dimension(0, 3)));
            }
        } else {
            JLabel noSubmissionsLabel = new JLabel("<html>Your achievements/drops will show up here as you receive them, if they qualify for notifications in any of your groups.<br />" +
                    "You can then re-try them if they appear to have not properly sent to your Discord server.</html>");
            noSubmissionsLabel.setFont(FontManager.getRunescapeSmallFont());
            noSubmissionsLabel.setForeground(Color.GRAY);
            noSubmissionsLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            submissionsPanel.add(noSubmissionsLabel);
        }

        submissionsPanel.revalidate();
        submissionsPanel.repaint();
        if (apiPanel != null) {
            apiPanel.revalidate();
            apiPanel.repaint();
        }
    }

    // ========== Submission Panel Rendering (moved from ValidSubmission) ==========

    private JPanel createSubmissionPanel(ValidSubmission submission) {
        JPanel card = new JPanel();
        card.setLayout(new BorderLayout(8, 0));
        card.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        card.setBorder(new EmptyBorder(8, 12, 8, 12));
        card.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 70));
        card.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 70));

        JPanel typePanel = createSubmissionTypeIndicator(submission);
        card.add(typePanel, BorderLayout.WEST);

        JPanel detailsPanel = createValidSubmissionDetails(submission);
        card.add(detailsPanel, BorderLayout.CENTER);

        JPanel actionPanel = createActionButtons(submission);
        card.add(actionPanel, BorderLayout.EAST);

        String tooltip = buildValidSubmissionTooltip(submission);
        card.setToolTipText(tooltip);

        card.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                card.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                card.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
            }
        });

        return card;
    }

    private JPanel createCompactSubmissionPanel(ValidSubmission submission) {
        JPanel card = new JPanel();
        card.setLayout(new BorderLayout(5, 0));
        card.setBackground(ColorScheme.DARK_GRAY_COLOR);
        card.setBorder(new EmptyBorder(4, 8, 4, 8));
        card.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));
        card.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));

        JLabel statusIndicator = new JLabel("\u2713");
        statusIndicator.setFont(FontManager.getRunescapeSmallFont());
        statusIndicator.setForeground(Color.GREEN);
        statusIndicator.setPreferredSize(new Dimension(15, 20));
        card.add(statusIndicator, BorderLayout.WEST);

        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        String submissionText = submission.getDisplayText();

        JLabel mainLabel = new JLabel();
        mainLabel.setFont(FontManager.getRunescapeSmallFont());
        mainLabel.setForeground(Color.LIGHT_GRAY);
        mainLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (submissionText.length() > 45) {
            mainLabel.setText(submissionText.substring(0, 42) + "...");
        } else {
            mainLabel.setText(submissionText);
        }

        JLabel statusLabel = new JLabel(submission.getStatusDescription());
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        statusLabel.setForeground(getStatusColor(submission.getStatus()));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        textPanel.add(mainLabel);
        textPanel.add(statusLabel);
        card.add(textPanel, BorderLayout.CENTER);

        JButton dismissButton = new JButton("\u00D7");
        dismissButton.setFont(FontManager.getRunescapeSmallFont());
        dismissButton.setPreferredSize(new Dimension(18, 18));
        dismissButton.setMaximumSize(new Dimension(18, 18));
        dismissButton.setMinimumSize(new Dimension(18, 18));
        dismissButton.setMargin(new java.awt.Insets(0, 0, 0, 0));
        dismissButton.setFocusPainted(false);
        dismissButton.setBorderPainted(true);
        dismissButton.setContentAreaFilled(true);
        dismissButton.setBackground(new Color(120, 40, 40));
        dismissButton.setForeground(Color.WHITE);
        dismissButton.setToolTipText("Remove from list");
        dismissButton.addActionListener(e -> {
            submissionManager.removeSubmission(submission);
            refreshSubmissions();
        });

        card.add(dismissButton, BorderLayout.EAST);

        card.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                card.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                card.setBackground(ColorScheme.DARK_GRAY_COLOR);
            }
        });

        return card;
    }

    private JPanel createSubmissionTypeIndicator(ValidSubmission submission) {
        JPanel typePanel = new JPanel();
        typePanel.setLayout(new BorderLayout());
        typePanel.setPreferredSize(new Dimension(40, 40));
        typePanel.setMaximumSize(new Dimension(40, 40));
        typePanel.setMinimumSize(new Dimension(40, 40));
        typePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        typePanel.setBorder(new javax.swing.border.StrokeBorder(new java.awt.BasicStroke(1), ColorScheme.BORDER_COLOR));

        JLabel typeLabel = new JLabel();
        typeLabel.setHorizontalAlignment(SwingConstants.CENTER);
        typeLabel.setVerticalAlignment(SwingConstants.CENTER);
        typeLabel.setFont(FontManager.getRunescapeSmallFont());

        // Use the type directly from the submission instead of parsing panel text
        String shortLabel = submission.getTypeShortLabel();
        typeLabel.setText(shortLabel);

        switch (shortLabel) {
            case "DROP":
                typeLabel.setForeground(Color.YELLOW);
                break;
            case "CLOG":
                typeLabel.setForeground(Color.ORANGE);
                break;
            case "PB":
                typeLabel.setForeground(Color.CYAN);
                break;
            case "CA":
                typeLabel.setForeground(Color.MAGENTA);
                break;
            case "PET":
                typeLabel.setForeground(new Color(0, 200, 100));
                break;
            case "LVL":
                typeLabel.setForeground(new Color(100, 200, 255));
                break;
            case "QST":
                typeLabel.setForeground(new Color(200, 150, 255));
                break;
            default:
                typeLabel.setForeground(Color.WHITE);
        }

        typePanel.add(typeLabel, BorderLayout.CENTER);
        return typePanel;
    }

    private JPanel createValidSubmissionDetails(ValidSubmission submission) {
        JPanel detailsPanel = new JPanel();
        detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
        detailsPanel.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);

        String submissionText = submission.getDisplayText();

        JLabel mainLabel = new JLabel();
        mainLabel.setFont(FontManager.getRunescapeBoldFont());
        mainLabel.setForeground(Color.WHITE);
        mainLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        if (submissionText.length() > 70) {
            mainLabel.setText(submissionText.substring(0, 67) + "...");
        } else {
            mainLabel.setText(submissionText);
        }

        SubmissionStatus status = submission.getStatus();
        JLabel statusLabel = new JLabel("<html>Status: " + submission.getStatusDescription() + "</html>");
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        statusLabel.setForeground(getStatusColor(status));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusLabel.setBackground(null);

        detailsPanel.add(mainLabel);
        detailsPanel.add(Box.createRigidArea(new Dimension(0, 2)));
        detailsPanel.add(statusLabel);
        detailsPanel.add(Box.createRigidArea(new Dimension(0, 1)));

        return detailsPanel;
    }

    private JPanel createActionButtons(ValidSubmission submission) {
        JPanel actionPanel = new JPanel();
        actionPanel.setLayout(new BoxLayout(actionPanel, BoxLayout.Y_AXIS));
        actionPanel.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        actionPanel.setPreferredSize(new Dimension(35, 50));
        actionPanel.setMaximumSize(new Dimension(35, 50));

        JButton retryButton = new JButton("\u21BB");
        retryButton.setFont(FontManager.getRunescapeSmallFont());
        retryButton.setPreferredSize(new Dimension(20, 20));
        retryButton.setMaximumSize(new Dimension(20, 20));
        retryButton.setMinimumSize(new Dimension(20, 20));
        retryButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        retryButton.setMargin(new java.awt.Insets(0, 0, 0, 0));
        retryButton.setFocusPainted(false);
        retryButton.setBorderPainted(true);
        retryButton.setContentAreaFilled(true);
        retryButton.setToolTipText("<html><div style='font-size: 10px;'>Retry sending this submission<br>to configured Discord servers</div></html>");
        retryButton.addActionListener(e -> {
            submissionManager.retrySubmission(submission);
            refreshSubmissions();
        });

        JButton dismissButton = new JButton("\u00D7");
        dismissButton.setFont(FontManager.getRunescapeSmallFont());
        dismissButton.setPreferredSize(new Dimension(20, 20));
        dismissButton.setMaximumSize(new Dimension(20, 20));
        dismissButton.setMinimumSize(new Dimension(20, 20));
        dismissButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        dismissButton.setMargin(new java.awt.Insets(0, 0, 0, 0));
        dismissButton.setFocusPainted(false);
        dismissButton.setBorderPainted(true);
        dismissButton.setContentAreaFilled(true);
        dismissButton.setBackground(new Color(180, 50, 50));
        dismissButton.setForeground(Color.WHITE);
        dismissButton.setToolTipText("<html><div style='font-size: 10px;'>Remove this submission<br>from the pending list</div></html>");
        dismissButton.addActionListener(e -> {
            submissionManager.removeSubmission(submission);
            refreshSubmissions();
        });

        actionPanel.add(Box.createVerticalGlue());
        actionPanel.add(retryButton);
        actionPanel.add(Box.createRigidArea(new Dimension(0, 8)));
        actionPanel.add(dismissButton);
        actionPanel.add(Box.createVerticalGlue());

        return actionPanel;
    }

    private String buildValidSubmissionTooltip(ValidSubmission submission) {
        StringBuilder tooltip = new StringBuilder();
        tooltip.append("<html><div style='font-size: 10px; line-height: 1.2; padding: 3px;'>");

        String submissionType = submission.getTypeShortLabel();
        tooltip.append("<div style='color: #FFFF00; font-weight: bold; margin-bottom: 3px;'>")
               .append(submissionType).append(" SUBMISSION</div>");

        String submissionText = submission.getDisplayText();
        tooltip.append("<div style='color: #FFFFFF; margin-bottom: 3px;'>")
               .append(submissionText).append("</div>");

        SubmissionStatus status = submission.getStatus();
        String statusColor = status != null ? status.getHexColor() : "#808080";
        tooltip.append("<div style='color: ").append(statusColor).append("; margin-bottom: 3px;'>")
               .append("Status: ").append(submission.getStatusDescription()).append("</div>");

        if (submission.getGroupIds() != null && submission.getGroupIds().length > 0) {
            tooltip.append("<div style='color: #C0C0C0; margin-bottom: 3px;'>")
                   .append("Configured for ").append(submission.getGroupIds().length).append(" group(s)</div>");
        }

        tooltip.append("<div style='color: #A0A0A0; font-style: italic; margin-top: 5px;'>")
               .append("Click Retry to resend or \u00D7 to dismiss</div>");

        tooltip.append("</div></html>");
        return tooltip.toString();
    }

    private Color getStatusColor(SubmissionStatus status) {
        if (status == null) return Color.GRAY;
        return status.getAwtColor();
    }

    public void refreshStatistics() {
        if (statisticsPanel == null) {
            return;
        }

        Component[] components = statisticsPanel.getComponents();
        for (int i = components.length - 1; i >= 2; i--) {
            statisticsPanel.remove(i);
        }

        JPanel statsContainer = createStreamlinedStatsDisplay();
        statisticsPanel.add(statsContainer);

        statisticsPanel.revalidate();
        statisticsPanel.repaint();
    }

    public void refresh() {
        updateStatusLabel();
        refreshStatistics();
        refreshSubmissions();
        refreshGroupConfigs();
    }

    public void cleanup() {
        if (statusUpdateTimer != null && statusUpdateTimer.isRunning()) {
            statusUpdateTimer.stop();
        }
    }

    public void refreshGroupConfigs() {
        if (groupsContainerPanel == null || groupsScrollPane == null) {
            return;
        }

        int scrollPosition = groupsScrollPane.getVerticalScrollBar().getValue();

        buildGroupConfigPanels(groupsContainerPanel);
        groupsContainerPanel.revalidate();
        groupsContainerPanel.repaint();

        SwingUtilities.invokeLater(() -> {
            groupsScrollPane.getVerticalScrollBar().setValue(scrollPosition);
        });

        if (groupConfigPanel != null) {
            groupConfigPanel.revalidate();
            groupConfigPanel.repaint();
        }
    }
}
