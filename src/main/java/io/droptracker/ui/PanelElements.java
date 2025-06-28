package io.droptracker.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.StrokeBorder;

import com.google.inject.Inject;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import net.runelite.api.Client;

public class PanelElements {

    @Inject
    private Client client;

    private static ImageIcon COLLAPSED_ICON;
    private static ImageIcon EXPANDED_ICON;
    public static BufferedImage cachedLootboardImage;
    private static String currentImageUrl = "https://www.droptracker.io/img/clans/2/lb/lootboard.png";
    private static Integer cachedGroupId = null; // Track which group's lootboard is currently cached

    static {
        Image collapsedImg = ImageUtil.loadImageResource(DropTrackerPlugin.class, "util/collapse.png");
        Image expandedImg = ImageUtil.loadImageResource(DropTrackerPlugin.class, "util/expand.png");
        Image collapsedResized = collapsedImg.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
        Image expandedResized = expandedImg.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
        COLLAPSED_ICON = new ImageIcon(collapsedResized);
        EXPANDED_ICON = new ImageIcon(expandedResized);
        
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

    // Method to show lootboard popup for a specific group ID
    public static void showLootboardForGroup(Client client, int groupId) {
        final JFrame parentFrame = getParentFrame(client);
        JDialog imageDialog = new JDialog(parentFrame, "Group " + groupId + " Lootboard", true);
        imageDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        // Check if we already have the right group cached
        if (cachedLootboardImage != null && cachedGroupId != null && cachedGroupId == groupId) {
            System.out.println("Displaying cached lootboard for group " + groupId);
            displayImage(imageDialog, cachedLootboardImage, parentFrame);
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
        imageDialog.setVisible(true);

        // Load the image with callback to update dialog when done
        loadLootboardForGroup(groupId, () -> {
            if (cachedLootboardImage != null) {
                System.out.println("Group " + groupId + " image loaded, updating dialog");
                imageDialog.getContentPane().removeAll();
                displayImage(imageDialog, cachedLootboardImage, parentFrame);
            } else {
                System.err.println("Failed to load group " + groupId + " lootboard");
                loadingLabel.setText("Failed to load group " + groupId + " lootboard");
                loadingLabel.setForeground(Color.RED);
                imageDialog.revalidate();
                imageDialog.repaint();
            }
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
        // The HTML structure:
        // <html> - Required wrapper for HTML content in JLabel.
        // <font color='orange'>!</font> - The first, normal-sized orange exclamation
        // mark.
        // <sup> - Superscript tag.
        // <font color='orange'>!</font> - The second, superscripted orange exclamation
        // mark.
        // </sup>
        // </html>
        String htmlText = "<html><font color='orange'>!</font><sup><font color='orange'>!</font></sup></html>";

        JLabel warningLabel = new JLabel(htmlText);

        // Optional: You might want to adjust the font for better visibility
        // For example, making it a bit larger and bold if it's meant to be prominent.
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

    public static void showLoadingDialogForGroup(JDialog imageDialog, JFrame parentFrame, int groupId) {
        // Create loading label
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
        imageDialog.setVisible(true);

        // Wait for the async load to complete and check periodically
        CompletableFuture.runAsync(() -> {
            int attempts = 0;
            while (attempts < 50) { // Wait up to 5 seconds (50 * 100ms)
                try {
                    Thread.sleep(100);
                    if (cachedLootboardImage != null && cachedGroupId != null && cachedGroupId == groupId) {
                        SwingUtilities.invokeLater(() -> {
                            System.out.println("Group " + groupId + " image loaded, updating dialog");
                            imageDialog.getContentPane().removeAll();
                            displayImageInDialog(imageDialog, cachedLootboardImage, parentFrame);
                        });
                        return;
                    }
                    attempts++;
                } catch (InterruptedException e) {
                    break;
                }
            }
            
            // Timeout or failure
            SwingUtilities.invokeLater(() -> {
                System.err.println("Failed to load group " + groupId + " lootboard");
                loadingLabel.setText("Failed to load group " + groupId + " lootboard");
                loadingLabel.setForeground(Color.RED);
            });
        });
    }

    public static void displayImage(JDialog imageDialog, BufferedImage image, JFrame parentFrame) {
        displayImageInDialog(imageDialog, image, parentFrame);
        imageDialog.setVisible(true);
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

    public static void showCachedImagePopup(Client client) {
        final JFrame parentFrame = getParentFrame(client);

        JDialog imageDialog = new JDialog(parentFrame, "Group Lootboard", true);
        imageDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        if (cachedLootboardImage != null) {
            System.out.println("Displaying cached lootboard image");
            displayImage(imageDialog, cachedLootboardImage, parentFrame);
        } else {
            System.out.println("No cached image available, showing loading message");
            showLoadingDialog(imageDialog, parentFrame);
        }
    }
    
}
