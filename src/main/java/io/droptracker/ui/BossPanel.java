package io.droptracker.ui;

import io.droptracker.DropTrackerPlugin;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.ImageUtil;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.Objects;

public class BossPanel extends JPanel {
    private static final String[] COLUMNS = {"", "Loot", "Rank"};
    private JTable bossTable;
    private String[] npcNames;
    private Map<String, Map<String, Object>> npcDetailsCache; // Cache for npc details

    public BossPanel() {
        setLayout(new BorderLayout());
        npcNames = new String[0];
        bossTable = new JTable(new String[0][COLUMNS.length], COLUMNS) {
            @Override
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                int column = columnAtPoint(e.getPoint());
                if (row != -1) {
                    String npcName = npcNames[row];
                    Map<String, Object> details = npcDetailsCache.get(npcName);
                    return buildTooltip(details, row, column);
                }
                return super.getToolTipText();
            }
        };
        bossTable.setEnabled(false); // Prevent editing
        JScrollPane scrollPane = new JScrollPane(bossTable);
        add(scrollPane, BorderLayout.CENTER);
        setCustomRenderers();
    }

    public void update(Map<String, Map<String, Object>> npcData) {
        // Clear previous content from the bossPanel
        removeAll();

        if (npcData == null || npcData.isEmpty()) {
            revalidate();
            repaint();
            return;
        }

        // Ensure npcNames array matches the size of the incoming data
        npcNames = new String[npcData.size()];
        npcDetailsCache = npcData; // Store NPC data for tooltips

        String[][] rowData = new String[npcData.size()][COLUMNS.length];
        int i = 0;

        for (Map.Entry<String, Map<String, Object>> entry : npcData.entrySet()) {
            String npcName = entry.getKey();
            npcNames[i] = npcName;

            Map<String, Object> details = entry.getValue();
            Map<String, String> loot = (Map<String, String>) details.get("loot");
            if (loot.get("all-time").equals("0")) {
                // skip npcs that the user has never reported loot from
                continue;
            }
            Map<String, Object> rankDetails = (Map<String, Object>) details.get("rank");

            String globalRank = rankDetails.get("global") != null ? rankDetails.get("global").toString() : "--";

            // Store row data in array
            rowData[i][0] = npcName;
            rowData[i][1] = loot.get("all-time") + " gp";
            rowData[i][2] = globalRank;

            i++;
        }

        // Update the table model without recreating the table
        bossTable.setModel(new javax.swing.table.DefaultTableModel(rowData, COLUMNS));
        setCustomRenderers();

        // Re-add the updated table to the panel
        JScrollPane scrollPane = new JScrollPane(bossTable);
        if (rowData.length > 0) {
            int height = Math.min(bossTable.getRowHeight() * rowData.length + 300, 1000); // Limit the height
            scrollPane.setPreferredSize(new Dimension(scrollPane.getPreferredSize().width, height));
        }
        add(scrollPane, BorderLayout.CENTER);
        int windowHeight = Math.min(bossTable.getRowHeight() * rowData.length + 200, 600);
        Dimension preferredSize = new Dimension(220, windowHeight);
        setPreferredSize(preferredSize);
        // Revalidate and repaint the panel to trigger a layout update
        revalidate();
        repaint();
    }


    // Custom renderer to display NPC icons with tooltips
    private class NpcImageRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String npcName = npcNames[row];
            String imagePath;
            String lowerName = (npcName.toLowerCase());
            // Handle edge-case NPCs
            if (lowerName.contains("barrows")) {
                imagePath = sanitizeNpcNameForImagePath("barrows chests");
            } else if (lowerName.contains("fortis")) {
                imagePath = sanitizeNpcNameForImagePath("sol heredit");
            } else if(lowerName.contains("gauntlet")){
                imagePath = sanitizeNpcNameForImagePath("gauntlet");
            } else if(lowerName.contains(("perilous moons"))) {
                imagePath = sanitizeNpcNameForImagePath("lunar chests");
            } else if(lowerName.contains("dagannoth kings")) {
                imagePath = sanitizeNpcNameForImagePath("dagannoth rex");
            } else if(lowerName.contains(("theatre of blood"))) {
                imagePath = sanitizeNpcNameForImagePath("theatre of blood");
            } else if(lowerName.contains("chambers of xeric")) {
                imagePath = sanitizeNpcNameForImagePath("chambers of xeric");
            } else if(lowerName.contains("tombs of amascut")){
                imagePath = sanitizeNpcNameForImagePath("tombs of amascut");
            } else if(lowerName.contains("vet'ion")){
                imagePath = sanitizeNpcNameForImagePath("vetion");
            } else if( lowerName.contains("callisto")){
                imagePath = sanitizeNpcNameForImagePath("callisto");
            } else if(lowerName.contains("venenatis")){
                imagePath = sanitizeNpcNameForImagePath("venenatis");
            } else if(lowerName.contains("nightmare")){
                imagePath = sanitizeNpcNameForImagePath("nightmare");
            } else {
                imagePath = sanitizeNpcNameForImagePath(npcName);
            }

            // Load the NPC icon
            ImageIcon npcIcon = new ImageIcon(ImageUtil.loadImageResource(DropTrackerPlugin.class, imagePath));

            label.setIcon(npcIcon);
            label.setText("");  // Hides the NPC name text

            // Ensure npcDetailsCache is not null and contains the data for npcName
            if (npcDetailsCache != null && npcDetailsCache.containsKey(npcName)) {
                Map<String, Object> details = npcDetailsCache.get(npcName);
                label.setToolTipText(buildTooltip(details, row, column));
            } else {
                label.setToolTipText("No data available");
            }

            return label;
        }
        private String sanitizeNpcNameForImagePath(String npcName) {
            String sanitizedNpcName = npcName.toLowerCase().replace(" ", "_");
            sanitizedNpcName = sanitizedNpcName.replace("'", "").replace(",", "")
                    .replace("-", "_");

            if (sanitizedNpcName.equals("the_nightmare")) {
                sanitizedNpcName = "nightmare";
            }

            return "icons/bosses/" + sanitizedNpcName + ".png";
        }
    }


    // Renderer to apply alternating row colors and provide tooltips
    private class AlternatingColorRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            c.setBackground(row % 2 == 0 ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR);
            String npcName = npcNames[row];
            Map<String, Object> details = npcDetailsCache.get(npcName);
            return c;
        }
    }

    private String buildTooltip(Map<String, Object> details, int row, int column) {
        Map<String, String> loot = (Map<String, String>) details.get("loot");
        Map<String, Object> rank = (Map<String, Object>) details.get("rank");
        Map<String, Object> pbDetails = (Map<String, Object>) details.get("PB");

        String monthLoot = loot.get("month") != null ? loot.get("month").toString() : "--";
        String allTimeLoot = loot.get("all-time") != null ? loot.get("all-time").toString() : "--";

        String groupRank = rank != null && rank.get("clan") != null ? rank.get("clan").toString() : "--";
        String globalRank = rank != null && rank.get("global") != null ? rank.get("global").toString() : "--";

        String pbTime = pbDetails != null && pbDetails.get("time") != null ? pbDetails.get("time").toString() : "--";
        String pbRankGlobal = pbDetails != null && pbDetails.get("rank_global") != null ? pbDetails.get("rank_global").toString() : "--";
        String pbRankClan = pbDetails != null && pbDetails.get("rank_clan") != null ? pbDetails.get("rank_clan").toString() : "--";

        String tooltip = "<html>";
        switch (column) {
            case 1: // Loot column
                tooltip += "All-time: " + allTimeLoot + "<br>This month: " + monthLoot;
                break;
            case 2: // Rank column
                if (groupRank != null && !groupRank.equals("0")) {
                    tooltip += "Rank (global): " + globalRank + "<br>Rank (clan): " + groupRank;
                } else {
                    tooltip += "Rank (global): " + globalRank;
                }
                break;
//            case 3: // PB column
//                tooltip += "PB: " + pbTime + "<br>Global Rank: " + pbRankGlobal + "<br>Clan Rank: " + pbRankClan;
//                break;
            default:
                // show pb time on tooltip for the boss if there is one present in the server response
                if (!Objects.equals(pbTime, "--")) {
                    if (!pbRankClan.equals("--") && pbRankClan != null) {
                        tooltip += npcNames[row] + "<br>PB: " + pbTime + "<br>Global Rank: " + pbRankGlobal + "<br>Clan Rank: " + pbRankClan;
                    } else {
                        tooltip += npcNames[row] + "<br>PB: " + pbTime + " (Rank: " + pbRankGlobal + ")";
                    }
                } else {
                    tooltip += npcNames[row];
            }
                break;
        }
        tooltip += "</html>";
        return tooltip;
    }

    private void setCustomRenderers() {
        // Get the table's column model
        TableColumnModel columnModel = bossTable.getColumnModel();

        TableColumn npcColumn = columnModel.getColumn(0);
        npcColumn.setCellRenderer(new NpcImageRenderer());
        npcColumn.setPreferredWidth(50);

        TableColumn lootColumn = columnModel.getColumn(1);
        lootColumn.setCellRenderer(new AlternatingColorRenderer());
        lootColumn.setPreferredWidth(150);

        // Column 2 (Rank)
        TableColumn rankColumn = columnModel.getColumn(2);
        rankColumn.setCellRenderer(new AlternatingColorRenderer());
        rankColumn.setPreferredWidth(75);

    }

}
