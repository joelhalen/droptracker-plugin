package io.droptracker.ui.pages;

import io.droptracker.api.DropTrackerUrls;
import io.droptracker.models.api.EventView;
import io.droptracker.ui.DropTrackerTheme;
import io.droptracker.ui.components.PanelElements;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;
import okhttp3.HttpUrl;

import javax.annotation.Nullable;
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The event window: an event's pages (overview, tasks, board, teams, players,
 * history) in a window of their own, for any event the account is on or its
 * groups run. The server composes every page as blocks (see {@link EventView});
 * this draws them and follows {@code page} links, nothing else.
 */
public class EventWindow extends JPanel {

    @Nullable
    private static JDialog openDialog;
    @Nullable
    private static EventWindow openWindow;

    private final EventsPanel panel;
    private final JLabel name = DropTrackerTheme.label(" ", FontManager.getRunescapeBoldFont().deriveFont(18f), DropTrackerTheme.GOLD);
    private final JLabel subtitle = DropTrackerTheme.label(" ", FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
    private final JComboBox<String> switcher = new JComboBox<>();
    private final JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JPanel column = new EventsPanel.ScrollableColumn();
    private final JScrollPane scroll = new JScrollPane(column,
        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

    private List<EventView.Event> events = new ArrayList<>();
    private int eventId;
    private String page = "overview";
    private long loadedAt;
    private int loadSeq;
    private boolean syncing;

    private EventWindow(EventsPanel panel) {
        super(new BorderLayout());
        this.panel = panel;
        setBackground(DropTrackerTheme.SURFACE_0);
        setPreferredSize(new Dimension(820, 600));

        JPanel header = new JPanel(new BorderLayout(10, 6));
        header.setBackground(DropTrackerTheme.SURFACE_1);
        header.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, DropTrackerTheme.BRONZE), new EmptyBorder(10, 14, 8, 14)));
        JPanel title = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
        title.add(name);
        title.add(subtitle);
        header.add(title, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        switcher.setFont(FontManager.getRunescapeSmallFont());
        switcher.setBackground(DropTrackerTheme.SURFACE_2);
        switcher.setForeground(DropTrackerTheme.TEXT);
        switcher.addActionListener(e -> {
            int i = switcher.getSelectedIndex();
            if (!syncing && i >= 0 && i < events.size() && events.get(i).getId() != eventId) {
                show(events.get(i).getId(), "overview");
            }
        });
        switcher.setVisible(false);
        actions.add(switcher);
        JButton refresh = DropTrackerTheme.button("Refresh");
        refresh.addActionListener(e -> load());
        actions.add(refresh);
        JButton web = DropTrackerTheme.button("Website");
        web.setIcon(PanelElements.getExternalLinkIcon());
        web.setToolTipText("Open this event on droptracker.io");
        web.addActionListener(e -> LinkBrowser.browse(DropTrackerUrls.web("events", String.valueOf(eventId)).toString()));
        actions.add(web);
        header.add(actions, BorderLayout.EAST);
        tabs.setOpaque(false);
        header.add(tabs, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBackground(DropTrackerTheme.SURFACE_0);
        column.setBorder(new EmptyBorder(10, 14, 14, 14));
        scroll.setBorder(null);
        scroll.getViewport().setBackground(DropTrackerTheme.SURFACE_0);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    /** Opens an event (0: the first the account may open), or brings the window forward. EDT. */
    static void open(EventsPanel panel, int eventId) {
        if (openDialog != null && openDialog.isDisplayable() && openWindow != null) {
            if (eventId > 0) {
                openWindow.show(eventId, "overview");
            }
            openDialog.toFront();
            return;
        }
        JFrame parent = PanelElements.getParentFrame(panel.client);
        JDialog dialog = new JDialog(parent, "DropTracker - Events", false);
        EventWindow window = new EventWindow(panel);
        dialog.setContentPane(window);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (openDialog == dialog) {
                    openDialog = null;
                    openWindow = null;
                }
            }
        });
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setMinimumSize(new Dimension(640, 440));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        openDialog = dialog;
        openWindow = window;
        dialog.setVisible(true);
        window.eventId = eventId;
        window.status("Loading...");
        window.loadList();
    }

    /** A fresh /event_state landed (something scored): refresh the open page, at most every 15s. EDT. */
    static void stateUpdated() {
        EventWindow w = openWindow;
        if (w != null && w.eventId > 0 && System.currentTimeMillis() - w.loadedAt > 15_000) {
            w.load();
        }
    }

    private void show(int id, String key) {
        eventId = id;
        page = key;
        load();
    }

    private void loadList() {
        String player = panel.config.lastAccountName();
        long hash = panel.client.getAccountHash();
        CompletableFuture.supplyAsync(() -> panel.api.fetchEventList(player, hash)).thenAccept(listing ->
            SwingUtilities.invokeLater(() -> {
                events = listing != null ? listing.getEvents() : new ArrayList<>();
                if (eventId <= 0 && !events.isEmpty()) {
                    eventId = events.get(0).getId();
                }
                syncSwitcher();
                if (eventId > 0) {
                    load();
                } else {
                    status(listing == null ? "Couldn't load your events. Try Refresh in a minute."
                        : "None of your groups have an event running, coming up or ended this week.");
                }
            }));
    }

    private void syncSwitcher() {
        syncing = true;
        switcher.removeAllItems();
        for (EventView.Event e : events) {
            switcher.addItem(e.getTeam() == null && e.getGroup() != null ? e.getName() + " (" + e.getGroup() + ")" : e.getName());
            if (e.getId() == eventId) {
                switcher.setSelectedIndex(switcher.getItemCount() - 1);
            }
        }
        switcher.setVisible(events.size() > 1);
        syncing = false;
    }

    private void load() {
        if (eventId <= 0) {
            loadList();
            return;
        }
        int seq = ++loadSeq;
        int id = eventId;
        String key = page;
        String player = panel.config.lastAccountName();
        long hash = panel.client.getAccountHash();
        CompletableFuture.supplyAsync(() -> panel.api.fetchEventView(id, key, player, hash)).thenAccept(view ->
            SwingUtilities.invokeLater(() -> {
                if (seq != loadSeq) {
                    return;
                }
                loadedAt = System.currentTimeMillis();
                if (view == null || view.getEvent() == null) {
                    status("Couldn't load this event. It may have been removed, or the server is busy.");
                    return;
                }
                render(view);
            }));
    }

    private void status(String text) {
        column.removeAll();
        column.add(text(text, DropTrackerTheme.TEXT_MUTED));
        column.revalidate();
        column.repaint();
    }

    private void render(EventView view) {
        name.setText(view.getEvent().getName());
        subtitle.setText(view.getEvent().getSubtitle() + (view.getTeam() != null ? "  ·  Your team: " + view.getTeam().getName() : ""));
        page = view.getPage();
        tabs.removeAll();
        for (EventView.Page p : view.getPages()) {
            JButton tab = DropTrackerTheme.button(p.getLabel());
            if (page.startsWith(p.getKey())) {
                tab.setForeground(DropTrackerTheme.GOLD);
                tab.setBorder(new CompoundBorder(new MatteBorder(0, 0, 2, 0, DropTrackerTheme.GOLD), tab.getBorder()));
            }
            tab.addActionListener(e -> show(eventId, p.getKey()));
            tabs.add(tab);
        }
        tabs.revalidate();
        tabs.repaint();

        int scrollValue = scroll.getVerticalScrollBar().getValue();
        boolean samePage = page.equals(column.getName());
        column.setName(page);
        column.removeAll();
        for (EventView.Block b : view.getBlocks()) {
            JComponent c = block(b);
            if (c != null) {
                c.setAlignmentX(LEFT_ALIGNMENT);
                column.add(c);
                column.add(DropTrackerTheme.gap(10));
            }
        }
        column.add(Box.createVerticalGlue());
        column.revalidate();
        column.repaint();
        // A refresh keeps the reader's place; a new page starts at the top.
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(samePage ? scrollValue : 0));
    }

    /* ===================== blocks ===================== */

    @Nullable
    private JComponent block(EventView.Block b) {
        switch (String.valueOf(b.getType())) {
            case "heading":
                JLabel h = DropTrackerTheme.label(b.getText(), FontManager.getRunescapeBoldFont(), DropTrackerTheme.GOLD);
                h.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, DropTrackerTheme.BRONZE), new EmptyBorder(4, 0, 3, 0)));
                return fit(h);
            case "text":
                return text(b.getText(), "muted".equals(b.getTone()) ? DropTrackerTheme.TEXT_MUTED : DropTrackerTheme.TEXT);
            case "stats":
                JPanel stats = new JPanel(new GridLayout(0, Math.min(Math.max(b.getItems().size(), 1), 4), 6, 6));
                stats.setOpaque(false);
                for (EventView.Item i : b.getItems()) {
                    JPanel box = PanelElements.createStatBox(i.getLabel(), i.getValue());
                    box.setToolTipText(i.getTip());
                    stats.add(box);
                }
                return fit(stats);
            case "table":
                return table(b);
            case "tasks":
                return tasks(b);
            case "board":
                return board(b);
            default:
                return null;
        }
    }

    private JComponent table(EventView.Block b) {
        JPanel t = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);
        if (b.getRows().isEmpty()) {
            return text(b.getEmpty(), DropTrackerTheme.TEXT_MUTED);
        }
        List<String> heads = new ArrayList<>();
        b.getColumns().forEach(c -> heads.add(c.getLabel()));
        t.add(tableRow(b.getColumns(), heads, null, null, false, true));
        for (EventView.Row r : b.getRows()) {
            JPanel row = tableRow(b.getColumns(), r.getCells(), r.getColor(), r.getPage(), r.isHighlight(), false);
            row.setToolTipText(r.getTip());
            if (r.getIconItemId() != null && row.getComponentCount() > 0) {
                // An item row (a task's checklist): the icon leads its first cell.
                JLabel first = (JLabel) row.getComponent(0);
                first.setPreferredSize(new Dimension(0, 20));
                first.setMinimumSize(first.getPreferredSize());
                panel.applyTaskIcon(first, r.getIconItemId(), null, 18);
            }
            t.add(row);
        }
        return t;
    }

    /** One row; zero-width cells with weights keep every row's columns aligned. */
    private JPanel tableRow(List<EventView.Column> cols, List<String> cells, @Nullable String color,
                            @Nullable String link, boolean highlight, boolean head) {
        JPanel row = new DropTrackerTheme.FitPanel(new GridBagLayout());
        row.setBackground(head ? DropTrackerTheme.SURFACE_1 : highlight ? DropTrackerTheme.SURFACE_3 : DropTrackerTheme.SURFACE_2);
        row.setBorder(new CompoundBorder(new MatteBorder(0, 3, 1, 0, color != null ? parse(color) : row.getBackground()), new EmptyBorder(4, 6, 4, 6)));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 8);
        for (int i = 0; i < cells.size(); i++) {
            EventView.Column col = i < cols.size() ? cols.get(i) : null;
            JLabel cell = DropTrackerTheme.label(cells.get(i), head ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont(),
                head ? DropTrackerTheme.TEXT_MUTED : highlight ? DropTrackerTheme.GOLD_BRIGHT : DropTrackerTheme.TEXT);
            cell.setPreferredSize(new Dimension(0, cell.getPreferredSize().height));
            cell.setMinimumSize(cell.getPreferredSize());
            cell.setToolTipText(cells.get(i));
            if (col != null && col.isRight()) {
                cell.setHorizontalAlignment(SwingConstants.RIGHT);
            }
            c.gridx = i;
            c.weightx = col != null ? Math.max(col.getWidth(), 1) : 1;
            row.add(cell, c);
        }
        if (link != null) {
            row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            row.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    show(eventId, link);
                }
            });
        }
        return row;
    }

    private JComponent tasks(EventView.Block b) {
        if (b.getItems().isEmpty()) {
            return text(b.getEmpty(), DropTrackerTheme.TEXT_MUTED);
        }
        JPanel list = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);
        for (EventView.Item i : b.getItems()) {
            JPanel card = new DropTrackerTheme.FitPanel(new BorderLayout(8, 4));
            card.setBackground(DropTrackerTheme.SURFACE_1);
            card.setBorder(DropTrackerTheme.cardBorder(6, 8, 6, 8));
            card.setToolTipText(i.getTip());
            JLabel icon = new JLabel();
            icon.setPreferredSize(new Dimension(28, 28));
            panel.applyTaskIcon(icon, i.getIconItemId(), null, 28);
            card.add(icon, BorderLayout.WEST);

            JPanel body = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
            JPanel top = new JPanel(new BorderLayout(6, 0));
            top.setOpaque(false);
            top.add(DropTrackerTheme.label(i.getLabel(), FontManager.getRunescapeBoldFont(), DropTrackerTheme.TEXT), BorderLayout.CENTER);
            if (i.getPoints() != null) {
                top.add(DropTrackerTheme.chip(i.getPoints(), DropTrackerTheme.GOLD), BorderLayout.EAST);
            }
            body.add(fit(top));
            if (i.getDetail() != null) {
                body.add(fit(DropTrackerTheme.label(i.getDetail(), FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED)));
            }
            for (EventView.Bar bar : i.getBars()) {
                JPanel line = new DropTrackerTheme.FitPanel(new BorderLayout(8, 0));
                line.setOpaque(false);
                line.setBorder(new EmptyBorder(3, 0, 0, 0));
                line.setAlignmentX(LEFT_ALIGNMENT);
                JLabel team = DropTrackerTheme.label(bar.getLabel(), bar.isHighlight() ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont(),
                    bar.isHighlight() ? DropTrackerTheme.GOLD_BRIGHT : DropTrackerTheme.TEXT_MUTED);
                team.setPreferredSize(new Dimension(130, 14));
                team.setToolTipText(bar.getLabel());
                line.add(team, BorderLayout.WEST);
                line.add(EventsPanel.progressBar(bar.getHave(), bar.getNeed(), bar.getText(),
                    bar.getColor() != null ? parse(bar.getColor()) : DropTrackerTheme.GREEN), BorderLayout.CENTER);
                body.add(line);
            }
            card.add(body, BorderLayout.CENTER);
            if (i.getPage() != null) {
                String link = i.getPage();
                card.setToolTipText((i.getTip() != null ? i.getTip() + " " : "") + "Click to see what counts and who has it.");
                card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                card.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        show(eventId, link);
                    }
                });
            }
            list.add(card);
            list.add(DropTrackerTheme.gap(4));
        }
        return list;
    }

    private JComponent board(EventView.Block b) {
        JPanel box = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);
        HttpUrl url = panel.api.eventBoardImageUrl(eventId, b.getTeamId(), panel.config.lastAccountName(), panel.client.getAccountHash());
        JButton full = DropTrackerTheme.button("Full size");
        full.addActionListener(e -> PanelElements.showRemoteImage(panel.client, name.getText(), url));
        box.add(full);
        box.add(DropTrackerTheme.gap(8));
        JLabel image = DropTrackerTheme.label("Loading the board...", FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
        box.add(image);
        int width = Math.max(scroll.getViewport().getWidth() - 30, 300);
        CompletableFuture.supplyAsync(() -> PanelElements.fetchImage(url)).thenAccept(img ->
            SwingUtilities.invokeLater(() -> {
                image.setText(img == null ? "Couldn't load the board. Try Refresh in a moment." : null);
                if (img != null) {
                    image.setIcon(new ImageIcon(img.getWidth() > width
                        ? img.getScaledInstance(width, img.getHeight() * width / img.getWidth(), Image.SCALE_SMOOTH) : img));
                    column.revalidate();
                }
            }));
        return box;
    }

    /* ===================== bits ===================== */

    private static JComponent text(@Nullable String value, Color color) {
        JTextArea area = new JTextArea(value == null ? "" : value);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setOpaque(false);
        area.setForeground(color);
        area.setFont(FontManager.getRunescapeSmallFont());
        return area;
    }

    private static JComponent fit(JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    private static Color parse(String hex) {
        return EventsPanel.parseColor(hex, DropTrackerTheme.STONE);
    }
}
