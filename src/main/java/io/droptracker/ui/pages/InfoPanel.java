package io.droptracker.ui.pages;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

import com.google.inject.Inject;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;



public class InfoPanel {

    @Inject
    private DropTrackerConfig config;	
    @Inject
    private DropTrackerApi api;

    public InfoPanel(DropTrackerConfig config, DropTrackerApi api) {
        this.config = config;
        this.api = api;
    }

    public JPanel create() {
		JPanel apiPanel = new JPanel();
		apiPanel.setLayout(new BoxLayout(apiPanel, BoxLayout.Y_AXIS));
		apiPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		apiPanel.setBorder(new EmptyBorder(0, 0, 0, 0));

		// Create a panel for the title with proper alignment
		JPanel titlePanel = new JPanel();
		titlePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		titlePanel.setLayout(new FlowLayout(FlowLayout.CENTER, 0, 0));
		titlePanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		titlePanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 40));
		titlePanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel apiTitle = new JLabel("DropTracker - About");
		apiTitle.setFont(FontManager.getRunescapeBoldFont());
		apiTitle.setForeground(Color.WHITE);

		titlePanel.add(apiTitle);

		// API status panel
		JPanel statusPanel = new JPanel(new BorderLayout());
		statusPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		statusPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		statusPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 40));
		statusPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		if (config.useApi()) {
			String lastCommunicationTime = "";
			LocalDateTime now = LocalDateTime.now();
			LocalDateTime lastCommunicationDate = LocalDateTime.ofEpochSecond(api.lastCommunicationTime, 0, ZoneOffset.UTC);
			Duration duration = Duration.between(lastCommunicationDate, now);
			
			if (duration.toDays() > 0) {
				lastCommunicationTime = duration.toDays() + " day ago";
			} else if (duration.toHours() > 0) {
				lastCommunicationTime = duration.toHours() + " hr ago"; 
			} else if (duration.toMinutes() > 0) {
				lastCommunicationTime = duration.toMinutes() + " min ago";
			} else {
				lastCommunicationTime = "just now";
			}
			JLabel statusLabel = new JLabel("Last communication with API: " + lastCommunicationTime);
			statusLabel.setForeground(config.useApi() ? Color.GREEN : Color.RED);
			statusLabel.setFont(FontManager.getRunescapeSmallFont());
			statusPanel.add(statusLabel, BorderLayout.CENTER);
		}

		// API features list
		JPanel featuresPanel = new JPanel();
		featuresPanel.setLayout(new BoxLayout(featuresPanel, BoxLayout.Y_AXIS));
		featuresPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		featuresPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		featuresPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		featuresPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel featuresTitle = new JLabel("With the API enabled, you can:");
		featuresTitle.setFont(FontManager.getRunescapeSmallFont());
		featuresTitle.setForeground(Color.WHITE);
		featuresTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel featuresList = new JPanel();
		featuresList.setLayout(new BoxLayout(featuresList, BoxLayout.Y_AXIS));
		featuresList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		featuresList.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, Integer.MAX_VALUE));
		featuresList.setAlignmentX(Component.LEFT_ALIGNMENT);

		String[] features = {
				"• Track all your drops across multiple accounts",
				"• View detailed statistics about your drops",
				"• Compare your drop rates with community averages",
				"• Access your drop history from anywhere",
				"• Share your collection log progress with friends"
		};

		for (String feature : features) {
			JLabel featureLabel = new JLabel(feature);
			featureLabel.setFont(FontManager.getRunescapeSmallFont());
			featureLabel.setForeground(Color.LIGHT_GRAY);
			featureLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			featuresList.add(featureLabel);
			featuresList.add(Box.createRigidArea(new Dimension(0, 5)));
		}

		featuresPanel.add(featuresTitle);
		featuresPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		featuresPanel.add(featuresList);

		// API setup instructions
		JPanel setupPanel = new JPanel();
		setupPanel.setLayout(new BoxLayout(setupPanel, BoxLayout.Y_AXIS));
		setupPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setupPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		setupPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
		setupPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel setupTitle = new JLabel("How to Enable the API");
		setupTitle.setFont(FontManager.getRunescapeSmallFont());
		setupTitle.setForeground(Color.WHITE);
		setupTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

		JTextArea setupInstructions = new JTextArea(
				"1. Go to the Settings tab\n" +
						"2. Check 'Enable API Integration'\n" +
						"3. Enter your API key (get one at droptracker.io)\n" +
						"4. Click Save");
		setupInstructions.setWrapStyleWord(true);
		setupInstructions.setLineWrap(true);
		setupInstructions.setOpaque(false);
		setupInstructions.setEditable(false);
		setupInstructions.setFocusable(false);
		setupInstructions.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setupInstructions.setForeground(Color.LIGHT_GRAY);
		setupInstructions.setFont(FontManager.getRunescapeSmallFont());
		setupInstructions.setAlignmentX(Component.LEFT_ALIGNMENT);
		setupInstructions.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, Integer.MAX_VALUE));

		setupPanel.add(setupTitle);
		setupPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		setupPanel.add(setupInstructions);

		// Button to get API key
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
		buttonPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttonPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 40));
		buttonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		JButton getApiKeyButton = new JButton("Get API Key");
		getApiKeyButton.addActionListener(e -> LinkBrowser.browse("https://www.droptracker.io/account/api"));
		buttonPanel.add(getApiKeyButton);

		// Add all components to the API panel
		apiPanel.add(titlePanel);
		apiPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		apiPanel.add(statusPanel);
		apiPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		apiPanel.add(featuresPanel);
		apiPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		apiPanel.add(setupPanel);
		apiPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		apiPanel.add(buttonPanel);
		apiPanel.add(Box.createVerticalGlue());


		JPanel wrapperPanel = new JPanel(new BorderLayout());
		wrapperPanel.add(apiPanel, BorderLayout.CENTER);
		return wrapperPanel;
	}
    
}
