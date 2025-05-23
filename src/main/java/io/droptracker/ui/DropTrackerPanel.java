package io.droptracker.ui;

import com.google.inject.Inject;
import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.api.DropTrackerApi;
import net.runelite.api.Client;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.info.InfoPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Map;

public class DropTrackerPanel extends PluginPanel implements DropTrackerApi.PanelDataLoadedCallback {
    private static final ImageIcon ARROW_RIGHT_ICON;
    private static final ImageIcon DISCORD_ICON;
    private static final ImageIcon WIKI_ICON;
    private static final ImageIcon TOP_LOGO;

    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final DropTrackerPlugin plugin;
    private final Client client;

    private IconTextField searchBar;
    private JPanel mainContentPanel;
    private final BossPanel bossPanel;
    @Inject
    private EventBus eventBus;

    @Inject
    public DropTrackerPanel(DropTrackerConfig config, DropTrackerApi api, DropTrackerPlugin plugin, Client client) {
        this.config = config;
        this.api = api;
        this.plugin = plugin;
        this.client = client;
        this.bossPanel = new BossPanel();  // Initialize BossPanel
        init();
    }

    // Initializes the panel components
    public void init() {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(10, 10, 10, 10));


        JPanel logoPanel = new JPanel(new BorderLayout());
        JLabel logoLabel = new JLabel(TOP_LOGO);
        logoLabel.setHorizontalAlignment(SwingConstants.CENTER);
        logoPanel.add(logoLabel, BorderLayout.CENTER);

        // Create the search panel
        JPanel searchPanel = new JPanel();
        searchPanel.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = GridBagConstraints.RELATIVE;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        gbc.insets = new Insets(0, 0, 0, 0);
        searchPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        if (config.useApi()) {
            searchBar = new IconTextField();
            searchBar.setIcon(IconTextField.Icon.SEARCH);
            searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            searchBar.addActionListener(e -> lookupPlayer());
            searchBar.addClearListener(this::resetPanel);

            JLabel infoText = new JLabel("<html><h2>Welcome to the DropTracker</h2>" +
                    "You should register your account<br>" +
                    "through our Discord bot (linked below) to " +
                    "enhance your experience with the plugin. " +
                    "Feel free to also visit our website to learn more.<br><br></html>");
            infoText.setForeground(Color.WHITE);
            JLabel searchBarText = new JLabel("Search for a player:");
            searchBarText.setForeground(Color.WHITE); // Ensure visibility on dark background

            searchPanel.add(infoText, gbc);
            searchPanel.add(searchBarText, gbc);
            searchPanel.add(searchBar, gbc);
        } else {
            JLabel noSearchText = new JLabel("<html>Enable the API (plugin config)<br>to search for players.</html>");
            noSearchText.setForeground(Color.WHITE);
            searchBar = new IconTextField();
            searchBar.setIcon(IconTextField.Icon.SEARCH);
            searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            searchBar.addClearListener(this::resetPanel);

            searchPanel.add(noSearchText, gbc);
            searchPanel.add(searchBar, gbc);
        }
        logoPanel.add(searchPanel, BorderLayout.SOUTH);

        mainContentPanel = new JPanel();
        mainContentPanel.setLayout(new BoxLayout(mainContentPanel, BoxLayout.Y_AXIS));
        mainContentPanel.add(logoPanel);
        add(mainContentPanel, BorderLayout.CENTER);

        // Footer buttons (Discord, Docs)
        JPanel actionsContainer = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        actionsContainer.setBorder(new EmptyBorder(10, 0, 0, 0));
        actionsContainer.add(buildRoundedPanel("Discord", "https://www.droptracker.io/discord"));
        actionsContainer.add(buildRoundedPanel("Website", "https://www.droptracker.io"));
        add(actionsContainer, BorderLayout.SOUTH);


        if (config.useApi()) {
            this.api.setDataLoadedCallback(this);
        }

        revalidate();
        repaint();
    }
    public void deinit()
    {
        eventBus.unregister(this);
    }
    static
    {
        ARROW_RIGHT_ICON = new ImageIcon(ImageUtil.loadImageResource(InfoPanel.class, "/util/arrow_right.png"));
        DISCORD_ICON = new ImageIcon(ImageUtil.loadImageResource(InfoPanel.class, "discord_icon.png"));
        WIKI_ICON = new ImageIcon(ImageUtil.loadImageResource(InfoPanel.class, "wiki_icon.png"));
        TOP_LOGO = new ImageIcon(ImageUtil.loadImageResource(DropTrackerPlugin.class, "toplogo.png"));
    }

    // Helper method to create rounded buttons
    private JPanel buildRoundedPanel(String text, String url) {
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
        roundedContainer.setPreferredSize(new Dimension(100, 35));

        JLabel textLabel = new JLabel(text, SwingConstants.CENTER);
        textLabel.setForeground(Color.LIGHT_GRAY);
        textLabel.setFont(FontManager.getRunescapeFont().deriveFont(16f));
        roundedContainer.add(textLabel, BorderLayout.CENTER);

        roundedContainer.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                roundedContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                LinkBrowser.browse(url);
                roundedContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                roundedContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                roundedContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            }
        });
        return roundedContainer;
    }

    // Resets the panel to its default state
    private void resetPanel() {
        searchBar.setIcon(IconTextField.Icon.SEARCH);
        searchBar.setEditable(true);


        bossPanel.removeAll();


        revalidate();
        repaint();
    }


    // Triggers the player lookup when the search bar is used
    private void lookupPlayer() {
        String playerName = searchBar.getText().trim();
        if (!playerName.isEmpty() && config.useApi()) {
            api.lookupPlayer(playerName).whenCompleteAsync((result, ex) -> updateAfterSearch(playerName, result, ex));
        }
    }

    private void updateAfterSearch(String playerName, Map<String, Object> result, Throwable ex) {
        SwingUtilities.invokeLater(() -> {
            if (ex != null || result == null) {
                searchBar.setIcon(IconTextField.Icon.ERROR);
                JLabel errorMessage = new JLabel("An error occurred: " + ex.getMessage());
                bossPanel.add(errorMessage, BorderLayout.CENTER);
                mainContentPanel.add(bossPanel);
                revalidate();
                repaint();
                return;
            }

            searchBar.setIcon(IconTextField.Icon.SEARCH);

            String message = (String) result.get("message");
            if (result.get("bossData") != null) {
                applySearchResult(result);
                return;
            }

            // If there is a message from the server, display it
            JLabel errorMessage;
            if (message != null) {
                errorMessage = new JLabel(message);
            } else {
                errorMessage = new JLabel("Couldn't search for this player...");
            }

            bossPanel.removeAll();  // Clear previous content
            bossPanel.add(errorMessage, BorderLayout.CENTER);
            mainContentPanel.add(bossPanel);
            revalidate();
            repaint();
        });
    }


    private void applySearchResult(Map<String, Object> data) {
        mainContentPanel.remove(bossPanel);

        Map<String, Map<String, Object>> bossData = (Map<String, Map<String, Object>>) data.get("bossData");
        if (bossData == null) {
            return;
        }

        // Clear the previous content in bossPanel
        bossPanel.removeAll();


        // Update the BossPanel with the new NPC data
        bossPanel.update(bossData);

        // Re-add the BossPanel to the mainContentPanel
        mainContentPanel.add(bossPanel);

        revalidate();
        repaint();
    }

    @Override
    public void onDataLoaded(Map<String, Object> data) {
        applySearchResult(data);
    }


}