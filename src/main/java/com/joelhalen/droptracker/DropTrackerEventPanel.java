/*      BSD 2-Clause License

		Copyright (c) 2023, joelhalen

		Redistribution and use in source and binary forms, with or without
		modification, are permitted provided that the following conditions are met:

		1. Redistributions of source code must retain the above copyright notice, this
		list of conditions and the following disclaimer.

		2. Redistributions in binary form must reproduce the above copyright notice,
		this list of conditions and the following disclaimer in the documentation
		and/or other materials provided with the distribution.

		THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
		AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
		IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
		DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
		FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
		DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
		SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
		CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
		OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
		OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.     */
package com.joelhalen.droptracker;

import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;

public class DropTrackerEventPanel extends PluginPanel {
    private final JPanel mainPanel;
    @Inject
    private final DropTrackerPlugin plugin;
    private final DropTrackerPluginConfig config;
    private final ItemManager itemManager;
    private final ChatMessageManager chatMessageManager;

    private static final BufferedImage TOP_LOGO = ImageUtil.loadImageResource(DropTrackerPlugin.class, "toplogo-events.png");

    public DropTrackerEventPanel(DropTrackerPlugin plugin, DropTrackerPluginConfig config,
                                 ItemManager itemManager, ChatMessageManager chatMessageManager) {
        super();
        this.plugin = plugin;
        this.config = config;
        this.itemManager = itemManager;
        this.chatMessageManager = chatMessageManager;
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 10, 10, 10));
        mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(new EmptyBorder(15, 0, 100, 0));
        mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        initializePanel();
    }

    private void initializePanel() {
        SwingUtilities.invokeLater(() -> {
            // Clear the panel
            this.removeAll();

            // Set layout for this panel
            this.setLayout(new BorderLayout());

            // Logo panel setup
            JPanel logoPanel = new JPanel();
            logoPanel.setLayout(new BoxLayout(logoPanel, BoxLayout.X_AXIS));
            logoPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

            // Create an ImageIcon from the TOP_LOGO BufferedImage
            ImageIcon logoIcon = new ImageIcon(TOP_LOGO);
            JLabel logoLabel = new JLabel(logoIcon);

            // Center the logo label in the logo panel
            logoPanel.add(Box.createHorizontalGlue());
            logoPanel.add(logoLabel);
            logoPanel.add(Box.createHorizontalGlue());

            // Add the logo panel to the top of this panel
            this.add(logoPanel, BorderLayout.NORTH);

            // Main content panel
            JPanel mainContentPanel = new JPanel();
            mainContentPanel.setLayout(new BoxLayout(mainContentPanel, BoxLayout.Y_AXIS));
            mainContentPanel.setBorder(new EmptyBorder(15, 0, 10, 0));
            mainContentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

            // Add team information and tile section panels
            JPanel teamInfoPanel = createTeamInfoPanel();
            JPanel tileSectionPanel = createTileSectionPanel();
            mainContentPanel.add(teamInfoPanel);
            mainContentPanel.add(tileSectionPanel);

            // Refresh button
            JButton refreshButton = new JButton("Refresh");
            refreshButton.addActionListener(e -> refreshPanel());

            // Add main content and refresh button to this panel
            this.add(mainContentPanel, BorderLayout.CENTER);
            this.add(refreshButton, BorderLayout.SOUTH);

            // Revalidate and repaint this panel
            this.revalidate();
            this.repaint();
        });
    }
    private JPanel createTeamInfoPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); // Adds padding around the panel

        // Team icon
        JLabel teamIconLabel = new JLabel(new ImageIcon("path/to/team/icon")); // Replace with actual icon path
        panel.add(teamIconLabel, BorderLayout.WEST);

        // Data for the table
        String[] columnNames = {"Info", "Value"};
        Object[][] data = {
                {"<html><b>Your Team</b>:</html>", "Zaros"},
                {"<html><b>Current Tile</b>:</html>", "5"},
                {"<html><b>Points</b>:</html>", "6"},
                {"<html><b>Turn #</b>:</html>", "2"},
                {"<html><b>Members</b>:</html>", "1"}
        };

        // Team info table
        JTable infoTable = new JTable(data, columnNames);
        infoTable.setPreferredScrollableViewportSize(new Dimension(250, 100));
        infoTable.setFillsViewportHeight(true);
        infoTable.setEnabled(false); // Make the table non-editable

        infoTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (column == 0) { // If first column (keys), set foreground to white
                    c.setForeground(Color.WHITE);
                } else { // Else, set foreground to default color
                    c.setForeground(table.getForeground());
                }
                return c;
            }
        });

        JScrollPane scrollPane = new JScrollPane(infoTable); // Add table to a scroll pane
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }
    private JPanel createTileSectionPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        BufferedImage itemImage = itemManager.getImage(20997);
        JLabel imageLabel = new JLabel(new ImageIcon(itemImage));
        JPanel tilePanel = createTilePanel(imageLabel);
        panel.add(tilePanel, BorderLayout.WEST);
        JLabel currentTaskLabel = new JLabel("Current Task");
        JPanel currentTaskTextPanel = new JPanel();
        currentTaskTextPanel.setLayout(new BoxLayout(currentTaskTextPanel, BoxLayout.Y_AXIS));
        currentTaskTextPanel.add(currentTaskLabel, BorderLayout.CENTER);
        panel.add(currentTaskTextPanel, BorderLayout.CENTER);
        // Create a panel for the text
        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setBorder(new EmptyBorder(10, 10, 10, 10)); // Optional padding

        // Add labels
        JLabel titleLabel = new JLabel("Any Raids Unique");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 12)); // Set font and size
        titleLabel.setForeground(Color.WHITE); // Set text color to white

        JLabel descriptionLabel = new JLabel("<html>Obtain a unique from any raid!</html>");
        descriptionLabel.setFont(new Font("Arial", Font.PLAIN, 10));


        JLabel progressLabel = new JLabel("<html><b>Status</b>: 0/1</html>");
        progressLabel.setFont(new Font("Arial", Font.PLAIN, 11));


        // Add labels to the text panel
        textPanel.add(titleLabel);
        textPanel.add(Box.createRigidArea(new Dimension(0, 5))); // Spacing between labels
        textPanel.add(descriptionLabel);
        textPanel.add(Box.createRigidArea(new Dimension(0, 5))); // Spacing between labels
        textPanel.add(progressLabel);

        // Add the text panel to the main panel
        panel.add(textPanel, BorderLayout.CENTER);

        return panel;
    }
    private JPanel createTilePanel(JLabel imageLabel) {
        int padding = 10;
        int tileWidth = imageLabel.getIcon().getIconWidth() + 2 * padding;
        int tileHeight = imageLabel.getIcon().getIconHeight() + 2 * padding;

        JPanel tilePanel = new JPanel();
        tilePanel.setPreferredSize(new Dimension(tileWidth, tileHeight));
        tilePanel.setBackground(Color.GRAY);
        tilePanel.setBorder(BorderFactory.createLineBorder(Color.BLACK, 2));
        tilePanel.setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        tilePanel.add(imageLabel, gbc);

        return tilePanel;
    }

    void refreshPanel() {
        SwingUtilities.invokeLater(() -> {
            mainPanel.removeAll(); // Clear all components from mainPanel
            remove(mainPanel);

            initializePanel(); // Reinitialize the components and add them back to mainPanel

            mainPanel.revalidate(); // Revalidate the layout of the mainPanel
            mainPanel.repaint(); // Repaint the mainPanel to reflect changes
        });
    }
}