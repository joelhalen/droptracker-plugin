package io.droptracker.ui.components;

import lombok.extern.slf4j.Slf4j;

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

import io.droptracker.ui.DropTrackerTheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

@Slf4j
public class LeaderboardComponents {

    /**
     * Creates a standard header panel with title and search functionality
     */
    public static HeaderResult createHeaderPanel(String title, String searchPlaceholder, Runnable searchAction) {
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(DropTrackerTheme.SURFACE_1);
        headerPanel.setBorder(DropTrackerTheme.cardBorder(10, 10, 10, 10));
        headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 90));
        headerPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 90));
        headerPanel.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH, 90));
        
        JPanel titleAndSearchPanel = new JPanel();
        titleAndSearchPanel.setLayout(new BoxLayout(titleAndSearchPanel, BoxLayout.Y_AXIS));
        titleAndSearchPanel.setBackground(DropTrackerTheme.SURFACE_1);
        
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(FontManager.getRunescapeBoldFont());
        titleLabel.setForeground(DropTrackerTheme.TEXT);
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        titleLabel.setHorizontalAlignment(JLabel.CENTER);
        titleLabel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
        titleLabel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
        
        // Search field
        JPanel searchPanel = new JPanel(new BorderLayout(5, 0));
        searchPanel.setBackground(DropTrackerTheme.SURFACE_2);
        searchPanel.setBorder(new StrokeBorder(new BasicStroke(1), DropTrackerTheme.SURFACE_3));
        searchPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        searchPanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));
        searchPanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));
        searchPanel.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 35));
        
        JTextField searchField = new JTextField();
        searchField.setBackground(DropTrackerTheme.SURFACE_2);
        searchField.setForeground(DropTrackerTheme.TEXT);
        searchField.setCaretColor(DropTrackerTheme.GOLD);
        searchField.setBorder(new StrokeBorder(new BasicStroke(1), DropTrackerTheme.SURFACE_3));
        searchField.setToolTipText(searchPlaceholder);
        searchField.setHorizontalAlignment(JTextField.LEFT);
        searchField.setMargin(new Insets(5, 8, 5, 8));
        searchField.setFont(FontManager.getRunescapeSmallFont());
        searchField.setPreferredSize(new Dimension(200, 35));
        searchField.setMinimumSize(new Dimension(100, 35));
        
        JButton searchButton = new JButton("Search");
        DropTrackerTheme.styleButton(searchButton);
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
        leaderboardPanel.setBackground(DropTrackerTheme.SURFACE_0);
        leaderboardPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        // Title for the leaderboard
        JLabel leaderboardTitle = new JLabel(title);
        leaderboardTitle.setFont(FontManager.getRunescapeBoldFont());
        leaderboardTitle.setForeground(DropTrackerTheme.TEXT);
        leaderboardTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        leaderboardTitle.setHorizontalAlignment(JLabel.CENTER);
        
        JPanel tableContainer = new JPanel();
        tableContainer.setLayout(new BoxLayout(tableContainer, BoxLayout.Y_AXIS));
        tableContainer.setBackground(DropTrackerTheme.SURFACE_1);
        tableContainer.setBorder(DropTrackerTheme.cardBorder(10, 10, 10, 10));
        tableContainer.setAlignmentX(Component.CENTER_ALIGNMENT);
        tableContainer.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 200));
        
        JPanel headerRow = createTableHeader(nameColumnHeader);
        
        JPanel dataContainer = new JPanel();
        dataContainer.setLayout(new BoxLayout(dataContainer, BoxLayout.Y_AXIS));
        dataContainer.setBackground(DropTrackerTheme.SURFACE_1);
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
            noDataLabel.setForeground(DropTrackerTheme.TEXT_MUTED);
            noDataLabel.setHorizontalAlignment(JLabel.CENTER);
            noDataLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            dataContainer.add(noDataLabel);
        }
        
        // Assemble the table
        tableContainer.add(headerRow);
        tableContainer.add(Box.createRigidArea(new Dimension(0, 5)));
        tableContainer.add(dataContainer);
        
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
        headerRow.setBackground(DropTrackerTheme.SURFACE_1);
        headerRow.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
        headerRow.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
        
        JLabel rankHeader = new JLabel("Rank");
        rankHeader.setFont(FontManager.getRunescapeBoldFont());
        rankHeader.setForeground(DropTrackerTheme.TEXT_MUTED);
        rankHeader.setHorizontalAlignment(JLabel.LEFT);
        rankHeader.setPreferredSize(new Dimension(40, 25));
        
        JPanel nameAndLootHeader = new JPanel(new BorderLayout(10, 0));
        nameAndLootHeader.setBackground(DropTrackerTheme.SURFACE_1);
        
        JLabel nameHeader = new JLabel(nameColumnHeader);
        nameHeader.setFont(FontManager.getRunescapeBoldFont());
        nameHeader.setForeground(DropTrackerTheme.TEXT_MUTED);
        nameHeader.setHorizontalAlignment(JLabel.LEFT);
        
        JLabel lootHeader = new JLabel("Loot");
        lootHeader.setFont(FontManager.getRunescapeBoldFont());
        lootHeader.setForeground(DropTrackerTheme.TEXT_MUTED);
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
        dataRow.setBackground(DropTrackerTheme.SURFACE_1);
        dataRow.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
        dataRow.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 25));
        
        // Rank
        Integer apiRank = renderer.getRank(item);
        int rankToShow = (apiRank != null) ? apiRank : displayRank;
        
        JLabel rankLabel = new JLabel("#" + rankToShow);
        rankLabel.setFont(FontManager.getRunescapeSmallFont());
        rankLabel.setForeground(rankToShow <= 3 ? DropTrackerTheme.GOLD : DropTrackerTheme.TEXT);
        rankLabel.setHorizontalAlignment(JLabel.LEFT);
        rankLabel.setPreferredSize(new Dimension(40, 25));
        
        // Name and loot panel
        JPanel nameAndLootData = new JPanel(new BorderLayout(10, 0));
        nameAndLootData.setBackground(DropTrackerTheme.SURFACE_1);
        
        // Name button. No remote <img> tags: Swing HTML would fetch them over the network.
        String name = renderer.getName(item);
        JButton nameButton = new JButton(name);
        nameButton.setFont(FontManager.getRunescapeSmallFont());
        nameButton.setForeground(DropTrackerTheme.TEXT);
        nameButton.setHorizontalAlignment(JLabel.LEFT);
        nameButton.setBorder(null);
        nameButton.setOpaque(false);
        nameButton.setContentAreaFilled(false);
        nameButton.setFocusPainted(false);
        nameButton.setToolTipText("View details");
        nameButton.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        nameButton.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                nameButton.setForeground(DropTrackerTheme.GOLD_BRIGHT);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                nameButton.setForeground(DropTrackerTheme.TEXT);
            }
        });
        nameButton.addActionListener(e -> renderer.onItemClick(item));
        
        // Loot value
        String lootValue = renderer.getLootValue(item);
        JLabel lootLabel = new JLabel(lootValue);
        lootLabel.setFont(FontManager.getRunescapeSmallFont());
        lootLabel.setForeground(DropTrackerTheme.GOLD);
        lootLabel.setHorizontalAlignment(JLabel.RIGHT);
        lootLabel.setPreferredSize(new Dimension(50, 25));
        
        nameAndLootData.add(nameButton, BorderLayout.CENTER);
        nameAndLootData.add(lootLabel, BorderLayout.EAST);
        
        dataRow.add(rankLabel, BorderLayout.WEST);
        dataRow.add(nameAndLootData, BorderLayout.CENTER);
        
        return dataRow;
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
     * Creates a clear button for search results
     */
    public static JButton createClearButton(Runnable clearAction) {
        JButton clearButton = new JButton("×");
        clearButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        clearButton.setForeground(DropTrackerTheme.TEXT_MUTED);
        clearButton.setBackground(DropTrackerTheme.SURFACE_1);
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
                log.debug("Async leaderboard load failed: {}", e.getMessage());
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