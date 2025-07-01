package io.droptracker.ui.pages;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
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

import com.google.inject.Inject;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.submissions.ValidSubmission;
import io.droptracker.service.SubmissionManager;
import io.droptracker.ui.components.PanelElements;
import io.droptracker.util.DurationAdapter;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;	



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
		JTextArea textArea = new JTextArea("");
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

        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BorderLayout());
        contentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        contentPanel.add(textArea, BorderLayout.CENTER);
		contentPanel.add(statusLabel, BorderLayout.SOUTH);
		JPanel titlePanel = PanelElements.createCollapsiblePanel("DropTracker - API", contentPanel, false);
		titlePanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 150));
		titlePanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 150));
		titlePanel.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 150));


		// Initialize submissions panel
		initializeSubmissionsPanel();

		// Add all components to the API panel
		apiPanel.add(titlePanel);
		apiPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		apiPanel.add(submissionsPanel);
		apiPanel.add(Box.createVerticalGlue());

		// Initial update
		updateStatusLabel();
		refreshSubmissions();

		// Start timer to update status every 10 seconds
		if (config.pollUpdates()) {
			statusUpdateTimer = new Timer(10000, e -> updateStatusLabel());
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

	public void updateStatusLabel() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime lastCommunicationDate = LocalDateTime.ofEpochSecond(api.lastCommunicationTime, 0, ZoneOffset.UTC);
		Duration duration = Duration.between(lastCommunicationDate, now);
		
		String lastCommunicationTime = DurationAdapter.formatDuration(duration);
		String statusText = "Last communication with API: " + lastCommunicationTime;
		
		// Update the text content
		statusLabel.setText(statusText);
		
		// Set color based on duration
		Color statusColor;
		if (duration.toSeconds() < 30) {
			statusColor = Color.GREEN;
		} else if (duration.toSeconds() < 60) {
			statusColor = Color.YELLOW;
		} else {
			statusColor = Color.RED;
		}
		
		// Apply color only if API is enabled, otherwise use red
		statusLabel.setForeground(config.useApi() ? statusColor : Color.RED);
		
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
		System.out.println("ApiPanel.refreshSubmissions() called");
		if (submissionManager != null) {
			List<ValidSubmission> validSubmissions = submissionManager.getValidSubmissions();
			System.out.println("Got " + (validSubmissions != null ? validSubmissions.size() : 0) + " submissions from SubmissionManager");
			if (validSubmissions != null) {
				for (ValidSubmission submission : validSubmissions) {
					System.out.println("Submission: " + submission.toString());
				}
			}
			updateSentSubmissions(validSubmissions);
		} else {
			System.out.println("submissionManager is null");
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
		System.out.println("ApiPanel.refresh() called");
		updateStatusLabel();
		refreshSubmissions();
		System.out.println("ApiPanel.refresh() completed");
	}

	/**
	 * Cleanup method to stop the timer when the panel is no longer needed
	 */
	public void cleanup() {
		if (statusUpdateTimer != null && statusUpdateTimer.isRunning()) {
			statusUpdateTimer.stop();
		}
	}
}
