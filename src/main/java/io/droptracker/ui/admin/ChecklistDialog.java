package io.droptracker.ui.admin;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

import io.droptracker.models.api.GroupAdmin;
import io.droptracker.ui.DropTrackerTheme;
import net.runelite.client.ui.FontManager;

/**
 * Picks several of a setting's options (Hall of Fame bosses, pets, slayer
 * masters): a search box, the options under their headings, and a count.
 * Nothing changes until Done.
 */
final class ChecklistDialog {

    private ChecklistDialog() {
    }

    static void open(Component owner, String title, List<GroupAdmin.Choice> options, Set<String> chosen,
                     Consumer<Set<String>> onDone) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(owner), title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setContentPane(content(options, chosen, dialog::dispose, picked -> {
            dialog.dispose();
            onDone.accept(picked);
        }));
        dialog.setSize(380, 480);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    static JPanel content(List<GroupAdmin.Choice> options, Set<String> chosen, Runnable close,
                          Consumer<Set<String>> onDone) {
        Set<String> picked = new LinkedHashSet<>(chosen);
        JPanel list = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
        list.setBorder(new EmptyBorder(4, 8, 4, 8));
        JLabel count = DropTrackerTheme.label(" ", FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Search");
        search.setFont(FontManager.getRunescapeSmallFont());

        Map<GroupAdmin.Choice, JCheckBox> boxes = new LinkedHashMap<>();
        for (GroupAdmin.Choice option : options) {
            JCheckBox box = new JCheckBox(option.getLabel() == null ? option.getValue() : option.getLabel(),
                picked.contains(option.getValue()));
            box.setFont(FontManager.getRunescapeSmallFont());
            box.setForeground(DropTrackerTheme.TEXT);
            box.setOpaque(false);
            box.addActionListener(e -> {
                if (box.isSelected()) {
                    picked.add(option.getValue());
                } else {
                    picked.remove(option.getValue());
                }
                count.setText(picked.size() + " of " + options.size() + " chosen");
            });
            boxes.put(option, box);
        }

        Runnable refresh = () -> {
            String needle = search.getText().trim().toLowerCase(Locale.ROOT);
            list.removeAll();
            String heading = null;
            for (Map.Entry<GroupAdmin.Choice, JCheckBox> entry : boxes.entrySet()) {
                JCheckBox box = entry.getValue();
                if (!box.getText().toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                String group = entry.getKey().getGroup();
                if (group != null && !group.equals(heading)) {
                    heading = group;
                    JLabel label = DropTrackerTheme.label(group, FontManager.getRunescapeSmallFont(), DropTrackerTheme.GOLD);
                    label.setBorder(new EmptyBorder(list.getComponentCount() == 0 ? 2 : 8, 2, 2, 0));
                    list.add(SettingsForm.left(label));
                }
                box.setSelected(picked.contains(entry.getKey().getValue()));
                list.add(SettingsForm.left(box));
            }
            if (list.getComponentCount() == 0) {
                list.add(SettingsForm.left(DropTrackerTheme.label("Nothing matches \"" + search.getText().trim() + "\".",
                    FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED)));
            }
            count.setText(picked.size() + " of " + options.size() + " chosen");
            list.revalidate();
            list.repaint();
        };
        SettingsForm.listen(search, refresh);

        JButton all = button("Select shown", () -> {
            boxes.forEach((option, box) -> {
                if (box.getParent() == list) {
                    picked.add(option.getValue());
                }
            });
            refresh.run();
        });
        JButton none = button("Clear", () -> {
            picked.clear();
            refresh.run();
        });
        JButton cancel = button("Cancel", close);
        JButton done = button("Done", () -> onDone.accept(picked));
        done.setForeground(DropTrackerTheme.GOLD_BRIGHT);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(search, BorderLayout.CENTER);
        top.add(count, BorderLayout.SOUTH);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(DropTrackerTheme.SURFACE_3));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setOpaque(false);
        buttons.add(row(all, none), BorderLayout.WEST);
        buttons.add(row(cancel, done), BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBackground(DropTrackerTheme.SURFACE_0);
        content.setBorder(new EmptyBorder(12, 12, 12, 12));
        content.add(top, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        content.registerKeyboardAction(e -> close.run(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
        refresh.run();
        return content;
    }

    private static JButton button(String text, Runnable action) {
        JButton b = DropTrackerTheme.button(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JPanel row(JComponent... items) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        for (JComponent item : items) {
            row.add(item);
        }
        return row;
    }
}
