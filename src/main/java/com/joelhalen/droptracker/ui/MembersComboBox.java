package com.joelhalen.droptracker.ui;

import com.joelhalen.droptracker.DropTrackerPlugin;
import com.joelhalen.droptracker.DropTrackerPluginConfig;
import com.joelhalen.droptracker.WiseOldManClient;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MembersComboBox extends JButton {
    private DefaultListModel<CheckboxListItem> listModel;
    private JList<CheckboxListItem> list;
    private String[] groupMembers;
    private Map<String, CheckboxListItem> membersMap = new HashMap<>();
    public MembersComboBox(DropTrackerPlugin plugin, DropTrackerPluginConfig config) {
        // Get the group members when the MembersComboBox is instantiated
        /* Add caching to the member list so we don't update it every time a drop is received */
        String[] cachedGroupMembers = null;
        long cacheExpiryTime = 0; // Time when the cache expires
        groupMembers = plugin.getGroupMembers();

        // Create the popup menu with the group members
        JTextField filterField = new JTextField(15);
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateList();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateList();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateList();
            }

            public void updateList() {
                String searchText = filterField.getText();
                listModel.clear();

                for (String member : groupMembers) {
                    if (member.toLowerCase().contains(searchText.toLowerCase())) {
                        listModel.addElement(membersMap.get(member));
                    }
                }
            }
        });
        this.listModel = new DefaultListModel<>();
        for (String member : groupMembers) {
            CheckboxListItem item = new CheckboxListItem(member);
            listModel.addElement(item);
            membersMap.put(member, item);
        }
        this.list = new JList<>(listModel);
        list.setCellRenderer(new CheckboxListRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent event) {
                JList<CheckboxListItem> list = (JList<CheckboxListItem>) event.getSource();

                // Get index of item clicked
                int index = list.locationToIndex(event.getPoint());
                CheckboxListItem item = list.getModel().getElementAt(index);

                // Toggle selected state
                item.setSelected(!item.isSelected());

                // Repaint cell
                list.repaint(list.getCellBounds(index, index));
            }
        });
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        panel.add(filterField, BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);

        JPopupMenu popup = new JPopupMenu();
        popup.add(panel);

        // Show the popup menu when the button is clicked
        this.addActionListener(e -> popup.show(MembersComboBox.this, 0, MembersComboBox.this.getHeight()));
    }

    // New method to get the selected items
    public List<String> getSelectedItems() {
        List<String> selectedItems = new ArrayList<>();
        for (int i = 0; i < listModel.getSize(); i++) {
            CheckboxListItem item = listModel.getElementAt(i);
            if (item.isSelected()) {
                selectedItems.add(item.toString());
            }
        }
        return selectedItems;
    }

    class CheckboxListItem {
        private String label;
        private boolean isSelected = false;

        public CheckboxListItem(String label) {
            this.label = label;
        }

        public boolean isSelected() {
            return isSelected;
        }

        public void setSelected(boolean isSelected) {
            this.isSelected = isSelected;
        }

        public String toString() {
            return label;
        }
    }

    class CheckboxListRenderer extends JCheckBox implements ListCellRenderer<CheckboxListItem> {
        @Override
        public Component getListCellRendererComponent(JList<? extends CheckboxListItem> list, CheckboxListItem value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            setEnabled(list.isEnabled());
            setSelected(value.isSelected());
            setFont(list.getFont());
            setBackground(list.getBackground());
            setForeground(list.getForeground());
            setText(value.toString());
            return this;
        }
    }
}