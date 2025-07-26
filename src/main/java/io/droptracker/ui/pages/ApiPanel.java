package io.droptracker.ui.pages;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import javax.swing.Box;
import javax.swing.Timer;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;
import javax.swing.JSeparator;
import javax.swing.ImageIcon;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;

import com.google.inject.Inject;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.api.GroupConfig;
import io.droptracker.models.submissions.ValidSubmission;
import io.droptracker.service.SubmissionManager;
import io.droptracker.ui.components.PanelElements;
import io.droptracker.util.DurationAdapter;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.Image;
import java.util.HashMap;
import java.util.Map;

public class ApiPanel {

    @Inject
    private DropTrackerConfig config;	
    @Inject
    private DropTrackerApi api;
    
    @Inject
    private SubmissionManager submissionManager;

	private JPanel apiPanel;
	private JTextArea statusLabel;
	private JPanel submissionsPanel;
	private Timer statusUpdateTimer;
	private JPanel groupConfigPanel;
	private JPanel groupsContainerPanel;
	private JScrollPane groupsScrollPane;
	private Map<String, Boolean> groupExpandStates = new HashMap<>(); // Track expand/collapse state by group ID


    public ApiPanel(DropTrackerConfig config, DropTrackerApi api, SubmissionManager submissionManager) {
        this.config = config;
        this.api = api;
        this.submissionManager = submissionManager;
    }

    public JPanel create() {
		apiPanel = new JPanel();
		apiPanel.setLayout(new BoxLayout(apiPanel, BoxLayout.Y_AXIS));
		apiPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		apiPanel.setBorder(new EmptyBorder(0, 0, 0, 0));

		// Create a panel for the title with proper alignment
		// API status panel
		statusLabel = new JTextArea("Last communication with API: loading...");
		statusLabel.setForeground(config.useApi() ? Color.GREEN : Color.RED);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setLineWrap(true);
		statusLabel.setWrapStyleWord(true);
		statusLabel.setEditable(false);
		statusLabel.setFocusable(false);
		statusLabel.setOpaque(false);
		statusLabel.setBorder(new EmptyBorder(0, 0, 0, 0));
		statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		JPanel configPanel = initializeConfigPanel();	


		// Initialize submissions panel
		initializeSubmissionsPanel();

		// Add all components to the API panel
		apiPanel.add(submissionsPanel);
		apiPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		// Add configs beneath the submissions panel
		apiPanel.add(configPanel);
		apiPanel.add(Box.createVerticalGlue());

		// Initial update
		updateStatusLabel();
		refreshSubmissions();
		refreshGroupConfigs();

		// Start timer to update status every 10 seconds
		if (config.pollUpdates()) {
			statusUpdateTimer = new Timer(10000, e -> {
				updateStatusLabel();
				refreshGroupConfigs();
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
		JLabel submissionsTitle = new JLabel("Recent Submissions");
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


	private JPanel initializeConfigPanel() {
		// Create a wrapper panel to contain the group configs
		JPanel wrapperPanel = new JPanel();
		wrapperPanel.setLayout(new BoxLayout(wrapperPanel, BoxLayout.Y_AXIS));
		wrapperPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrapperPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 400)); // Limit wrapper size
		
		// Add status label at the top
		statusLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
		wrapperPanel.add(statusLabel);
		wrapperPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		
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
		final boolean[] isCollapsed = { false };

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
		final boolean[] isCollapsedRef = { isCollapsed };

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
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime lastCommunicationDate = LocalDateTime.ofEpochSecond(api.lastCommunicationTime, 0, ZoneOffset.UTC);
		Duration duration = Duration.between(lastCommunicationDate, now);
		
		String lastCommunicationTime = DurationAdapter.formatDuration(duration);
		String statusText;
		Color statusColor;
		if (config.useApi()) {
			if (duration.toSeconds() < 30) {
				statusText = "Connected - last ping: " + lastCommunicationTime;
				statusColor = Color.GREEN;
			} else {
				statusText = "May be disconnected! Last ping: " + lastCommunicationTime;
				statusColor = Color.YELLOW;
			}
		} else {
			statusText = "API disabled - check plugin config";
			statusColor = Color.RED;
		}
		
		
		// Update the text content
		statusLabel.setText(statusText);
		statusLabel.setForeground(statusColor);
		
		// repaint and revalidate to update the UI
		if (apiPanel != null) {
			apiPanel.repaint();
			apiPanel.revalidate();
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
			for (ValidSubmission submission : validSubmissions) {
				JPanel submissionPanel = createSubmissionPanel(submission);
				submissionsPanel.add(submissionPanel);
				submissionsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
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
	 * Creates a panel for displaying a single ValidSubmission with retry/dismiss buttons
	 * @param submission The submission to display
	 * @return A JPanel containing the submission display
	 */
	private JPanel createSubmissionPanel(ValidSubmission submission) {
		JPanel panel = new JPanel();
		panel.setLayout(new GridBagLayout());
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(5, 5, 5, 5));
		panel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 60));

		GridBagConstraints gbc = new GridBagConstraints();

		// Submission text
		JPanel submissionPanel = submission.toSubmissionPanel();
		panel.add(submissionPanel, gbc);

		// Status and group info
		String statusText = "Status: " + submission.getStatus();
		if (submission.getGroupIds() != null && submission.getGroupIds().length > 0) {
			statusText += " | Groups: " + submission.getGroupIds().length;
		}
		
		JLabel statusLabel = new JLabel(statusText);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(getStatusColor(submission.getStatus()));
		
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 1;
		gbc.weightx = 0.7;
		panel.add(statusLabel, gbc);

		// Retry button
		JButton retryButton = new JButton("Retry");
		retryButton.setFont(FontManager.getRunescapeSmallFont());
		retryButton.addActionListener(e -> {
			submissionManager.retrySubmission(submission);
			refreshSubmissions(); // Refresh to show updated status
		});
		
		gbc.gridx = 1;
		gbc.gridy = 1;
		gbc.gridwidth = 1;
		gbc.weightx = 0.15;
		gbc.anchor = GridBagConstraints.EAST;
		gbc.insets = new Insets(0, 5, 0, 0);
		panel.add(retryButton, gbc);

		// Dismiss button
		JButton dismissButton = new JButton("×");
		dismissButton.setFont(FontManager.getRunescapeSmallFont());
		dismissButton.setToolTipText("Dismiss this submission");
		dismissButton.addActionListener(e -> {
			submissionManager.removeSubmission(submission);
			refreshSubmissions(); // Refresh to remove from display
		});
		
		gbc.gridx = 2;
		gbc.gridy = 1;
		gbc.weightx = 0.15;
		gbc.insets = new Insets(0, 2, 0, 0);
		panel.add(dismissButton, gbc);

		return panel;
	}

	/**
	 * Gets the appropriate color for a submission status
	 * @param status The submission status
	 * @return Color for the status
	 */
	private Color getStatusColor(String status) {
		if (status == null) return Color.GRAY;
		
		switch (status.toLowerCase()) {
			case "success":
			case "processed":
				return Color.GREEN;
			case "pending":
			case "retrying":
				return Color.YELLOW;
			case "failed":
			case "error":
				return Color.RED;
			default:
				return Color.GRAY;
		}
	}

	/**
	 * Public method to refresh the entire API panel
	 * Can be called from outside to update the display
	 */
	public void refresh() {
		updateStatusLabel();
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
