package io.droptracker.ui.admin;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.text.JTextComponent;

import com.google.gson.*;

import io.droptracker.models.api.GroupAdmin;
import io.droptracker.ui.DropTrackerTheme;
import net.runelite.client.ui.FontManager;

/**
 * One settings category, drawn from the server's field list.
 *
 * <p>Each field becomes a row: name and help on the left, a control on the
 * right. Only the types in {@link GroupAdmin#KNOWN_TYPES} get a control; any
 * other type, and anything the server marks locked, is shown read-only. A
 * changed row gets a gold edge, and {@link #changes()} holds exactly the rows
 * whose value differs from what the server sent.
 */
class SettingsForm extends JPanel implements Scrollable {

    private static final int CONTROL_WIDTH = 210;

    private final List<Row> rows = new ArrayList<>();
    private final Runnable onDirty;

    SettingsForm(GroupAdmin.Settings settings, GroupAdmin.Category category, Runnable onDirty, Runnable openWebsite) {
        this.onDirty = onDirty;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(DropTrackerTheme.SURFACE_0);
        setBorder(new EmptyBorder(14, 16, 14, 16));

        add(left(DropTrackerTheme.label(category.getLabel(), FontManager.getRunescapeBoldFont(), DropTrackerTheme.GOLD)));
        if (category.getHelp() != null) {
            add(left(wrapped(category.getHelp(), DropTrackerTheme.TEXT_MUTED)));
        }
        add(DropTrackerTheme.gap(10));

        for (GroupAdmin.Field field : settings.getFields()) {
            if (category.getKey().equals(field.getCategory())) {
                Row row = new Row(field, settings, openWebsite);
                rows.add(row);
                add(left(row.panel));
                add(DropTrackerTheme.gap(6));
            }
        }
        if (rows.isEmpty()) {
            add(left(wrapped("Nothing to set here yet.", DropTrackerTheme.TEXT_MUTED)));
        }
    }

    /** Changed keys and their new values, ready to send. */
    Map<String, Object> changes() {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (Row row : rows) {
            if (row.dirty()) {
                JsonElement value = row.read.get();
                changes.put(row.field.getKey(), value == null || value.isJsonNull() ? null : value);
            }
        }
        return changes;
    }

    int dirtyCount() {
        int n = 0;
        for (Row row : rows) {
            n += row.dirty() ? 1 : 0;
        }
        return n;
    }

    /** False while a number is out of range: Save stays off until it's fixed. */
    boolean valid() {
        for (Row row : rows) {
            if ("int".equals(row.field.getType()) && intIssue(row.read.get(), row.field) != null) {
                return false;
            }
        }
        return true;
    }

    /** Shows the server's per-key problems under the rows they belong to. */
    void showErrors(Map<String, String> errors) {
        for (Row row : rows) {
            row.setError(errors.get(row.field.getKey()));
        }
    }

    // Scrollable: track the viewport's width so help text wraps instead of
    // widening the form.
    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
        return r.height - 32;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    /** One setting: its labels, its control, and how to read the control back. */
    private final class Row {
        final GroupAdmin.Field field;
        final JPanel panel = new JPanel(new BorderLayout(12, 0));
        final JLabel error = DropTrackerTheme.label(" ", FontManager.getRunescapeSmallFont(), DropTrackerTheme.RED);
        Supplier<JsonElement> read;
        String baseline;

        Row(GroupAdmin.Field field, GroupAdmin.Settings settings, Runnable openWebsite) {
            this.field = field;
            panel.setBackground(DropTrackerTheme.SURFACE_1);

            JPanel text = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
            JPanel title = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            title.setOpaque(false);
            title.add(DropTrackerTheme.label(field.getLabel() == null ? field.getKey() : field.getLabel(),
                FontManager.getRunescapeFont(), DropTrackerTheme.TEXT));
            if (field.isLocked()) {
                title.add(Box.createRigidArea(new Dimension(6, 0)));
                JLabel chip = DropTrackerTheme.chip(field.getLockedReason() == null ? "Locked" : field.getLockedReason(),
                    DropTrackerTheme.EMBER);
                title.add(chip);
            }
            text.add(left(title));
            if (field.getHelp() != null && !field.getHelp().isEmpty()) {
                text.add(left(wrapped(field.getHelp(), DropTrackerTheme.TEXT_MUTED)));
            }
            error.setVisible(false);
            text.add(left(error));
            panel.add(text, BorderLayout.CENTER);

            JComponent control = control(field, settings, openWebsite);
            JPanel east = new JPanel(new GridBagLayout());
            east.setOpaque(false);
            east.add(control);
            panel.add(east, BorderLayout.EAST);

            baseline = String.valueOf(read.get());
            paintEdge();
        }

        boolean dirty() {
            return !baseline.equals(String.valueOf(read.get()));
        }

        void changed() {
            setError(null);
            paintEdge();
            onDirty.run();
        }

        void setError(@Nullable String message) {
            error.setText(message == null ? " " : message);
            error.setVisible(message != null);
            panel.revalidate();
        }

        /** A gold edge on a changed row, so a leader sees what Save will send. */
        private void paintEdge() {
            boolean dirty = dirty();
            panel.setBorder(new CompoundBorder(
                new MatteBorder(0, 3, 0, 0, dirty ? DropTrackerTheme.GOLD : DropTrackerTheme.SURFACE_0),
                new CompoundBorder(BorderFactory.createLineBorder(dirty ? DropTrackerTheme.BRONZE : DropTrackerTheme.SURFACE_3),
                    new EmptyBorder(8, 10, 8, 10))));
        }

        private JComponent control(GroupAdmin.Field field, GroupAdmin.Settings settings, Runnable openWebsite) {
            JsonElement value = field.getValue();
            String type = field.getType() == null ? "" : field.getType();
            if (!GroupAdmin.KNOWN_TYPES.contains(type)) {
                read = () -> value;
                JButton web = new JButton("Edit on website");
                DropTrackerTheme.styleButton(web);
                web.addActionListener(e -> openWebsite.run());
                return web;
            }
            JComponent control;
            switch (type) {
                case "boolean": {
                    ToggleSwitch toggle = new ToggleSwitch(asBoolean(value), this::changed);
                    read = () -> new JsonPrimitive(toggle.isOn());
                    control = toggle;
                    break;
                }
                case "select":
                case "channel":
                case "role": {
                    List<GroupAdmin.Choice> choices = "select".equals(type) ? field.getOptions()
                        : "channel".equals(type) ? settings.getChannels() : settings.getRoles();
                    JComboBox<GroupAdmin.Choice> box = choiceBox(choices, asString(value), !"select".equals(type), type);
                    box.addActionListener(e -> changed());
                    read = () -> {
                        GroupAdmin.Choice choice = (GroupAdmin.Choice) box.getSelectedItem();
                        return choice == null || choice.getValue() == null ? JsonNull.INSTANCE
                            : new JsonPrimitive(choice.getValue());
                    };
                    control = box;
                    break;
                }
                case "int": {
                    JTextField input = textField(grouped(asString(value)));
                    input.setHorizontalAlignment(JTextField.RIGHT);
                    read = () -> {
                        String digits = input.getText().replaceAll("[,\\s]", "");
                        if (digits.isEmpty()) {
                            return JsonNull.INSTANCE;
                        }
                        try {
                            return new JsonPrimitive(Long.parseLong(digits));
                        } catch (NumberFormatException e) {
                            return new JsonPrimitive(input.getText());
                        }
                    };
                    listen(input, () -> {
                        changed();
                        setError(intIssue(read.get(), field));
                    });
                    control = input;
                    break;
                }
                case "text": {
                    JTextArea area = new JTextArea(asString(value), 3, 18);
                    area.setLineWrap(true);
                    area.setWrapStyleWord(true);
                    style(area);
                    read = () -> new JsonPrimitive(area.getText());
                    listen(area, this::changed);
                    JScrollPane scroll = new JScrollPane(area);
                    scroll.setBorder(BorderFactory.createLineBorder(DropTrackerTheme.SURFACE_3, 1));
                    scroll.setPreferredSize(new Dimension(CONTROL_WIDTH, 64));
                    control = scroll;
                    break;
                }
                default: {
                    JTextField input = textField(asString(value));
                    read = () -> new JsonPrimitive(input.getText());
                    listen(input, this::changed);
                    control = input;
                }
            }
            if (field.isLocked()) {
                control.setEnabled(false);
                control.setToolTipText(field.getLockedReason());
            }
            return control;
        }
    }

    /** "Must be at least 0", or null when the number is fine (or empty). */
    @Nullable
    static String intIssue(JsonElement value, GroupAdmin.Field field) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        long n;
        try {
            n = value.getAsLong();
        } catch (NumberFormatException e) {
            return "Numbers only.";
        }
        if (field.getMin() != null && n < field.getMin()) {
            return "Must be at least " + String.format("%,d", field.getMin()) + ".";
        }
        if (field.getMax() != null && n > field.getMax()) {
            return "Must be at most " + String.format("%,d", field.getMax()) + ".";
        }
        return null;
    }

    /** "2500000" as "2,500,000": GP thresholds are unreadable without commas. */
    static String grouped(String digits) {
        try {
            return String.format("%,d", Long.parseLong(digits.trim()));
        } catch (NumberFormatException e) {
            return digits;
        }
    }

    static boolean asBoolean(@Nullable JsonElement value) {
        String s = asString(value).trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes");
    }

    static String asString(@Nullable JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static JComboBox<GroupAdmin.Choice> choiceBox(List<GroupAdmin.Choice> choices, String current,
                                                          boolean allowNone, String type) {
        JComboBox<GroupAdmin.Choice> box = new JComboBox<>();
        if (allowNone) {
            box.addItem(new GroupAdmin.Choice(null, "None", null));
        }
        GroupAdmin.Choice selected = null;
        for (GroupAdmin.Choice c : choices) {
            box.addItem(c);
            if (c.getValue() != null && c.getValue().equals(current)) {
                selected = c;
            }
        }
        if (selected == null && !current.isEmpty()) {
            // A channel or role the bot can't see any more: keep it, but say so.
            selected = new GroupAdmin.Choice(current, "Unknown " + type + " (" + current + ")", null);
            box.addItem(selected);
        }
        box.setSelectedItem(selected != null ? selected : box.getItemCount() > 0 ? box.getItemAt(0) : null);
        box.setPreferredSize(new Dimension(CONTROL_WIDTH, box.getPreferredSize().height));
        box.setBackground(DropTrackerTheme.SURFACE_2);
        box.setForeground(DropTrackerTheme.TEXT);
        box.setFont(FontManager.getRunescapeSmallFont());
        String prefix = "channel".equals(type) ? "#" : "role".equals(type) ? "@" : "";
        box.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object item, int index, boolean selected,
                                                          boolean focus) {
                super.getListCellRendererComponent(list, item, index, selected, focus);
                GroupAdmin.Choice c = (GroupAdmin.Choice) item;
                if (c != null) {
                    String label = c.getLabel() == null ? c.getValue() : c.getLabel();
                    // Only real channels/roles get a # or @; "None" and a lost id don't.
                    String name = choices.contains(c) ? prefix + label : label;
                    // Channel categories trail in muted text inside the list only.
                    setText(index >= 0 && c.getGroup() != null
                        ? "<html>" + escape(name) + " <font color='#9a8a6a'>" + escape(c.getGroup()) + "</font></html>"
                        : name);
                }
                setFont(FontManager.getRunescapeSmallFont());
                setBackground(selected ? DropTrackerTheme.SURFACE_3 : DropTrackerTheme.SURFACE_2);
                setForeground(selected ? DropTrackerTheme.GOLD_BRIGHT : DropTrackerTheme.TEXT);
                return this;
            }
        });
        return box;
    }

    private static JTextField textField(String text) {
        JTextField input = new JTextField(text);
        style(input);
        input.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(DropTrackerTheme.SURFACE_3, 1), new EmptyBorder(4, 6, 4, 6)));
        input.setPreferredSize(new Dimension(CONTROL_WIDTH, input.getPreferredSize().height));
        return input;
    }

    private static void style(JTextComponent c) {
        c.setBackground(DropTrackerTheme.SURFACE_2);
        c.setForeground(DropTrackerTheme.TEXT);
        c.setCaretColor(DropTrackerTheme.GOLD_BRIGHT);
        c.setFont(FontManager.getRunescapeSmallFont());
    }

    private static void listen(JTextComponent c, Runnable onChange) {
        c.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onChange.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onChange.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
    }

    static JComponent wrapped(String text, Color color) {
        JTextArea area = new JTextArea(text);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setForeground(color);
        area.setFont(FontManager.getRunescapeSmallFont());
        area.setBorder(new EmptyBorder(2, 0, 0, 0));
        return area;
    }

    static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
