package com.joelhalen.droptracker;

import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.NumberFormat;
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
        JLabel descText;
        if(config.serverId().equals("")) {
            descText = new JLabel("<html>Welcome to the DropTracker!<br><br>In order to start tracking drops,<br>" +
                    "your server must be added<br> to our database. Contact a<br>member of your clan's<br> staff team to get set up!</html>");
            dropsPanel.add(descText);
        } else {
            String serverName = plugin.getServerName(config.serverId());
            int minimumClanLoot = plugin.getServerMinimumLoot(config.serverId());
            NumberFormat clanLootFormat = NumberFormat.getNumberInstance();
            String minimumLootString = clanLootFormat.format(minimumClanLoot);
            descText = new JLabel("<html>Welcome to the <b>DropTracker</b> plugin!<br><br><em>This plugin is under construction.</em><br><br>Your Clan: <b>" + serverName +
                    "</b><br>Minimum value: <b>" + minimumLootString +
                    "gp<br></b><br>To submit a drop, enter " +
                    "any <em>clan<br>members</em> who were " +
                    "with you <b>on their <br>own line</b>" +
                    " in the text field.<br>" +
                    "Then, select how many " +
                    "<em>non-members</em> were involved" +
                    " in the drop.<br><br>" +
                    "<br>Once you press submit, your<br>" +
                    "drop will automatically be sent!</html>");
            dropsPanel.add(descText);
        }


        add(dropsPanel, BorderLayout.CENTER);
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
            //re-set the layout and styles
            dropsPanel.setLayout(new BoxLayout(dropsPanel, BoxLayout.Y_AXIS));
            dropsPanel.setBorder(new EmptyBorder(15, 0, 100, 0));
            dropsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

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
                String serverName = plugin.getServerName(config.serverId());
                int minimumClanLoot = plugin.getServerMinimumLoot(config.serverId());
                NumberFormat clanLootFormat = NumberFormat.getNumberInstance();
                String minimumLootString = clanLootFormat.format(minimumClanLoot);
                descText = new JLabel("<html>Welcome to the <b>DropTracker</b> plugin!<br><br><em>This plugin is under construction.</em><br><br>Your Clan: <b>" + serverName +
                        "</b><br>Minimum value: <b>" + minimumLootString +
                        "gp<br></b><br>To submit a drop, enter " +
                        "any <em>clan<br>members</em> who were " +
                        "with you <b>on their <br>own line</b>" +
                        " in the text field.<br>" +
                        "Then, select how many " +
                        "<em>non-members</em> were involved" +
                        " in the drop.<br><br>" +
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

                JLabel itemTextLabel = new JLabel("<html>Item: "+itemName+"<br>Value: " + String.valueOf(geValue) + "gp</html>");

                JPanel nameFieldPanel = new JPanel(new BorderLayout());
                JLabel nameLabel = new JLabel("Names:");
                nameFieldPanel.add(nameLabel, BorderLayout.NORTH);

                JTextArea nameField = new JTextArea(2,10);
                nameField.setToolTipText("<html>Enter clan members who were involved in the split.<br>They must be tracked in your server!</html>");
                JScrollPane scrollPane = new JScrollPane(nameField);
                nameFieldPanel.add(scrollPane, BorderLayout.CENTER);

                Integer[] nonMemberOptions = new Integer[21];
                for (int i = 0; i <= 20; i++) {
                    nonMemberOptions[i] = i; // Fill array with numbers 0-20
                }

                JPanel nonMemberDropdownPanel = new JPanel(new BorderLayout());
                JLabel nonMemberLabel = new JLabel("Non-members:");
                nonMemberDropdownPanel.add(nonMemberLabel, BorderLayout.NORTH);

                JComboBox<Integer> nonMemberDropdown = new JComboBox<>(nonMemberOptions);
                nonMemberDropdown.setToolTipText("Select # of non-members involved in the drop.");
                nonMemberDropdown.setPreferredSize((new Dimension(45,25)));
                nonMemberDropdownPanel.add(nonMemberDropdown, BorderLayout.CENTER);

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
                submitButton.setAlignmentX(Component.CENTER_ALIGNMENT);
                submitButton.setAlignmentY(Component.CENTER_ALIGNMENT);
                JPanel nameMemberPanel = new JPanel();
                nameMemberPanel.add(nameFieldPanel);
                nameMemberPanel.add(nonMemberDropdownPanel);
                //Panels for each entry
                JPanel entryPanel = new JPanel();
                entryPanel.setLayout(new BoxLayout(entryPanel, BoxLayout.Y_AXIS));
                entryPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                entryPanel.setBackground(Color.DARK_GRAY);
                Border outerBorder = new MatteBorder(1, 1, 1, 1, Color.BLACK);
                Border innerBorder = new EmptyBorder(0, 0, 10, 0);
                CompoundBorder compoundBorder = new CompoundBorder(outerBorder, innerBorder);
                entryPanel.setBorder(compoundBorder);
                // Place the item, value, and loot inside an object together
                JPanel itemContainer = new JPanel();
                itemContainer.add(imageLabel);
                // Change the alignment of objects inside the itemContainer
                imageLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                itemTextLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                itemContainer.add(itemTextLabel);
                entryPanel.add(itemContainer);
                //entryPanel.add(numNameTextLabel);
                //entryPanel.add(valueTextLabel);
                entryPanel.add(nameMemberPanel);
                entryPanel.add(submitButton);
                entryPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
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
            // data is sent to another method inside main class; which sends an embed with the entered information for this item
            plugin.sendConfirmedWebhook(playerName, npcName, npcLevel, itemId, itemName, memberList, quantity, value, nonMembers);
            //System.out.println("Sent a webhook with your " + itemName);
            entries.remove(entry);
            refreshPanel();
        });
    }

}