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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class DropTrackerEventPanel extends PluginPanel {
    private final JPanel mainPanel;
    @Inject
    private final DropTrackerPlugin plugin;
    private final DropTrackerPluginConfig config;
    private final ItemManager itemManager;
    private JLabel currentTaskLabel;
    private JLabel progressLabel;
    private JLabel descriptionLabel;
    private JTable infoTable;
    private JLabel imageLabel;
    private static final BufferedImage TOP_LOGO = ImageUtil.loadImageResource(DropTrackerPlugin.class, "toplogo-events.png");

    public DropTrackerEventPanel(DropTrackerPlugin plugin, DropTrackerPluginConfig config,
                                 ItemManager itemManager, ChatMessageManager chatMessageManager) {
        super();
        this.plugin = plugin;
        this.config = config;
        this.itemManager = itemManager;
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
        refreshEventStatus();
    }
    private void updateTeamInfoTable(Map<String, String> eventData) {
        String[] columnNames = {"Info", "Value"};
        Object[][] newData = {
                {"<html><b>Your Team</b>:</html>", eventData.getOrDefault("teamName", "N/A")},
                {"<html><b>Current Tile</b>:</html>", eventData.getOrDefault("currentTile", "N/A")},
                {"<html><b>Points</b>:</html>", eventData.getOrDefault("currentPoints", "N/A")},
                {"<html><b>Turn #</b>:</html>", eventData.getOrDefault("currentTurnNumber", "N/A")},
                {"<html><b>Members</b>:</html>", eventData.getOrDefault("teamMembers", "N/A")}
        };

        SwingUtilities.invokeLater(() -> {
            DefaultTableModel model = new DefaultTableModel(newData, columnNames);
            infoTable.setModel(model);
        });
    }
    private void updateTileSectionPanel(Map<String, String> eventData) {
        SwingUtilities.invokeLater(() -> {
            String currentTask = eventData.getOrDefault("currentTask", "N/A");
            currentTaskLabel.setText(currentTask);
            String required_amount = eventData.getOrDefault("currentTaskAmt", "1");
            descriptionLabel.setText("<html>" + eventData.getOrDefault("currentTaskDescription", "Obtain" + required_amount + " x " + currentTask + ".") + "</html>");
            progressLabel.setText("<html><b>Status</b>: " + eventData.getOrDefault("currentTaskProgress", "N/A") + "/" + required_amount + ".</html>");
            try {
                imageLabel.setIcon(new ImageIcon(new URL(eventData.getOrDefault("currentTaskImage","https://www.droptracker.io/img/dt-logo.png"))));
                imageLabel.setPreferredSize(new Dimension(50,60));
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        });
    }
    public void refreshEventStatus() {
        fetchEventStatus().thenAccept(eventData -> SwingUtilities.invokeLater(() -> {
            updateTeamInfoTable(eventData);
            updateTileSectionPanel(eventData);
            // Update other UI components here as needed
        }));
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


        infoTable = new JTable(data, columnNames);
        infoTable.setPreferredScrollableViewportSize(new Dimension(250, 100));
        infoTable.setFillsViewportHeight(true);
        infoTable.setEnabled(false);

        infoTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (column == 0) {
                    c.setForeground(Color.WHITE);
                } else {
                    c.setForeground(table.getForeground());
                }
                return c;
            }
        });

        JScrollPane scrollPane = new JScrollPane(infoTable);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }
    public CompletableFuture<Map<String, String>> fetchEventStatus() {
        return CompletableFuture.supplyAsync(() -> {
            Long discordServerId = Long.valueOf(config.serverId());
            Map<String, String> eventData = new HashMap<>();
            try {
                String playerName = plugin.getPlayerName();

                playerName = URLEncoder.encode(playerName, StandardCharsets.UTF_8.toString());
                URL url = new URL("https://www.droptracker.io/admin/api/events/get_current_status.php?" + discordServerId + "&player_name=" + playerName);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String line;
                StringBuilder builder = new StringBuilder();

                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }

                reader.close();
                JsonParser parser = new JsonParser();
                JsonObject jsonResponse = parser.parse(builder.toString()).getAsJsonObject();
                String value = jsonResponse.get("key").getAsString();
                // TODO: Build a response on the server containing all of the relevant data for the current game status
                eventData.put("currentTile", jsonResponse.get("team_current_tile").getAsString());
                eventData.put("teamName", jsonResponse.get("team_name").getAsString());
                eventData.put("currentPoints", jsonResponse.get("team_current_points").getAsString());
                eventData.put("currentTask", jsonResponse.get("current_task_string").getAsString());
                eventData.put("currentTaskDescription", jsonResponse.get("current_task_description").getAsString());
                eventData.put("currentPlayerPoints", jsonResponse.get("current_player_points").getAsString());
                eventData.put("teamMembers", jsonResponse.get("current_team_members").getAsString());
                eventData.put("currentTaskAmt", jsonResponse.get("current_task_quantity_required").getAsString());
                eventData.put("currentTaskProgress", jsonResponse.get("current_task_progress").getAsString());
                eventData.put("currentTaskItemsObtained", jsonResponse.get("current_task_items_obtained").getAsString());
                eventData.put("currentTurnNumber", jsonResponse.get("current_turn_number").getAsString());
                eventData.put("teamRank", jsonResponse.get("team_current_placement").getAsString());
                eventData.put("allTeamLocations", jsonResponse.get("all_team_locations").getAsString());
                eventData.put("currentEffects", jsonResponse.get("current_effects").getAsString());
                eventData.put("currentTaskImage", jsonResponse.get("current_task_image").getAsString());

            } catch (IOException e) {
                e.printStackTrace();
            }
            return eventData;
        });
    }
    private JPanel createTileSectionPanel() {
        /*  Creates the panel for the currently-assigned task to appear on.  */
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        BufferedImage itemImage = itemManager.getImage(20997);
        imageLabel = new JLabel(new ImageIcon(itemImage));
        JPanel tilePanel = createTilePanel(imageLabel);
        panel.add(tilePanel, BorderLayout.WEST);
        JLabel currentTaskTitleLabel = new JLabel("Current Task"); // Update class field
        progressLabel = new JLabel("<html><b>Status</b>: 0/1</html>");
        JPanel currentTaskTextPanel = new JPanel();
        currentTaskTextPanel.setLayout(new BoxLayout(currentTaskTextPanel, BoxLayout.Y_AXIS));
        currentTaskTextPanel.add(currentTaskTitleLabel, BorderLayout.CENTER);
        panel.add(currentTaskTextPanel, BorderLayout.CENTER);
        // Create a panel for the text
        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setBorder(new EmptyBorder(10, 10, 10, 10)); // Optional padding

        // Add labels
        currentTaskLabel = new JLabel("Unknown...");
        currentTaskLabel.setFont(new Font("Arial", Font.PLAIN, 12));

        descriptionLabel = new JLabel("<html><i>Your task could not be loaded...</i></html>");
        descriptionLabel.setFont(new Font("Arial", Font.PLAIN, 10));


        progressLabel = new JLabel("<html>Please try again later.</html>");
        progressLabel.setFont(new Font("Arial", Font.PLAIN, 11));


        // Add labels to the text panel
        textPanel.add(currentTaskLabel);
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
            refreshEventStatus();
            mainPanel.removeAll(); // Clear all components from mainPanel
            remove(mainPanel);

            initializePanel(); // Reinitialize the components and add them back to mainPanel

            mainPanel.revalidate(); // Revalidate the layout of the mainPanel
            mainPanel.repaint(); // Repaint the mainPanel to reflect changes
        });
    }
}