package io.droptracker.ui.pages;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.api.GroupConfig;
import io.droptracker.models.submissions.SubmissionStatus;
import io.droptracker.models.submissions.ValidSubmission;
import io.droptracker.service.SubmissionManager;
import io.droptracker.ui.DropTrackerPanel;
import io.droptracker.ui.DropTrackerTheme;
import io.droptracker.ui.components.PanelElements;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Activity" tab: this session's submission feed (promoted from the old buried
 * "API" tab), a compact session stats row, and the per-group configuration summary.
 */
public class ActivityPanel {
    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final SubmissionManager submissionManager;
    private final DropTrackerPanel mainPanel;

    private JPanel activityRoot;
    private JPanel statsCard;
    private JPanel feedCard;
    private JPanel feedListPanel;
    private Timer statusUpdateTimer;

    private JPanel groupConfigPanel;
    private JPanel groupsContainerPanel;
    private JScrollPane groupsScrollPane;
    private final Map<String, Boolean> groupExpandStates = new HashMap<>();

    public ActivityPanel(
        DropTrackerConfig config,
        DropTrackerApi api,
        SubmissionManager submissionManager,
        DropTrackerPanel mainPanel
    ) {
        this.config = config;
        this.api = api;
        this.submissionManager = submissionManager;
        this.mainPanel = mainPanel;
    }

    public JPanel create() {
        activityRoot = new JPanel();
        activityRoot.setLayout(new BoxLayout(activityRoot, BoxLayout.Y_AXIS));
        activityRoot.setBackground(DropTrackerTheme.SURFACE_0);
        activityRoot.setBorder(new EmptyBorder(5, 0, 5, 0));

        statsCard = buildStatsCard();
        feedCard = buildFeedCard();
        JPanel configCard = buildGroupConfigCard();

        activityRoot.add(statsCard);
        activityRoot.add(Box.createRigidArea(new Dimension(0, 6)));
        activityRoot.add(feedCard);
        activityRoot.add(Box.createRigidArea(new Dimension(0, 6)));
        activityRoot.add(configCard);
        activityRoot.add(Box.createVerticalGlue());

        refresh();

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
        wrapperPanel.setBackground(DropTrackerTheme.SURFACE_0);
        wrapperPanel.add(activityRoot, BorderLayout.CENTER);
        return wrapperPanel;
    }

    /* ===================== Session stats ===================== */

    private JPanel buildStatsCard() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(DropTrackerTheme.SURFACE_1);
        card.setBorder(DropTrackerTheme.cardBorder(8, 8, 8, 8));
        card.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 70));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        rebuildStatsCard(card);
        return card;
    }

    private void rebuildStatsCard(JPanel card) {
        card.removeAll();

        JLabel title = new JLabel("This Session");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(DropTrackerTheme.TEXT);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(title);
        card.add(Box.createRigidArea(new Dimension(0, 4)));

        int sent = submissionManager.getTotalSubmissions();
        int processed = submissionManager.getNotificationsSent();
        int failed = submissionManager.getFailedSubmissions();

        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        row.setBackground(DropTrackerTheme.SURFACE_1);
        row.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 30, 16));
        row.add(compactStat("Sent", String.valueOf(sent), DropTrackerTheme.GOLD));
        row.add(compactStat("Processed", String.valueOf(processed), DropTrackerTheme.GREEN));
        row.add(compactStat("Failed", String.valueOf(failed), DropTrackerTheme.RED));
        row.setToolTipText("Submissions that qualified for notifications in your groups this session");
        card.add(row);

        JLabel gpLabel = new JLabel("Session loot: " + formatValue(submissionManager.getTotalValue()) + " GP");
        gpLabel.setFont(FontManager.getRunescapeSmallFont());
        gpLabel.setForeground(DropTrackerTheme.TEXT_MUTED);
        gpLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(Box.createRigidArea(new Dimension(0, 3)));
        card.add(gpLabel);

        card.revalidate();
        card.repaint();
    }

    private JPanel compactStat(String label, String value, Color valueColor) {
        JPanel stat = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        stat.setBackground(DropTrackerTheme.SURFACE_1);

        JLabel labelComponent = new JLabel(label);
        labelComponent.setFont(FontManager.getRunescapeSmallFont());
        labelComponent.setForeground(DropTrackerTheme.TEXT_MUTED);

        JLabel valueComponent = new JLabel(value);
        valueComponent.setFont(FontManager.getRunescapeSmallFont());
        valueComponent.setForeground(valueColor);

        stat.add(labelComponent);
        stat.add(valueComponent);
        return stat;
    }

    private String formatValue(long value) {
        if (value >= 1_000_000) {
            return String.format("%.1fM", value / 1_000_000.0);
        } else if (value >= 1_000) {
            return String.format("%.1fK", value / 1_000.0);
        }
        return String.valueOf(value);
    }

    /* ===================== Submission feed ===================== */

    private JPanel buildFeedCard() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(DropTrackerTheme.SURFACE_1);
        card.setBorder(DropTrackerTheme.cardBorder(8, 8, 8, 8));
        card.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 320));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = new JLabel("Submission Feed");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(DropTrackerTheme.TEXT);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(title);
        card.add(Box.createRigidArea(new Dimension(0, 4)));

        feedListPanel = new JPanel();
        feedListPanel.setLayout(new BoxLayout(feedListPanel, BoxLayout.Y_AXIS));
        feedListPanel.setBackground(DropTrackerTheme.SURFACE_1);

        JScrollPane scrollPane = new JScrollPane(feedListPanel);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setBorder(null);
        scrollPane.setBackground(DropTrackerTheme.SURFACE_1);
        scrollPane.getViewport().setBackground(DropTrackerTheme.SURFACE_1);
        scrollPane.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 30, 260));
        scrollPane.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 30, 280));
        card.add(scrollPane);

        return card;
    }

    public void refreshSubmissions() {
        if (feedListPanel == null) {
            return;
        }

        feedListPanel.removeAll();

        List<ValidSubmission> submissions = submissionManager.getValidSubmissions();
        if (submissions != null && !submissions.isEmpty()) {
            // Newest first: submissions are appended chronologically.
            List<ValidSubmission> newestFirst = new ArrayList<>(submissions);
            Collections.reverse(newestFirst);
            for (ValidSubmission submission : newestFirst) {
                feedListPanel.add(createFeedRow(submission));
                feedListPanel.add(Box.createRigidArea(new Dimension(0, 3)));
            }
        } else {
            JLabel emptyLabel = new JLabel("<html>Qualifying drops and achievements will appear here as you receive them. Failed sends can be retried.</html>");
            emptyLabel.setFont(FontManager.getRunescapeSmallFont());
            emptyLabel.setForeground(DropTrackerTheme.TEXT_MUTED);
            emptyLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
            feedListPanel.add(emptyLabel);
        }

        feedListPanel.revalidate();
        feedListPanel.repaint();
    }

    private JPanel createFeedRow(ValidSubmission submission) {
        JPanel row = new JPanel(new BorderLayout(5, 0));
        row.setBackground(DropTrackerTheme.SURFACE_2);
        row.setBorder(DropTrackerTheme.cardBorder(4, 5, 4, 5));
        row.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 42));
        row.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 42));

        // Type indicator
        JLabel typeLabel = new JLabel(submission.getTypeShortLabel());
        typeLabel.setFont(FontManager.getRunescapeSmallFont());
        typeLabel.setForeground(typeColor(submission.getTypeShortLabel()));
        typeLabel.setHorizontalAlignment(SwingConstants.CENTER);
        typeLabel.setPreferredSize(new Dimension(32, 32));
        row.add(typeLabel, BorderLayout.WEST);

        // Name + timestamp
        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setBackground(DropTrackerTheme.SURFACE_2);

        String displayText = submission.getDisplayText();
        JLabel nameLabel = new JLabel(displayText.length() > 24 ? displayText.substring(0, 21) + "..." : displayText);
        nameLabel.setFont(FontManager.getRunescapeSmallFont());
        nameLabel.setForeground(DropTrackerTheme.TEXT);
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel metaRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        metaRow.setBackground(DropTrackerTheme.SURFACE_2);
        metaRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        String timestamp = formatReceivedTime(submission.getTimeReceived());
        if (timestamp != null) {
            JLabel timeLabel = new JLabel(timestamp);
            timeLabel.setFont(FontManager.getRunescapeSmallFont());
            timeLabel.setForeground(DropTrackerTheme.TEXT_MUTED);
            metaRow.add(timeLabel);
        }
        metaRow.add(statusChip(submission.getStatus()));

        textPanel.add(nameLabel);
        textPanel.add(metaRow);
        row.add(textPanel, BorderLayout.CENTER);

        // Actions: retry for failed submissions, dismiss for everything
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        actions.setBackground(DropTrackerTheme.SURFACE_2);

        if (submission.getStatus() == SubmissionStatus.FAILED) {
            JButton retryButton = smallActionButton("↻", "Retry sending this submission");
            retryButton.setForeground(DropTrackerTheme.EMBER);
            retryButton.addActionListener(e -> {
                submissionManager.retrySubmission(submission);
                refreshSubmissions();
            });
            actions.add(retryButton);
        }

        JButton dismissButton = smallActionButton("×", "Remove from list");
        dismissButton.setForeground(DropTrackerTheme.RED);
        dismissButton.addActionListener(e -> {
            submissionManager.removeSubmission(submission);
            refresh();
        });
        actions.add(dismissButton);

        row.add(actions, BorderLayout.EAST);

        row.setToolTipText(buildSubmissionTooltip(submission));
        return row;
    }

    private JButton smallActionButton(String glyph, String tooltip) {
        JButton button = new JButton(glyph);
        button.setFont(FontManager.getRunescapeSmallFont());
        button.setPreferredSize(new Dimension(20, 20));
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setFocusPainted(false);
        button.setContentAreaFilled(true);
        button.setOpaque(true);
        button.setBackground(DropTrackerTheme.SURFACE_3);
        button.setBorder(BorderFactory.createLineBorder(DropTrackerTheme.SURFACE_3, 1));
        button.setToolTipText(tooltip);
        return button;
    }

    private JLabel statusChip(SubmissionStatus status) {
        if (status == null) {
            return DropTrackerTheme.chip("Unknown", DropTrackerTheme.STONE);
        }
        switch (status) {
            case PROCESSED:
                return DropTrackerTheme.chip("Processed", DropTrackerTheme.GREEN);
            case SENT:
                return DropTrackerTheme.chip("Sent", DropTrackerTheme.GREEN);
            case FAILED:
                return DropTrackerTheme.chip("Failed", DropTrackerTheme.RED);
            case PENDING:
            case SENDING:
            case RETRYING:
            default:
                return DropTrackerTheme.chip("Pending", DropTrackerTheme.EMBER);
        }
    }

    private Color typeColor(String shortLabel) {
        switch (shortLabel) {
            case "DROP":
                return DropTrackerTheme.GOLD;
            case "CLOG":
                return DropTrackerTheme.EMBER;
            case "PB":
                return DropTrackerTheme.GOLD_BRIGHT;
            case "CA":
                return new Color(0xc98bd8);
            case "PET":
                return DropTrackerTheme.GREEN;
            case "LVL":
                return new Color(0x8fb8d8);
            case "QST":
                return DropTrackerTheme.TEXT_MUTED;
            default:
                return DropTrackerTheme.TEXT;
        }
    }

    private String buildSubmissionTooltip(ValidSubmission submission) {
        StringBuilder tooltip = new StringBuilder();
        tooltip.append("<html><div style='font-size: 10px; line-height: 1.2; padding: 3px;'>");

        tooltip.append("<div style='color: #ffb83f; font-weight: bold; margin-bottom: 3px;'>")
               .append(submission.getTypeShortLabel()).append(" SUBMISSION</div>");

        tooltip.append("<div style='color: #efe6d2; margin-bottom: 3px;'>")
               .append(submission.getDisplayText()).append("</div>");

        SubmissionStatus status = submission.getStatus();
        String statusColor = status != null ? status.getHexColor() : "#808080";
        tooltip.append("<div style='color: ").append(statusColor).append("; margin-bottom: 3px;'>")
               .append("Status: ").append(submission.getStatusDescription()).append("</div>");

        if (submission.getGroupIds() != null && submission.getGroupIds().length > 0) {
            tooltip.append("<div style='color: #d8c9a3; margin-bottom: 3px;'>")
                   .append("Configured for ").append(submission.getGroupIds().length).append(" group(s)</div>");
        }

        String receivedAt = formatReceivedTime(submission.getTimeReceived());
        if (receivedAt != null) {
            tooltip.append("<div style='color: #d8c9a3; margin-bottom: 3px;'>")
                   .append("Received: ").append(receivedAt).append("</div>");
        }

        if (submission.getRetryAttempts() > 0) {
            tooltip.append("<div style='color: #ff8c42; margin-bottom: 3px;'>")
                   .append("Retry attempts: ").append(submission.getRetryAttempts()).append("</div>");
        }

        if (submission.getLastFailureReason() != null && !submission.getLastFailureReason().isEmpty()) {
            tooltip.append("<div style='color: #e05c4d; margin-bottom: 3px;'>")
                   .append("Last failure: ").append(submission.getLastFailureReason()).append("</div>");
        }

        tooltip.append("</div></html>");
        return tooltip.toString();
    }

    /** Formats the ISO timeReceived value into a short human-readable time, or null. */
    private String formatReceivedTime(String isoTime) {
        if (isoTime == null || isoTime.isEmpty()) {
            return null;
        }
        try {
            java.time.LocalDateTime received = java.time.LocalDateTime.parse(isoTime, java.time.format.DateTimeFormatter.ISO_DATE_TIME);
            return received.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        } catch (Exception e) {
            return isoTime;
        }
    }

    /* ===================== Group config summary ===================== */

    private JPanel buildGroupConfigCard() {
        JPanel wrapperPanel = new JPanel();
        wrapperPanel.setLayout(new BoxLayout(wrapperPanel, BoxLayout.Y_AXIS));
        wrapperPanel.setBackground(DropTrackerTheme.SURFACE_1);
        wrapperPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 400));

        JPanel groupsContainer = new JPanel();
        groupsContainer.setLayout(new BoxLayout(groupsContainer, BoxLayout.Y_AXIS));
        groupsContainer.setBackground(DropTrackerTheme.SURFACE_1);

        // Built empty here; refreshGroupConfigs() populates it from the in-memory list.
        buildGroupConfigPanels(groupsContainer, null);

        groupsScrollPane = new JScrollPane(groupsContainer);
        groupsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        groupsScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        groupsScrollPane.setBorder(null);
        groupsScrollPane.setBackground(DropTrackerTheme.SURFACE_1);
        groupsScrollPane.getViewport().setBackground(DropTrackerTheme.SURFACE_1);
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
        panel.setBackground(DropTrackerTheme.SURFACE_1);
        panel.setBorder(DropTrackerTheme.cardBorder(8, 8, 8, 8));

        panel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 200));
        panel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 200));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(DropTrackerTheme.SURFACE_1);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(FontManager.getRunescapeBoldFont());
        titleLabel.setForeground(DropTrackerTheme.TEXT);

        final ImageIcon expandedIcon = PanelElements.getExpandedIcon();
        final ImageIcon collapsedIcon = PanelElements.getCollapsedIcon();
        final JLabel toggleIcon = new JLabel(expandedIcon);

        headerPanel.add(titleLabel, BorderLayout.WEST);
        headerPanel.add(toggleIcon, BorderLayout.EAST);

        final boolean[] isCollapsed = {false};

        headerPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                isCollapsed[0] = !isCollapsed[0];
                toggleIcon.setIcon(isCollapsed[0] ? collapsedIcon : expandedIcon);
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
        separator.setBackground(DropTrackerTheme.SURFACE_3);
        separator.setForeground(DropTrackerTheme.SURFACE_3);

        panel.add(headerPanel);
        panel.add(separator);
        panel.add(content);

        return panel;
    }

    private void buildGroupConfigPanels(JPanel parentPanel, List<GroupConfig> groupConfigs) {
        parentPanel.removeAll();

        if (groupConfigs != null && !groupConfigs.isEmpty()) {
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
            emptyPanel.setBackground(DropTrackerTheme.SURFACE_1);
            emptyPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            emptyPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 30));

            JLabel noConfigsLabel = new JLabel("No group configurations loaded.");
            noConfigsLabel.setFont(FontManager.getRunescapeSmallFont());
            noConfigsLabel.setForeground(DropTrackerTheme.TEXT_MUTED);
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
        panel.setBackground(DropTrackerTheme.SURFACE_1);
        panel.setBorder(new EmptyBorder(2, 5, 2, 5));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(DropTrackerTheme.SURFACE_1);
        headerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        headerPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 18));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(FontManager.getRunescapeSmallFont());
        titleLabel.setForeground(DropTrackerTheme.TEXT);

        final ImageIcon expandedIcon = PanelElements.getExpandedIcon();
        final ImageIcon collapsedIcon = PanelElements.getCollapsedIcon();
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
                toggleIcon.setIcon(isCollapsedRef[0] ? collapsedIcon : expandedIcon);
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
        separator.setBackground(DropTrackerTheme.SURFACE_3);
        separator.setForeground(DropTrackerTheme.SURFACE_3);
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));

        panel.add(headerPanel);
        panel.add(separator);
        panel.add(content);

        return panel;
    }

    private JPanel createGroupConfigPanel(GroupConfig groupConfig) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(DropTrackerTheme.SURFACE_1);
        panel.setBorder(new EmptyBorder(2, 5, 2, 5));

        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        headerPanel.setBackground(DropTrackerTheme.SURFACE_1);
        headerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        headerPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 70, 18));

        JLabel notificationsLabel = new JLabel("Notifications:");
        notificationsLabel.setFont(FontManager.getRunescapeSmallFont());
        notificationsLabel.setForeground(DropTrackerTheme.TEXT);
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
        row.setBackground(DropTrackerTheme.SURFACE_1);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        row.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 70, 18));

        JLabel nameLabel = new JLabel(label + ":");
        nameLabel.setFont(FontManager.getRunescapeSmallFont());
        nameLabel.setForeground(DropTrackerTheme.TEXT_MUTED);
        row.add(nameLabel);

        JLabel statusLabel = new JLabel(enabled ? "On" : "Off");
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        statusLabel.setForeground(enabled ? DropTrackerTheme.GREEN : DropTrackerTheme.RED);
        row.add(statusLabel);

        if (details != null && !details.isEmpty()) {
            JLabel detailsLabel = new JLabel("(" + details + ")");
            detailsLabel.setFont(FontManager.getRunescapeSmallFont());
            detailsLabel.setForeground(DropTrackerTheme.STONE);
            row.add(detailsLabel);
        }

        return row;
    }

    public void refreshGroupConfigs() {
        if (groupsContainerPanel == null || groupsScrollPane == null) {
            return;
        }

        // Kick a rate-limited async refresh, then render whatever is in memory right now.
        // getGroupConfigs() is a pure in-memory read, so this never blocks the EDT.
        api.refreshGroupConfigsAsync();
        List<GroupConfig> groupConfigs = api.getGroupConfigs();
        SwingUtilities.invokeLater(() -> {
            if (groupsContainerPanel == null || groupsScrollPane == null) {
                return;
            }
            int scrollPosition = groupsScrollPane.getVerticalScrollBar().getValue();

            buildGroupConfigPanels(groupsContainerPanel, groupConfigs);
            groupsContainerPanel.revalidate();
            groupsContainerPanel.repaint();

            SwingUtilities.invokeLater(() -> groupsScrollPane.getVerticalScrollBar().setValue(scrollPosition));

            if (groupConfigPanel != null) {
                groupConfigPanel.revalidate();
                groupConfigPanel.repaint();
            }
        });
    }

    /* ===================== Status + lifecycle ===================== */

    /** Recomputes the header connection state from the last successful API communication. */
    public void updateStatusLabel() {
        if (!config.useApi() || mainPanel == null) {
            return;
        }

        long deltaSeconds = Math.max(0L, Instant.now().getEpochSecond() - api.lastCommunicationTime);
        Duration sinceLastContact = Duration.ofSeconds(deltaSeconds);

        DropTrackerPanel.ConnectionState state;
        if (sinceLastContact.getSeconds() < 60) {
            state = DropTrackerPanel.ConnectionState.CONNECTED;
        } else if (sinceLastContact.getSeconds() < 300) {
            state = DropTrackerPanel.ConnectionState.CONNECTING;
        } else {
            state = DropTrackerPanel.ConnectionState.OFFLINE;
        }
        mainPanel.updateConnectionState(state);
    }

    public void refreshStatistics() {
        if (statsCard != null) {
            rebuildStatsCard(statsCard);
        }
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
}
