package io.droptracker.ui.admin;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.*;

import javax.annotation.Nullable;
import javax.swing.*;
import javax.swing.border.*;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.*;
import io.droptracker.models.api.GroupAdmin;
import io.droptracker.ui.DropTrackerTheme;
import io.droptracker.ui.components.*;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

/**
 * The group manager: a window of its own rather than more side-panel rows,
 * because a group has dozens of settings and the side panel is 225px wide.
 *
 * <p>Left, a list of pages (Overview, Members, then one per settings category
 * from the server); right, the page; along the bottom, a save bar that appears once
 * something has changed. Before any of that, a group needs its verification
 * code once on this device (see {@link GroupAdminApi}).
 */
public class GroupAdminWindow extends JPanel {

    private static final String STATUS = "status";
    private static final String LINK = "link";
    private static final String MANAGE = "manage";

    @Nullable
    private static JDialog openDialog;
    @Nullable
    private static GroupAdminWindow openWindow;

    private final GroupAdminApi api;
    private final Supplier<String> playerName;
    private final LongSupplier accountHash;
    private final List<GroupAdmin.Group> groups;

    private final CardLayout bodyCards = new CardLayout();
    private final JPanel body = new JPanel(bodyCards);
    private final CardLayout pageCards = new CardLayout();
    private final JPanel pages = new JPanel(pageCards);
    private final JPanel nav = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
    private final List<NavItem> navItems = new ArrayList<>();
    private final List<SettingsForm> forms = new ArrayList<>();

    private final JLabel groupName = DropTrackerTheme.label(" ", FontManager.getRunescapeBoldFont(), DropTrackerTheme.GOLD);
    private final JLabel groupMeta = DropTrackerTheme.label(" ", FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
    private final JButton signOut = button("Sign out", "Forget this device's access to the group");
    private final JComboBox<String> switcher = new JComboBox<>();

    private final JLabel statusText = DropTrackerTheme.label(" ", FontManager.getRunescapeFont(), DropTrackerTheme.TEXT_MUTED);
    private final JButton statusRetry = button("Try again", null);

    private final CodeEntryPanel codeEntry;
    private final JLabel linkTitle = DropTrackerTheme.label(" ", FontManager.getRunescapeBoldFont(), DropTrackerTheme.GOLD);
    private final JLabel linkStatus = DropTrackerTheme.label(" ", FontManager.getRunescapeSmallFont(), DropTrackerTheme.RED);
    private final JButton verify = button("Verify", null);

    private final JPanel saveBar = new JPanel(new BorderLayout());
    private final JLabel saveText = DropTrackerTheme.label(" ", FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT);
    private final JButton save = button("Save changes", null);
    private final JButton discard = button("Discard", null);

    private GroupAdmin.Group group;
    @Nullable
    private GroupAdmin.Settings settings;
    @Nullable
    private MembersPage members;
    private boolean busy;
    private boolean closed;

    public GroupAdminWindow(GroupAdminApi api, Supplier<String> playerName, LongSupplier accountHash,
                            List<GroupAdmin.Group> groups) {
        super(new BorderLayout());
        this.api = api;
        this.playerName = playerName;
        this.accountHash = accountHash;
        this.groups = groups;
        this.codeEntry = new CodeEntryPanel(this::link, () -> verify.setEnabled(!busy && codeEntryReady()));
        setBackground(DropTrackerTheme.SURFACE_0);
        setPreferredSize(new Dimension(780, 560));

        add(header(), BorderLayout.NORTH);
        body.setBackground(DropTrackerTheme.SURFACE_0);
        body.add(statusPage(), STATUS);
        body.add(linkPage(), LINK);
        body.add(managePage(), MANAGE);
        add(body, BorderLayout.CENTER);
        add(saveBar(), BorderLayout.SOUTH);
    }

    /** Opens the manager on a group, or brings the open one forward. Call on the Swing thread. */
    public static void open(Client client, DropTrackerConfig config, GroupAdminApi api,
                            List<GroupAdmin.Group> groups, int groupId) {
        if (openDialog != null && openDialog.isDisplayable() && openWindow != null) {
            openWindow.select(groupId);
            openDialog.toFront();
            return;
        }
        JFrame parent = PanelElements.getParentFrame(client);
        JDialog dialog = new JDialog(parent, "DropTracker - Group manager", false);
        GroupAdminWindow window = new GroupAdminWindow(api, config::lastAccountName, client::getAccountHash, groups);
        dialog.setContentPane(window);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (window.confirmLeave("close the manager")) {
                    window.closed = true;
                    dialog.dispose();
                }
            }

            @Override
            public void windowClosed(WindowEvent e) {
                if (openDialog == dialog) {
                    openDialog = null;
                    openWindow = null;
                }
            }
        });
        dialog.setMinimumSize(new Dimension(640, 440));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        openDialog = dialog;
        openWindow = window;
        dialog.setVisible(true);
        window.select(groupId);
    }

    /** Switches to a group: straight in with a stored session, else the code page. */
    public void select(int groupId) {
        GroupAdmin.Group next = groups.stream().filter(g -> g.getId() == groupId).findFirst()
            .orElse(groups.isEmpty() ? null : groups.get(0));
        if (next == null || (group != null && next.getId() == group.getId() && settings != null)) {
            return;
        }
        if (group != null && !confirmLeave("switch groups")) {
            syncSwitcher();
            return;
        }
        group = next;
        syncSwitcher();
        groupName.setText(next.getName());
        groupMeta.setText(meta(next));
        settings = null;
        if (api.hasSession(next.getId())) {
            load();
        } else {
            showLink(null);
        }
    }

    /** Points the switcher at the current group (it's empty with one group). */
    private void syncSwitcher() {
        int i = groups.indexOf(group);
        if (i >= 0 && i < switcher.getItemCount()) {
            switcher.setSelectedIndex(i);
        }
    }

    // ------------------------------------------------------------------ flows

    private void load() {
        int id = group.getId();
        showStatus("Loading " + group.getName() + "...", false);
        GroupAdmin.Overview[] overview = new GroupAdmin.Overview[1];
        GroupAdmin.Settings[] loaded = new GroupAdmin.Settings[1];
        String[] error = new String[1];
        boolean[] needsCode = new boolean[1];
        int[] pending = {2};
        Runnable done = () -> {
            if (--pending[0] > 0 || closed || group.getId() != id) {
                return;
            }
            if (needsCode[0]) {
                showLink(error[0]);
            } else if (error[0] != null) {
                showStatus(error[0], true);
            } else {
                build(overview[0], loaded[0]);
            }
        };
        api.overview(playerName.get(), accountHash.getAsLong(), id, r -> {
            overview[0] = r.value;
            note(r, error, needsCode);
            done.run();
        });
        api.settings(playerName.get(), accountHash.getAsLong(), id, r -> {
            loaded[0] = r.value;
            note(r, error, needsCode);
            done.run();
        });
    }

    private static void note(GroupAdminApi.Result<?> r, String[] error, boolean[] needsCode) {
        needsCode[0] |= r.needsCode;
        if (r.error != null && error[0] == null) {
            error[0] = r.error;
        }
    }

    private void link() {
        String code = codeEntry.code();
        if (code == null || busy) {
            return;
        }
        setBusy(true);
        linkStatus.setText(" ");
        int id = group.getId();
        api.link(playerName.get(), accountHash.getAsLong(), id, code, r -> {
            setBusy(false);
            if (closed || group.getId() != id) {
                return;
            }
            if (r.value == null) {
                linkStatus.setText(r.error == null ? " " : r.error);
                codeEntry.clear();
                return;
            }
            signOut.setVisible(true);
            load();
        });
    }

    private void signOut() {
        int choice = JOptionPane.showConfirmDialog(this,
            "Sign this device out of " + group.getName() + "?\nYou'll need the verification code to come back.",
            "Sign out", JOptionPane.OK_CANCEL_OPTION);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        api.unlink(playerName.get(), accountHash.getAsLong(), group.getId(), r -> showLink(null));
    }

    private void save() {
        Map<String, Object> changes = new LinkedHashMap<>();
        forms.forEach(f -> changes.putAll(f.changes()));
        if (changes.isEmpty() || busy) {
            return;
        }
        setBusy(true);
        saveText.setText("Saving...");
        int id = group.getId();
        api.save(playerName.get(), accountHash.getAsLong(), id, changes, r -> {
            setBusy(false);
            if (closed || group.getId() != id) {
                return;
            }
            if (r.needsCode) {
                showLink(r.error);
            } else if (r.value == null) {
                saveText.setText(r.error);
                saveText.setForeground(DropTrackerTheme.RED);
            } else if (!r.value.getErrors().isEmpty()) {
                forms.forEach(f -> f.showErrors(r.value.getErrors()));
                saveText.setText("Some changes weren't saved. Check the highlighted settings.");
                saveText.setForeground(DropTrackerTheme.RED);
            } else {
                int page = selectedPage();
                settings = r.value;
                buildForms(r.value, page);
                saveBar.setVisible(true);
                saveText.setText("Saved.");
                saveText.setForeground(DropTrackerTheme.GREEN);
                save.setVisible(false);
                discard.setVisible(false);
            }
        });
    }

    private void discard() {
        if (settings != null) {
            buildForms(settings, selectedPage());
        }
    }

    /** True when there's nothing unsaved, or the leader agrees to drop it. */
    boolean confirmLeave(String action) {
        int dirty = dirtyCount();
        if (dirty == 0) {
            return true;
        }
        return JOptionPane.showConfirmDialog(this,
            "You have " + plural(dirty, "unsaved change") + ". Discard and " + action + "?",
            "Unsaved changes", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }

    // ------------------------------------------------------------------ pages

    private void build(GroupAdmin.Overview overview, GroupAdmin.Settings loaded) {
        settings = loaded;
        if (overview.getGroup() != null) {
            groupMeta.setText(meta(overview.getGroup()));
        }
        signOut.setVisible(true);
        pages.removeAll();
        pages.add(overviewPage(overview), "page0");
        members = new MembersPage(api, playerName, accountHash, () -> group.getId(), this::showLink);
        buildForms(loaded, 0);
        bodyCards.show(body, MANAGE);
    }

    /** (Re)builds the nav and settings pages from the server's copy. */
    private void buildForms(GroupAdmin.Settings loaded, int page) {
        while (pages.getComponentCount() > 1) {
            pages.remove(1);
        }
        forms.clear();
        navItems.clear();
        nav.removeAll();
        nav.add(navHeading("GROUP"));
        addNav("Overview", 0);
        pages.add(members, "page1");
        addNav("Members", 1);
        nav.add(navHeading("SETTINGS"));
        int index = 2;
        for (GroupAdmin.Category category : loaded.getCategories()) {
            if (loaded.getFields().stream().noneMatch(f -> category.getKey().equals(f.getCategory()))) {
                continue;
            }
            SettingsForm form = new SettingsForm(loaded, category, this::updateSaveBar, this::openSettingsOnWeb);
            forms.add(form);
            JScrollPane scroll = new JScrollPane(form);
            scroll.setBorder(null);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            scroll.getViewport().setBackground(DropTrackerTheme.SURFACE_0);
            pages.add(scroll, "page" + index);
            addNav(category.getLabel(), index++);
        }
        nav.add(Box.createVerticalGlue());
        nav.revalidate();
        nav.repaint();
        showPage(Math.min(page, index - 1));
        updateSaveBar();
    }

    private JComponent overviewPage(GroupAdmin.Overview overview) {
        JPanel page = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);
        page.setBorder(new EmptyBorder(14, 16, 14, 16));
        page.add(SettingsForm.left(DropTrackerTheme.label("Overview", FontManager.getRunescapeBoldFont(), DropTrackerTheme.GOLD)));
        page.add(DropTrackerTheme.gap(10));

        JPanel tiles = new JPanel(new GridLayout(0, 3, 8, 8));
        tiles.setOpaque(false);
        for (GroupAdmin.Stat stat : overview.getStats()) {
            JPanel tile = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
            tile.setBorder(DropTrackerTheme.cardBorder(8, 10, 8, 10));
            tile.add(DropTrackerTheme.label(stat.getLabel(), FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED));
            tile.add(DropTrackerTheme.label(stat.getValue(), FontManager.getRunescapeBoldFont(), DropTrackerTheme.GOLD_BRIGHT));
            if (stat.getDetail() != null) {
                tile.add(DropTrackerTheme.label(stat.getDetail(), FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED));
            }
            tiles.add(tile);
        }
        page.add(SettingsForm.left(tiles));
        page.add(DropTrackerTheme.gap(14));

        if (!overview.getChecklist().isEmpty()) {
            page.add(SettingsForm.left(DropTrackerTheme.label("Setup", FontManager.getRunescapeBoldFont(), DropTrackerTheme.TEXT)));
            page.add(DropTrackerTheme.gap(6));
            JPanel list = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
            list.setBorder(DropTrackerTheme.cardBorder(4, 10, 4, 10));
            for (GroupAdmin.Check check : overview.getChecklist()) {
                JPanel row = new JPanel(new BorderLayout(8, 0));
                row.setOpaque(false);
                row.setBorder(new EmptyBorder(5, 0, 5, 0));
                JLabel mark = new JLabel(check.isOk() ? PanelIcons.check(DropTrackerTheme.GREEN, 12)
                    : PanelIcons.dot(DropTrackerTheme.EMBER, 10));
                mark.setVerticalAlignment(SwingConstants.TOP);
                row.add(mark, BorderLayout.WEST);
                JPanel text = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
                text.add(SettingsForm.left(DropTrackerTheme.label(check.getLabel(), FontManager.getRunescapeSmallFont(),
                    check.isOk() ? DropTrackerTheme.TEXT : DropTrackerTheme.GOLD_BRIGHT)));
                if (!check.isOk() && check.getHint() != null) {
                    text.add(SettingsForm.left(SettingsForm.wrapped(check.getHint(), DropTrackerTheme.TEXT_MUTED)));
                }
                row.add(text, BorderLayout.CENTER);
                list.add(SettingsForm.left(row));
            }
            page.add(SettingsForm.left(list));
            page.add(DropTrackerTheme.gap(14));
        }

        JPanel links = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        links.setOpaque(false);
        links.add(webButton("Group page", ""));
        links.add(Box.createRigidArea(new Dimension(6, 0)));
        links.add(webButton("Events", "events"));
        links.add(Box.createRigidArea(new Dimension(6, 0)));
        links.add(webButton("Roles & access", "authorized"));
        page.add(SettingsForm.left(links));

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(DropTrackerTheme.SURFACE_0);
        wrap.add(page, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(wrap);
        scroll.setBorder(null);
        return scroll;
    }

    private JComponent header() {
        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setBackground(DropTrackerTheme.SURFACE_1);
        header.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, DropTrackerTheme.BRONZE), new EmptyBorder(10, 14, 10, 14)));
        JPanel title = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
        groupName.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
        title.add(groupName);
        title.add(groupMeta);
        header.add(title, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        if (groups.size() > 1) {
            groups.forEach(g -> switcher.addItem(g.getName()));
            switcher.setFont(FontManager.getRunescapeSmallFont());
            switcher.setBackground(DropTrackerTheme.SURFACE_2);
            switcher.setForeground(DropTrackerTheme.TEXT);
            switcher.addActionListener(e -> {
                int i = switcher.getSelectedIndex();
                if (i >= 0 && group != null && groups.get(i).getId() != group.getId()) {
                    select(groups.get(i).getId());
                }
            });
            actions.add(switcher);
        }
        JButton website = button("Open on website", "Open this group's settings on droptracker.io");
        website.addActionListener(e -> openSettingsOnWeb());
        actions.add(website);
        signOut.addActionListener(e -> signOut());
        signOut.setVisible(false);
        actions.add(signOut);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private JComponent statusPage() {
        JPanel page = new JPanel(new GridBagLayout());
        page.setBackground(DropTrackerTheme.SURFACE_0);
        JPanel inner = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);
        statusText.setAlignmentX(CENTER_ALIGNMENT);
        statusRetry.setAlignmentX(CENTER_ALIGNMENT);
        statusRetry.addActionListener(e -> load());
        inner.add(statusText);
        inner.add(DropTrackerTheme.gap(10));
        inner.add(statusRetry);
        page.add(inner);
        return page;
    }

    private JComponent linkPage() {
        JPanel page = new JPanel(new GridBagLayout());
        page.setBackground(DropTrackerTheme.SURFACE_0);
        JPanel card = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
        card.setBorder(DropTrackerTheme.accentCardBorder(18, 22, 18, 22));
        card.setPreferredSize(new Dimension(400, 300));

        linkTitle.setAlignmentX(CENTER_ALIGNMENT);
        card.add(linkTitle);
        card.add(DropTrackerTheme.gap(6));
        JComponent intro = SettingsForm.wrapped("Enter your group's verification code to manage it from here. "
            + "You only need to do this once on this account.", DropTrackerTheme.TEXT_MUTED);
        card.add(intro);
        card.add(DropTrackerTheme.gap(14));
        codeEntry.setAlignmentX(CENTER_ALIGNMENT);
        card.add(codeEntry);
        card.add(DropTrackerTheme.gap(10));
        linkStatus.setAlignmentX(CENTER_ALIGNMENT);
        card.add(linkStatus);
        card.add(DropTrackerTheme.gap(6));
        verify.setAlignmentX(CENTER_ALIGNMENT);
        verify.setForeground(DropTrackerTheme.GOLD_BRIGHT);
        verify.setPreferredSize(new Dimension(160, 28));
        verify.setMaximumSize(new Dimension(160, 28));
        verify.setEnabled(false);
        verify.addActionListener(e -> link());
        card.add(verify);
        card.add(DropTrackerTheme.gap(14));
        card.add(SettingsForm.wrapped("Don't have it? Your group's owner can see or reset it under Roles & access "
            + "on droptracker.io. Never share it outside your leadership team.", DropTrackerTheme.TEXT_MUTED));
        card.add(DropTrackerTheme.gap(8));
        JButton roles = button("Open Roles & access", null);
        roles.setAlignmentX(CENTER_ALIGNMENT);
        roles.addActionListener(e -> LinkBrowser.browse(groupUrl("authorized")));
        card.add(roles);
        page.add(card);
        return page;
    }

    private JComponent managePage() {
        JPanel page = new JPanel(new BorderLayout());
        nav.setBorder(new CompoundBorder(new MatteBorder(0, 0, 0, 1, DropTrackerTheme.SURFACE_3), new EmptyBorder(8, 0, 8, 0)));
        nav.setPreferredSize(new Dimension(170, 0));
        page.add(nav, BorderLayout.WEST);
        pages.setBackground(DropTrackerTheme.SURFACE_0);
        page.add(pages, BorderLayout.CENTER);
        return page;
    }

    private JComponent saveBar() {
        saveBar.setBackground(DropTrackerTheme.SURFACE_1);
        saveBar.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, DropTrackerTheme.GOLD), new EmptyBorder(8, 14, 8, 14)));
        saveBar.add(saveText, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        discard.addActionListener(e -> discard());
        save.addActionListener(e -> save());
        save.setForeground(DropTrackerTheme.GOLD_BRIGHT);
        buttons.add(discard);
        buttons.add(save);
        saveBar.add(buttons, BorderLayout.EAST);
        saveBar.setVisible(false);
        return saveBar;
    }

    // ------------------------------------------------------------------ state

    private void showStatus(String text, boolean retry) {
        statusText.setText(text);
        statusRetry.setVisible(retry);
        saveBar.setVisible(false);
        bodyCards.show(body, STATUS);
    }

    private void showLink(@Nullable String message) {
        settings = null;
        forms.clear();
        signOut.setVisible(false);
        saveBar.setVisible(false);
        linkTitle.setText("Verify you manage " + group.getName());
        linkStatus.setText(message == null ? " " : message);
        codeEntry.clear();
        verify.setEnabled(false);
        bodyCards.show(body, LINK);
        SwingUtilities.invokeLater(codeEntry::focusFirst);
    }

    private void updateSaveBar() {
        int dirty = dirtyCount();
        if (dirty > 0) {
            saveText.setText(plural(dirty, "unsaved change"));
            saveText.setForeground(DropTrackerTheme.TEXT);
        }
        save.setVisible(dirty > 0);
        discard.setVisible(dirty > 0);
        save.setEnabled(!busy && forms.stream().allMatch(SettingsForm::valid));
        saveBar.setVisible(dirty > 0 || "Saved.".equals(saveText.getText()) && saveBar.isVisible());
        saveBar.revalidate();
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        save.setEnabled(!busy);
        discard.setEnabled(!busy);
        codeEntry.setEnabled(!busy);
        verify.setEnabled(!busy && codeEntryReady());
        verify.setText(busy ? "Checking..." : "Verify");
    }

    private boolean codeEntryReady() {
        return codeEntry != null && codeEntry.code() != null;
    }

    int dirtyCount() {
        return forms.stream().mapToInt(SettingsForm::dirtyCount).sum();
    }

    private void addNav(String label, int page) {
        NavItem item = new NavItem(label, page);
        navItems.add(item);
        nav.add(item);
    }

    private void showPage(int page) {
        pageCards.show(pages, "page" + page);
        if (page == 1 && members != null) {
            members.opened();
        }
        navItems.forEach(n -> n.setSelected(n.page == page));
    }

    private int selectedPage() {
        return navItems.stream().filter(n -> n.selected).mapToInt(n -> n.page).findFirst().orElse(0);
    }

    private void openSettingsOnWeb() {
        LinkBrowser.browse(groupUrl("settings"));
    }

    private JButton webButton(String text, String path) {
        JButton b = button(text, null);
        b.setIcon(PanelElements.getExternalLinkIcon());
        b.addActionListener(e -> LinkBrowser.browse(groupUrl(path)));
        return b;
    }

    private String groupUrl(String path) {
        String id = String.valueOf(group == null ? 0 : group.getId());
        return (path.isEmpty() ? DropTrackerUrls.web("groups", id) : DropTrackerUrls.web("groups", id, path)).toString();
    }

    static String meta(GroupAdmin.Group g) {
        StringBuilder sb = new StringBuilder("owner".equals(g.getRole()) ? "Owner" : "Admin");
        if (g.getMemberCount() > 0) {
            sb.append("  ·  ").append(plural(g.getMemberCount(), "member"));
        }
        if (g.getTier() != null) {
            sb.append("  ·  ").append(g.getTier());
        }
        return sb.toString();
    }

    static String plural(int n, String noun) {
        return String.format("%,d %s%s", n, noun, n == 1 ? "" : "s");
    }

    private static JButton button(String text, @Nullable String tooltip) {
        JButton b = DropTrackerTheme.button(text);
        b.setToolTipText(tooltip);
        return b;
    }

    private static JComponent navHeading(String text) {
        JLabel label = DropTrackerTheme.label(text, FontManager.getRunescapeSmallFont(), DropTrackerTheme.STONE);
        label.setBorder(new EmptyBorder(8, 14, 3, 8));
        label.setAlignmentX(LEFT_ALIGNMENT);
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height));
        return label;
    }

    /** One entry in the left-hand list. Selected: lighter row and a gold edge. */
    private final class NavItem extends JLabel {
        final int page;
        boolean selected;

        NavItem(String text, int page) {
            super(text);
            this.page = page;
            setFont(FontManager.getRunescapeSmallFont());
            setOpaque(true);
            setAlignmentX(LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            setSelected(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    showPage(page);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    if (!selected) {
                        setForeground(DropTrackerTheme.GOLD_BRIGHT);
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    setSelected(selected);
                }
            });
        }

        void setSelected(boolean selected) {
            this.selected = selected;
            setBackground(selected ? DropTrackerTheme.SURFACE_2 : DropTrackerTheme.SURFACE_1);
            setForeground(selected ? DropTrackerTheme.GOLD : DropTrackerTheme.TEXT);
            setBorder(new CompoundBorder(new MatteBorder(0, 3, 0, 0, selected ? DropTrackerTheme.GOLD : DropTrackerTheme.SURFACE_1),
                new EmptyBorder(5, 11, 5, 8)));
        }
    }
}
