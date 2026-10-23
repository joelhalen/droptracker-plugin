package io.droptracker.ui.admin;

import java.awt.*;
import java.util.function.*;

import javax.swing.*;
import javax.swing.border.*;

import io.droptracker.api.GroupAdminApi;
import io.droptracker.models.api.GroupAdmin;
import io.droptracker.ui.DropTrackerTheme;
import net.runelite.client.ui.FontManager;

/**
 * The group's members, drawn from the server's page: search, most loot this
 * month first, and a switch per member for whether they appear on the group's
 * boards. Loaded the first time the page is opened.
 */
class MembersPage extends JPanel {

    private final GroupAdminApi api;
    private final Supplier<String> playerName;
    private final LongSupplier accountHash;
    private final IntSupplier groupId;
    /** Called when the session has ended, with the server's sentence. */
    private final Consumer<String> onNeedsCode;

    private final JLabel summary = DropTrackerTheme.label(" ", FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
    private final JLabel help = DropTrackerTheme.label(" ", FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
    private final JTextField search = new JTextField();
    private final JButton sync = DropTrackerTheme.button("Sync from WiseOldMan");
    private final JLabel notice = DropTrackerTheme.label(" ", FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
    private final JPanel list = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);
    private final JLabel footer = DropTrackerTheme.label(" ", FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
    private final Timer debounce;

    private int request;
    private boolean loaded;

    MembersPage(GroupAdminApi api, Supplier<String> playerName, LongSupplier accountHash, IntSupplier groupId,
                Consumer<String> onNeedsCode) {
        super(new BorderLayout());
        this.api = api;
        this.playerName = playerName;
        this.accountHash = accountHash;
        this.groupId = groupId;
        this.onNeedsCode = onNeedsCode;
        setBackground(DropTrackerTheme.SURFACE_0);
        setBorder(new EmptyBorder(14, 16, 10, 16));

        JPanel top = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);
        JPanel title = new JPanel(new BorderLayout(8, 0));
        title.setOpaque(false);
        title.add(DropTrackerTheme.label("Members", FontManager.getRunescapeBoldFont(), DropTrackerTheme.GOLD), BorderLayout.WEST);
        title.add(summary);
        top.add(SettingsForm.left(title));
        top.add(SettingsForm.left(help));
        top.add(DropTrackerTheme.gap(8));

        search.putClientProperty("JTextField.placeholderText", "Search by name");
        search.setFont(FontManager.getRunescapeSmallFont());
        debounce = new Timer(300, e -> load());
        debounce.setRepeats(false);
        SettingsForm.listen(search, debounce::restart);
        sync.addActionListener(e -> sync());
        JPanel tools = new JPanel(new BorderLayout(8, 0));
        tools.setOpaque(false);
        tools.add(search, BorderLayout.CENTER);
        tools.add(sync, BorderLayout.EAST);
        tools.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        top.add(SettingsForm.left(tools));
        top.add(DropTrackerTheme.gap(4));
        top.add(SettingsForm.left(notice));
        top.add(DropTrackerTheme.gap(6));
        add(top, BorderLayout.NORTH);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(DropTrackerTheme.SURFACE_0);
        wrap.add(list, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(wrap);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        footer.setBorder(new EmptyBorder(8, 0, 0, 0));
        add(footer, BorderLayout.SOUTH);
        message("Loading members...");
    }

    /** Loads the first page the first time the page is shown. */
    void opened() {
        if (!loaded) {
            loaded = true;
            load();
        }
    }

    private void load() {
        int ticket = ++request;
        api.members(playerName.get(), accountHash.getAsLong(), groupId.getAsInt(), search.getText().trim(), 1, r -> {
            if (ticket != request) {
                return;  // an older search answered after a newer one
            }
            if (r.needsCode) {
                onNeedsCode.accept(r.error);
            } else if (r.value == null) {
                message(r.error);
            } else {
                show(r.value);
            }
        });
    }

    private void show(GroupAdmin.Members m) {
        summary.setText(m.getSummary());
        help.setText(m.getHelp());
        GroupAdmin.Wom wom = m.getWom();
        sync.setVisible(wom != null && wom.isCanSync());
        if (wom != null && !notice.getForeground().equals(DropTrackerTheme.GREEN)) {
            setNotice(wom.getNote(), DropTrackerTheme.TEXT_MUTED);
        }
        list.removeAll();
        for (GroupAdmin.Member member : m.getRows()) {
            list.add(SettingsForm.left(row(member)));
            list.add(DropTrackerTheme.gap(4));
        }
        if (m.getRows().isEmpty()) {
            String q = search.getText().trim();
            message(q.isEmpty() ? "No members yet. They appear once your WiseOldMan group syncs."
                : "No members match \"" + q + "\".");
        }
        footer.setText(m.getFooter() == null ? " " : m.getFooter());
        list.revalidate();
        list.repaint();
    }

    private JComponent row(GroupAdmin.Member member) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setBackground(DropTrackerTheme.SURFACE_1);
        row.setBorder(DropTrackerTheme.cardBorder(6, 10, 6, 10));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JLabel rank = DropTrackerTheme.label("#" + member.getRank(), FontManager.getRunescapeSmallFont(), DropTrackerTheme.STONE);
        rank.setPreferredSize(new Dimension(34, 16));
        row.add(rank, BorderLayout.WEST);

        JPanel text = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
        JLabel name = DropTrackerTheme.label(member.getName(), FontManager.getRunescapeFont(), DropTrackerTheme.TEXT);
        JLabel detail = DropTrackerTheme.label(member.getDetail(), FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
        text.add(name);
        text.add(detail);
        row.add(text, BorderLayout.CENTER);

        JLabel state = DropTrackerTheme.label(" ", FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
        ToggleSwitch[] toggle = new ToggleSwitch[1];
        Consumer<Boolean> paint = shown -> {
            state.setText(shown ? "On boards" : "Hidden");
            state.setForeground(shown ? DropTrackerTheme.TEXT_MUTED : DropTrackerTheme.EMBER);
            name.setForeground(shown ? DropTrackerTheme.TEXT : DropTrackerTheme.STONE);
        };
        toggle[0] = new ToggleSwitch(!member.isHidden(), () -> {
            boolean shown = toggle[0].isOn();
            paint.accept(shown);
            api.setHidden(playerName.get(), accountHash.getAsLong(), groupId.getAsInt(), member.getPlayerId(), !shown, r -> {
                if (r.value == null) {
                    // Put it back: the change didn't happen.
                    toggle[0].setOn(!shown);
                    paint.accept(!shown);
                    setNotice(r.error, DropTrackerTheme.RED);
                    if (r.needsCode) {
                        onNeedsCode.accept(r.error);
                    }
                }
            });
        });
        toggle[0].setToolTipText("Show " + member.getName() + " on your lootboard and leaderboards");
        paint.accept(!member.isHidden());
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        east.setOpaque(false);
        east.add(state);
        east.add(toggle[0]);
        row.add(east, BorderLayout.EAST);
        return row;
    }

    private void sync() {
        sync.setEnabled(false);
        sync.setText("Syncing...");
        api.womSync(playerName.get(), accountHash.getAsLong(), groupId.getAsInt(), r -> {
            sync.setEnabled(true);
            sync.setText("Sync from WiseOldMan");
            if (r.needsCode) {
                onNeedsCode.accept(r.error);
                return;
            }
            boolean good = r.value != null && "good".equals(r.value.getTone());
            setNotice(r.value != null ? r.value.getMessage() : r.error, good ? DropTrackerTheme.GREEN : DropTrackerTheme.EMBER);
            if (good) {
                load();
            }
        });
    }

    private void setNotice(String text, Color color) {
        notice.setText(text == null ? " " : text);
        notice.setForeground(color);
    }

    private void message(String text) {
        list.removeAll();
        list.add(SettingsForm.left(SettingsForm.wrapped(text, DropTrackerTheme.TEXT_MUTED)));
        list.revalidate();
        list.repaint();
    }
}
