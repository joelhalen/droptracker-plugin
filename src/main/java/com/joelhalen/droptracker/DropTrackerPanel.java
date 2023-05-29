package com.joelhalen.droptracker;

import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;


public class DropTrackerPanel extends PluginPanel
{
    @Inject
    private final DropTrackerPlugin plugin;
    private final DropTrackerPluginConfig config;
    @Inject
    private final ItemManager itemManager;

    private final List<DropEntry> entries = new ArrayList<>();
    private final JPanel dropsPanel;

    public DropTrackerPanel(DropTrackerPlugin plugin, DropTrackerPluginConfig config, ItemManager itemManager) {
        super();
        this.plugin = plugin;
        this.config = config;
        this.itemManager = itemManager;

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // Panel for drops
        dropsPanel = new JPanel();
        dropsPanel.setLayout(new BoxLayout(dropsPanel, BoxLayout.Y_AXIS));
        dropsPanel.setBorder(new EmptyBorder(15, 0, 100, 0));
        dropsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        String topLogoUrl = "http://instinctmc.world/upload/toplogo.png";
        URL url = null;
        try {
            url = new URL(topLogoUrl);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        BufferedImage urlImage = null;
        try {
            urlImage = ImageIO.read(url);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Create an ImageIcon from the URL image
        ImageIcon urlIcon = new ImageIcon(urlImage);
        JLabel urlLabel = new JLabel(urlIcon);
        dropsPanel.add(urlLabel);
        if(config.serverId().equals("")) {
            JLabel descText = new JLabel("<html>Welcome to the DropTracker!<br><br>In order to start tracking drops,<br>" +
                    "your server must be added<br> to our database. Contact a<br>member of your clan's<br> staff team to get set up!</html>");
            dropsPanel.add(descText);
        } else {
            JLabel descText = new JLabel("<html>Welcome to the <b>DropTracker</b> plugin!<br><br><em>This plugin is under construction.</em><br><br>Your Server ID:<b><em>" + config.serverId() +
                    "<br></b></em><br>To submit a drop, enter<br>" +
                    "any <em>clan members</em> who were <br>" +
                    "with you <b>on their own line</b><br>" +
                    " in the text field.<br>" +
                    "Then, select how many <em>non-<br>" +
                    "members</em><br>" +
                    "were involved in the drop.<br>" +
                    "<br>Once you press submit, your<br>" +
                    "drop will automatically be sent!</html>");
            dropsPanel.add(descText);
        }
        JScrollPane scrollPane = new JScrollPane(dropsPanel);
        add(scrollPane, BorderLayout.CENTER);
    }


    public void addDrop(DropEntry entry) {
        SwingUtilities.invokeLater(() -> {
            // Add the entry to the list
            entries.add(entry);
            // Update the panel
            refreshPanel();
        });
    }
    public void clear() {
        SwingUtilities.invokeLater(() -> {
            entries.clear();
            refreshPanel();
        });
    }

    void refreshPanel() {
        SwingUtilities.invokeLater(() -> {
            // Clear the panel
            dropsPanel.removeAll();

            String topLogoUrl = "http://instinctmc.world/upload/toplogo.png";
            URL url;
            try {
                url = new URL(topLogoUrl);
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
            BufferedImage urlImage;
            try {
                urlImage = ImageIO.read(url);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            // Create an ImageIcon from the URL image
            ImageIcon urlIcon = new ImageIcon(urlImage);
            JLabel urlLabel = new JLabel(urlIcon);
            dropsPanel.add(urlLabel);

            JLabel descText;
            if(config.serverId().equals("")) {
                descText = new JLabel("<html>Welcome to the DropTracker!<br><br>In order to start tracking drops,<br>" +
                        "your server must be added<br> to our database. Contact a<br>member of your clan's<br> staff team to get set up!</html>");
                dropsPanel.add(descText);
            } else {
                descText = new JLabel("<html>Welcome to the <b>DropTracker</b> plugin!<br><br><em>This plugin is under construction.</em><br><br>Your Server ID:<b><em>" + config.serverId() +
                        "<br></b></em><br>To submit a drop, enter<br>" +
                        "any <em>clan members</em> who were <br>" +
                        "with you <b>on their own line</b><br>" +
                        " in the text field.<br>" +
                        "Then, select how many <em>non-<br>" +
                        "members</em><br>" +
                        "were involved in the drop.<br>" +
                        "<br>Once you press submit, your<br>" +
                        "drop will automatically be sent!</html>");
                dropsPanel.add(descText);
            }

            // Add each drop to the panel
            for (DropEntry entry : entries) {
                // Fetch image for the item
                BufferedImage itemImage = itemManager.getImage(entry.getItemId());
                JLabel imageLabel = new JLabel(new ImageIcon(itemImage));
                String itemName = entry.getItemName();
                int geValue = entry.getGeValue();

                JLabel numNameTextLabel = new JLabel("Item: "+itemName);
                JLabel valueTextLabel = new JLabel("Value: " + String.valueOf(geValue) + " gp");

                JTextArea nameField = new JTextArea(2,10);
                nameField.setToolTipText("<html>Enter clan members who were involved in the split.<br>They must be tracked in your server!</html>");
                JScrollPane scrollPane = new JScrollPane(nameField);

                Integer[] nonMemberOptions = new Integer[20];
                for (int i = 0; i < 20; i++) {
                    nonMemberOptions[i] = i + 1; // Fill array with numbers 1-20
                }
                JComboBox<Integer> nonMemberDropdown = new JComboBox<>(nonMemberOptions);
                nonMemberDropdown.setToolTipText("Select # of non-members involved in the drop.");
                nonMemberDropdown.setPreferredSize((new Dimension(45,25)));

                JButton submitButton = new JButton("Submit");
                JComboBox<Integer> finalNonMemberDropdown = nonMemberDropdown;
                JTextArea finalNameField = nameField;
                submitButton.addActionListener(e -> {
                    // Get values from text area and combo box
                    String names = finalNameField.getText();  // Extract names entered by player
                    int nonMemberCount = (Integer) finalNonMemberDropdown.getSelectedItem();  // Get non-member count from dropdown

                    entry.setClanMembers(names);
                    entry.setNonMemberCount(nonMemberCount);

                    submitDrop(entry);
                });
                submitButton.setPreferredSize(new Dimension(90, 20));

                JPanel nameMemberPanel = new JPanel();
                nameMemberPanel.add(scrollPane);
                nameMemberPanel.add(nonMemberDropdown);

                JPanel entryPanel = new JPanel();
                entryPanel.setLayout(new BoxLayout(entryPanel, BoxLayout.Y_AXIS));
                entryPanel.add(imageLabel);
                entryPanel.add(numNameTextLabel);
                entryPanel.add(valueTextLabel);
                entryPanel.add(nameMemberPanel);
                entryPanel.add(submitButton);

                dropsPanel.add(entryPanel);
            }
            dropsPanel.revalidate();
            dropsPanel.repaint();
        });
    }


    private void submitDrop(DropEntry entry) {
        SwingUtilities.invokeLater(() -> {
            String itemName = entry.getItemName();
            String playerName = entry.getPlayerName();
            String npcName = entry.getNpcOrEventName();
            int value = entry.getGeValue();
            int itemId = entry.getItemId();
            int npcLevel = entry.getNpcCombatLevel();
            int quantity = entry.getQuantity();
            int haValue = entry.getHaValue();
            int nonMembers = entry.getNonMemberCount();
            String memberList = entry.getClanMembers();
            // `` Drop is removed from the entries list; and the panel is refreshed without it.
            // `` TODO: Implement a way of sending the actual data entered by the user to the webhook (will need a new method)
            // `` This way we can remove the necessity for verification later on Discord and handle the webhook accordingly.
            plugin.sendEmbedWebhook(playerName, npcName, npcLevel, itemId, quantity, value, haValue);
            System.out.println("You've submitted an item: " + itemName + "Tagged: " + memberList + " with non members: "+ nonMembers);
            entries.remove(entry);
            refreshPanel();
        });
    }

}