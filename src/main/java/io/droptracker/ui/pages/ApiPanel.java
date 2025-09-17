package io.droptracker.ui.pages;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.api.GroupConfig;
import io.droptracker.models.submissions.ValidSubmission;
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

    private JPanel apiPanel;
    private JPanel submissionsPanel;
    private JPanel statisticsPanel;
    private Timer statusUpdateTimer;
    private JPanel groupConfigPanel;
    private JPanel groupsContainerPanel;
    private DropTrackerPanel mainPanel;
    private JScrollPane groupsScrollPane;
    private final Map<String, Boolean> groupExpandStates = new HashMap<>(); // Track expand/collapse state by group ID


    public ApiPanel(DropTrackerConfig config, DropTrackerApi api, SubmissionManager submissionManager, DropTrackerPanel mainPanel) {
        this.config = config;
        this.api = api;
        this.submissionManager = submissionManager;
        this.mainPanel = mainPanel;
    }

    public JPanel create() {
        apiPanel = new JPanel();
        apiPanel.setLayout(new BoxLayout(apiPanel, BoxLayout.Y_AXIS));
        apiPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        apiPanel.setBorder(new EmptyBorder(0, 0, 0, 0));

        // Communication status is now handled in the main panel header

        JPanel configPanel = initializeConfigPanel();


        // Initialize statistics panel
        JPanel statisticsPanel = initializeStatisticsPanel();
        
        // Initialize submissions panel
        initializeSubmissionsPanel();

        // Add all components to the API panel
        apiPanel.add(statisticsPanel);
        apiPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        apiPanel.add(submissionsPanel);
        apiPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        // Add configs beneath the submissions panel
        apiPanel.add(configPanel);
        apiPanel.add(Box.createVerticalGlue());

        // Initial update
        updateStatusLabel();
        refreshStatistics();
        refreshSubmissions();
        refreshGroupConfigs();

        // Start timer to update status every 10 seconds
        if (config.pollUpdates()) {
            statusUpdateTimer = new Timer(10000, e -> {
                updateStatusLabel();
                refreshStatistics();
                refreshGroupConfigs();
                // Poll pending submissions for processed status when API is enabled
                if (config.useApi()) {
                    // Only check pending statuses if there are submissions that need checking
                    if (hasPendingOrFailedSubmissions()) {
                        submissionManager.checkPendingStatuses();
                        // After polling, refresh the submissions list
                        refreshSubmissions();
                    }
                }
            });
            statusUpdateTimer.start();
        }

        JPanel wrapperPanel = new JPanel(new BorderLayout());
        wrapperPanel.add(apiPanel, BorderLayout.CENTER);
        return wrapperPanel;
    }

    private void initializeSubmissionsPanel() {
        submissionsPanel = new JPanel();
        submissionsPanel.setLayout(new BoxLayout(submissionsPanel, BoxLayout.Y_AXIS));
        submissionsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        submissionsPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        submissionsPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 200));
        submissionsPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 300));
        submissionsPanel.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 50));

        // Create title for submissions section
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

        // Create title for statistics section
        JLabel statisticsTitle = new JLabel("Session Statistics");
        statisticsTitle.setFont(FontManager.getRunescapeBoldFont());
        statisticsTitle.setForeground(Color.WHITE);
        statisticsTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        statisticsPanel.add(statisticsTitle);
        statisticsPanel.add(Box.createRigidArea(new Dimension(0, 5)));

        // Create streamlined statistics display
        JPanel statsContainer = createStreamlinedStatsDisplay();
        statisticsPanel.add(statsContainer);

        return statisticsPanel;
    }

    private JPanel createStreamlinedStatsDisplay() {
        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        container.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 50));
        
        // Create two rows of statistics in a compact format
        JPanel row1 = createStatsRow(
            "Notified count:", String.valueOf(submissionManager.totalSubmissions), Color.CYAN,
            "Sent:", String.valueOf(submissionManager.notificationsSent), Color.GREEN
        );
        row1.setToolTipText("Total number of submissions you have sent, and number of those which have created Discord notifications");
        
        JPanel row2 = createStatsRow(
            "# Failed:", String.valueOf(submissionManager.failedSubmissions), Color.RED,
            "GP value:", formatValue(submissionManager.totalValue), Color.YELLOW
        );
        
        // Get retry stats for status indicator
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
        
        // First stat
        JPanel stat1 = createCompactStat(label1, value1, color1);
        row.add(stat1);
        
        // Separator
        JLabel separator = new JLabel("|");
        separator.setForeground(Color.GRAY);
        separator.setFont(FontManager.getRunescapeSmallFont());
        row.add(separator);
        
        // Second stat
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
        
        // Simple status based on last communication time
        JLabel healthIcon = new JLabel("●");
        boolean healthy = true; // consider healthy here; header shows real timing
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

    
    private String formatValue(Long value) {
        if (value == null || value == 0) return "0";
        
        if (value >= 1_000_000) {
            return String.format("%.1fM", value / 1_000_000.0);
        } else if (value >= 1_000) {
            return String.format("%.1fK", value / 1_000.0);
        } else {
            return String.valueOf(value);
        }
    }

    private JPanel initializeConfigPanel() {
        // Create a wrapper panel to contain the group configs
        JPanel wrapperPanel = new JPanel();
        wrapperPanel.setLayout(new BoxLayout(wrapperPanel, BoxLayout.Y_AXIS));
        wrapperPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        wrapperPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 400)); // Limit wrapper size

        // Status label removed - now shown in main panel header

        // Create the content panel that will hold all group configs
        JPanel groupsContainer = new JPanel();
        groupsContainer.setLayout(new BoxLayout(groupsContainer, BoxLayout.Y_AXIS));
        groupsContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Build the group config panels
        buildGroupConfigPanels(groupsContainer);

        // Create scroll pane for the groups
        groupsScrollPane = new JScrollPane(groupsContainer);
        groupsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        groupsScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        groupsScrollPane.setBorder(null);
        groupsScrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupsScrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupsScrollPane.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 150)); // Limit scroll pane height
        groupsScrollPane.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 150));

        // Add the scroll pane to wrapper
        wrapperPanel.add(groupsScrollPane);

        // Store reference to groups container for refresh
        this.groupsContainerPanel = groupsContainer;

        // Create the main collapsible panel - use custom method for consistent behavior
        groupConfigPanel = createMainCollapsiblePanel("Group Configurations", wrapperPanel);

        return groupConfigPanel;
    }

    /**
     * Creates the main collapsible panel with consistent sizing behavior
     */
    private JPanel createMainCollapsiblePanel(String title, JPanel content) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10)); // Standard padding for main panel

        // Set initial size constraints
        panel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 200));
        panel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 200));

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Create title
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(FontManager.getRunescapeBoldFont());
        titleLabel.setForeground(Color.WHITE);

        // Get the static icons from PanelElements using getter methods
        ImageIcon expandedIcon = PanelElements.getExpandedIcon();
        ImageIcon collapsedIcon = PanelElements.getCollapsedIcon();

        final ImageIcon finalExpandedIcon = expandedIcon;
        final ImageIcon finalCollapsedIcon = collapsedIcon;
        final JLabel toggleIcon = new JLabel(expandedIcon);

        headerPanel.add(titleLabel, BorderLayout.WEST);
        headerPanel.add(toggleIcon, BorderLayout.EAST);

        // Store initial expanded state
        final boolean[] isCollapsed = {false};

        // Add click listener for collapsing/expanding
        headerPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                isCollapsed[0] = !isCollapsed[0];
                toggleIcon.setIcon(isCollapsed[0] ? finalCollapsedIcon : finalExpandedIcon);
                content.setVisible(!isCollapsed[0]);

                // Maintain consistent sizing
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

        // Add separator
        JSeparator separator = new JSeparator();
        separator.setBackground(ColorScheme.LIGHT_GRAY_COLOR);
        separator.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        panel.add(headerPanel);
        panel.add(separator);
        panel.add(content);

        return panel;
    }

    /**
     * Builds individual collapsible panels for each group configuration
     */
    private void buildGroupConfigPanels(JPanel parentPanel) {
        parentPanel.removeAll();

        List<GroupConfig> groupConfigs = api.getGroupConfigs();
        if (groupConfigs != null && groupConfigs.size() > 0) {
            for (GroupConfig groupConfig : groupConfigs) {
                // Create content for this group
                JPanel groupContentPanel = createGroupConfigPanel(groupConfig);

                // Create collapsible panel for this group with reduced padding
                String groupTitle = groupConfig.getGroupName() + " (ID: " + groupConfig.getGroupId() + ")";
                String groupKey = String.valueOf(groupConfig.getGroupId());

                // Check if we have a saved state for this group, default to collapsed (true)
                boolean isCollapsed = groupExpandStates.getOrDefault(groupKey, true);

                JPanel collapsibleGroup = createCompactCollapsiblePanel(groupTitle, groupContentPanel, groupKey, isCollapsed);

                parentPanel.add(collapsibleGroup);
                parentPanel.add(Box.createRigidArea(new Dimension(0, 2))); // Reduced space between groups
            }
        } else {
            // Show "no configs" message with minimal height
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

    /**
     * Creates a compact collapsible panel with reduced padding
     */
    private JPanel createCompactCollapsiblePanel(String title, JPanel content, String groupKey, boolean isCollapsed) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(2, 5, 2, 5)); // Further reduced padding

        // Set maximum size when expanded
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130)); // Limit total height

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        headerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18)); // Reduced header height
        headerPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 18));

        // Create title
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(FontManager.getRunescapeSmallFont()); // Use small font for group titles
        titleLabel.setForeground(Color.WHITE);

        // Get the static icons from PanelElements using getter methods
        ImageIcon expandedIcon = PanelElements.getExpandedIcon();
        ImageIcon collapsedIcon = PanelElements.getCollapsedIcon();

        // Toggle icon - set initial state based on isCollapsed parameter
        final ImageIcon finalExpandedIcon = expandedIcon;
        final ImageIcon finalCollapsedIcon = collapsedIcon;
        final JLabel toggleIcon = new JLabel(isCollapsed ? collapsedIcon : expandedIcon);

        headerPanel.add(titleLabel, BorderLayout.WEST);
        headerPanel.add(toggleIcon, BorderLayout.EAST);

        // Set initial visibility based on collapsed state
        content.setVisible(!isCollapsed);

        // Set initial panel size based on collapsed state
        if (isCollapsed) {
            panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
            panel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 24));
        }

        // Create a final reference to the content for use in the listener
        final boolean[] isCollapsedRef = {isCollapsed};

        // Add click listener for collapsing/expanding
        MouseAdapter clickListener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                isCollapsedRef[0] = !isCollapsedRef[0];
                toggleIcon.setIcon(isCollapsedRef[0] ? finalCollapsedIcon : finalExpandedIcon);
                content.setVisible(!isCollapsedRef[0]);

                // Save the state
                groupExpandStates.put(groupKey, isCollapsedRef[0]);

                // Update panel size
                if (isCollapsedRef[0]) {
                    panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24)); // Just header + borders
                    panel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 24));
                } else {
                    panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130)); // Limited height when expanded
                    panel.setPreferredSize(null);
                }

                // Force the parent to re-layout
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

        // Add separator
        JSeparator separator = new JSeparator();
        separator.setBackground(ColorScheme.LIGHT_GRAY_COLOR);
        separator.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));

        panel.add(headerPanel);
        panel.add(separator);
        panel.add(content);

        return panel;
    }

    /**
     * Creates the configuration panel for a single group
     */
    private JPanel createGroupConfigPanel(GroupConfig groupConfig) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(2, 5, 2, 5));

        // Notifications header
        JLabel notificationsLabel = new JLabel("Notifications:");
        notificationsLabel.setFont(FontManager.getRunescapeSmallFont());
        notificationsLabel.setForeground(Color.WHITE);
        panel.add(notificationsLabel);
        panel.add(Box.createRigidArea(new Dimension(0, 2)));

        // Drops configuration
        panel.add(createConfigRow("Drops",
                groupConfig.isSendDrops(),
                "Min: " + groupConfig.getMinimumDropValue()));

        // CAs configuration
        panel.add(createConfigRow("CAs",
                groupConfig.isSendCAs(),
                "Min Tier: " + groupConfig.getMinimumCATier()));

        // PBs configuration
        panel.add(createConfigRow("PBs",
                groupConfig.isSendPbs(),
                null));

        // CLogs configuration
        panel.add(createConfigRow("CLogs",
                groupConfig.isSendClogs(),
                null));
        
        // Pets config
        panel.add(createConfigRow("Pets",
                groupConfig.isSendPets(),
                null));
        
        // Quests config
        panel.add(createConfigRow("Quests",
                groupConfig.isSendQuests(),
                null));
        
        // Level config
        String levelText = "";
        if (groupConfig.isSendXP()) {
            levelText = "Levels (" + groupConfig.getMinimumLevel() + " min)";
        } else {
            levelText = "Levels";
        }
        panel.add(createConfigRow(levelText,
                groupConfig.isSendXP(),
                null));
        return panel;
    }

    /**
     * Creates a configuration row with label, status, and optional details
     */
    private JPanel createConfigRow(String label, boolean enabled, String details) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18)); // Reduced from 20
        row.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 70, 18));

        // Label
        JLabel nameLabel = new JLabel(label + ":");
        nameLabel.setFont(FontManager.getRunescapeSmallFont());
        nameLabel.setForeground(Color.LIGHT_GRAY);
        row.add(nameLabel);

        // Status
        JLabel statusLabel = new JLabel(enabled ? "On" : "Off");
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        statusLabel.setForeground(enabled ? Color.GREEN : Color.RED);
        row.add(statusLabel);

        // Optional details
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
            return; // No need to update communication status when API is disabled
        }
        
        long nowEpochSeconds = Instant.now().getEpochSecond();
        long lastEpochSeconds = api.lastCommunicationTime;
        long deltaSeconds = Math.max(0L, nowEpochSeconds - lastEpochSeconds);
        Duration duration = Duration.ofSeconds(deltaSeconds);

        String lastCommunicationTime = DurationAdapter.formatDuration(duration);
        String statusText;
        Color statusColor;
        
        // More conservative disconnect detection
        if (duration.toSeconds() < 60) {
            // Connected - recent communication
            statusText = "Last ping: " + lastCommunicationTime;
            statusColor = ColorScheme.PROGRESS_COMPLETE_COLOR;
        } else if (duration.toSeconds() < 300) { // 5 minutes
            // Warning state - might be having issues
            statusText = "Last ping: " + lastCommunicationTime;
            statusColor = Color.YELLOW;
        } else {
            // Likely disconnected after 5+ minutes of no communication
            statusText = "Disconnected (" + lastCommunicationTime + ")";
            statusColor = ColorScheme.PROGRESS_ERROR_COLOR;
        }

        // Update the header communication status
        if (mainPanel != null) {
            mainPanel.updateCommunicationStatus(statusText, statusColor);
        }
    }

    /**
     * Refreshes the submissions display with current data from SubmissionManager
     */
    public void refreshSubmissions() {
        if (submissionManager != null) {
            List<ValidSubmission> validSubmissions = submissionManager.getValidSubmissions();
            if (validSubmissions != null) {
                updateSentSubmissions(validSubmissions);
            }
        }
    }

    /**
     * Updates the submissions panel with the provided list of ValidSubmissions
     *
     * @param validSubmissions List of submissions to display
     */
    public void updateSentSubmissions(List<ValidSubmission> validSubmissions) {
        if (submissionsPanel == null) {
            return;
        }

        // Clear existing submission entries (keep title)
        Component[] components = submissionsPanel.getComponents();
        for (int i = components.length - 1; i >= 2; i--) { // Keep title and spacer
            submissionsPanel.remove(i);
        }

        if (validSubmissions != null && validSubmissions.size() > 0) {
            // Separate submissions by status - pending submissions first, then processed
            List<ValidSubmission> pendingSubmissions = new ArrayList<>();
            List<ValidSubmission> processedSubmissions = new ArrayList<>();
            
            for (ValidSubmission submission : validSubmissions) {
                String status = submission.getStatus();
                if ("processed".equals(status)) {
                    processedSubmissions.add(submission);
                } else {
                    // Treat everything else (pending, sent, queued, retrying, failed, etc.) as in-progress list
                    pendingSubmissions.add(submission);
                }
            }
            
            // Add pending submissions with full action panels
            for (ValidSubmission submission : pendingSubmissions) {
                JPanel submissionPanel = createSubmissionPanel(submission);
                submissionsPanel.add(submissionPanel);
                submissionsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
            }
            
            // Add processed submissions with compact display
            for (ValidSubmission submission : processedSubmissions) {
                JPanel compactPanel = createCompactSubmissionPanel(submission);
                submissionsPanel.add(compactPanel);
                submissionsPanel.add(Box.createRigidArea(new Dimension(0, 3)));
            }
        } else {
            // Show "no submissions" message
            JLabel noSubmissionsLabel = new JLabel("<html>Your achievements/drops will show up here as you receive them, if they qualify for notifications in any of your groups.<br />" +
                    "You can then re-try them if they appear to have not properly sent to your Discord server.</html>");
            noSubmissionsLabel.setFont(FontManager.getRunescapeSmallFont());
            noSubmissionsLabel.setForeground(Color.GRAY);
            noSubmissionsLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            submissionsPanel.add(noSubmissionsLabel);
        }

        // Refresh the UI
        submissionsPanel.revalidate();
        submissionsPanel.repaint();
        if (apiPanel != null) {
            apiPanel.revalidate();
            apiPanel.repaint();
        }
    }

    /**
     * Creates an enhanced panel for displaying a single ValidSubmission with retry/dismiss buttons
     *
     * @param submission The submission to display
     * @return A JPanel containing the submission display
     */
    private JPanel createSubmissionPanel(ValidSubmission submission) {
        JPanel card = new JPanel();
        card.setLayout(new BorderLayout(8, 0));
        card.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        card.setBorder(new EmptyBorder(8, 12, 8, 12));
        card.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 70));
        card.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 70));

        // Left side - Submission type indicator with color coding
        JPanel typePanel = createSubmissionTypeIndicator(submission);
        card.add(typePanel, BorderLayout.WEST);

        // Center - Enhanced submission details
        JPanel detailsPanel = createValidSubmissionDetails(submission);
        card.add(detailsPanel, BorderLayout.CENTER);

        // Right side - Action buttons
        JPanel actionPanel = createActionButtons(submission);
        card.add(actionPanel, BorderLayout.EAST);

        // Add enhanced tooltip
        String tooltip = buildValidSubmissionTooltip(submission);
        card.setToolTipText(tooltip);

        // Add hover effect
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
    
    /**
     * Creates a compact panel for processed submissions with just dismiss option
     */
    private JPanel createCompactSubmissionPanel(ValidSubmission submission) {
        JPanel card = new JPanel();
        card.setLayout(new BorderLayout(5, 0));
        card.setBackground(ColorScheme.DARK_GRAY_COLOR);
        card.setBorder(new EmptyBorder(4, 8, 4, 8));
        card.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));
        card.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));

        // Left side - Small status indicator
        JLabel statusIndicator = new JLabel("✓");
        statusIndicator.setFont(FontManager.getRunescapeSmallFont());
        statusIndicator.setForeground(Color.GREEN);
        statusIndicator.setPreferredSize(new Dimension(15, 20));
        card.add(statusIndicator, BorderLayout.WEST);

        // Center - Compact submission text
        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        // Get submission content but make it more compact
        JPanel originalPanel = submission.toSubmissionPanel();
        String submissionText = extractTextFromPanel(originalPanel);
        
        // Fallback: if text extraction failed or returned empty, create text from submission fields
        if (submissionText == null || submissionText.trim().isEmpty()) {
            submissionText = createSubmissionTextFromFields(submission);
        }
        
        JLabel mainLabel = new JLabel();
        mainLabel.setFont(FontManager.getRunescapeSmallFont());
        mainLabel.setForeground(Color.LIGHT_GRAY);
        mainLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Truncate for compact display
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

        // Right side - Only dismiss button
        JButton dismissButton = new JButton("×");
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

        // Add subtle hover effect
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
        typePanel.setPreferredSize(new Dimension(40, 40)); // 1:1 square ratio
        typePanel.setMaximumSize(new Dimension(40, 40));
        typePanel.setMinimumSize(new Dimension(40, 40));
        typePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        typePanel.setBorder(new javax.swing.border.StrokeBorder(new java.awt.BasicStroke(1), ColorScheme.BORDER_COLOR));

        JLabel typeLabel = new JLabel();
        typeLabel.setHorizontalAlignment(SwingConstants.CENTER);
        typeLabel.setVerticalAlignment(SwingConstants.CENTER);
        typeLabel.setFont(FontManager.getRunescapeSmallFont());

        // Determine submission type and set appropriate styling
        String submissionType = getSubmissionTypeFromValidSubmission(submission);
        switch (submissionType.toLowerCase()) {
            case "drop":
                typeLabel.setText("DROP");
                typeLabel.setForeground(Color.YELLOW);
                break;
            case "clog":
                typeLabel.setText("CLOG");
                typeLabel.setForeground(Color.ORANGE);
                break;
            case "pb":
                typeLabel.setText("PB");
                typeLabel.setForeground(Color.CYAN);
                break;
            case "ca":
                typeLabel.setText("CA");
                typeLabel.setForeground(Color.MAGENTA);
                break;
            default:
                typeLabel.setText("SUB");
                typeLabel.setForeground(Color.WHITE);
        }

        typePanel.add(typeLabel, BorderLayout.CENTER);
        return typePanel;
    }

    private JPanel createValidSubmissionDetails(ValidSubmission submission) {
        JPanel detailsPanel = new JPanel();
        detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
        detailsPanel.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);

        // Get submission content from the original panel but format it better
        JPanel originalPanel = submission.toSubmissionPanel();
        String submissionText = extractTextFromPanel(originalPanel);
        
        // Fallback: if text extraction failed or returned empty, create text from submission fields
        if (submissionText == null || submissionText.trim().isEmpty()) {
            submissionText = createSubmissionTextFromFields(submission);
        }

        // Main submission text - allow more space for longer text
        JLabel mainLabel = new JLabel();
        mainLabel.setFont(FontManager.getRunescapeBoldFont());
        mainLabel.setForeground(Color.WHITE);
        mainLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Truncate long text for better display but allow more characters
        if (submissionText.length() > 70) {
            mainLabel.setText(submissionText.substring(0, 67) + "...");
        } else {
            mainLabel.setText(submissionText);
        }

        // Status information - use HTML to prevent cutoff
        JLabel statusLabel = new JLabel("<html>Status: " + submission.getStatus() + "</html>");
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        statusLabel.setForeground(getStatusColor(submission.getStatus()));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusLabel.setBackground(null);

        // Group information - use HTML to prevent cutoff
        

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
        actionPanel.setPreferredSize(new Dimension(35, 50)); // Reduced width for small icon buttons
        actionPanel.setMaximumSize(new Dimension(35, 50));

        // Retry button - small icon button
        JButton retryButton = new JButton("↻");
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
            refreshSubmissions(); // Refresh to show updated status
        });

        // Dismiss button - small red X button
        JButton dismissButton = new JButton("×");
        dismissButton.setFont(FontManager.getRunescapeSmallFont());
        dismissButton.setPreferredSize(new Dimension(20, 20));
        dismissButton.setMaximumSize(new Dimension(20, 20));
        dismissButton.setMinimumSize(new Dimension(20, 20));
        dismissButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        dismissButton.setMargin(new java.awt.Insets(0, 0, 0, 0));
        dismissButton.setFocusPainted(false);
        dismissButton.setBorderPainted(true);
        dismissButton.setContentAreaFilled(true);
        dismissButton.setBackground(new Color(180, 50, 50)); // Red background
        dismissButton.setForeground(Color.WHITE);
        dismissButton.setToolTipText("<html><div style='font-size: 10px;'>Remove this submission<br>from the pending list</div></html>");
        dismissButton.addActionListener(e -> {
            submissionManager.removeSubmission(submission);
            refreshSubmissions(); // Refresh to remove from display
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

        // Submission type header
        String submissionType = getSubmissionTypeFromValidSubmission(submission).toUpperCase();
        tooltip.append("<div style='color: #FFFF00; font-weight: bold; margin-bottom: 3px;'>")
               .append(submissionType).append(" SUBMISSION</div>");

        // Get full submission text
        JPanel originalPanel = submission.toSubmissionPanel();
        String submissionText = extractTextFromPanel(originalPanel);
        
        // Fallback: if text extraction failed or returned empty, create text from submission fields
        if (submissionText == null || submissionText.trim().isEmpty()) {
            submissionText = createSubmissionTextFromFields(submission);
        }
        
        tooltip.append("<div style='color: #FFFFFF; margin-bottom: 3px;'>")
               .append(submissionText.replaceAll("<.*?>", "")).append("</div>");

        // Status with color coding
        String status = submission.getStatus();
        String statusColor = getStatusHexColor(status);
        tooltip.append("<div style='color: ").append(statusColor).append("; margin-bottom: 3px;'>")
               .append("Status: ").append(status).append("</div>");

        // Group information
        if (submission.getGroupIds() != null && submission.getGroupIds().length > 0) {
            tooltip.append("<div style='color: #C0C0C0; margin-bottom: 3px;'>")
                   .append("Configured for ").append(submission.getGroupIds().length).append(" group(s)</div>");
        }

        tooltip.append("<div style='color: #A0A0A0; font-style: italic; margin-top: 5px;'>")
               .append("Click Retry to resend or × to dismiss</div>");

        tooltip.append("</div></html>");
        return tooltip.toString();
    }

    private String getSubmissionTypeFromValidSubmission(ValidSubmission submission) {
        // Try to determine type from the submission panel content
        JPanel panel = submission.toSubmissionPanel();
        String text = extractTextFromPanel(panel).toLowerCase();
        
        if (text.contains("drop") || text.contains("received")) {
            return "drop";
        } else if (text.contains("collection") || text.contains("clog")) {
            return "clog";
        } else if (text.contains("personal best") || text.contains("pb") || text.contains("time")) {
            return "pb";
        } else if (text.contains("combat achievement") || text.contains("ca")) {
            return "ca";
        }
        return "unknown";
    }

    private String extractTextFromPanel(JPanel panel) {
        StringBuilder text = new StringBuilder();
        extractTextRecursive(panel, text);
        return text.toString().trim();
    }

    private void extractTextRecursive(java.awt.Container container, StringBuilder text) {
        for (java.awt.Component component : container.getComponents()) {
            if (component instanceof JLabel) {
                String labelText = ((JLabel) component).getText();
                if (labelText != null && !labelText.isEmpty()) {
                    text.append(labelText).append(" ");
                }
            } else if (component instanceof JTextArea) {
                String textAreaText = ((JTextArea) component).getText();
                if (textAreaText != null && !textAreaText.isEmpty()) {
                    text.append(textAreaText).append(" ");
                }
            } else if (component instanceof java.awt.Container) {
                extractTextRecursive((java.awt.Container) component, text);
            }
        }
    }

    /**
     * Creates submission text directly from ValidSubmission fields as a fallback
     * when panel-based text extraction fails
     */
    private String createSubmissionTextFromFields(ValidSubmission submission) {
        if (submission == null) {
            return "Unknown submission";
        }
        
        String itemName = submission.getItemName();
        if (itemName == null || itemName.trim().isEmpty()) {
            itemName = submission.getDescription();
        }
        if (itemName == null || itemName.trim().isEmpty()) {
            itemName = "Unknown item";
        }
        
        String typeText = "Submission";
        if (submission.getType() != null) {
            switch (submission.getType()) {
                case DROP:
                    typeText = "Drop";
                    break;
                case KILL_TIME:
                    typeText = "Personal Best";
                    break;
                case COLLECTION_LOG:
                    typeText = "Collection Log";
                    break;
                case COMBAT_ACHIEVEMENT:
                    typeText = "Combat Achievement";
                    break;
                case LEVEL_UP:
                    typeText = "Level Up";
                    break;
                case QUEST_COMPLETION:
                    typeText = "Quest Completion";
                    break;
                case EXPERIENCE:
                    typeText = "Experience";
                    break;
                case EXPERIENCE_MILESTONE:
                    typeText = "Experience Milestone";
                    break;
                case PET:
                    typeText = "Pet";
                    break;
                default:
                    typeText = "Submission";
                    break;
            }
        }
        
        return typeText + ": " + itemName;
    }

    private String getStatusHexColor(String status) {
        if (status == null) return "#808080";
        
        switch (status.toLowerCase()) {
            case "success":
            case "processed":
            case "sent":
                return "#00FF00";
            case "pending":
            case "retrying":
            case "queued":
                return "#FFFF00";
            case "failed":
            case "error":
                return "#FF0000";
            default:
                return "#808080";
        }
    }

    /**
     * Gets the appropriate color for a submission status
     *
     * @param status The submission status
     * @return Color for the status
     */
    private Color getStatusColor(String status) {
        if (status == null) return Color.GRAY;

        switch (status.toLowerCase()) {
            case "success":
            case "processed":
            case "sent":
                return Color.GREEN;
            case "pending":
            case "retrying":
            case "queued":
                return Color.YELLOW;
            case "failed":
            case "error":
                return Color.RED;
            default:
                return Color.GRAY;
        }
    }

    /**
     * Refreshes the statistics panel with current data from SubmissionManager
     */
    public void refreshStatistics() {
        if (statisticsPanel == null) {
            return;
        }
        
        // Remove existing components except title
        Component[] components = statisticsPanel.getComponents();
        for (int i = components.length - 1; i >= 2; i--) { // Keep title and spacer
            statisticsPanel.remove(i);
        }
        
        // Recreate the streamlined statistics display
        JPanel statsContainer = createStreamlinedStatsDisplay();
        statisticsPanel.add(statsContainer);
        
        // Refresh the UI
        statisticsPanel.revalidate();
        statisticsPanel.repaint();
    }

    /**
     * Public method to refresh the entire API panel
     * Can be called from outside to update the display
     */
    public void refresh() {
        updateStatusLabel();
        refreshStatistics();
        refreshSubmissions();
        refreshGroupConfigs();
    }

    /**
     * Cleanup method to stop the timer when the panel is no longer needed
     */
    public void cleanup() {
        if (statusUpdateTimer != null && statusUpdateTimer.isRunning()) {
            statusUpdateTimer.stop();
        }
    }

    /**
     * Check if there are any submissions that need status checking
     */
    private boolean hasPendingOrFailedSubmissions() {
        if (submissionManager == null) {
            return false;
        }
        
        List<ValidSubmission> validSubmissions = submissionManager.getValidSubmissions();
        if (validSubmissions == null || validSubmissions.isEmpty()) {
            return false;
        }
        
        // Check if any submissions are in states that need polling
        for (ValidSubmission submission : validSubmissions) {
            String status = submission.getStatus();
            if (status != null && (
                "pending".equalsIgnoreCase(status) || 
                "sent".equalsIgnoreCase(status) || 
                "retrying".equalsIgnoreCase(status) ||
                "queued".equalsIgnoreCase(status)
            )) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Refreshes the group configurations display
     */
    public void refreshGroupConfigs() {
        if (groupsContainerPanel == null || groupsScrollPane == null) {
            return;
        }

        // Save current scroll position
        int scrollPosition = groupsScrollPane.getVerticalScrollBar().getValue();

        // Rebuild all group config panels
        buildGroupConfigPanels(groupsContainerPanel);
        groupsContainerPanel.revalidate();
        groupsContainerPanel.repaint();

        // Restore scroll position after a brief delay to allow layout
        SwingUtilities.invokeLater(() -> {
            groupsScrollPane.getVerticalScrollBar().setValue(scrollPosition);
        });

        // Also update the parent panel
        if (groupConfigPanel != null) {
            groupConfigPanel.revalidate();
            groupConfigPanel.repaint();
        }
    }
}
