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

import com.joelhalen.droptracker.ui.MembersComboBox;
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
import org.json.JSONException;
import org.json.JSONObject;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private JTable table = new JTable();
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
        String playerName = getPlayerName();
        // If the server ID is empty OR the player has not entered an authentication key:
        if(config.serverId().equals("") || !config.authKey().equals("")) {
            descText = new JLabel("<html><center><h1>Welcome to the DropTracker!</h1><br>" +
                    "<span>It appears that you do not have a server ID configured or that your auth token has been left blank.<br></span>" +
                    "<span>This plugin requires registration and valid authentication!<br>Use your clan's Discord Server ID!</span>" +
                    "<br />Try using /gettoken inside the discord server to retrieve your token if it's been lost.</html>");
            dropsPanel.add(descText);
        } else {
            // If they entered a server ID, check if the auth key is empty
            // We also handle if the auth key does not match the expected value here.
            if(localAuthKey != null && localPlayerName == plugin.getLocalPlayerName()) {
                // do nothing if they have a localAuthKey stored with the correct playername
            //if they have entered nothing for their auth token
                //todo: insert a message if their account was not found?
            } else if (config.authKey().equals("")) {
                descText = new JLabel("<html>You do not have an authentication token in your plugin configuration!<br>" +
                        "This should have been provided to you when you registered in the Discord.<br>" +
                        "If you've lost it, visit www.droptracker.io to request a reset.</html>");
                descText.setAlignmentX(Component.LEFT_ALIGNMENT);
                Box descTextBox = Box.createHorizontalBox();
                descTextBox.add(descText);
                descTextBox.add(Box.createHorizontalGlue());  // Pushes the descText to the left
                dropsPanel.add(descTextBox);
            //invalid authentication token entered
            } else if (config.authKey().equals(checkAuthKey(playerName, config.serverId(), config.authKey()))) {
                descText = new JLabel("<html><center>The authentication token you entered is invalid.<br><br>" +
                        "If you play multiple accounts, ensure that the account you registered with is entered" +
                        " into the <b>Permanent Player Name</b> config option.<br><br>" +
                        "If you've lost your authentication token, use /gettoken inside Discord.</center></html>");
                descText.setAlignmentX(Component.LEFT_ALIGNMENT);
                Box descTextBox = Box.createHorizontalBox();
                descTextBox.add(descText);
                descTextBox.add(Box.createHorizontalGlue());  // Pushes the descText to the left
                dropsPanel.add(descTextBox);
            } else {
                String serverName = plugin.getServerName(config.serverId());
                int minimumClanLoot = plugin.getServerMinimumLoot(config.serverId());
                Long discordServerId = Long.valueOf(config.serverId());
                NumberFormat clanLootFormat = NumberFormat.getNumberInstance();
                String minimumLootString = clanLootFormat.format(minimumClanLoot);
                AtomicReference<String> playerLoot = new AtomicReference<>("none");
                String formattedServerTotal = "0";
                if (!config.serverId().equals("")) {
                    AtomicReference<String> serverLootTotal = new AtomicReference<>("none");
                    fetchLootFromServer().thenAccept(lootData -> {
                        SwingUtilities.invokeLater(() -> {
                            if (lootData != null) {
                                if (lootData.containsKey("player_total")) {
                                    playerLoot.set(formatNumber(Double.parseDouble(lootData.get("playerLoot"))));
                                    // refresh the panel or perform other updates for player loot
                                }
                                if (lootData.containsKey("server_total")) {
                                    serverLootTotal.set(formatNumber(Double.parseDouble(lootData.get("serverLoot"))));
                                    // refresh the panel or perform other updates for server loot
                                }
                            }
                        });
                    });
                } else {
                    playerLoot.set("not signed in!");
                    formattedServerTotal = "0";
                }
                String[][] data = {
                        {"Your Clan", serverName},
                        {"Minimum value", minimumLootString + " gp"},
                        {"Your total loot", "loading..."},
                        {"Clan Total:", "loading..."},
                };
                updateTable("load", "load");
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
                if (config.showHelpText()) {
                    descText = new JLabel("<html>The database will automatically track all drops you receive.<br><br>" +
                            "Any item above your clan's minimum, <b>" + minimumLootString + " gp</b>, will appear below.<br><br>" +
                            "You can select any clan members involved in the drop from the left-side dropdown list to credit them for their split.<br>" +
                            "The non-member dropdown allows you to specify the split-size if any players from outside of your clan were involved with the drop.<br>" +
                            "<br><b>You can prevent this information from re-appearing in the plugin config!</b></html>");
                    // to place the text in the correct location
                    descText.setAlignmentX(Component.LEFT_ALIGNMENT);
                    Box descTextBox = Box.createHorizontalBox();
                    descTextBox.add(descText);
                    descTextBox.add(Box.createHorizontalGlue());  // Pushes the descText to the left
                    dropsPanel.add(descTextBox);
                }
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
    //send an update to the table on the panel once the data has returned from the php script
    private void updateTable(String playerLoot, String serverTotal) {
        SwingUtilities.invokeLater(() -> {
            String serverName = plugin.getServerName(config.serverId());
            Integer minimumClanLoot = plugin.getServerMinimumLoot(config.serverId());
            NumberFormat clanLootFormat = NumberFormat.getNumberInstance();
            String minimumLootString = clanLootFormat.format(minimumClanLoot);
            String serverFormattedTotal;
            String playerFormattedTotal;
            if(serverTotal.equals("load") && playerLoot.equals("load")) {
                serverFormattedTotal = "<em>unknown</em>";
                playerFormattedTotal = "<em>unknown</em>";
            } else {
                serverFormattedTotal = serverTotal;
                playerFormattedTotal = playerLoot;
            }
            String[][] data = {
                    {"Your Clan: ", serverName},
                    {"Minimum Value: ", minimumLootString + " gp"},
                    {"Your total loot: ", playerFormattedTotal, " gp"},
                    {"Clan Total: ", serverFormattedTotal + " gp"},
            };
            String[] columnNames = {"Key", "Value"};
            DefaultTableModel model = new DefaultTableModel(data, columnNames) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    // This causes all cells to be not editable
                    return false;
                }
            };
            table.setModel(model);
            table.setPreferredScrollableViewportSize(new Dimension(500, 70));
            table.setFillsViewportHeight(true);
            // Set custom renderer to bold the keys in the table (is there a better way to do this?)
            if (table.getColumnModel().getColumnCount() > 0) {
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
            }
            table.setModel(model);
            table.setPreferredScrollableViewportSize(new Dimension(500, 70));
            table.setFillsViewportHeight(true);
        });
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
            String finalPlayerName = !config.permPlayerName().equals("") ? config.permPlayerName() : playerName;
            if(playerName != null && !serverId.equals("")) {
                String result = checkAuthKey(finalPlayerName, serverId, authKey);
                if (result.equals("Authenticated")) {
                    callback.accept("yes");
                } else {
                    callback.accept(result); // Pass the exact error message for handling
                }
            } else {
                callback.accept("invalid parameters");
            }
        });
    }
    public void shutdownExecutorService() {
        executorService.shutdown();
    }
    public String checkAuthKey(String playerName, String serverId, String authKey) {
        try {
            URL url = new URL("http://data.droptracker.io/admin/api/authenticate.php");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setDoOutput(true);

            String message = String.format("player_name=%s&server_id=%s&auth_key=%s", playerName, serverId, authKey);
            connection.getOutputStream().write(message.getBytes(StandardCharsets.UTF_8));

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            connection.disconnect();

            // Parse the JSON response
            JSONObject jsonResponse = new JSONObject(response.toString());
            if (jsonResponse.has("success")) {
                return "Authenticated";
            } else if (jsonResponse.has("error")) {
                return jsonResponse.getString("error"); // Returns the exact error message
            } else {
                return "Unknown response";
            }
        } catch (IOException e) {
            e.printStackTrace();
            return "error: " + e.getMessage();
        }
    }

    void refreshPanel() {
        SwingUtilities.invokeLater(() -> {
            // Clear the panel
            dropsPanel.removeAll();
            //re-set the layout and styles
            String playerName = plugin.getLocalPlayerName();
            dropsPanel.setLayout(new BoxLayout(dropsPanel, BoxLayout.Y_AXIS));
            AtomicReference<String> playerLoot = new AtomicReference<>("loading...");
            AtomicReference<String> formattedServerTotalRef = new AtomicReference<>("loading...");
            String formattedServerTotal = "0";
            //if they have a playerName assigned:
            if (playerName != null) {
                // if the localAuthKey is stored; and the playerName is still the same, don't update.
//                if(localAuthKey != null && localAuthKey.equals(config.authKey())) {
//                    // do not perform an authentication check if the auth key is validated & stored, and their name is correct.
//                } else {
                if(config.serverId().equals("")) {
                    ChatMessageBuilder messageResponse = new ChatMessageBuilder();
                    messageResponse.append(ChatColorType.NORMAL).append("[").append(ChatColorType.HIGHLIGHT)
                            .append("DropTracker")
                            .append(ChatColorType.NORMAL)
                            .append("]")
                            .append("You have not configured a serverID in the plugin config! If your server is not part of the DropTracker, the plugin will not work!");
                    plugin.chatMessageManager.queue(QueuedMessage.builder()
                            .type(ChatMessageType.CONSOLE)
                            .runeLiteFormattedMessage(messageResponse.build())
                            .build());
                    playerLoot.set("<em>...</em>");
                    try {
                        plugin.shutDown();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return;
                }
                    checkAuthKeyAsync(playerName, config.serverId(), config.authKey(), (authRes) -> {
                            SwingUtilities.invokeLater(() -> {
                                if (authRes.equals("User not found")) {
                                    ChatMessageBuilder messageResponse = new ChatMessageBuilder();
                                    messageResponse.append(ChatColorType.HIGHLIGHT).append("[")
                                            .append("DropTracker")
                                            .append("] Your account was not found in " + plugin.getServerName(config.serverId()))
                                            .append("'s database!");
                                    ChatMessageBuilder registrationMemberResponse = new ChatMessageBuilder();
                                    messageResponse.append(ChatColorType.HIGHLIGHT).append("In order to register, type your")
                                            .append("RSN inside of your clan's designated discord channel.");
                                    playerLoot.set("<em>unregistered</em>");
                                    plugin.chatMessageManager.queue(QueuedMessage.builder()
                                            .type(ChatMessageType.CONSOLE)
                                            .runeLiteFormattedMessage(messageResponse.build())
                                            .build());
                                    plugin.chatMessageManager.queue(QueuedMessage.builder()
                                            .type(ChatMessageType.CONSOLE)
                                            .runeLiteFormattedMessage(registrationMemberResponse.build())
                                            .build());
                                } else if (authRes.equals("Invalid auth token")) {
                                    // in any other case, if the response doesn't say "yes", the auth key is invalid
                                    ChatMessageBuilder messageResponse = new ChatMessageBuilder();
                                    messageResponse.append(ChatColorType.HIGHLIGHT).append("[")
                                            .append("DropTracker")
                                            .append("] You have entered an invalid authentication")
                                            .append(" token in the configuration for DropTracker.");
                                    playerLoot.set("<em>unregistered</em>");
                                    plugin.chatMessageManager.queue(QueuedMessage.builder()
                                            .type(ChatMessageType.CONSOLE)
                                            .runeLiteFormattedMessage(messageResponse.build())
                                            .build());
                                } else if (authRes.equals("yes")) {
                                    // authentication has succeeded, proceed.
                                    localAuthKey = config.authKey();
                                    if(plugin.getLocalPlayerName() != null) {
                                        localPlayerName = plugin.getLocalPlayerName();
                                    }
                                    //Grab server loot total + personal loot total
                                    CompletableFuture.runAsync(() -> {
                                        if(!config.permPlayerName().equals("")) {
                                            localPlayerName = config.permPlayerName();
                                        }
                                        fetchLootFromServer().thenAccept(lootData -> {
                                            SwingUtilities.invokeLater(() -> {
                                                if (lootData != null) {
                                                    if (lootData.containsKey("playerLoot")) {
                                                        playerLoot.set(formatNumber(Double.parseDouble(lootData.get("playerLoot"))));
                                                    }
                                                    if (lootData.containsKey("serverLoot")) {
                                                        formattedServerTotalRef.set(formatNumber(Double.parseDouble(lootData.get("serverLoot"))));
                                                    }
                                                } else {
                                                        playerLoot.set("unregistered");
                                                    }
                                                updateTable(playerLoot.get(), formattedServerTotalRef.get());
                                            });
                                        });
                                    });
                                } else {
                                    log.debug("Some type of error occurred authenticating with the DropTracker database.");
                                }
                            });
                        });

            } else {
                playerLoot.set("not signed in!");
            }
            if(config.authKey().equals("")) {
                return;
            }
            dropsPanel.setBorder(new EmptyBorder(15, 0, 100, 0));
            dropsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

            ImageIcon logoIcon = new ImageIcon(TOP_LOGO);
            JLabel logoLabel = new JLabel(logoIcon);

            dropsPanel.add(logoLabel);

            /* Add a button to refresh the panel */
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
            // If the server ID is empty OR the player has not entered an authentication key:
            if(config.serverId().equals("") || config.authKey().equals("")) {
                descText = new JLabel("<html><br><br>Welcome to the DropTracker!<br><br>In order to start tracking drops,<br>" +
                        "your server must be added<br> to our database, and you must configure the plugin from settings panel -><br> Contact a member of your clan's staff team to get set up, or obtain your ServerID!</html>");
                descText.setAlignmentX(Component.LEFT_ALIGNMENT);
                Box descTextBox = Box.createHorizontalBox();
                descTextBox.add(descText);
                descTextBox.add(Box.createHorizontalGlue());  // Pushes the descText to the left
                dropsPanel.add(descTextBox);
            } else {

                    String serverName = plugin.getServerName(config.serverId());
                    int minimumClanLoot = plugin.getServerMinimumLoot(config.serverId());
                    NumberFormat clanLootFormat = NumberFormat.getNumberInstance();
                    String minimumLootString = clanLootFormat.format(minimumClanLoot);
                    String[][] data = {
                            {"Your Clan: ", serverName},
                            {"Minimum Value: ", minimumLootString + " gp"},
                            {"Your total loot: ", playerLoot.get(), ""},
                            {"Clan Total: ", formattedServerTotalRef.get() + ""},
                    };
                    String[] columnNames = {"Key", "Value"};
                    DefaultTableModel model = new DefaultTableModel(data, columnNames) {
                        @Override
                        public boolean isCellEditable(int row, int column) {
                            // This causes all cells to be not editable
                            return false;
                        }
                    };
                    table.setModel(model);
                    table.setPreferredScrollableViewportSize(new Dimension(500, 70));
                    table.setFillsViewportHeight(true);
                    // Set custom renderer to bold the keys in the table (is there a better way to do this?)
                if (table.getColumnModel().getColumnCount() > 0) {
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
                }

                    dropsPanel.add(table);
                    //dropsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                if (config.showHelpText()) {
                    descText = new JLabel("<html>The database will automatically track all drops you receive.<br><br>" +
                            "Any item above your clan's minimum, <b>" + minimumLootString + " gp</b>, will appear below.<br><br>" +
                            "You can select any clan members involved in the drop from the left-side dropdown list to credit them for their split.<br>" +
                            "The non-member dropdown allows you to specify the split-size if any players from outside of your clan were involved with the drop.<br>" +
                            "<br><b>You can prevent this information from re-appearing in the plugin config!</b></html>");
                    // to place the text in the correct location
                    descText.setAlignmentX(Component.LEFT_ALIGNMENT);
                    Box descTextBox = Box.createHorizontalBox();
                    descTextBox.add(descText);
                    descTextBox.add(Box.createHorizontalGlue());  // Pushes the descText to the left
                    dropsPanel.add(descTextBox);
                }

            }
            /* Initialize the membersComboBox prior to each drop, so that we don't try to pull data for each drop received */
            if (entries.isEmpty()) {
                descText = new JLabel("<html><i>You have not yet received any drops to submit.</i></html>");
                // to place the text in the correct location
                descText.setAlignmentX(Component.LEFT_ALIGNMENT);
                Box descTextBox = Box.createHorizontalBox();
                descTextBox.add(descText);
                descTextBox.add(Box.createHorizontalGlue());  // Pushes the descText to the left
                dropsPanel.add(descTextBox);
            } else {
                for (DropEntry entry : entries) {
                    dropsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                    Box entryItemBox = Box.createHorizontalBox();
                    // Fetch image for the item
                    BufferedImage itemImage = itemManager.getImage(entry.getItemId());
                    JLabel imageLabel = new JLabel(new ImageIcon(itemImage));
                    String itemName = entry.getItemName();
                    int geValue = entry.getGeValue();

                    JLabel itemTextLabel = new JLabel("<html>Item: " + itemName + "<br>Value: " + String.valueOf(geValue) + "gp</html>");

                    JPanel nameFieldPanel = new JPanel(new BorderLayout());
                    JLabel nameLabel = new JLabel("Select Members:");
                    nameFieldPanel.add(nameLabel, BorderLayout.NORTH);
                    MembersComboBox membersComboBox = new MembersComboBox(plugin, config);
                    membersComboBox.setPreferredSize(new Dimension(75, 25));
                    nameFieldPanel.add(membersComboBox, BorderLayout.CENTER);

                    Integer[] nonMemberOptions = new Integer[21];
                    for (int i = 0; i <= 20; i++) {
                        nonMemberOptions[i] = i; // Fill array with numbers 0-20
                    }

                    JPanel nonMemberDropdownPanel = new JPanel(new BorderLayout());
                    JLabel nonMemberLabel = new JLabel("Non-members:");
                    nonMemberDropdownPanel.add(nonMemberLabel, BorderLayout.NORTH);

                    JComboBox<Integer> nonMemberDropdown = new JComboBox<>(nonMemberOptions);
                    nonMemberDropdown.setToolTipText("Select # of non-members involved in the drop.");
                    nonMemberDropdown.setPreferredSize((new Dimension(45, 25)));
                    nonMemberDropdownPanel.add(nonMemberDropdown, BorderLayout.CENTER);

                    JButton submitButton = new JButton("Submit");
                    JComboBox<Integer> finalNonMemberDropdown = nonMemberDropdown;

                    submitButton.addActionListener(e -> {
                        // Get values from combo box and other field
                        List<String> selectedMembersList = membersComboBox.getSelectedItems();  // Get selected names from MembersComboBox
                        String selectedMembersString = String.join(", ", selectedMembersList);  // Join the names into a single string with comma separators

                        int nonMemberCount = (Integer) finalNonMemberDropdown.getSelectedItem();  // Get non-member count from dropdown

                        entry.setClanMembers(selectedMembersString);  // Set the selected names
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
                    entryPanel.add(nameMemberPanel);
                    entryPanel.add(submitButton);
                    entryPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                    entryItemBox.add(entryPanel);
                    dropsPanel.add(entryItemBox);
                }
            }
            dropsPanel.revalidate();
            dropsPanel.repaint();
        });
    }

    public String getPlayerName() {
        if (config.permPlayerName().equals("")) {
            return client.getLocalPlayer().getName();
        } else {
            return config.permPlayerName();
        }
    }

    public CompletableFuture<Map<String, String>> fetchLootFromServer() {
        return CompletableFuture.supplyAsync(() -> {
            Long discordServerId = Long.valueOf(config.serverId());
            Map<String, String> lootData = new HashMap<>();
            try {
                String playerName = getPlayerName();

                playerName = URLEncoder.encode(playerName, StandardCharsets.UTF_8.toString());
                URL url = new URL("http://data.droptracker.io/admin/api/fetch_drop_data.php?server_id=" + config.serverId() + "&player_name=" + playerName);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String line;
                StringBuilder builder = new StringBuilder();

                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }

                reader.close();
                JSONObject jsonResponse = new JSONObject(builder.toString());
                lootData.put("playerLoot", jsonResponse.getString("player_total"));
                lootData.put("serverLoot", jsonResponse.getString("server_total"));

            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
            return lootData;
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
            int nonMembers = entry.getNonMemberCount();
            String imageUrl = entry.getImageLink();
            String memberList = entry.getClanMembers();
            String authKey = config.authKey();
            try {
                plugin.sendDropData(playerName, npcName, itemId, itemName, memberList, quantity, value, nonMembers, authKey, imageUrl);
            } catch (Exception e) {

            }
            entries.remove(entry);
            refreshPanel();
        });
    }

}
