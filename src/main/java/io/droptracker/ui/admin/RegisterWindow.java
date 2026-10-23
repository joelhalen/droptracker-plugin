package io.droptracker.ui.admin;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;

import javax.annotation.Nullable;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.*;
import io.droptracker.models.api.GroupAdmin;
import io.droptracker.ui.DropTrackerTheme;
import io.droptracker.ui.components.PanelElements;
import net.runelite.api.Client;
import net.runelite.api.clan.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

/**
 * Registers a group without leaving the game: a name and a WiseOldMan group
 * (one the leader has, or one made from their clan's member list), then one
 * trip through Discord to add the bot, which also proves they manage the
 * server. The window waits for that and opens the group manager.
 */
public final class RegisterWindow extends JPanel {

    private static final Pattern WOM_ID = Pattern.compile("(?:groups/)?(\\d{1,9})/?\\s*$");
    @Nullable
    private static JDialog openDialog;

    private final Client client;
    private final DropTrackerConfig config;
    private final GroupAdminApi api;
    private final CardLayout cards = new CardLayout();
    private final JTextField name = new JTextField();
    private final JRadioButton create = new JRadioButton();
    private final JRadioButton existing = new JRadioButton("I already have one:");
    private final JTextField womId = new JTextField();
    private final JLabel error = label(" ", DropTrackerTheme.RED);
    private final JButton next = DropTrackerTheme.button("Continue");
    private final JLabel waitTitle = bold(" ");
    private final JLabel waiting = label(" ", DropTrackerTheme.TEXT_MUTED);
    private final JLabel doneTitle = bold(" ");
    private final JComponent doneText = SettingsForm.wrapped(" ", DropTrackerTheme.TEXT_MUTED);
    private final JButton manage = DropTrackerTheme.button("Open group manager");
    private final javax.swing.Timer poll;

    private List<Map<String, String>> roster = new ArrayList<>();
    @Nullable
    private String id;
    private long deadline;
    private GroupAdmin.RegistrationStatus done;

    private RegisterWindow(Client client, DropTrackerConfig config, GroupAdminApi api) {
        this.client = client;
        this.config = config;
        this.api = api;
        setLayout(cards);
        setPreferredSize(new Dimension(440, 300));
        poll = new javax.swing.Timer(3000, e -> check());
        add(form(), "form");
        add(waitPage(), "wait");
        add(donePage(), "done");
    }

    /** Opens the window, or brings the open one forward. Call on the Swing thread. */
    public static void open(Client client, ClientThread clientThread, DropTrackerConfig config, GroupAdminApi api) {
        if (openDialog != null && openDialog.isDisplayable()) {
            openDialog.toFront();
            return;
        }
        RegisterWindow window = new RegisterWindow(client, config, api);
        JDialog dialog = new JDialog(PanelElements.getParentFrame(client), "DropTracker - Register your group", false);
        dialog.setContentPane(window);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                window.poll.stop();
                openDialog = null;
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(dialog.getParent());
        openDialog = dialog;
        dialog.setVisible(true);
        clientThread.invoke(() -> {
            ClanSettings clan = client.getClanSettings();
            String clanName = clan != null ? clan.getName() : null;
            List<Map<String, String>> members = new ArrayList<>();
            if (clan != null) {
                for (ClanMember m : clan.getMembers()) {
                    ClanTitle title = clan.titleForRank(m.getRank());
                    Map<String, String> row = new HashMap<>();
                    row.put("name", m.getName());
                    row.put("rank", title != null ? title.getName() : "");
                    members.add(row);
                }
            }
            SwingUtilities.invokeLater(() -> window.clan(clanName, members));
        });
    }

    private void clan(@Nullable String clanName, List<Map<String, String>> members) {
        roster = members;
        if (name.getText().isEmpty() && clanName != null) {
            name.setText(clanName);
        }
        create.setText(members.isEmpty() ? "Make one from my clan (join your clan first)"
            : "Make one from my clan's " + GroupAdminWindow.plural(members.size(), "member"));
        create.setEnabled(!members.isEmpty());
        (members.isEmpty() ? existing : create).setSelected(true);
        womId.setEnabled(existing.isSelected());
    }

    private JComponent form() {
        JPanel page = page("Register your group",
            "Put your clan on the DropTracker from here. You'll add our bot to your clan's Discord at the end.");
        page.add(SettingsForm.left(label("Group name", DropTrackerTheme.TEXT)));
        name.setFont(FontManager.getRunescapeSmallFont());
        name.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        page.add(SettingsForm.left(name));
        page.add(DropTrackerTheme.gap(10));
        page.add(SettingsForm.left(label("WiseOldMan group (we sync your members from it)", DropTrackerTheme.TEXT)));
        ButtonGroup choice = new ButtonGroup();
        for (JRadioButton b : new JRadioButton[]{create, existing}) {
            choice.add(b);
            b.setOpaque(false);
            b.setFont(FontManager.getRunescapeSmallFont());
            b.setForeground(DropTrackerTheme.TEXT);
            b.addActionListener(e -> womId.setEnabled(existing.isSelected()));
        }
        clan(null, roster);
        page.add(SettingsForm.left(create));
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        row.add(existing, BorderLayout.WEST);
        womId.setFont(FontManager.getRunescapeSmallFont());
        womId.putClientProperty("JTextField.placeholderText", "Group id or wiseoldman.net link");
        row.add(womId);
        page.add(SettingsForm.left(row));
        page.add(DropTrackerTheme.gap(10));
        page.add(SettingsForm.left(error));
        next.addActionListener(e -> start());
        return card(page, next);
    }

    private JComponent waitPage() {
        JPanel page = page(null, null);
        page.add(SettingsForm.left(waitTitle));
        page.add(SettingsForm.left(SettingsForm.wrapped("Your browser opens Discord. Pick your clan's server and "
            + "approve. You need Manage Server there. This window carries on by itself.", DropTrackerTheme.TEXT_MUTED)));
        page.add(DropTrackerTheme.gap(12));
        page.add(SettingsForm.left(waiting));
        JButton again = DropTrackerTheme.button("Open Discord again");
        again.addActionListener(e -> browse());
        return card(page, again);
    }

    private JComponent donePage() {
        JPanel page = page(null, null);
        page.add(SettingsForm.left(doneTitle));
        page.add(DropTrackerTheme.gap(6));
        page.add(SettingsForm.left(doneText));
        manage.addActionListener(e -> openManager());
        return card(page, manage);
    }

    private void start() {
        Integer wom = null;
        if (existing.isSelected()) {
            Matcher m = WOM_ID.matcher(womId.getText().trim());
            if (!m.find()) {
                error.setText("Enter your WiseOldMan group's id, or paste its link.");
                return;
            }
            wom = Integer.parseInt(m.group(1));
        }
        next.setEnabled(false);
        next.setText("Checking...");
        error.setText(" ");
        api.register(config.lastAccountName(), client.getAccountHash(), name.getText().trim(), wom,
            existing.isSelected() ? Collections.emptyList() : roster, r -> {
                next.setEnabled(true);
                next.setText("Continue");
                if (r.value == null || r.value.getId() == null || !r.value.getId().matches("[A-Za-z0-9_-]{16,40}")) {
                    error.setText(r.error == null ? " " : r.error);
                    return;
                }
                id = r.value.getId();
                deadline = System.currentTimeMillis() + 29 * 60_000L;
                waitTitle.setText("Add the bot to " + name.getText().trim() + "'s Discord");
                waiting.setText(r.value.getNote() != null ? r.value.getNote() + " Waiting for Discord..." : "Waiting for Discord...");
                cards.show(this, "wait");
                browse();
                poll.start();
            });
    }

    private void browse() {
        LinkBrowser.browse(DropTrackerUrls.web("api", "auth", "login").newBuilder()
            .addQueryParameter("bot", "1").addQueryParameter("redirect", "/register-group/" + id).build().toString());
    }

    private void check() {
        if (System.currentTimeMillis() > deadline) {
            poll.stop();
            finish("This took too long", "The registration expired. Close this window and start again.", false);
            return;
        }
        api.registration(config.lastAccountName(), client.getAccountHash(), id, r -> {
            if (r.value == null || "pending".equals(r.value.getStatus()) || !poll.isRunning()) {
                return;
            }
            poll.stop();
            done = r.value;
            boolean ok = "done".equals(done.getStatus());
            finish(ok ? done.getGroupName() + " is on the DropTracker" : "Not registered",
                done.getMessage() != null ? done.getMessage()
                    : "Your members are syncing from WiseOldMan. Next, pick the channels your clan's "
                    + "drops and achievements go to.", ok && api.hasSession(done.getGroupId()));
        });
    }

    private void finish(String title, String text, boolean canManage) {
        doneTitle.setText(title);
        ((JTextArea) doneText).setText(text);
        manage.setVisible(canManage);
        cards.show(this, "done");
    }

    private void openManager() {
        api.groups(config.lastAccountName(), client.getAccountHash(), r -> {
            if (r.value != null) {
                Window self = SwingUtilities.getWindowAncestor(this);
                GroupAdminWindow.open(client, config, api, r.value.getGroups(), done.getGroupId());
                self.dispose();
            }
        });
    }

    /** A step: its content at the top, its action at the bottom left. */
    private static JPanel card(JComponent content, JComponent action) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(DropTrackerTheme.SURFACE_0);
        card.setBorder(new EmptyBorder(16, 18, 16, 18));
        card.add(content, BorderLayout.NORTH);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        south.setOpaque(false);
        south.add(action);
        card.add(south, BorderLayout.SOUTH);
        return card;
    }

    private static JPanel page(@Nullable String title, @Nullable String intro) {
        JPanel page = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);
        if (title != null) {
            page.add(SettingsForm.left(bold(title)));
        }
        if (intro != null) {
            page.add(SettingsForm.left(SettingsForm.wrapped(intro, DropTrackerTheme.TEXT_MUTED)));
            page.add(DropTrackerTheme.gap(12));
        }
        return page;
    }

    private static JLabel bold(String text) {
        return DropTrackerTheme.label(text, FontManager.getRunescapeBoldFont(), DropTrackerTheme.GOLD);
    }

    private static JLabel label(String text, Color color) {
        return DropTrackerTheme.label(text, FontManager.getRunescapeSmallFont(), color);
    }

    private static JButton button(String text) {
        JButton b = DropTrackerTheme.button(text);
        return b;
    }
}
