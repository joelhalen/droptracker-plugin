/*
 * Copyright (c) 2018 Abex
 * Copyright (c) 2018, Psikoi <https://github.com/psikoi>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package io.droptracker.ui;

import com.google.inject.Inject;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.api.DropTrackerApi;
import net.runelite.api.Client;
import net.runelite.client.account.SessionManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.SessionClose;
import net.runelite.client.events.SessionOpen;
import net.runelite.client.plugins.info.InfoPanel;
import net.runelite.client.plugins.info.JRichTextPane;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

public class DropTrackerPanel extends PluginPanel implements DropTrackerApi.PanelDataLoadedCallback
{
    private static final ImageIcon ARROW_RIGHT_ICON;
    private static final ImageIcon DISCORD_ICON;
    private static final ImageIcon WIKI_ICON;
    private static final ImageIcon TOP_LOGO;

    private final JLabel loggedLabel = new JLabel();
    private final JRichTextPane emailLabel = new JRichTextPane();
    private JPanel actionsContainer;
    private JPanel mainContentPanel;
    private boolean isRefreshing = false;
    @Inject
    private DropTrackerApi api;
    private final DropTrackerConfig config;
    @Inject
    private final DropTrackerPlugin plugin;
    private JPanel eventPanel;
    private Map<String, Object> currentEvents;
    @Inject
    private ClientThread clientThread;
    private int currentEventIndex;
    @Inject
    @Nullable
    private Client client;
    @Inject
    private EventBus eventBus;
    @Inject
    private ScheduledExecutorService executor;
    private String discordInvite = "https://www.droptracker.io/discord";
    private String docsLink = "https://www.droptracker.io/docs";

    static
    {
        ARROW_RIGHT_ICON = new ImageIcon(ImageUtil.loadImageResource(InfoPanel.class, "/util/arrow_right.png"));
        DISCORD_ICON = new ImageIcon(ImageUtil.loadImageResource(InfoPanel.class, "discord_icon.png"));
        WIKI_ICON = new ImageIcon(ImageUtil.loadImageResource(InfoPanel.class, "wiki_icon.png"));
        TOP_LOGO = new ImageIcon(ImageUtil.loadImageResource(DropTrackerPlugin.class, "toplogo.png"));
    }

    @Inject
    public DropTrackerPanel(DropTrackerConfig config, DropTrackerApi api, DropTrackerPlugin plugin) {
        this.config = config;
        this.api = api;
        this.plugin = plugin;
    }

    @Override
    public void onDataLoaded(Map<String, Object> data) {
        if ("success".equals(data.get("status"))) {
            Map<String, Object> items = (Map<String, Object>) data.get("data");
            String playerLoot = (String) items.get("loot");
            String clanName = (String) items.get("clan");
            String clanLoot = (String) items.get("clanLoot");
            Map<String, Object> events = (Map<String, Object>) items.get("events");
            JPanel lootInfoPanel = createLootInfoPanel(data);
            populateMainContent(playerLoot, clanName, clanLoot, events);
            mainContentPanel.add(lootInfoPanel);
        }
    }

    public void init()
    {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        final Font smallFont = FontManager.getRunescapeSmallFont();
        JPanel logoPanel = new JPanel(new BorderLayout());
        ImageIcon logoIcon = TOP_LOGO;
        JLabel logoLabel = new JLabel(logoIcon);
        if(config.useApi()) {
            JButton refreshButton = new JButton("Refresh");
            refreshButton.addActionListener(e -> {
                if (!isRefreshing) {
                    if (!config.useApi())
                        isRefreshing = true;
                    refreshData();
                    isRefreshing = false;
                }
            });
            logoPanel.add(refreshButton, BorderLayout.SOUTH);
        }

        logoPanel.add(logoLabel, BorderLayout.NORTH);
        logoPanel.add(Box.createRigidArea(new Dimension(0, 5))); // spacer
        mainContentPanel = new JPanel();
        mainContentPanel.setLayout(new BoxLayout(mainContentPanel, BoxLayout.Y_AXIS));
        add(mainContentPanel, BorderLayout.CENTER);
        if(config.useApi()) {
            this.api.setDataLoadedCallback(this);
            api.loadPanelData(false);
        } else {

            JLabel noApiLabel = new JLabel("<html><h3>Welcome to the DropTracker</h3><br>" +
                    "An all-in-one loot tracking system with support for Discord Webhooks, Google Sheets and more.<br>" +
                    "<br>" +
                    "Learn more about the project by clicking below.<br>" +
                    "<br>&#9432; If you enable external connections in the " +
                    "plugin configuration, you can view more info & event data here!<br><br>" +
                    "If you don't see the panel after enabling the API, restart the DropTracker plugin.</html>");
            noApiLabel.setAlignmentX(RIGHT_ALIGNMENT);
            mainContentPanel.add(noApiLabel);
        }
        actionsContainer = new JPanel();
        actionsContainer.setBorder(new EmptyBorder(10, 0, 0, 0));
        actionsContainer.setLayout(new FlowLayout(FlowLayout.CENTER, 10, 0));
        actionsContainer.add(buildRoundedPanel(DISCORD_ICON, "Discord", discordInvite));
        actionsContainer.add(buildRoundedPanel(WIKI_ICON, "Docs", docsLink));
        add(logoPanel, BorderLayout.NORTH);
        add(actionsContainer, BorderLayout.SOUTH);
        eventPanel = new JPanel();
        currentEvents = new HashMap<>();
        currentEventIndex = 0;
        eventBus.register(this);
    }
    private JPanel createLootInfoPanel(Map<String, Object> inData) {
        JPanel lootInfoPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx=0;
        gbc.gridy=0;
        gbc.insets = new Insets(3,3,3,3);
        Map<String, Object> data = (Map<String, Object>) inData.get("data");
        JLabel playerLootLabel = new JLabel(data.get("loot").toString());
        Font font = FontManager.getRunescapeFont().deriveFont(14f);
        playerLootLabel.setFont(font);
        JLabel clanLootLabel = new JLabel(data.get("clanLoot").toString());
        JLabel playerGlobalRankLabel = new JLabel(data.get("playerGlobalRank").toString());
        JLabel clanGlobalRankLabel = new JLabel(data.get("clanRank").toString());
        JLabel[] labels = {
                new JLabel("Your loot:"), playerLootLabel,
                new JLabel("Rank:"), playerGlobalRankLabel,
                new JLabel("Clan loot:"), clanLootLabel,
                new JLabel("Clan Rank:"), clanGlobalRankLabel,
        };
        Color playerLootColor = new Color(46, 169, 255, 89);
        Color clanLootColor = new Color(42, 255, 0, 89);
        for (JLabel label : labels) {
            label.setForeground(Color.WHITE);
            if (label.getText().equals(playerLootLabel.getText())) {
                label.setForeground(playerLootColor);
            }
            if (label.getText().equals(clanLootLabel.getText())) {
                label.setForeground(clanLootColor);
            }
            lootInfoPanel.add(label, gbc);
            gbc.gridx++;
            if (gbc.gridx == 2) {
                gbc.gridx = 0;
                gbc.gridy++;
            }
        }
        lootInfoPanel.setAlignmentX(RIGHT_ALIGNMENT);
        lootInfoPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY), "Loot Statistics"));
        return lootInfoPanel;
    }

    private void populateMainContent(String playerLoot, String clanName, String clanLoot, Map<String, Object> events) {
        mainContentPanel.removeAll();

        mainContentPanel.add(Box.createRigidArea(new Dimension(0, 8)));

        SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
        SimpleDateFormat outputFormat = new SimpleDateFormat("dd MMM yyyy");

        JPanel eventCardsPanel = new JPanel(new CardLayout());
        int eventCount = 0;
        int totalEvents = 0;
        if (events != null && !events.isEmpty()) {
            for (Map.Entry<String, Object> eventEntry : events.entrySet()) {
                totalEvents++;
            }
            for (Map.Entry<String, Object> eventEntry : events.entrySet()) {
                String eventId = eventEntry.getKey();
                Map<String, Object> eventData = (Map<String, Object>) eventEntry.getValue();

                JPanel individualEventPanel = new JPanel(new BorderLayout());
                BasicStroke stroke = new BasicStroke();
                String imageUrl = (String) eventData.get("imageUrl");

                JPanel imagePanel = new JPanel();
                JLabel imageLabel = new JLabel("Loading image...");
                imagePanel.add(imageLabel);
                individualEventPanel.add(imagePanel, BorderLayout.WEST);
                String eventUrl = eventData.get("learnMore").toString();
                    try {
                        URL url = new URL(imageUrl);
                        ImageIcon originalIcon = new ImageIcon(url);
                        Image image = originalIcon.getImage();
                        Image scaledImage = image.getScaledInstance(50, 50, Image.SCALE_SMOOTH);
                        ImageIcon scaledIcon = new ImageIcon(scaledImage);
                        imageLabel.setIcon(scaledIcon);
                        imageLabel.setText("");
                        imageLabel.setPreferredSize(new Dimension(50, 50));
                    } catch (Exception e) {
                        e.printStackTrace();
                        SwingUtilities.invokeLater(() -> {
                            imageLabel.setText("Image load failed");
                        });
                    }

                JPanel eventInfoPanel = new JPanel();
                eventInfoPanel.setLayout(new BoxLayout(eventInfoPanel, BoxLayout.Y_AXIS));

                JLabel eventLabel = new JLabel(eventData.get("type").toString());
                JLabel eventStartLabel;
                try {
                    Date startDate = dateFormat.parse(eventData.get("startDate").toString());
                    eventStartLabel = new JLabel("Starts: " + outputFormat.format(startDate));
                } catch (Exception e) {
                    eventStartLabel = new JLabel("Starts: Unknown");
                }

                JLabel participatingLabel = null;
                if ((boolean) eventData.get("participating")) {
                    Map<String, Object> playerStatus = (Map<String, Object>) eventData.get("playerStatus");
                    participatingLabel = new JLabel("You have " + playerStatus.get("points") + " points. (Rank" + playerStatus.get("rank") + ")");
                }

                JLabel eventContentLabel = new JLabel(eventData.get("content").toString());

                eventInfoPanel.add(eventLabel);
                eventInfoPanel.add(eventContentLabel);
                eventInfoPanel.add(eventStartLabel);
                if (participatingLabel != null) {
                    eventInfoPanel.add(participatingLabel);
                }


                individualEventPanel.add(eventInfoPanel, BorderLayout.CENTER);
                TitledBorder titleBorder = BorderFactory.createTitledBorder(BorderFactory.createStrokeBorder(stroke), "Events (" + eventEntry.getKey() + "/" + totalEvents + ")");

                Color titleColor = Color.yellow;
                titleBorder.setTitleColor(titleColor);
                individualEventPanel.setBorder(titleBorder);

                JLabel learnMoreLink = new JLabel("<html>&#9432; Click to learn more</html>");
                eventInfoPanel.createToolTip();
                eventInfoPanel.setToolTipText("View this event on our website");
                eventInfoPanel.setCursor(new Cursor(Cursor.HAND_CURSOR));
                eventInfoPanel.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        try {
                            Desktop.getDesktop().browse(new URI(eventUrl));
                        } catch (IOException | URISyntaxException ex) {
                            ex.printStackTrace();
                        }
                    }

                    @Override
                    public void mouseEntered(MouseEvent e) {
                        learnMoreLink.setText("<html>&#9432; Click to learn more</html>");
                        eventInfoPanel.setCursor(new Cursor(Cursor.HAND_CURSOR));
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        learnMoreLink.setText("<html>&#9432; Click to learn more</html>");
                        eventInfoPanel.setCursor(new Cursor(Cursor.HAND_CURSOR));
                    }
                });

                eventCardsPanel.add(individualEventPanel, eventId);

                individualEventPanel.add(learnMoreLink, BorderLayout.SOUTH);
                eventCount++;
            }

            mainContentPanel.add(eventCardsPanel);

            if (eventCount > 1) {
                JButton prevButton = new JButton("<");
                JButton nextButton = new JButton(">");
                prevButton.addActionListener(e -> ((CardLayout) eventCardsPanel.getLayout()).previous(eventCardsPanel));
                nextButton.addActionListener(e -> ((CardLayout) eventCardsPanel.getLayout()).next(eventCardsPanel));

                JPanel buttonPanel = new JPanel(new FlowLayout());
                buttonPanel.add(prevButton);
                buttonPanel.add(nextButton);

                mainContentPanel.add(buttonPanel);
            }
        } else {
            JLabel noEventLabel = new JLabel("No current events.");
            noEventLabel.setAlignmentX(RIGHT_ALIGNMENT);
            mainContentPanel.add(noEventLabel, BorderLayout.WEST);
        }
        eventCardsPanel.setLayout(new CardLayout());
        mainContentPanel.add(eventCardsPanel);
        mainContentPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        mainContentPanel.revalidate();
        mainContentPanel.repaint();
    }
    private void updateEventDisplay() throws MalformedURLException {
        eventPanel.removeAll();
        eventPanel.setLayout(new BoxLayout(eventPanel, BoxLayout.Y_AXIS));

        if (currentEvents.size() > 0) {
            String eventId = String.valueOf(currentEventIndex + 1);
            Map<String, Object> eventDetails = (Map<String, Object>) currentEvents.get(eventId);

            if (eventDetails != null) {
                JLabel eventLabel = new JLabel("Type: " + eventDetails.get("type"));
                eventLabel.setAlignmentY(CENTER_ALIGNMENT);
                JLabel contentLabel = new JLabel("Content: " + eventDetails.get("content"));

                String imageUrl = (String) eventDetails.get("imageUrl");
                ImageIcon origImage = new ImageIcon(new URL(imageUrl));
                Image scaledImage = origImage.getImage().getScaledInstance(30, 30, Image.SCALE_SMOOTH);
                ImageIcon eventImage = new ImageIcon(scaledImage);
                JLabel imageLabel = new JLabel(eventImage);
                contentLabel.setAlignmentY(CENTER_ALIGNMENT);
                imageLabel.setAlignmentX(LEFT_ALIGNMENT);
                imageLabel.setAlignmentY(CENTER_ALIGNMENT);
                eventPanel.add(imageLabel);
                eventPanel.add(eventLabel);
                eventPanel.add(contentLabel);
            }

            if (currentEvents.size() > 1) {
                JButton prevButton = new JButton("<");
                JButton nextButton = new JButton(">");

                prevButton.addActionListener(e -> {
                    try {
                        navigateEvents(-1);
                    } catch (MalformedURLException ex) {
                        throw new RuntimeException(ex);
                    }
                });
                nextButton.addActionListener(e -> {
                    try {
                        navigateEvents(1);
                    } catch (MalformedURLException ex) {
                        throw new RuntimeException(ex);
                    }
                });

                JPanel navPanel = new JPanel();
                navPanel.add(prevButton);
                navPanel.add(nextButton);
                eventPanel.add(navPanel);
            }
        }

        eventPanel.revalidate();
        eventPanel.repaint();
    }
    private void navigateEvents(int direction) throws MalformedURLException {
        currentEventIndex += direction;
        if (currentEventIndex < 0) currentEventIndex = currentEvents.size() - 1;
        else if (currentEventIndex >= currentEvents.size()) currentEventIndex = 0;

        updateEventDisplay();
    }

    public void deinit()
    {
        eventBus.unregister(this);
    }

    /**
     * Builds a link panel with a given icon, text and url to redirect to.
     */
    private static JPanel buildRoundedPanel(ImageIcon icon, String description, String url)
    {
        return buildRoundedPanel(icon, description, () -> LinkBrowser.browse(url));
    }

    /**
     * Builds a link panel with a given icon, text and callable to call.
     */
    private static JPanel buildRoundedPanel(ImageIcon icon, String description, Runnable callback) {
        // Icon and text container with rounded corners
        JPanel roundedContainer = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
            }
        };
        roundedContainer.setOpaque(false);
        roundedContainer.setPreferredSize(new Dimension(35,35));
        JLabel iconLabel = new JLabel(icon);
        iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        roundedContainer.add(iconLabel);

        // Text label below the icon
        JPanel textPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JLabel textLabel = new JLabel(description, SwingConstants.CENTER);
        textLabel.setForeground(Color.LIGHT_GRAY);
        textLabel.setFont(FontManager.getRunescapeFont().deriveFont(16f));
        textLabel.setAlignmentX(CENTER_ALIGNMENT);
        textPanel.add(textLabel);
        textPanel.setPreferredSize(new Dimension(100, 30));
        // Main container for the icon and text
        JPanel mainContainer = new JPanel();
        mainContainer.setLayout(new BoxLayout(mainContainer, BoxLayout.Y_AXIS));
        mainContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        mainContainer.add(roundedContainer);
        mainContainer.add(Box.createRigidArea(new Dimension(0, 5))); // Space between icon and text
        mainContainer.add(textPanel);

        mainContainer.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                mainContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                callback.run();
                mainContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                mainContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
                mainContainer.setCursor(new Cursor(Cursor.HAND_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                mainContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                mainContainer.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
            }
        });

        return mainContainer;
    }
    private static String htmlLabel(String key, String value)
    {
        return "<html><body style = 'color:#a5a5a5'>" + key + "<span style = 'color:white'>" + value + "</span></body></html>";
    }

    @Subscribe
    public void onSessionOpen(SessionOpen sessionOpen)
    {
    }

    @Subscribe
    public void onSessionClose(SessionClose e)
    {
    }

    public void refreshData() {
        if(!config.useApi()) {
            return;
        }
        this.api.loadPanelData(true);

    }

}