package io.droptracker.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.awt.Cursor;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.StrokeBorder;

import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;    
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.api.RecentSubmission;

import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;

public class PanelElements {


    private static ImageIcon COLLAPSED_ICON;
    private static ImageIcon EXPANDED_ICON;
    private static ImageIcon BOARD_ICON;
    private static ImageIcon EXTERNAL_LINK_ICON;
    public static BufferedImage cachedLootboardImage;
    private static String currentImageUrl = "https://www.droptracker.io/img/clans/2/lb/lootboard.png";
    private static Integer cachedGroupId = null; // Track which group's lootboard is currently cached
    public static String cachedGroupName = "All Players";

    private static Client client;
    static {
        Image collapsedImg = ImageUtil.loadImageResource(DropTrackerPlugin.class, "util/collapse.png");
        Image expandedImg = ImageUtil.loadImageResource(DropTrackerPlugin.class, "util/expand.png");
        Image boardIcon = ImageUtil.loadImageResource(DropTrackerPlugin.class, "util/board.png");
        Image extLinkIcon = ImageUtil.loadImageResource(DropTrackerPlugin.class, "util/external-link.png");
        Image boardResized = boardIcon.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
        Image extRecolored = ImageUtil.recolorImage(extLinkIcon, ColorScheme.LIGHT_GRAY_COLOR);
        Image extLinkResized = extRecolored.getScaledInstance(16, 16, Image.SCALE_SMOOTH);

        Image collapsedResized = collapsedImg.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
        Image expandedResized = expandedImg.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
        Image collapsedRecolored = ImageUtil.recolorImage(collapsedResized, ColorScheme.LIGHT_GRAY_COLOR);
        Image expandedRecolored = ImageUtil.recolorImage(expandedResized, ColorScheme.LIGHT_GRAY_COLOR);
        COLLAPSED_ICON = new ImageIcon(collapsedRecolored);
        EXPANDED_ICON = new ImageIcon(expandedRecolored);
        BOARD_ICON = new ImageIcon(boardResized);
        EXTERNAL_LINK_ICON = new ImageIcon(extLinkResized);
        
        // Initialize with default global group lootboard (group 2)
        loadLootboardForGroup(2);
    }

    // Method to load lootboard for a specific group ID
    public static void loadLootboardForGroup(int groupId) {
        // Check if we already have this group cached
        if (cachedGroupId != null && cachedGroupId == groupId && cachedLootboardImage != null) {
            System.out.println("Group " + groupId + " lootboard already cached");
            return;
        }
        
        String imageUrl = "https://www.droptracker.io/img/clans/" + groupId + "/lb/lootboard.png";
        System.out.println("Loading lootboard for group " + groupId + ": " + imageUrl);
        
        CompletableFuture.supplyAsync(() -> {
            try {
                URL url = new URL(imageUrl);
                return ImageIO.read(url);
            } catch (IOException e) {
                System.err.println("Failed to load lootboard for group " + groupId + ": " + e.getMessage());
                return null;
            }
        }).thenAccept(image -> {
            SwingUtilities.invokeLater(() -> {
                cachedLootboardImage = image;
                cachedGroupId = groupId;
                currentImageUrl = imageUrl;
                if (image != null) {
                    System.out.println("Lootboard for group " + groupId + " cached successfully");
                } else {
                    System.err.println("Failed to cache lootboard for group " + groupId);
                }
            });
        });
    }

    // Method to load lootboard for a specific group ID with callback
    public static void loadLootboardForGroup(int groupId, Runnable onComplete) {
        // Check if we already have this group cached
        if (cachedGroupId != null && cachedGroupId == groupId && cachedLootboardImage != null) {
            System.out.println("Group " + groupId + " lootboard already cached");
            if (onComplete != null) {
                SwingUtilities.invokeLater(onComplete);
            }
            return;
        }
        
        String imageUrl = "https://www.droptracker.io/img/clans/" + groupId + "/lb/lootboard.png";
        System.out.println("Loading lootboard for group " + groupId + ": " + imageUrl);
        
        CompletableFuture.supplyAsync(() -> {
            try {
                URL url = new URL(imageUrl);
                return ImageIO.read(url);
            } catch (IOException e) {
                System.err.println("Failed to load lootboard for group " + groupId + ": " + e.getMessage());
                return null;
            }
        }).thenAccept(image -> {
            SwingUtilities.invokeLater(() -> {
                cachedLootboardImage = image;
                cachedGroupId = groupId;
                currentImageUrl = imageUrl;
                if (image != null) {
                    System.out.println("Lootboard for group " + groupId + " cached successfully");
                } else {
                    System.err.println("Failed to cache lootboard for group " + groupId);
                }
                // Call the completion callback
                if (onComplete != null) {
                    onComplete.run();
                }
            });
        });
    }
    /**
	 * Creates a styled container for submission icons with border and background
	 */
	public static JLabel createStyledIconContainer() {
		JLabel container = new JLabel();
		container.setVerticalAlignment(SwingConstants.CENTER);
		container.setHorizontalAlignment(SwingConstants.CENTER);
		container.setPreferredSize(new Dimension(32, 32));
		container.setMinimumSize(new Dimension(32, 32));
		container.setMaximumSize(new Dimension(32, 32));
		
		// Add styling with border and background
		container.setOpaque(true);
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);
		container.setBorder(new StrokeBorder(new BasicStroke(1), ColorScheme.BORDER_COLOR));
		
		// Add hover effect
		container.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseEntered(MouseEvent e) {
				container.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				container.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			}
			
			@Override
			public void mouseExited(MouseEvent e) {
				container.setBackground(ColorScheme.DARK_GRAY_COLOR);
				container.setCursor(Cursor.getDefaultCursor());
			}
		});
		
		return container;
	}

    // Method to show lootboard popup for a specific group ID
    public static void showLootboardForGroup(Client client, int groupId) {
        if (cachedGroupName == null) {
            cachedGroupName = "All Players";
        }
        final JFrame parentFrame = getParentFrame(client);
        JDialog imageDialog = new JDialog(parentFrame, cachedGroupName + " - Lootboard", true);
        imageDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        // Check if we already have the right group cached
        if (cachedLootboardImage != null && cachedGroupId != null && cachedGroupId == groupId) {
            System.out.println("Displaying cached lootboard for group " + groupId);
            displayImageInDialog(imageDialog, cachedLootboardImage, parentFrame);
            imageDialog.revalidate();
            imageDialog.repaint();
            imageDialog.setVisible(true);
            return;
        }

        // Show loading dialog first
        JLabel loadingLabel = new JLabel("Loading group " + groupId + " lootboard...");
        loadingLabel.setForeground(Color.WHITE);
        loadingLabel.setFont(FontManager.getRunescapeBoldFont());
        loadingLabel.setHorizontalAlignment(JLabel.CENTER);
        loadingLabel.setVerticalAlignment(JLabel.CENTER);
        loadingLabel.setPreferredSize(new Dimension(400, 300));
        loadingLabel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        loadingLabel.setOpaque(true);

        // Add click to close
        addCloseListener(loadingLabel, imageDialog);

        imageDialog.add(loadingLabel);
        imageDialog.pack();
        imageDialog.setLocationRelativeTo(parentFrame);

        // Start loading BEFORE showing the dialog to avoid modality blocking
        loadLootboardForGroup(groupId, () -> {
            if (cachedLootboardImage != null) {
                System.out.println("Group " + groupId + " image loaded, updating dialog");
                imageDialog.getContentPane().removeAll();
                displayImageInDialog(imageDialog, cachedLootboardImage, parentFrame);
                imageDialog.revalidate();
                imageDialog.repaint();
            } else {
                System.err.println("Failed to load group " + groupId + " lootboard");
                loadingLabel.setText("Failed to load group " + groupId + " lootboard");
                loadingLabel.setForeground(Color.RED);
                imageDialog.revalidate();
                imageDialog.repaint();
            }
        });

        // Now show the modal dialog (this blocks, but image loading is already underway)
        imageDialog.setVisible(true);
    }

    // Method to show submission image popup
    public static void showSubmissionImage(Client client, String submissionType, String submissionImageUrl, String tooltip) {
        final JFrame parentFrame = getParentFrame(client);
        String dialogTitle = getSubmissionDialogTitle(submissionType);
        JDialog imageDialog = new JDialog(parentFrame, dialogTitle, true);
        imageDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        // Show loading dialog first
        JLabel loadingLabel = new JLabel("Loading " + submissionType + " image...");
        loadingLabel.setForeground(Color.WHITE);
        loadingLabel.setFont(FontManager.getRunescapeBoldFont());
        loadingLabel.setHorizontalAlignment(JLabel.CENTER);
        loadingLabel.setVerticalAlignment(JLabel.CENTER);
        loadingLabel.setPreferredSize(new Dimension(400, 300));
        loadingLabel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        loadingLabel.setOpaque(true);

        // Add click to close
        addCloseListener(loadingLabel, imageDialog);

        imageDialog.add(loadingLabel);
        imageDialog.pack();
        imageDialog.setLocationRelativeTo(parentFrame);

        // Load image from submission URL (similar to lootboard loading)
        loadUrlImage(submissionImageUrl, imageDialog, loadingLabel, parentFrame, tooltip);

        // Show the modal dialog
        imageDialog.setVisible(true);
    }

    private static String getSubmissionDialogTitle(String submissionType) {
        switch (submissionType.toLowerCase()) {
            case "drop":
                return "Drop Submission";
            case "clog":
                return "Collection Log Submission";
            case "pb":
                return "Personal Best Submission";
            default:
                return "Submission";
        }
    }



    private static void loadUrlImage(String imageUrl, JDialog imageDialog, JLabel loadingLabel, JFrame parentFrame, String tooltip) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            loadingLabel.setText("No image URL available");
            loadingLabel.setForeground(Color.RED);
            return;
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                URL url = new URL(imageUrl);
                return ImageIO.read(url);
            } catch (IOException e) {
                System.err.println("Failed to load image from URL: " + e.getMessage());
                return null;
            }
        }).thenAccept(image -> {
            SwingUtilities.invokeLater(() -> {
                if (image != null) {
                    System.out.println("URL image loaded, updating dialog");
                    imageDialog.getContentPane().removeAll();
                    displayImageInDialog(imageDialog, image, parentFrame);
                    imageDialog.revalidate();
                    imageDialog.repaint();
                } else {
                    System.err.println("Failed to load image from URL");
                    loadingLabel.setText("Failed to load image from URL");
                    loadingLabel.setForeground(Color.RED);
                    imageDialog.revalidate();
                    imageDialog.repaint();
                }
            });
        });
    }



    // Method to get currently cached group ID
    public static Integer getCachedGroupId() {
        return cachedGroupId;
    }

    // Method to get current image URL
    public static String getCurrentImageUrl() {
        return currentImageUrl;
    }

    // Helper method to create a stat box with fixed size
    public static JPanel createStatBox(String label, String value) {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        box.setBorder(new EmptyBorder(5, 5, 5, 5));

        JLabel valueLabel = new JLabel(value);
        valueLabel.setFont(FontManager.getRunescapeBoldFont());
        valueLabel.setForeground(Color.WHITE);
        valueLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel nameLabel = new JLabel(label);
        nameLabel.setFont(FontManager.getRunescapeSmallFont());
        nameLabel.setForeground(Color.LIGHT_GRAY);
        nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        box.add(valueLabel);
        box.add(nameLabel);

        return box;
    }

    // Helper method to create an NPC row (similar to member row)
    public static JPanel createNpcRow(String npcName, String lootValue, String rank) {
        JPanel row = new JPanel(new BorderLayout(5, 0));
        row.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        row.setBorder(new EmptyBorder(5, 5, 5, 5));

        JLabel rankLabel = new JLabel("#" + rank);
        rankLabel.setFont(FontManager.getRunescapeSmallFont());
        rankLabel.setForeground(Color.YELLOW);

        JLabel nameLabel = new JLabel(npcName);
        nameLabel.setFont(FontManager.getRunescapeSmallFont());
        nameLabel.setForeground(Color.WHITE);

        JLabel lootLabel = new JLabel(lootValue);
        lootLabel.setFont(FontManager.getRunescapeSmallFont());
        lootLabel.setForeground(Color.GREEN);

        row.add(rankLabel, BorderLayout.WEST);
        row.add(nameLabel, BorderLayout.CENTER);
        row.add(lootLabel, BorderLayout.EAST);

        return row;
    }

    // Helper method to create a member row with fixed size for player rank lists
    public static JPanel createMemberRow(String name, String loot, String rank) {
        JPanel row = new JPanel(new BorderLayout(5, 0));
        row.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        row.setBorder(new EmptyBorder(5, 5, 5, 5));

        JLabel rankLabel = new JLabel("#" + rank);
        rankLabel.setFont(FontManager.getRunescapeSmallFont());
        rankLabel.setForeground(Color.YELLOW);

        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(FontManager.getRunescapeSmallFont());
        nameLabel.setForeground(Color.WHITE);

        JLabel lootLabel = new JLabel(loot);
        lootLabel.setFont(FontManager.getRunescapeSmallFont());
        lootLabel.setForeground(Color.GREEN);

        row.add(rankLabel, BorderLayout.WEST);
        row.add(nameLabel, BorderLayout.CENTER);
        row.add(lootLabel, BorderLayout.EAST);

        return row;
    }

    public static JPanel getLatestWelcomeContent(DropTrackerApi api) {
        String welcomeText;
        welcomeText = (api != null && api.getLatestUpdateString() != null) ? api.getLatestUpdateString()
                : "Welcome to the DropTracker!";
        JTextArea textArea = collapsibleSubText(welcomeText);

        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BorderLayout());
        contentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        contentPanel.add(textArea, BorderLayout.CENTER);

        return contentPanel;
    }

    public static JPanel getLatestUpdateContent(DropTrackerConfig config, DropTrackerApi api) {
        String updateText;
        if (config != null && config.useApi()) {
            updateText = (api != null && api.getLatestUpdateString() != null) ? api.getLatestUpdateString()
                    : "No updates found.";
        } else {
            updateText = "• Implemented support for tracking Personal Bests from a POH adventure log.\n\n" +
                    "• Added pet collection submissions when adventure logs are opened.\n\n" +
                    "• Fixed various personal best tracking bugs.\n\n" +
                    "• A new side panel & stats functionality";
        }

        JTextArea textArea = collapsibleSubText(updateText);

        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BorderLayout());
        contentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        contentPanel.add(textArea, BorderLayout.CENTER);

        return contentPanel;
    }

    private static JTextArea collapsibleSubText(String inputString) {
        JTextArea textArea = new JTextArea();
        textArea.setText(inputString);
        textArea.setWrapStyleWord(true);
        textArea.setLineWrap(true);
        textArea.setOpaque(false);
        textArea.setEditable(false);
        textArea.setFocusable(false);
        textArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        textArea.setForeground(Color.LIGHT_GRAY);
        Font textAreaFont = FontManager.getRunescapeSmallFont();
        textArea.setFont(textAreaFont);
        textArea.setBorder(new EmptyBorder(5, 5, 5, 5));

        return textArea;
    }

    public static JPanel createFeaturePanel(String title, String description) {
        // Create a panel with fixed dimensions
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Fixed height for the entire panel
        Dimension panelSize = new Dimension(PluginPanel.PANEL_WIDTH, 90);
        panel.setPreferredSize(panelSize);
        panel.setMinimumSize(panelSize);
        panel.setMaximumSize(panelSize);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(FontManager.getRunescapeBoldFont());
        titleLabel.setForeground(Color.WHITE);

        JTextArea descArea = new JTextArea(description);
        descArea.setWrapStyleWord(true);
        descArea.setLineWrap(true);
        descArea.setOpaque(false);
        descArea.setEditable(false);
        descArea.setFocusable(false);
        descArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        descArea.setForeground(Color.LIGHT_GRAY);
        descArea.setFont(FontManager.getRunescapeSmallFont());
        descArea.setBorder(new EmptyBorder(5, 0, 0, 0));

        panel.add(titleLabel, BorderLayout.NORTH);
        panel.add(descArea, BorderLayout.CENTER);

        return panel;
    }

    public static JPanel createCollapsiblePanel(String title, JPanel content, boolean isUnderlined) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Create title with optional underline
        JLabel titleLabel;
        if (isUnderlined) {
            titleLabel = new JLabel("<html><u>" + title + "</u></html>");
        } else {
            titleLabel = new JLabel(title);
        }
        titleLabel.setFont(FontManager.getRunescapeBoldFont());
        titleLabel.setForeground(Color.WHITE);

        JLabel toggleIcon = new JLabel(EXPANDED_ICON);

        headerPanel.add(titleLabel, BorderLayout.WEST);
        headerPanel.add(toggleIcon, BorderLayout.EAST);

        // Create a final reference to the content for use in the listener
        final JPanel contentRef = content;
        final boolean[] isCollapsed = { false };

        // Add click listener for collapsing/expanding
        headerPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                isCollapsed[0] = !isCollapsed[0];
                toggleIcon.setIcon(isCollapsed[0] ? COLLAPSED_ICON : EXPANDED_ICON);
                contentRef.setVisible(!isCollapsed[0]);

                // Force fixed height when collapsed
                if (isCollapsed[0]) {
                    panel.setPreferredSize(
                            new Dimension(PluginPanel.PANEL_WIDTH, headerPanel.getPreferredSize().height + 20));
                    panel.setMaximumSize(
                            new Dimension(PluginPanel.PANEL_WIDTH, headerPanel.getPreferredSize().height + 20));
                    panel.revalidate();
                    panel.repaint();
                } else {
                    panel.setPreferredSize(null);
                    panel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
                    panel.revalidate();
                    panel.repaint();
                }
            }
        });

        panel.add(headerPanel);
        panel.add(getJSeparator(ColorScheme.LIGHT_GRAY_COLOR));
        panel.add(content);

        return panel;
    }

    private static JSeparator getJSeparator(Color color) {
        JSeparator sep = new JSeparator();
        sep.setBackground(color);
        sep.setForeground(color);
        return sep;
    }

    public static JLabel createSuperscriptWarningLabel() {
        String htmlText = "<html><font color='orange'>!</font><sup><font color='orange'>!</font></sup></html>";

        JLabel warningLabel = new JLabel(htmlText);
        // warningLabel.setFont(new Font("SansSerif", Font.BOLD, 16));

        return warningLabel;
    }

    public static JFrame getParentFrame(Client client) {
        try {
            if (SwingUtilities.getWindowAncestor(client.getCanvas()) instanceof JFrame) {
                return (JFrame) SwingUtilities.getWindowAncestor(client.getCanvas());
            }
        } catch (Exception e) {
            System.err.println("Could not find parent frame: " + e.getMessage());
        }
        return null;
    }

    public static JButton createExternalLinkButton(String text, String tooltip, boolean withIcon, Runnable action) {
        JButton button = new JButton(text);
        if (withIcon) {
            button.setIcon(EXTERNAL_LINK_ICON);
        }
        button.setText(text);
        button.setToolTipText(tooltip);		
        button.setFont(FontManager.getRunescapeSmallFont());
		button.setPreferredSize(new Dimension(150, 30));
        button.addActionListener(e -> action.run());
        return button;
    }

    public static JButton createLootboardButton(String text, String tooltip, Runnable action) {
        JButton button = new JButton(text);
        button.setIcon(BOARD_ICON);
        button.setText("<html>" + text + "&nbsp;&nbsp;<img src='https://www.droptracker.io/img/external-16px-g.png'></img></html>");
        button.setToolTipText(tooltip);
        button.addActionListener(e -> action.run());
        return button;
    }

    public static JPanel createRecentSubmissionPanel(List<RecentSubmission> recentSubmissions,
			ItemManager itemManager, Client client, boolean forGroup) {
		System.out.println("Creating recent submission panel...");
		
		// Main container with title and submissions
		JPanel container = new JPanel();
		container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
		container.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		container.setBorder(new EmptyBorder(10, 0, 10, 0));
		container.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 120)); // Adjusted height
		container.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 120));
		container.setAlignmentX(Component.LEFT_ALIGNMENT); // Keep consistent with parent
		
		// Title panel to ensure centering
		JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		titlePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		titlePanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 20));
		titlePanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 20));
		
		JLabel title = new JLabel("Recent Submissions");
		title.setFont(FontManager.getRunescapeSmallFont());
		title.setForeground(Color.WHITE);
		titlePanel.add(title);
		
		// Submissions panel - use FlowLayout wrapper to center the GridBagLayout
		JPanel submissionWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		submissionWrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		submissionWrapper.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 80));
		submissionWrapper.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 80));
		
		JPanel panel = new JPanel();
		panel.setLayout(new GridBagLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 80)); 
		
		submissionWrapper.add(updateRecentSubmissionPanel(panel, recentSubmissions, itemManager, forGroup));

		// Add components to container
		container.add(titlePanel);
		container.add(Box.createRigidArea(new Dimension(0, 5))); // Small gap between title and submissions
		container.add(submissionWrapper);
		
		return container;
	}

	private static JPanel updateRecentSubmissionPanel(JPanel panel, List<RecentSubmission> recentSubmissions, ItemManager itemManager, boolean forGroup) {
		panel.removeAll();
		
		// Debug logging
		System.out.println("Starting updateRecentSubmissionPanel with " + recentSubmissions.size() + " submissions");
		System.out.println("ItemManager is null: " + (itemManager == null));

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.NONE;
		c.anchor = GridBagConstraints.CENTER;
		c.weightx = 0.2; // Equal weight for 5 columns
		c.weighty = 0.5; // Equal weight for 2 rows
		c.gridx = 0;
		c.gridy = 0;	
		c.insets = new Insets(1, 1, 1, 1); // Padding around each icon

		int successfullyAdded = 0;
		final int ITEMS_PER_ROW = 5;
		final int MAX_ITEMS = 10;

		// Add each submission icon to the panel (limit to 10 items)
		for (int i = 0; i < Math.min(recentSubmissions.size(), MAX_ITEMS); i++) {
			RecentSubmission submission = recentSubmissions.get(i);
			
			try {
				JLabel iconContainer = null;
				
				if (submission.getSubmissionType().equalsIgnoreCase("drop")) {
					// Handle drops
					Integer itemId = submission.getDropItemId();
					Integer quantity = submission.getDropQuantity();
					
					System.out.println(submission.toString());
					
					
					if (itemId != null && quantity != null && itemManager != null) {
						final AsyncBufferedImage originalImage = itemManager.getImage(itemId, quantity, quantity > 1);
						final float alpha = (quantity > 0 ? 1.0f : 0.5f);
						
						// Create a scaled version of the image for initial display
						BufferedImage scaledImage = new BufferedImage(28, 28, BufferedImage.TYPE_INT_ARGB);
						BufferedImage opaque = ImageUtil.alphaOffset(scaledImage, alpha);		

						final JLabel dropContainer = PanelElements.createStyledIconContainer();
						dropContainer.setToolTipText(buildSubmissionTooltip(submission, forGroup));
						dropContainer.setIcon(new ImageIcon(opaque));
						iconContainer = dropContainer;

						originalImage.onLoaded(() -> {
							// Scale the loaded image to 16x16
							Image scaled = originalImage.getScaledInstance(28, 28, Image.SCALE_SMOOTH);
							BufferedImage scaledBuffered = new BufferedImage(28, 28, BufferedImage.TYPE_INT_ARGB);
							Graphics g = scaledBuffered.getGraphics();
							g.drawImage(scaled, 0, 0, null);
							g.dispose(); // Clean up graphics resources
							
							BufferedImage finalImage = ImageUtil.alphaOffset(scaledBuffered, alpha);
							dropContainer.setIcon(new ImageIcon(finalImage));
							dropContainer.revalidate();
							dropContainer.repaint();
						});
					} 
				} else if (submission.getSubmissionType().equalsIgnoreCase("clog")) {
					// Handle collection log items
					Integer itemId = submission.getClogItemId();
					
					System.out.println("  Clog - itemId=" + itemId);
					
					if (itemId != null && itemManager != null) {
						final AsyncBufferedImage originalImage = itemManager.getImage(itemId, 1, false);
						final float alpha = 1.0f;
						
						// Create a scaled version of the image for initial display
						BufferedImage scaledImage = new BufferedImage(28, 28, BufferedImage.TYPE_INT_ARGB);
						BufferedImage opaque = ImageUtil.alphaOffset(scaledImage, alpha);

						final JLabel clogContainer = PanelElements.createStyledIconContainer();
						clogContainer.setToolTipText(buildSubmissionTooltip(submission, forGroup));
						clogContainer.setIcon(new ImageIcon(opaque));
						iconContainer = clogContainer;

						originalImage.onLoaded(() -> {
							// Scale the loaded image to 16x16
							Image scaled = originalImage.getScaledInstance(28, 28, Image.SCALE_SMOOTH);
							BufferedImage scaledBuffered = new BufferedImage(28, 28, BufferedImage.TYPE_INT_ARGB);
							Graphics g = scaledBuffered.getGraphics();
							g.drawImage(scaled, 0, 0, null);
							g.dispose(); // Clean up graphics resources
							
							BufferedImage finalImage = ImageUtil.alphaOffset(scaledBuffered, alpha);
							clogContainer.setIcon(new ImageIcon(finalImage));
							clogContainer.revalidate();
							clogContainer.repaint();
						});
						
						System.out.println("  Successfully added clog icon");
					} else {
						System.out.println("  Skipped clog - missing data or null itemManager");
					}
				} else if (submission.getSubmissionType().equalsIgnoreCase("pb")) {
					// Handle personal best submissions with image URL
					String imageUrl = submission.getImageUrl();
					System.out.println("  PB - imageUrl=" + imageUrl);
					
					if (imageUrl != null && !imageUrl.isEmpty()) {
						final JLabel pbContainer = PanelElements.createStyledIconContainer();
						pbContainer.setToolTipText(buildSubmissionTooltip(submission, forGroup));
						pbContainer.setText("PB");
						pbContainer.setFont(FontManager.getRunescapeSmallFont());
						pbContainer.setForeground(Color.WHITE);
						iconContainer = pbContainer;
						
						// Load image asynchronously
						CompletableFuture.supplyAsync(() -> {
							try {
								BufferedImage image = ImageIO.read(new URL(imageUrl));
								if (image != null) {
									Image scaled = image.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
									return new ImageIcon(scaled);
								}
							} catch (IOException e) {
								System.err.println("Failed to load PB image: " + e.getMessage());
							}
							return null;
						}).thenAccept(imageIcon -> {
							if (imageIcon != null) {
								SwingUtilities.invokeLater(() -> {
									pbContainer.setText(""); // Remove text
									pbContainer.setIcon(imageIcon);
									pbContainer.revalidate();
									pbContainer.repaint();
								});
							}
						});
						
						System.out.println("  Successfully added PB placeholder");
					} else {
						System.out.println("  Skipped PB - no image URL");
					}
				} else {
					System.out.println("  Unknown submission type: " + submission.getSubmissionType());
				}
				
				
				// Add the icon container if it was created successfully
				if (iconContainer != null) {
					if (submission.getSubmissionImageUrl() != null && !submission.getSubmissionImageUrl().isEmpty()) {
						// Capture submission data for the click listener
						final String submissionTypeForListener = submission.getSubmissionType();
						final String submissionImageUrlForListener = submission.getSubmissionImageUrl();
						final String tooltipForListener = buildSubmissionTooltip(submission, forGroup);
						
						// Add hover effect and click listener
						iconContainer.addMouseListener(new MouseAdapter() {
							@Override
							public void mouseClicked(MouseEvent e) {
								PanelElements.showSubmissionImage(client, submissionTypeForListener, submissionImageUrlForListener, tooltipForListener);
							}
						});
					}
					
					panel.add(iconContainer, c);
					successfullyAdded++;
					
					// Move to next position
					c.gridx++;
					if (c.gridx >= ITEMS_PER_ROW) {
						c.gridx = 0;
						c.gridy++;
					}
				}
			} catch (Exception e) {
				System.err.println("Error processing submission " + i + ": " + e.getMessage());
				e.printStackTrace();
			}
		}
		
		System.out.println("Successfully added " + successfullyAdded + " icons to panel");
        
		// If no icons were added, show a message
		if (successfullyAdded == 0) {
			JLabel debugLabel = new JLabel("No recent submissions to display");
			debugLabel.setForeground(Color.LIGHT_GRAY);
			debugLabel.setFont(FontManager.getRunescapeSmallFont());
			c.gridx = 0;
			c.gridy = 0;
			c.gridwidth = ITEMS_PER_ROW;
			c.gridheight = 2;
			panel.add(debugLabel, c);
		}
		
		panel.revalidate();
		panel.repaint();
		return panel;	
	}
	
	

	public static String buildSubmissionTooltip(RecentSubmission submission, boolean forGroup) {
		try {
			String tooltip = "<html>";
            if (forGroup) {
                if (submission.getSubmissionType().equalsIgnoreCase("pb")) {
                    String pbTime = submission.getPbTime();
                    tooltip += "<b>" + pbTime + "</b> at " + submission.getSourceName() + "<br>" +
                    submission.getPlayerName() + " - new personal best!<br>" + "<br>" +
                    "<i>" + submission.timeSinceReceived() + "</i>";
                } else if (submission.getSubmissionType().equalsIgnoreCase("drop")) {
                    String itemName = submission.getDropItemName();
                    tooltip += "<b>" + itemName + "</b><br>" +
                        submission.getPlayerName() + "<br>" +
                        "from: <i>" + submission.getSourceName() + "</i><br>" +
                        "<i>" + submission.timeSinceReceived() + "</i>";
                } else if (submission.getSubmissionType().equalsIgnoreCase("clog")) {
                    String itemName = submission.getClogItemName();
                    tooltip += submission.getPlayerName() + " - New Collection Log:<br>" +
                        "<b>" + itemName + "</b><br>" +
                        "<i>from: " + submission.getSourceName() + "</i><br>" +
                        "<i>" + submission.timeSinceReceived() + "</i>";
                }
            else {
                if (submission.getSubmissionType().equalsIgnoreCase("pb")) {
                    String pbTime = submission.getPbTime();
                    tooltip += "<b>" + pbTime + "</b> at " + submission.getSourceName() + "<br>" +
                    submission.getPlayerName() + " - new personal best!<br>" + "<br>" +
                    "<i>" + submission.timeSinceReceived() + "</i>";
                } else if (submission.getSubmissionType().equalsIgnoreCase("drop")) {
                    String itemName = submission.getDropItemName();
                    tooltip += "<b>" + itemName + "</b><br>" +
                        submission.getPlayerName() + "<br>" +
                        "from: <i>" + submission.getSourceName() + "</i><br>" +
                        "<i>" + submission.timeSinceReceived() + "</i>";
                } else if (submission.getSubmissionType().equalsIgnoreCase("clog")) {
                    String itemName = submission.getClogItemName();
                    tooltip += submission.getPlayerName() + " - New Collection Log:<br>" +
                        "<b>" + itemName + "</b><br>" +
                        "<i>from: " + submission.getSourceName() + "</i><br>" +
                        "<i>" + submission.timeSinceReceived() + "</i>";
                }
            }
			tooltip += "</html>";
			return tooltip;
        }
		} catch (Exception e) {
			System.err.println("Error building tooltip: " + e.getMessage());
		}
		return submission.getPlayerName() + " - " + submission.getSubmissionType() + " - " + submission.getSourceName();
	}


    public static void showLoadingDialog(JDialog imageDialog, JFrame parentFrame) {
        // Create loading label
        JLabel loadingLabel = new JLabel("Loading lootboard...");
        loadingLabel.setForeground(Color.WHITE);
        loadingLabel.setFont(FontManager.getRunescapeBoldFont());
        loadingLabel.setHorizontalAlignment(JLabel.CENTER);
        loadingLabel.setVerticalAlignment(JLabel.CENTER);
        loadingLabel.setPreferredSize(new Dimension(400, 300));
        loadingLabel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        loadingLabel.setOpaque(true);

        // Add click to close
        addCloseListener(loadingLabel, imageDialog);

        imageDialog.add(loadingLabel);
        imageDialog.pack();
        imageDialog.setLocationRelativeTo(parentFrame);
        imageDialog.setVisible(true);

        // Try to load the image again if it's not cached
        if (cachedLootboardImage == null) {
            System.out.println("Attempting to reload image...");
            CompletableFuture.supplyAsync(() -> {
                try {
                    URL url = new URL(currentImageUrl);
                    return ImageIO.read(url);
                } catch (IOException e) {
                    System.err.println("Failed to reload image: " + e.getMessage());
                    return null;
                }
            }).thenAccept(image -> {
                SwingUtilities.invokeLater(() -> {
                    if (image != null) {
                        System.out.println("Image reloaded successfully, updating dialog");
                        cachedLootboardImage = image;
                        imageDialog.getContentPane().removeAll();
                        displayImageInDialog(imageDialog, image, parentFrame);
                    } else {
                        System.err.println("Failed to reload image");
                        loadingLabel.setText("Failed to load lootboard");
                        loadingLabel.setForeground(Color.RED);
                    }
                });
            });
        }
    }

    

    private static void displayImageInDialog(JDialog imageDialog, BufferedImage originalImage, JFrame parentFrame) {
        // Calculate display size based on screen dimensions
        Dimension screenSize = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        int maxWidth = (int) (screenSize.width * 0.9);
        int maxHeight = (int) (screenSize.height * 0.9);

        int originalWidth = originalImage.getWidth();
        int originalHeight = originalImage.getHeight();

        int displayWidth = originalWidth;
        int displayHeight = originalHeight;

        // Scale down if image is too large for screen
        if (originalWidth > maxWidth || originalHeight > maxHeight) {
            double scaleX = (double) maxWidth / originalWidth;
            double scaleY = (double) maxHeight / originalHeight;
            double scale = Math.min(scaleX, scaleY);

            displayWidth = (int) (originalWidth * scale);
            displayHeight = (int) (originalHeight * scale);
        }

        // Create image label
        JLabel imageLabel = new JLabel();
        imageLabel.setPreferredSize(new Dimension(displayWidth, displayHeight));
        imageLabel.setBorder(new StrokeBorder(new BasicStroke(2), ColorScheme.LIGHT_GRAY_COLOR));
        imageLabel.setHorizontalAlignment(JLabel.CENTER);
        imageLabel.setVerticalAlignment(JLabel.CENTER);
        imageLabel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        imageLabel.setOpaque(true);

        // Scale image if needed
        Image displayImage;
        if (displayWidth == originalWidth && displayHeight == originalHeight) {
            displayImage = originalImage;
        } else {
            displayImage = originalImage.getScaledInstance(displayWidth, displayHeight, Image.SCALE_SMOOTH);
        }

        imageLabel.setIcon(new ImageIcon(displayImage));

        // Add listeners
        addCloseListener(imageLabel, imageDialog);
        addEscapeKeyListener(imageLabel, imageDialog);

        // Set up dialog
        imageDialog.add(imageLabel);
        imageDialog.pack();
        imageDialog.setLocationRelativeTo(parentFrame);

        // Request focus for escape key
        SwingUtilities.invokeLater(() -> imageLabel.requestFocusInWindow());

        System.out.println("Image dialog setup complete");
    }

    // Helper methods to reduce code duplication
    private static void addCloseListener(JLabel label, JDialog dialog) {
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                dialog.dispose();
            }
        });
    }

    private static void addEscapeKeyListener(JLabel label, JDialog dialog) {
        label.setFocusable(true);
        label.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) {
                    dialog.dispose();
                }
            }
        });
    }
    
}
