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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;


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
    private final JPanel dropsPanel;
    public boolean isRefreshing = false;
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
        dropsPanel = new JPanel();
        dropsPanel.setLayout(new BoxLayout(dropsPanel, BoxLayout.Y_AXIS));
        dropsPanel.setBorder(new EmptyBorder(15, 0, 100, 0));
        dropsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        ImageIcon logoIcon = new ImageIcon(TOP_LOGO);
        JLabel logoLabel = new JLabel(logoIcon);

        dropsPanel.add(logoLabel);

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> {
                    if (!isRefreshing) {
                        isRefreshing = true;
                        refreshPanel();
                        isRefreshing = false;
                    }
                });
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
        String playerName = plugin.getPlayerName();

        if(config.serverId().equals("") || !config.authKey().equals("")) {
            descText = new JLabel("<html><center><h1>Welcome to the DropTracker!</h1><br>" +
                    "<span>It appears that you do not have a server ID configured or that your auth token has been left blank.<br></span>" +
                    "<span>This plugin requires registration and valid authentication!<br>Use your clan's Discord Server ID!</span>" +
                    "<br />Try using /gettoken inside the discord server to retrieve your token if it's been lost.</html>");
            dropsPanel.add(descText);
        } else {

            if(localAuthKey != null && localPlayerName == plugin.getLocalPlayerName()) {
                // do nothing
            } else if (config.authKey().equals("")) {
                descText = new JLabel("<html>You do not have an authentication token in your plugin configuration!<br>" +
                        "This should have been provided to you when you registered in the Discord.<br>" +
                        "If you've lost it, visit www.droptracker.io to request a reset.</html>");
                descText.setAlignmentX(Component.LEFT_ALIGNMENT);
                Box descTextBox = Box.createHorizontalBox();
                descTextBox.add(descText);
                descTextBox.add(Box.createHorizontalGlue());  // Pushes the descText to the left
                dropsPanel.add(descTextBox);
            } else if (config.authKey().equals(checkAuthKey(playerName, config.serverId(), config.authKey()))) {
                descText = new JLabel("<html><center>The authentication token you entered is invalid.<br><br>" +
                        "If you play multiple accounts, ensure that the account you registered with is entered" +
                        " into the <b>Permanent Player Name</b> config option.<br><br>" +
                        "If you've lost your authentication token, use /gettoken inside Discord.</center></html>");
                descText.setAlignmentX(Component.LEFT_ALIGNMENT);
                Box descTextBox = Box.createHorizontalBox();
                descTextBox.add(descText);
                descTextBox.add(Box.createHorizontalGlue());
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
                                }
                                if (lootData.containsKey("server_total")) {
                                    serverLootTotal.set(formatNumber(Double.parseDouble(lootData.get("serverLoot"))));
                                }
                            }
                        });
                    });
                } else {
                    playerLoot.set("not signed in!");
                    formattedServerTotal = "0";
                }
                String[][] data = {
                        {"Clan:", serverName},
                        {"Discord pings:", ">" + minimumLootString + " gp"},
                        {"Your total:", "loading..."},
                        {"Clan Total", "loading..."},
                };
                updateTable("load", "load");
                String[] columnNames = {"Key", "Value"};
                DefaultTableModel model = new DefaultTableModel(data, columnNames) {
                    @Override
                    public boolean isCellEditable(int row, int column) {
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


    public CompletableFuture<Map<String, String>> fetchLootFromServer() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, String> lootData = new HashMap<>();
            try {
                String playerName = plugin.getPlayerName();

                playerName = URLEncoder.encode(playerName, StandardCharsets.UTF_8.toString());
                URL url = new URL("http://api.droptracker.io/api/get_drop_data?server_id=" + config.serverId() + "&player_name=" + playerName + "&auth=" + config.authKey());
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
                lootData.put("playerLoot", jsonResponse.has("player_total") ? jsonResponse.get("player_total").getAsString() : "");
                lootData.put("serverLoot", jsonResponse.has("server_total") ? jsonResponse.get("server_total").getAsString() : "");
                JsonArray recentDropsArray = jsonResponse.has("recent_drops") ? jsonResponse.get("recent_drops").getAsJsonArray() : new JsonArray();
                lootData.put("recentDrops", recentDropsArray.toString());

            } catch (IOException e) {
                e.printStackTrace();
            }
            return lootData;
        });
    }


    void refreshPanel() {
            SwingUtilities.invokeLater(() -> {
                dropsPanel.removeAll();
                dropsPanel.validate();
                dropsPanel.repaint();
                String playerName = plugin.getLocalPlayerName();
                dropsPanel.setLayout(new BoxLayout(dropsPanel, BoxLayout.Y_AXIS));
                AtomicReference<String> playerLoot = new AtomicReference<>("loading...");
                AtomicReference<String> formattedServerTotalRef = new AtomicReference<>("loading...");
                String formattedServerTotal = "0";
                AtomicBoolean isAllItemsBoxAdded = new AtomicBoolean(false);
                if (playerName != null) {
                    if (config.serverId().equals("")) {
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
                                localAuthKey = config.authKey();
                                if (plugin.getLocalPlayerName() != null) {
                                    localPlayerName = plugin.getLocalPlayerName();
                                }
                                CompletableFuture.runAsync(() -> {
                                    if (!config.permPlayerName().equals("")) {
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
                                            if (lootData.containsKey("recentDrops")) {
                                                JLabel descText = new JLabel("<html><h3><b>" + plugin.getServerName(config.serverId()) + "'s Recent Submissions:</b></h3></html>");
                                                descText.setAlignmentX(Component.CENTER_ALIGNMENT);
                                                Box descTextBox = Box.createHorizontalBox();
                                                descTextBox.add(Box.createHorizontalGlue());
                                                descTextBox.add(descText);
                                                descTextBox.add(Box.createHorizontalGlue());
                                                dropsPanel.add(descTextBox, BorderLayout.NORTH);
                                                JsonParser parser = new JsonParser();
                                                JsonArray recentDropsArray = parser.parse(lootData.get("recentDrops")).getAsJsonArray();
                                                List<JsonObject> dropList = new ArrayList<>();

                                                for (JsonElement element : recentDropsArray) {
                                                    dropList.add(element.getAsJsonObject());
                                                }

                                                dropList.sort((a, b) -> Integer.compare(b.get("value").getAsInt(), a.get("value").getAsInt()));
                                                List<JsonObject> topDrops = dropList.stream().limit(3).collect(Collectors.toList());
                                                Box allItemsBox = Box.createHorizontalBox();

                                                for (JsonObject drop : topDrops) {
                                                    Box entryItemBox = Box.createVerticalBox();
                                                    entryItemBox.setAlignmentX(Component.CENTER_ALIGNMENT);

                                                    int itemId = drop.get("item_id").getAsInt();
                                                    String dropPlayerName = drop.has("player_name") ? drop.get("player_name").getAsString() : "Unknown";
                                                    String timeReceived = drop.has("time") ? drop.get("time").getAsString() : "";
                                                    String itemName = drop.has("item_name") ? drop.get("item_name").getAsString() : "";
                                                    BufferedImage itemImage = itemManager.getImage(itemId);
                                                    JLabel imageLabel = new JLabel(new ImageIcon(itemImage));
                                                    imageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

                                                    JLabel itemNameLabel = new JLabel("<html><b>" + itemName + "</b></html>");
                                                    itemNameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                                                    JLabel playerLabel = new JLabel(dropPlayerName);
                                                    playerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                                                    JLabel timeLabel = new JLabel("<html><em>" + timeReceived + "</em></html>");
                                                    timeLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

                                                    entryItemBox.add(itemNameLabel);
                                                    entryItemBox.add(imageLabel);
                                                    entryItemBox.add(playerLabel);
                                                    entryItemBox.add(timeLabel);

                                                    allItemsBox.add(Box.createHorizontalStrut(10));
                                                    allItemsBox.add(entryItemBox);
                                                }

                                                if (!isAllItemsBoxAdded.get()) {
                                                    dropsPanel.add(allItemsBox);
                                                    isAllItemsBoxAdded.set(true);
                                                }
                                            }
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
                if (config.authKey().equals("")) {
                    return;
                }
                dropsPanel.setBorder(new EmptyBorder(15, 0, 100, 0));
                dropsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

                ImageIcon logoIcon = new ImageIcon(TOP_LOGO);
                JLabel logoLabel = new JLabel(logoIcon);

                dropsPanel.add(logoLabel);

                /* Add a button to refresh the panel */
                JButton refreshButton = new JButton("Refresh");
                refreshButton.addActionListener(e -> {
                            if (!isRefreshing) {
                                isRefreshing = true;
                                refreshPanel();
                                isRefreshing = false;
                            }
                        });
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
                if (config.serverId().equals("") || config.authKey().equals("")) {
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
                    if (config.showHelpText()) {
                        descText = new JLabel("<html>The DropTracker will automatically track all drops you receive.<br><br>" +
                                "You can visit https://www.droptracker.io/ to view metrics and leaderboards.<br><br>" +
                                "Items received above the 'minimum value' displayed above will be sent to your clan's Discord server<br><br>" +
                                "<em>Note: You can turn this helper text off in the plugin config!</em><br>" +
                                "</html>");
                        // to place the text in the correct location
                        descText.setAlignmentX(Component.LEFT_ALIGNMENT);
                        Box descTextBox = Box.createHorizontalBox();
                        descTextBox.add(descText);
                        descTextBox.add(Box.createHorizontalGlue());  // Pushes the descText to the left
                        dropsPanel.add(descTextBox);
                    }

                }


                dropsPanel.revalidate();
                dropsPanel.repaint();
                isRefreshing = false;
            });

    }

    public static String formatNumber(double number) {
        if (number == 0) {
            return "0";
        }
        String[] units = new String[] { "", "K", "M", "B", "T" };
        int unit = (int) Math.floor((Math.log10(number) / 3));

        if (unit >= units.length) unit = units.length - 1;

        double num = number / Math.pow(1000, unit);
        DecimalFormat df = new DecimalFormat("#.#");
        String formattedNum = df.format(num);
        return formattedNum + units[unit];
    }
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
                    return false;
                }
            };
            table.setModel(model);
            table.setPreferredScrollableViewportSize(new Dimension(500, 70));
            table.setFillsViewportHeight(true);
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

    public void checkAuthKeyAsync(String playerName, String serverId, String authKey, Consumer<String> callback) {
        executorService.submit(() -> {
            String finalPlayerName = !config.permPlayerName().equals("") ? config.permPlayerName() : playerName;
            if(playerName != null && !serverId.equals("")) {
                String result = checkAuthKey(finalPlayerName, serverId, authKey);
                if (result.equals("Authenticated")) {
                    callback.accept("yes");
                } else {
                    callback.accept(result);
                }
            } else {
                callback.accept("invalid parameters");
            }
        });
    }
    public String checkAuthKey(String playerName, String serverId, String authKey) {
        try {
            URL url = new URL("http://api.droptracker.io/api/authenticate");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setDoOutput(true);

            String message = String.format("player_name=%s&server_id=%s&auth_key=%s", URLEncoder.encode(playerName, StandardCharsets.UTF_8.toString()), URLEncoder.encode(serverId, StandardCharsets.UTF_8.toString()), URLEncoder.encode(authKey, StandardCharsets.UTF_8.toString()));
            connection.getOutputStream().write(message.getBytes(StandardCharsets.UTF_8));

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            connection.disconnect();

            JsonParser parser = new JsonParser();
            JsonObject jsonResponse = parser.parse(response.toString()).getAsJsonObject();
            if (jsonResponse.has("success")) {
                return "Authenticated";
            } else if (jsonResponse.has("error")) {
                return jsonResponse.get("error").getAsString();
            } else {
                return "Unknown response";
            }
        } catch (IOException e) {
            e.printStackTrace();
            return "error: " + e.getMessage();
        }
    }

}
