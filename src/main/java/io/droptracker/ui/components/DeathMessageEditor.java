package io.droptracker.ui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;

import io.droptracker.api.DeathMessageApi;
import io.droptracker.api.DropTrackerUrls;
import io.droptracker.models.api.DeathMessages;
import io.droptracker.ui.DropTrackerTheme;
import io.droptracker.util.DeathMessageRules;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

/**
 * The death message editor itself: five boxes, placeholders, a preview, and
 * where the message is posted. {@link DeathMessageDialog} puts it in a window.
 *
 * <p>What the member writes here is what their clan sees when they die — in
 * every clan whose leaders let members write their own. The same messages are
 * on the website and in Discord's {@code /settings}, so this is a view onto the
 * server's copy, never a local one: it loads on open and saves straight back.
 * Nothing here is a RuneLite config item, which would be per profile rather
 * than per account and a second copy to disagree with the server's.
 */
public class DeathMessageEditor extends JPanel {

    static final int WIDTH = 390;

    private final DeathMessageApi api;
    private final LongSupplier accountHash;
    private final Supplier<String> playerNameSupplier;
    private final Runnable onClose;
    private final Runnable onResize;

    private final List<JTextField> fields = new ArrayList<>();
    private final JLabel groupsLabel = new JLabel(" ");
    private final JLabel previewLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton saveButton = new JButton("Save");
    private final JPanel tokenPanel = new JPanel(new GridLayout(0, 2, 4, 4));

    private JTextField lastFocused;
    private long hash = -1;
    @Nullable
    private String playerName;
    private List<String> savedMessages = new ArrayList<>();
    private Map<String, String> samples = new HashMap<>();
    private boolean busy;
    private boolean closed;

    /**
     * @param onClose  what "Close" does (dispose the window)
     * @param onResize called after the content changes size (re-pack the window)
     */
    public DeathMessageEditor(DeathMessageApi api, LongSupplier accountHash, Supplier<String> playerName,
                              Runnable onClose, Runnable onResize) {
        this.api = api;
        this.accountHash = accountHash;
        this.playerNameSupplier = playerName;
        this.onClose = onClose;
        this.onResize = onResize;
        build();
    }

    /** Loads the server's copy for the logged-in account. */
    public void load() {
        hash = accountHash.getAsLong();
        playerName = playerNameSupplier.get();
        if (hash == -1) {
            showStatus("Log in first: your death message belongs to the account you're playing.",
                DropTrackerTheme.EMBER);
            return;
        }
        busy = true;
        showStatus("Loading...", DropTrackerTheme.TEXT_MUTED);
        api.fetch(playerName, hash, result -> {
            busy = false;
            if (closed) {
                return;
            }
            if (result.messages == null) {
                showStatus(result.error, DropTrackerTheme.RED);
                return;
            }
            apply(result.messages);
            setEditable(true);
            showStatus(" ", DropTrackerTheme.TEXT_MUTED);
            refresh();
        });
    }

    /** Stops late answers from touching a closed window. */
    public void markClosed() {
        closed = true;
    }

    private void build() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(DropTrackerTheme.SURFACE_0);
        setBorder(new EmptyBorder(10, 12, 10, 12));

        JLabel title = new JLabel("Your death message");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(DropTrackerTheme.GOLD);
        add(row(title));

        add(row(wrapped(
            "Write what your clan sees when you die. One of your messages is picked at random for each death, "
                + "in every clan whose leaders let members write their own. You can also edit these on the "
                + "DropTracker website or with /settings in Discord.")));
        add(Box.createRigidArea(new Dimension(0, 6)));

        groupsLabel.setFont(FontManager.getRunescapeSmallFont());
        groupsLabel.setForeground(DropTrackerTheme.TEXT_MUTED);
        add(row(groupsLabel));
        add(Box.createRigidArea(new Dimension(0, 6)));

        JPanel rows = new JPanel(new GridLayout(DeathMessageRules.MAX_MESSAGES, 1, 0, 4));
        rows.setBackground(DropTrackerTheme.SURFACE_0);
        for (int i = 0; i < DeathMessageRules.MAX_MESSAGES; i++) {
            JTextField field = new JTextField();
            // A fixed width: a text field's preferred width follows its text,
            // so a long message would otherwise widen the whole window.
            field.setPreferredSize(new Dimension(WIDTH - 24, field.getPreferredSize().height));
            field.setToolTipText("Message " + (i + 1) + " - leave blank if you don't need it");
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    refresh();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    refresh();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    refresh();
                }
            });
            field.addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    lastFocused = field;
                    refresh();
                }
            });
            fields.add(field);
            rows.add(field);
        }
        lastFocused = fields.get(0);
        add(row(rows));
        add(Box.createRigidArea(new Dimension(0, 6)));

        JLabel tokensTitle = new JLabel("Click a placeholder to add it:");
        tokensTitle.setFont(FontManager.getRunescapeSmallFont());
        tokensTitle.setForeground(DropTrackerTheme.TEXT_MUTED);
        add(row(tokensTitle));
        tokenPanel.setBackground(DropTrackerTheme.SURFACE_0);
        setTokens(DeathMessageRules.TOKENS);
        add(row(tokenPanel));
        add(Box.createRigidArea(new Dimension(0, 6)));

        previewLabel.setFont(FontManager.getRunescapeSmallFont());
        previewLabel.setForeground(DropTrackerTheme.TEXT);
        add(row(previewLabel));
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        add(row(statusLabel));
        add(Box.createRigidArea(new Dimension(0, 8)));

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBackground(DropTrackerTheme.SURFACE_0);
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftButtons.setBackground(DropTrackerTheme.SURFACE_0);
        DropTrackerTheme.styleButton(saveButton);
        saveButton.addActionListener(e -> save());
        JButton close = new JButton("Close");
        DropTrackerTheme.styleButton(close);
        close.addActionListener(e -> onClose.run());
        leftButtons.add(saveButton);
        leftButtons.add(Box.createRigidArea(new Dimension(6, 0)));
        leftButtons.add(close);
        buttons.add(leftButtons, BorderLayout.WEST);
        JButton website = new JButton("Open website");
        DropTrackerTheme.styleButton(website);
        website.setToolTipText("Edit your death messages on the DropTracker website");
        website.addActionListener(e -> LinkBrowser.browse(DropTrackerUrls.web("settings").toString()));
        buttons.add(website, BorderLayout.EAST);
        add(row(buttons));

        setEditable(false);
    }

    private void save() {
        List<String> rows = rows();
        String issue = DeathMessageRules.listIssue(rows);
        if (issue != null) {
            showStatus(issue, DropTrackerTheme.RED);
            return;
        }
        List<String> messages = DeathMessageRules.normalize(rows);
        busy = true;
        setEditable(false);
        showStatus("Saving...", DropTrackerTheme.TEXT_MUTED);
        api.save(playerName, hash, messages, result -> {
            busy = false;
            if (closed) {
                return;
            }
            setEditable(true);
            if (result.messages == null) {
                refresh();
                showStatus(result.error, DropTrackerTheme.RED);
                return;
            }
            apply(result.messages);
            refresh();
            showStatus(messages.isEmpty() ? "Cleared. Your clans will use their own death message."
                : "Saved.", DropTrackerTheme.GREEN);
        });
    }

    /** Puts the server's copy into the boxes, the groups line and the samples. */
    private void apply(DeathMessages loaded) {
        if (loaded.getPlayerName() != null) {
            playerName = loaded.getPlayerName();
        }
        savedMessages = new ArrayList<>(DeathMessageRules.normalize(loaded.getMessages()));
        for (int i = 0; i < fields.size(); i++) {
            fields.get(i).setText(i < savedMessages.size() ? savedMessages.get(i) : "");
        }
        samples = new HashMap<>();
        List<String> tokens = new ArrayList<>();
        for (DeathMessages.Token token : loaded.getTokens()) {
            if (token.getToken() == null) {
                continue;
            }
            tokens.add(token.getToken());
            samples.put(token.getToken(), token.getSample() == null ? "" : token.getSample());
        }
        setTokens(tokens.isEmpty() ? DeathMessageRules.TOKENS : tokens);
        groupsLabel.setText(groupsText(loaded.getGroups()));
        onResize.run();
    }

    /** Re-validates after any edit: the preview, the problem line and the Save button. */
    private void refresh() {
        if (busy) {
            return;
        }
        List<String> rows = rows();
        String issue = DeathMessageRules.listIssue(rows);
        boolean changed = !DeathMessageRules.normalize(rows).equals(savedMessages);
        saveButton.setEnabled(issue == null && changed && hash != -1 && fields.get(0).isEditable());

        String source = lastFocused != null && !lastFocused.getText().trim().isEmpty()
            ? lastFocused.getText() : firstNonBlank(rows);
        if (issue == null && source != null) {
            previewLabel.setText(html("Example: " + escape(DeathMessageRules.preview(source, samples, playerName))));
        } else {
            previewLabel.setText(" ");
        }
        if (issue != null) {
            showStatus(issue, DropTrackerTheme.RED);
        } else if (changed) {
            showStatus("Unsaved changes.", DropTrackerTheme.TEXT_MUTED);
        } else if (DropTrackerTheme.RED.equals(statusLabel.getForeground())) {
            showStatus(" ", DropTrackerTheme.TEXT_MUTED);
        }
    }

    private void insertToken(String token) {
        JTextField target = lastFocused != null ? lastFocused : fields.get(0);
        if (!target.isEditable()) {
            return;
        }
        String text = target.getText();
        int caret = Math.max(0, Math.min(target.getCaretPosition(), text.length()));
        boolean needsSpace = caret > 0 && !Character.isWhitespace(text.charAt(caret - 1));
        try {
            target.getDocument().insertString(caret, (needsSpace ? " " : "") + token, null);
        } catch (BadLocationException e) {
            target.setText(text + (needsSpace ? " " : "") + token);
        }
        target.requestFocusInWindow();
    }

    private void setTokens(List<String> tokens) {
        tokenPanel.removeAll();
        boolean editable = !fields.isEmpty() && fields.get(0).isEditable();
        for (String token : tokens) {
            JButton button = new JButton(token);
            DropTrackerTheme.styleButton(button);
            button.setEnabled(editable);
            button.addActionListener(e -> insertToken(token));
            tokenPanel.add(button);
        }
        tokenPanel.revalidate();
    }

    private void setEditable(boolean editable) {
        for (JTextField field : fields) {
            field.setEditable(editable);
        }
        for (Component c : tokenPanel.getComponents()) {
            c.setEnabled(editable);
        }
        saveButton.setEnabled(false);
    }

    private List<String> rows() {
        List<String> rows = new ArrayList<>();
        for (JTextField field : fields) {
            rows.add(field.getText());
        }
        return rows;
    }

    private void showStatus(@Nullable String text, Color color) {
        statusLabel.setText(text == null || text.trim().isEmpty() ? " " : html(escape(text)));
        statusLabel.setForeground(color);
    }

    // Package-private views for the tests.

    List<JTextField> fields() {
        return fields;
    }

    JButton saveButton() {
        return saveButton;
    }

    String statusText() {
        return statusLabel.getText();
    }

    String previewText() {
        return previewLabel.getText();
    }

    String groupsLabelText() {
        return groupsLabel.getText();
    }

    void clickToken(String token) {
        insertToken(token);
    }

    void clickSave() {
        save();
    }

    /** "Posted in: Clan A, Clan B (not switched on)" — where the message goes, and where not. */
    static String groupsText(List<DeathMessages.Group> groups) {
        if (groups == null || groups.isEmpty()) {
            return html("This account isn't in any clans on the DropTracker yet.");
        }
        StringBuilder sb = new StringBuilder("Posted in: ");
        for (int i = 0; i < groups.size(); i++) {
            DeathMessages.Group group = groups.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            String name = escape(group.getName() == null ? "Group " + group.getId() : group.getName());
            if (group.isBlocked()) {
                sb.append(colored(name + " (blocked by its leaders)", DropTrackerTheme.RED));
            } else if (group.isAllowed()) {
                sb.append(colored(name, DropTrackerTheme.GREEN));
            } else {
                sb.append(colored(name + " (not switched on)", DropTrackerTheme.TEXT_MUTED));
            }
        }
        return html(sb.toString());
    }

    @Nullable
    private static String firstNonBlank(List<String> rows) {
        for (String row : rows) {
            if (row != null && !row.trim().isEmpty()) {
                return row;
            }
        }
        return null;
    }

    private static String colored(String escapedText, Color color) {
        return String.format("<font color='#%06x'>%s</font>", color.getRGB() & 0xFFFFFF, escapedText);
    }

    private static String html(String body) {
        // Swing's HTML renderer scales a CSS width by 96/72, so ask for three
        // quarters of the space the text should actually take.
        return "<html><div style='width:" + ((WIDTH - 30) * 3 / 4) + "px'>" + body + "</div></html>";
    }

    /** Clan names and messages are user-written; a JLabel would render their HTML. */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static JTextArea wrapped(String text) {
        JTextArea area = new JTextArea(text);
        area.setWrapStyleWord(true);
        area.setLineWrap(true);
        area.setOpaque(false);
        area.setEditable(false);
        area.setFocusable(false);
        area.setForeground(DropTrackerTheme.TEXT_MUTED);
        area.setFont(FontManager.getRunescapeSmallFont());
        area.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        area.setSize(new Dimension(WIDTH - 24, Short.MAX_VALUE));
        return area;
    }

    private static JPanel row(Component component) {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(DropTrackerTheme.SURFACE_0);
        wrap.add(component, BorderLayout.CENTER);
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.setMaximumSize(new Dimension(WIDTH, Integer.MAX_VALUE));
        if (component instanceof JTextArea) {
            wrap.setPreferredSize(new Dimension(WIDTH - 24, component.getPreferredSize().height));
        }
        return wrap;
    }
}
