package io.droptracker.ui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.StrokeBorder;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

public class LeaderboardComponents {

    /**
     * Creates a standard header panel with title and search functionality
     */
    public static HeaderResult createHeaderPanel(String title, String searchPlaceholder, Runnable searchAction) {
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        headerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 90));
        headerPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 90));
        headerPanel.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH, 90));
        
        // Create a vertical panel for title and search
        JPanel titleAndSearchPanel = new JPanel();
        titleAndSearchPanel.setLayout(new BoxLayout(titleAndSearchPanel, BoxLayout.Y_AXIS));
        titleAndSearchPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(FontManager.getRunescapeBoldFont());
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        titleLabel.setHorizontalAlignment(JLabel.CENTER);
        titleLabel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
        titleLabel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
        
        // Search field
        JPanel searchPanel = new JPanel(new BorderLayout(5, 0));
        searchPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        searchPanel.setBorder(new StrokeBorder(new BasicStroke(1), ColorScheme.DARKER_GRAY_HOVER_COLOR));
        searchPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        searchPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));
        searchPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));
        searchPanel.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));
        
        JTextField searchField = new JTextField();
        searchField.setBorder(new StrokeBorder(new BasicStroke(1), ColorScheme.DARKER_GRAY_HOVER_COLOR));
        searchField.setToolTipText(searchPlaceholder);
        searchField.setHorizontalAlignment(JTextField.LEFT);
        searchField.setMargin(new Insets(5, 8, 5, 8));
        searchField.setFont(FontManager.getRunescapeSmallFont());
        searchField.setPreferredSize(new Dimension(200, 35));
        searchField.setMinimumSize(new Dimension(100, 35));
        
        JButton searchButton = new JButton("Search");
        searchButton.setPreferredSize(new Dimension(70, 35));
        searchButton.setMaximumSize(new Dimension(70, 35));
        searchButton.setMinimumSize(new Dimension(70, 35));
        searchButton.setMargin(new Insets(5, 5, 5, 5));
        searchButton.addActionListener(e -> searchAction.run());
        
        searchPanel.add(searchField, BorderLayout.CENTER);
        searchPanel.add(searchButton, BorderLayout.EAST);
        
        titleAndSearchPanel.add(titleLabel);
        titleAndSearchPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        titleAndSearchPanel.add(searchPanel);
        
        headerPanel.add(titleAndSearchPanel, BorderLayout.CENTER);
        
        return new HeaderResult(headerPanel, searchField);
    }

    /**
     * Creates a standard leaderboard table with rank, name, and value columns
     */
    public static <T> JPanel createLeaderboardTable(String title, String nameColumnHeader, 
            List<T> data, LeaderboardItemRenderer<T> renderer) {
        
        JPanel leaderboardPanel = new JPanel();
        leaderboardPanel.setLayout(new BoxLayout(leaderboardPanel, BoxLayout.Y_AXIS));
        leaderboardPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        leaderboardPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        // Title for the leaderboard
        JLabel leaderboardTitle = new JLabel(title);
        leaderboardTitle.setFont(FontManager.getRunescapeBoldFont());
        leaderboardTitle.setForeground(Color.WHITE);
        leaderboardTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        leaderboardTitle.setHorizontalAlignment(JLabel.CENTER);
        
        // Create table container
        JPanel tableContainer = new JPanel();
        tableContainer.setLayout(new BoxLayout(tableContainer, BoxLayout.Y_AXIS));
        tableContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        tableContainer.setBorder(new EmptyBorder(10, 10, 10, 10));
        tableContainer.setAlignmentX(Component.CENTER_ALIGNMENT);
        tableContainer.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 200));
        
        // Create table header
        JPanel headerRow = createTableHeader(nameColumnHeader);
        
        // Create data rows
        JPanel dataContainer = new JPanel();
        dataContainer.setLayout(new BoxLayout(dataContainer, BoxLayout.Y_AXIS));
        dataContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        dataContainer.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        if (data != null && !data.isEmpty()) {
            int displayRank = 1;
            for (T item : data) {
                if (displayRank > 5) break; // Only show top 5
                
                JPanel dataRow = createDataRow(item, displayRank, renderer);
                dataContainer.add(dataRow);
                
                if (displayRank < Math.min(5, data.size())) {
                    dataContainer.add(Box.createRigidArea(new Dimension(0, 3)));
                }
                displayRank++;
            }
        } else {
            // No data fallback
            JLabel noDataLabel = new JLabel("No leaderboard data available");
            noDataLabel.setFont(FontManager.getRunescapeSmallFont());
            noDataLabel.setForeground(Color.LIGHT_GRAY);
            noDataLabel.setHorizontalAlignment(JLabel.CENTER);
            noDataLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            dataContainer.add(noDataLabel);
        }
        
        // Assemble the table
        tableContainer.add(headerRow);
        tableContainer.add(Box.createRigidArea(new Dimension(0, 5)));
        tableContainer.add(dataContainer);
        
        // Add to main panel
        leaderboardPanel.add(leaderboardTitle);
        leaderboardPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        leaderboardPanel.add(tableContainer);
        
        return leaderboardPanel;
    }

    /**
     * Creates table header row
     */
    private static JPanel createTableHeader(String nameColumnHeader) {
        JPanel headerRow = new JPanel(new BorderLayout(5, 0));
        headerRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        headerRow.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
        headerRow.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
        
        JLabel rankHeader = new JLabel("Rank");
        rankHeader.setFont(FontManager.getRunescapeBoldFont());
        rankHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        rankHeader.setHorizontalAlignment(JLabel.LEFT);
        rankHeader.setPreferredSize(new Dimension(40, 25));
        
        JPanel nameAndLootHeader = new JPanel(new BorderLayout(10, 0));
        nameAndLootHeader.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JLabel nameHeader = new JLabel(nameColumnHeader);
        nameHeader.setFont(FontManager.getRunescapeBoldFont());
        nameHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        nameHeader.setHorizontalAlignment(JLabel.LEFT);
        
        JLabel lootHeader = new JLabel("Loot");
        lootHeader.setFont(FontManager.getRunescapeBoldFont());
        lootHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        lootHeader.setHorizontalAlignment(JLabel.RIGHT);
        lootHeader.setPreferredSize(new Dimension(50, 25));
        
        nameAndLootHeader.add(nameHeader, BorderLayout.CENTER);
        nameAndLootHeader.add(lootHeader, BorderLayout.EAST);
        
        headerRow.add(rankHeader, BorderLayout.WEST);
        headerRow.add(nameAndLootHeader, BorderLayout.CENTER);
        
        return headerRow;
    }

    /**
     * Creates a data row for the leaderboard table
     */
    private static <T> JPanel createDataRow(T item, int displayRank, LeaderboardItemRenderer<T> renderer) {
        JPanel dataRow = new JPanel(new BorderLayout(5, 0));
        dataRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        dataRow.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
        dataRow.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
        
        // Rank
        Integer apiRank = renderer.getRank(item);
        int rankToShow = (apiRank != null) ? apiRank : displayRank;
        
        JLabel rankLabel = new JLabel("#" + rankToShow);
        rankLabel.setFont(FontManager.getRunescapeSmallFont());
        rankLabel.setForeground(rankToShow <= 3 ? ColorScheme.PROGRESS_COMPLETE_COLOR : Color.WHITE);
        rankLabel.setHorizontalAlignment(JLabel.LEFT);
        rankLabel.setPreferredSize(new Dimension(40, 25));
        
        // Name and loot panel
        JPanel nameAndLootData = new JPanel(new BorderLayout(10, 0));
        nameAndLootData.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        // Name button
        String name = renderer.getName(item);
        JButton nameButton = new JButton("<html>" + name + "&nbsp;&nbsp;<img src='https://www.droptracker.io/img/external-8px-g.png'></html>");
        nameButton.setFont(FontManager.getRunescapeSmallFont());
        nameButton.setForeground(Color.WHITE);
        nameButton.setHorizontalAlignment(JLabel.LEFT);
        nameButton.setBorder(null);
        nameButton.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        nameButton.setOpaque(false);
        nameButton.setContentAreaFilled(false);
        nameButton.addActionListener(e -> renderer.onItemClick(item));
        
        // Loot value
        String lootValue = renderer.getLootValue(item);
        JLabel lootLabel = new JLabel(lootValue);
        lootLabel.setFont(FontManager.getRunescapeSmallFont());
        lootLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
        lootLabel.setHorizontalAlignment(JLabel.RIGHT);
        lootLabel.setPreferredSize(new Dimension(50, 25));
        
        nameAndLootData.add(nameButton, BorderLayout.CENTER);
        nameAndLootData.add(lootLabel, BorderLayout.EAST);
        
        dataRow.add(rankLabel, BorderLayout.WEST);
        dataRow.add(nameAndLootData, BorderLayout.CENTER);
        
        return dataRow;
    }

    /**
     * Creates a loading placeholder panel
     */
    public static JPanel createLoadingPlaceholder(String loadingText) {
        JPanel placeholder = new JPanel();
        placeholder.setLayout(new BoxLayout(placeholder, BoxLayout.Y_AXIS));
        placeholder.setBackground(ColorScheme.DARK_GRAY_COLOR);
        placeholder.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        JLabel loadingLabel = new JLabel(loadingText);
        loadingLabel.setFont(FontManager.getRunescapeSmallFont());
        loadingLabel.setForeground(Color.LIGHT_GRAY);
        loadingLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        loadingLabel.setHorizontalAlignment(JLabel.CENTER);
        
        placeholder.add(loadingLabel);
        return placeholder;
    }

    /**
     * Replaces a placeholder panel with the actual content
     */
    public static void replacePlaceholder(JPanel placeholder, JPanel replacement) {
        SwingUtilities.invokeLater(() -> {
            if (placeholder != null && replacement != null) {
                Container parent = placeholder.getParent();
                if (parent != null) {
                    // Find the index of the placeholder
                    int index = -1;
                    for (int i = 0; i < parent.getComponentCount(); i++) {
                        if (parent.getComponent(i) == placeholder) {
                            index = i;
                            break;
                        }
                    }
                    
                    if (index != -1) {
                        parent.remove(index);
                        parent.add(replacement, index);
                        parent.revalidate();
                        parent.repaint();
                    }
                }
            }
        });
    }

    /**
     * Creates an error panel for search failures
     */
    public static JPanel createErrorPanel(String message, Runnable backAction) {
        JPanel errorPanel = new JPanel();
        errorPanel.setLayout(new BoxLayout(errorPanel, BoxLayout.Y_AXIS));
        errorPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        errorPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        errorPanel.add(Box.createRigidArea(new Dimension(0, 50)));
        
        JLabel errorLabel = new JLabel(message);
        errorLabel.setFont(FontManager.getRunescapeFont());
        errorLabel.setForeground(Color.RED);
        errorLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        errorLabel.setHorizontalAlignment(JLabel.CENTER);
        
        JButton backButton = new JButton("Back to Search");
        backButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        backButton.addActionListener(e -> backAction.run());
        
        errorPanel.add(errorLabel);
        errorPanel.add(Box.createRigidArea(new Dimension(0, 20)));
        errorPanel.add(backButton);
        errorPanel.add(Box.createVerticalGlue());
        
        return errorPanel;
    }

    /**
     * Creates a clear button for search results
     */
    public static JButton createClearButton(Runnable clearAction) {
        JButton clearButton = new JButton("×");
        clearButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        clearButton.setForeground(Color.LIGHT_GRAY);
        clearButton.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        clearButton.setBorder(new EmptyBorder(5, 8, 5, 8));
        clearButton.setPreferredSize(new Dimension(30, 30));
        clearButton.setMaximumSize(new Dimension(30, 30));
        clearButton.setMinimumSize(new Dimension(30, 30));
        clearButton.setToolTipText("Clear search");
        clearButton.setOpaque(false);
        clearButton.setContentAreaFilled(false);
        clearButton.addActionListener(e -> clearAction.run());
        return clearButton;
    }

    /**
     * Handles asynchronous leaderboard loading
     */
    public static <T> void loadLeaderboardAsync(JPanel placeholder, 
            Supplier<T> dataLoader, 
            java.util.function.Function<T, JPanel> panelCreator) {
        
        CompletableFuture.supplyAsync(() -> {
            try {
                T data = dataLoader.get();
                if (data != null) {
                    return panelCreator.apply(data);
                }
            } catch (Exception e) {
            }
            return null;
        }).thenAccept(leaderboardPanel -> {
            if (leaderboardPanel != null) {
                replacePlaceholder(placeholder, leaderboardPanel);
            }
        });
    }

    /**
     * Result container for header creation
     */
    public static class HeaderResult {
        public final JPanel panel;
        public final JTextField searchField;

        public HeaderResult(JPanel panel, JTextField searchField) {
            this.panel = panel;
            this.searchField = searchField;
        }
    }

    /**
     * Interface for rendering leaderboard items
     */
    public interface LeaderboardItemRenderer<T> {
        String getName(T item);
        String getLootValue(T item);
        Integer getRank(T item);
        void onItemClick(T item);
    }
} 