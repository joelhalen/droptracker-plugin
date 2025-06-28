package io.droptracker.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;

public class PanelElements {

    private static ImageIcon COLLAPSED_ICON;
    private static ImageIcon EXPANDED_ICON;

    static {
        Image collapsedImg = ImageUtil.loadImageResource(DropTrackerPlugin.class, "util/collapse.png");
        Image expandedImg = ImageUtil.loadImageResource(DropTrackerPlugin.class, "util/expand.png");
        Image collapsedResized = collapsedImg.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
        Image expandedResized = expandedImg.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
        COLLAPSED_ICON = new ImageIcon(collapsedResized);
        EXPANDED_ICON = new ImageIcon(expandedResized);
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

}
