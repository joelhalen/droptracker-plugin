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

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;


public class DropTrackerPanel extends PluginPanel
{
    @Inject
    private final DropTrackerPlugin plugin;
    private final DropTrackerPluginConfig config;
    @Inject
    private final ItemManager itemManager;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    @Inject
    private Client client;
    private static final Logger log = LoggerFactory.getLogger(DropTrackerPlugin.class);
    private final List<DropEntry> entries = new ArrayList<>();
    private final JPanel dropsPanel;
    public String localAuthKey = null;
    public String localPlayerName = null;
    private static final BufferedImage TOP_LOGO = ImageUtil.loadImageResource(DropTrackerPlugin.class, "toplogo.png");

    public DropTrackerPanel(DropTrackerPlugin plugin, DropTrackerPluginConfig config, ItemManager itemManager, ChatMessageManager chatMessageManager) {
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
        // Create an ImageIcon from the TOP_LOGO BufferedImage
        ImageIcon logoIcon = new ImageIcon(TOP_LOGO);
        JLabel logoLabel = new JLabel(logoIcon);
        // Add the logo to the top of panel
        dropsPanel.add(logoLabel);
        /* Add a button to refresh the panel  */
        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> refreshPanel());
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
        buttonPanel.add(Box.createHorizontalGlue());
        buttonPanel.add(refreshButton);
        buttonPanel.add(Box.createHorizontalGlue());
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(buttonPanel);
        topPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        topPanel.add(logoLabel, BorderLayout.NORTH);
        topPanel.add(buttonPanel, BorderLayout.CENTER);
        dropsPanel.add(topPanel, BorderLayout.NORTH);
        dropsPanel.setLayout(new BoxLayout(dropsPanel, BoxLayout.Y_AXIS));
        JLabel descText;
        String playerName = plugin.getLocalPlayerName();
        // If the server ID is empty OR the player has not entered an authentication key:
        if(config.serverId().equals("") || !config.authKey().equals("")) {
            descText = new JLabel("<html>Welcome to the DropTracker!<br><br>In order to start tracking drops,<br>" +
                    "your server must be added<br> to our database. Contact a<br>member of your clan's<br> staff team to get set up!</html>");
            dropsPanel.add(descText);
        } else {
            // If they entered a server ID, check if the auth key is empty
            // We also handle if the auth key does not match the expected value here.
            if(localAuthKey != null && localPlayerName == plugin.getLocalPlayerName()) {
                // do nothing if they have a localAuthKey stored with the correct playername
            } else if (config.authKey().equals("") || config.authKey().equals(checkAuthKey(playerName, config.serverId(), config.authKey()))) {
                descText = new JLabel("<html>You have not entered an <br>" +
                        "authentication token into the DropTracker config.<br>" +
                        "<br>You should have been DMed one by @DropTracker#4420<br><br>" +
                        "If not, send the discord bot a DM<br>Saying: `auth`</html>");
                dropsPanel.add(descText);
            } else {
                String serverName = plugin.getServerName(config.serverId());
                int minimumClanLoot = plugin.getServerMinimumLoot(config.serverId());
                Long discordServerId = plugin.getClanDiscordServerID(config.serverId());
                NumberFormat clanLootFormat = NumberFormat.getNumberInstance();
                String minimumLootString = clanLootFormat.format(minimumClanLoot);
                String playerLoot;
                String formattedServerTotal = "0";
                if (config.serverId() != "") {
                    String serverLootTotal = fetchServerLootTotal();
                    if (serverLootTotal == "Invalid server ID.") {
                        formattedServerTotal = "0";
                    } else {
                        formattedServerTotal = formatNumber(Double.parseDouble(serverLootTotal));
                    }
                }
                if (playerName != null) {
                    playerLoot = fetchPlayerLootFromPHP(config.serverId(), playerName);
                    playerLoot = formatNumber(Double.parseDouble(playerLoot));
                } else {
                    playerLoot = "not signed in!";
                    formattedServerTotal = "0";
                }
                String[][] data = {
                        {"Your Clan", serverName},
                        {"Minimum value", minimumLootString + " gp"},
                        {"Your total loot", playerLoot},
                        {"Clan Total:", formattedServerTotal + " gp"},
                };

                String[] columnNames = {"Key", "Value"};
                DefaultTableModel model = new DefaultTableModel(data, columnNames) {
                    @Override
                    public boolean isCellEditable(int row, int column) {
                        // This causes all cells to be not editable
                        return false;
                    }
                };
                JTable table = new JTable(model);
                table.setPreferredScrollableViewportSize(new Dimension(500, 70));
                table.setFillsViewportHeight(true);
                // Set custom renderer to bold the keys in the table (is there a better way to do this?)
                table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
                    Font originalFont = null;

                    @Override
                    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                        Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                        if (originalFont == null) {
                            originalFont = c.getFont();
                        }
                        c.setFont(originalFont.deriveFont(Font.BOLD));
                        return c;
                    }
                });

                dropsPanel.add(table);
                descText = new JLabel("<html>To submit a drop, enter " +
                        "any <em>clan<br>members</em> who were " +
                        "with you <b>on their <br>own line</b>" +
                        " in the text field.<br>" +
                        "Then, select how many " +
                        "<em>non-members</em> were involved" +
                        " in the drop.<br><br>" +
                        "<br>Once you press submit, your<br>" +
                        "drop will automatically be sent!" +
                        "</html>");
                descText.setAlignmentX(Component.LEFT_ALIGNMENT);
                dropsPanel.add(descText);
            }
        }


        add(dropsPanel, BorderLayout.CENTER);
    }

    public static String formatNumber(double number) {
        if (number == 0) {
            return "0";
        }
        String[] units = new String[] { "", "K", "M", "B", "T" };
        int unit = (int) Math.floor((Math.log10(number) / 3)); // Determine the unit (K, M, B, etc.)

        if (unit >= units.length) unit = units.length - 1; // Prevent array index out of bounds error

        double num = number / Math.pow(1000, unit);
        DecimalFormat df = new DecimalFormat("#.#");
        String formattedNum = df.format(num);
        return formattedNum + units[unit];
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

    public void checkAuthKeyAsync(String playerName, String serverId, String authKey, Consumer<String> callback) {
        executorService.submit(() -> {
            if(playerName != null && serverId != "" && authKey != "") {
                String result = checkAuthKey(playerName, serverId, authKey);
                if (result.equals("New token generated.")) {
                    callback.accept("discord");
                } else if (result.equals("Authenticated.")) {
                    callback.accept("yes");
                } else {
                    callback.accept(result);
                }
            } else {
                callback.accept("invalid parameters.");
        }
        });
    }

    public void shutdownExecutorService() {
        executorService.shutdown();
    }
    public String checkAuthKey(String playerName, String serverId, String authKey) {
        Long discordServerId = plugin.getClanDiscordServerID(serverId);
        int port = 6000;
        try {
            URL url = new URL("http://instinctmc.world/data/uuid.php");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setDoOutput(true);

            String message = String.format("player_name=%s&server_id=%s&auth_key=%s", playerName, discordServerId, authKey);
            connection.getOutputStream().write(message.getBytes("UTF-8"));

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String response = reader.readLine();
            reader.close();
            connection.disconnect();
            if(response.equals("<br />")) {
                response = "yes";
            }
            return response;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    void refreshPanel() {
        SwingUtilities.invokeLater(() -> {
            // Clear the panel
            dropsPanel.removeAll();
            //re-set the layout and styles
            String playerName = plugin.getLocalPlayerName();
            dropsPanel.setLayout(new BoxLayout(dropsPanel, BoxLayout.Y_AXIS));
            AtomicReference<String> playerLoot = new AtomicReference<>("none");
            String formattedServerTotal = "0";
            //if they have a playerName assigned:
            if (playerName != null) {
                String finalPlayerName = playerName;
                // if the localAuthKey is stored; and the playerName is still the same, don't update.
                if(localAuthKey != null && localAuthKey.equals(config.authKey())) {
                    // do not perform an authentication check if the auth key is validated & stored, and their name is correct.
                } else {
                    checkAuthKeyAsync(playerName, config.serverId(), config.authKey(), (authRes) -> {
                            SwingUtilities.invokeLater(() -> {
                                if (authRes.equals("discord")) {
                                    // This response means they did not have a auth key before, but had one generated just now.
                                    ChatMessageBuilder messageResponse = new ChatMessageBuilder();
                                    messageResponse.append(ChatColorType.HIGHLIGHT).append("[")
                                            .append("DropTracker")
                                            .append("]")
                                            .append(ChatColorType.NORMAL)
                                            .append("A new authentication token has been generated for you! Check your discord.");
                                    plugin.chatMessageManager.queue(QueuedMessage.builder()
                                            .type(ChatMessageType.CONSOLE)
                                            .runeLiteFormattedMessage(messageResponse.build())
                                            .build());
                                } else if (!authRes.equals("yes")) {
                                    // in any other case, if the response doesn't say "yes", the auth key is invalid.
                                    ChatMessageBuilder messageResponse = new ChatMessageBuilder();
                                    messageResponse.append(ChatColorType.HIGHLIGHT).append("[")
                                            .append("DropTracker")
                                            .append("] You have entered an invalid authentication")
                                            .append(" token in the configuration for DropTracker.");
                                    plugin.chatMessageManager.queue(QueuedMessage.builder()
                                            .type(ChatMessageType.CONSOLE)
                                            .runeLiteFormattedMessage(messageResponse.build())
                                            .build());
                                } else {
                                    // authentication has succeeded, proceed.
                                    localAuthKey = config.authKey();
                                    if(plugin.getLocalPlayerName() != null) {
                                        localPlayerName = plugin.getLocalPlayerName();
                                    }
                                    CompletableFuture.runAsync(() -> {
                                        String loot = fetchPlayerLootFromPHP(config.serverId(), finalPlayerName);
                                        SwingUtilities.invokeLater(() -> {
                                            playerLoot.set(formatNumber(Double.parseDouble(loot)));
                                        });
                                    });
                                }
                            });
                        });
                }
            } else {
                playerLoot.set("not signed in!");
                formattedServerTotal = "0";
            }
            if(config.authKey().equals("")) {
                return;
            }
            //Remove notification for successful authentications to reduce spam.
//            ChatMessageBuilder messageResponse = new ChatMessageBuilder();
//            messageResponse.append(ChatColorType.HIGHLIGHT).append("[")
//                    .append("DropTracker")
//                    .append("] ")
//                    .append(ChatColorType.NORMAL)
//                    .append("Authenticated.");
//                plugin.chatMessageManager.queue(QueuedMessage.builder()
//                        .type(ChatMessageType.CONSOLE)
//                        .runeLiteFormattedMessage(messageResponse.build())
//                        .build());
            dropsPanel.setBorder(new EmptyBorder(15, 0, 100, 0));
            dropsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
            // Create an ImageIcon from the TOP_LOGO BufferedImage
            ImageIcon logoIcon = new ImageIcon(TOP_LOGO);
            JLabel logoLabel = new JLabel(logoIcon);
            // Add the logo to the top of panel
            dropsPanel.add(logoLabel);
            /* Add a button to refresh the panel incase data is inaccurate */
            JButton refreshButton = new JButton("Refresh");
            refreshButton.addActionListener(e -> refreshPanel());
            JPanel buttonPanel = new JPanel();
            buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
            buttonPanel.add(Box.createHorizontalGlue());
            buttonPanel.add(refreshButton);
            buttonPanel.add(Box.createHorizontalGlue());
            JPanel topPanel = new JPanel(new BorderLayout());
            topPanel.add(buttonPanel);
            topPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
            topPanel.add(logoLabel, BorderLayout.NORTH);
            topPanel.add(buttonPanel, BorderLayout.CENTER);
            dropsPanel.add(topPanel, BorderLayout.NORTH);
            dropsPanel.setLayout(new BoxLayout(dropsPanel, BoxLayout.Y_AXIS));
            JLabel descText;
            playerName = plugin.getLocalPlayerName();
            // If the server ID is empty OR the player has not entered an authentication key:
            if(config.serverId().equals("") || config.authKey().equals("")) {
                descText = new JLabel("<html>Welcome to the DropTracker!<br><br>In order to start tracking drops,<br>" +
                        "your server must be added<br> to our database, and you must configure the plugin from settings panel -><br> Contact a member of your clan's staff team to get set up, or obtain your ServerID!</html>");
                descText.setAlignmentX(Component.LEFT_ALIGNMENT);
                Box descTextBox = Box.createHorizontalBox();
                descTextBox.add(descText);
                descTextBox.add(Box.createHorizontalGlue());  // Pushes the descText to the left
                dropsPanel.add(descTextBox);
            } else {

                    String serverName = plugin.getServerName(config.serverId());
                    int minimumClanLoot = plugin.getServerMinimumLoot(config.serverId());
                    Long discordServerId = plugin.getClanDiscordServerID(config.serverId());
                    NumberFormat clanLootFormat = NumberFormat.getNumberInstance();
                    String minimumLootString = clanLootFormat.format(minimumClanLoot);
                    AtomicReference<String> formattedServerTotalRef = new AtomicReference<>("0");
                    if (!config.serverId().isEmpty()) {
                        CompletableFuture<String> serverLootTotalFuture = CompletableFuture.supplyAsync(() -> {
                            // Here is your blocking PHP function call
                            return fetchServerLootTotal();
                        });
                        serverLootTotalFuture.thenAccept(serverLootTotal -> {
                            SwingUtilities.invokeLater(() -> {
                                // Ensure thread safety for GUI updates
                                if (serverLootTotal.equals("Invalid server ID.")) {
                                    formattedServerTotalRef.set("0");
                                } else {
                                    if(serverLootTotal.equals("None")) {
                                        formattedServerTotalRef.set("0");
                                    } else {
                                        formattedServerTotalRef.set(formatNumber(Double.parseDouble(serverLootTotal)));
                                    }
                                }
                            });
                        });
                    }
                    SwingUtilities.invokeLater(() -> {
                        if (formattedServerTotalRef.get().equals("Invalid server ID.")) {
                            formattedServerTotalRef.set("0");
                        } else {
                            formattedServerTotalRef.set(formatNumber(Double.parseDouble(formattedServerTotalRef.get())));
                        }
                    });

                    String[][] data = {
                            {"Your Clan: ", serverName},
                            {"Minimum Value: ", minimumLootString + " gp"},
                            {"Your total loot: ", playerLoot.get(), " gp"},
                            {"Clan Total: ", formattedServerTotalRef.get() + " gp"},
                    };
                    String[] columnNames = {"Key", "Value"};
                    DefaultTableModel model = new DefaultTableModel(data, columnNames) {
                        @Override
                        public boolean isCellEditable(int row, int column) {
                            // This causes all cells to be not editable
                            return false;
                        }
                    };
                    JTable table = new JTable(model);
                    table.setPreferredScrollableViewportSize(new Dimension(500, 70));
                    table.setFillsViewportHeight(true);
                    // Set custom renderer to bold the keys in the table (is there a better way to do this?)
                    table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
                        Font originalFont = null;

                        @Override
                        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                            if (originalFont == null) {
                                originalFont = c.getFont();
                            }
                            c.setFont(originalFont.deriveFont(Font.BOLD));
                            return c;
                        }
                    });

                    dropsPanel.add(table);
                    //dropsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                    descText = new JLabel("<html>To submit a drop, enter " +
                            "any <em>clan members</em> who were " +
                            "with you <b>on their own line</b> " +
                            "in the text field.<br />" +
                            "Then, select how many " +
                            "<em>non-members</em> were involved " +
                            "in the drop." +
                            "Once you press submit, your " +
                            "drop will automatically be sent!</html>");
                    descText.setAlignmentX(Component.LEFT_ALIGNMENT);
                    Box descTextBox = Box.createHorizontalBox();
                    descTextBox.add(descText);
                    descTextBox.add(Box.createHorizontalGlue());  // Pushes the descText to the left

                dropsPanel.add(descTextBox);

            }

            // Add each drop to the panel
            for (DropEntry entry : entries) {
                dropsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                Box entryItemBox = Box.createHorizontalBox();
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
                entryItemBox.add(entryPanel);
                dropsPanel.add(entryItemBox);
                //dropsPanel.add(entryPanel);
            }
            dropsPanel.revalidate();
            dropsPanel.repaint();
        });
    }

    public String fetchServerLootTotal() {
        Long discordServerId = plugin.getClanDiscordServerID(config.serverId());
        try {
            URL url = new URL("http://instinctmc.world/data/player_data.php?totalServerId=" + discordServerId);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;
            StringBuilder builder = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }

            reader.close();
            return builder.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public String fetchPlayerLootFromPHP(String serverId, String playerName) {
        /* Grab the player's total loot from the server via PHP*/
        Long discordServerId = plugin.getClanDiscordServerID(serverId);
        try {
            String encodedPlayerName = URLEncoder.encode(playerName, StandardCharsets.UTF_8.toString());
            URL url = new URL("http://instinctmc.world/data/player_data.php?serverId=" + discordServerId + "&playerName=" + encodedPlayerName);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;
            StringBuilder builder = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }

            reader.close();
            return builder.toString();

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
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
            int nonMembers = entry.getNonMemberCount();
            String memberList = entry.getClanMembers();
            // `` Drop is removed from the entries list; and the panel is refreshed without it.
            // data is sent to another method inside main class; which sends an embed with the entered information for this item
            // Python bot reads the webhook inside discord and updates the servers' loot tracker accordingly.
            String authKey = config.authKey();
            plugin.sendConfirmedWebhook(playerName, npcName, npcLevel, itemId, itemName, memberList, quantity, value, nonMembers, authKey);
            entries.remove(entry);
            refreshPanel();
        });
    }

}
