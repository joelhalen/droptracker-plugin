package io.droptracker.ui.pages;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.api.EventState;
import io.droptracker.service.EventNotificationService;
import io.droptracker.ui.DropTrackerTheme;
import io.droptracker.ui.components.PanelElements;
import io.droptracker.util.ItemIDSearch;
import io.droptracker.util.RemoteImageCache;
import io.droptracker.util.ValueFormat;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * "Events" side-panel tab: one card per active event — themed header with the
 * time remaining, stat tiles, the tracked task (click any task in the list to
 * track it on the HUD; click again to let the server decide), a full-width
 * standings table with per-team board pop-outs, and the team roster.
 */
public class EventsPanel {
    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final EventNotificationService service;
    private final Client client;
    private final ItemManager itemManager;
    private final RemoteImageCache remoteImages;
    private final ItemIDSearch itemIds;

    /* Tracked-task required-item strip: small wrapping grid of item sprites. */
    private static final int REQ_ICONS_PER_ROW = 5;
    private static final int MAX_REQ_ICONS = 10;
    private static final int REQ_ICON_SLOT = 28;
    private static final int REQ_ICON_SIZE = 26;

    private JPanel root;
    private JPanel listPanel;

    public EventsPanel(DropTrackerConfig config, DropTrackerApi api,
                       EventNotificationService service, Client client,
                       ItemManager itemManager, RemoteImageCache remoteImages,
                       ItemIDSearch itemIds) {
        this.config = config;
        this.api = api;
        this.service = service;
        this.client = client;
        this.itemManager = itemManager;
        this.remoteImages = remoteImages;
        this.itemIds = itemIds;
    }

    public JPanel create() {
        root = new JPanel(new BorderLayout());
        root.setBackground(DropTrackerTheme.SURFACE_0);

        listPanel = new ScrollableColumn();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(DropTrackerTheme.SURFACE_0);
        listPanel.setBorder(new EmptyBorder(5, 0, 5, 0));

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(DropTrackerTheme.SURFACE_0);
        JLabel title = new JLabel("Your events");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(DropTrackerTheme.GOLD);
        header.add(title, BorderLayout.WEST);
        JButton refresh = new JButton("Refresh");
        DropTrackerTheme.styleButton(refresh);
        refresh.addActionListener(e -> refreshAsync());
        header.add(refresh, BorderLayout.EAST);
        header.setBorder(new EmptyBorder(4, 6, 4, 6));

        JScrollPane scroll = new JScrollPane(listPanel,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(DropTrackerTheme.SURFACE_0);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        root.add(header, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);

        rebuild();
        refreshAsync();
        return root;
    }

    /** Kick a state refetch off the EDT; rebuild lands via onUpdated(). */
    public void refreshAsync() {
        CompletableFuture.runAsync(service::refreshEventStateNow);
    }

    /** Called (any thread) when a fresh /event_state snapshot lands. */
    public void onUpdated() {
        SwingUtilities.invokeLater(this::rebuild);
    }

    private void rebuild() {
        if (listPanel == null) {
            return;
        }
        listPanel.removeAll();

        EventState state = service.getEventState();
        List<EventState.Entry> entries = state != null ? state.getEvents() : null;
        if (!config.useApi() || !config.eventNotifications()) {
            listPanel.add(emptyLabel("Enable the API and event notifications to see your events."));
        } else if (entries == null || entries.isEmpty()) {
            listPanel.add(emptyLabel("No active events right now."));
        } else {
            boolean multiple = entries.size() > 1;
            // The event the HUD is actually rendering right now (explicit pin,
            // else the first event) — cards mark it so "which event has the
            // HUD" is always visible when the player is in several.
            EventState.Entry hudEntry = service.hudEntry();
            int hudEventId = hudEntry != null && hudEntry.getEvent() != null
                ? hudEntry.getEvent().getId() : -1;
            for (EventState.Entry entry : entries) {
                listPanel.add(eventCard(entry, multiple, hudEventId));
                listPanel.add(Box.createRigidArea(new Dimension(0, 8)));
            }
        }
        listPanel.add(Box.createVerticalGlue());
        listPanel.revalidate();
        listPanel.repaint();
    }

    private JLabel emptyLabel(String text) {
        JLabel label = new JLabel("<html><div style='text-align:center;'>" + text + "</div></html>");
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(DropTrackerTheme.TEXT_MUTED);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        label.setBorder(new EmptyBorder(12, 8, 12, 8));
        return label;
    }

    /* ===================== event card ===================== */

    private JPanel eventCard(EventState.Entry entry, boolean showHudPick, int hudEventId) {
        EventState.EventInfo event = entry.getEvent();
        EventState.TeamInfo team = entry.getTeam();
        Color teamColor = team != null
            ? parseColor(team.getColor(), DropTrackerTheme.GOLD) : DropTrackerTheme.GOLD;
        boolean onHud = event.getId() == hudEventId;

        JPanel card = PanelElements.heightCappedPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(DropTrackerTheme.SURFACE_1);
        // With several events running, the card owning the HUD gets the gold
        // edge so the player can tell which event is in focus at a glance.
        card.setBorder(BorderFactory.createLineBorder(
            showHudPick && onHud ? DropTrackerTheme.GOLD : DropTrackerTheme.BRONZE, 1));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        card.add(cardHeader(entry, showHudPick, onHud));

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(DropTrackerTheme.SURFACE_1);
        body.setBorder(new EmptyBorder(8, 8, 8, 8));
        body.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Stat tiles: tasks done + team standing.
        JPanel stats = new JPanel(new GridLayout(1, 2, 6, 0));
        stats.setBackground(DropTrackerTheme.SURFACE_1);
        stats.setAlignmentX(Component.LEFT_ALIGNMENT);
        stats.add(PanelElements.createStatBox("Tasks done",
            entry.getTasksCompleted() + " / " + entry.getTasksTotal()));
        if (team != null && team.getRank() != null) {
            stats.add(PanelElements.createStatBox("Standing",
                ordinal(team.getRank()) + " of " + team.getTeamCount()));
        } else {
            stats.add(PanelElements.createStatBox("Score",
                team != null ? ValueFormat.commas(team.getScore()) : "—"));
        }
        body.add(stats);
        body.add(vgap(8));

        // The task being worked toward (tracked pick or server focus).
        EventNotificationService.DisplayTask display = service.displayTask(entry);
        if ("awaiting_roll".equals(entry.getBoardStatus())) {
            body.add(rollBanner());
            body.add(vgap(8));
        } else if (display != null) {
            body.add(trackedTaskBox(entry, display));
            body.add(vgap(8));
        }

        List<EventState.TaskInfo> tasks = entry.getTasks();
        if (tasks != null && !tasks.isEmpty()) {
            boolean pickable = !"board_game".equals(event.getKind());
            JPanel taskList = taskListPanel(entry, tasks, display, pickable);
            body.add(section("Tasks (" + tasks.size() + ")", taskList,
                tasks.size() > 12));
            body.add(vgap(8));
        }

        List<EventState.Standing> standings = entry.getStandings();
        if (standings != null && !standings.isEmpty()) {
            boolean boardAvailable = entry.getBoard() != null && entry.getBoard().isAvailable();
            JPanel table = standingsTable(entry, standings,
                team != null ? team.getId() : -1, boardAvailable);
            body.add(section("Standings", table, false));
            body.add(vgap(8));
        }

        List<EventState.Member> members = entry.getMembers();
        if (members != null && !members.isEmpty()) {
            String title = "Your team (" + Math.max(entry.getMembersTotal(), members.size()) + ")";
            body.add(section(title, membersBox(entry, members, teamColor), true));
        }

        card.add(body);
        return card;
    }

    /** Header strip: event name + kind/countdown, HUD pick on the right. */
    private JPanel cardHeader(EventState.Entry entry, boolean showHudPick, boolean onHud) {
        EventState.EventInfo event = entry.getEvent();

        JPanel header = new JPanel(new BorderLayout(6, 0)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        header.setBackground(DropTrackerTheme.SURFACE_2);
        header.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, DropTrackerTheme.BRONZE),
            new EmptyBorder(6, 8, 6, 8)));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel titleCol = new JPanel();
        titleCol.setLayout(new BoxLayout(titleCol, BoxLayout.Y_AXIS));
        titleCol.setBackground(DropTrackerTheme.SURFACE_2);

        JLabel name = new JLabel(entry.getEvent().getName());
        name.setFont(FontManager.getRunescapeBoldFont());
        name.setForeground(DropTrackerTheme.GOLD);
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleCol.add(name);

        String sub = kindLabel(event.getKind());
        String endsIn = endsIn(event.getEndsAt());
        if (endsIn != null) {
            sub += "  ·  " + endsIn;
        }
        JLabel subLabel = new JLabel(sub);
        subLabel.setFont(FontManager.getRunescapeSmallFont());
        subLabel.setForeground(DropTrackerTheme.TEXT_MUTED);
        subLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleCol.add(subLabel);

        header.add(titleCol, BorderLayout.CENTER);

        if (showHudPick) {
            JPanel east = new JPanel(new BorderLayout());
            east.setBackground(DropTrackerTheme.SURFACE_2);
            if (onHud) {
                // The event the HUD is currently rendering (explicit pin or
                // the default) — a chip, not a dead button.
                JLabel chip = DropTrackerTheme.chip("ON HUD", DropTrackerTheme.GOLD);
                chip.setToolTipText("The Enhanced Display HUD is showing this event");
                east.add(chip, BorderLayout.NORTH);
            } else {
                JButton pin = new JButton("Show on HUD");
                DropTrackerTheme.styleButton(pin);
                pin.setToolTipText("Switch the Enhanced Display HUD to this event");
                pin.addActionListener(e -> {
                    config.setPinnedEventId(event.getId());
                    onUpdated();
                });
                east.add(pin, BorderLayout.NORTH);
            }
            header.add(east, BorderLayout.EAST);
        }
        return header;
    }

    /** The highlighted "working toward" block with icon + progress bar. */
    private JPanel trackedTaskBox(EventState.Entry entry,
                                  EventNotificationService.DisplayTask task) {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBackground(DropTrackerTheme.SURFACE_2);
        box.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(DropTrackerTheme.SURFACE_3, 1),
            new EmptyBorder(6, 6, 6, 6)));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel head = new JPanel(new BorderLayout());
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.setBackground(DropTrackerTheme.SURFACE_2);
        JLabel caption = new JLabel(task.tracked ? "TRACKING (your pick)" : "TRACKING (auto)");
        caption.setFont(FontManager.getRunescapeSmallFont());
        caption.setForeground(task.tracked ? DropTrackerTheme.GOLD_BRIGHT : DropTrackerTheme.TEXT_MUTED);
        head.add(caption, BorderLayout.WEST);
        if (task.tracked) {
            JLabel reset = new JLabel("auto ✕");
            reset.setFont(FontManager.getRunescapeSmallFont());
            reset.setForeground(DropTrackerTheme.TEXT_MUTED);
            reset.setToolTipText("Stop tracking this task and let the server pick again");
            reset.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            reset.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    service.setTrackedTask(entry.getEvent().getId(), 0);
                    rebuild();
                }
            });
            head.add(reset, BorderLayout.EAST);
        }
        box.add(head);
        box.add(vgap(4));

        JPanel row = new JPanel(new BorderLayout(6, 0)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBackground(DropTrackerTheme.SURFACE_2);
        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(24, 24));
        applyTaskIcon(icon, task.iconItemId, task.iconUrl, 24);
        row.add(icon, BorderLayout.WEST);

        JLabel label = new JLabel("<html>" + escape(task.label) + "</html>");
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(DropTrackerTheme.TEXT);
        row.add(label, BorderLayout.CENTER);
        box.add(row);

        if (task.need > 1 || task.have > 0) {
            box.add(vgap(4));
            box.add(progressBar(task.have, task.need));
        }

        // Self-describing detail: the description and requirement completion the
        // list-row tooltip shows, surfaced here so a tracked task explains itself
        // without re-finding and hovering it. Only bingo/list events carry the
        // full task record (board games send no task list — displayed as before).
        EventState.TaskInfo info = taskInfoById(entry, task.id);
        if (info != null) {
            String description = info.getDescription();
            if (description != null && !description.isEmpty()) {
                box.add(vgap(5));
                box.add(taskDescription(description));
            }
            List<EventState.Requirement> requirements = info.getRequirements();
            if (requirements != null && !requirements.isEmpty()) {
                box.add(vgap(5));
                box.add(requirementStrip(requirements));
            }
        }
        return box;
    }

    /** The full task record behind the headlined task (description +
     *  requirements), or null when the server sent no task list (board games,
     *  older servers) — the box then shows only the label and progress. */
    @Nullable
    private static EventState.TaskInfo taskInfoById(EventState.Entry entry, int id) {
        List<EventState.TaskInfo> tasks = entry.getTasks();
        if (tasks == null) {
            return null;
        }
        for (EventState.TaskInfo task : tasks) {
            if (task.getId() == id) {
                return task;
            }
        }
        return null;
    }

    /** Wrapped, muted description line for the tracked task box. */
    private JComponent taskDescription(String description) {
        int width = PluginPanel.PANEL_WIDTH - 55;
        JLabel label = new JLabel("<html><div style='width:" + width + "px;'>"
            + escape(description) + "</div></html>");
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(DropTrackerTheme.TEXT_MUTED);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /** "Required items" caption above a wrapping grid of item sprites, each
     *  full-colour with a green tick when the team has banked it or dimmed
     *  when still needed (the same obtained flag the tooltip strikes through). */
    private JComponent requirementStrip(List<EventState.Requirement> requirements) {
        JPanel wrap = new JPanel();
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.setBackground(DropTrackerTheme.SURFACE_2);
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel caption = new JLabel("Required items");
        caption.setFont(FontManager.getRunescapeSmallFont());
        caption.setForeground(DropTrackerTheme.TEXT_MUTED);
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.add(caption);
        wrap.add(vgap(3));

        JPanel grid = new JPanel(new GridBagLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        grid.setBackground(DropTrackerTheme.SURFACE_2);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);

        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(1, 0, 1, 3);
        c.gridx = 0;
        c.gridy = 0;

        int shown = Math.min(requirements.size(), MAX_REQ_ICONS);
        for (int i = 0; i < shown; i++) {
            grid.add(requirementSlot(requirements.get(i)), c);
            if (++c.gridx >= REQ_ICONS_PER_ROW) {
                c.gridx = 0;
                c.gridy++;
            }
        }
        if (requirements.size() > shown) {
            JLabel more = new JLabel("+" + (requirements.size() - shown));
            more.setFont(FontManager.getRunescapeSmallFont());
            more.setForeground(DropTrackerTheme.TEXT_MUTED);
            grid.add(more, c);
        }
        wrap.add(grid);
        return wrap;
    }

    /** One requirement cell: its item sprite (resolved from the name when the
     *  server sends no id), or a short name token when no icon resolves. */
    private JComponent requirementSlot(EventState.Requirement req) {
        boolean obtained = Boolean.TRUE.equals(req.getObtained());
        JLabel slot = new JLabel();
        slot.setHorizontalAlignment(SwingConstants.CENTER);
        slot.setVerticalAlignment(SwingConstants.CENTER);
        slot.setPreferredSize(new Dimension(REQ_ICON_SLOT, REQ_ICON_SLOT));
        slot.setToolTipText(requirementTooltip(req, obtained));

        Integer itemId = req.getIconItemId() != null && req.getIconItemId() > 0
            ? req.getIconItemId()
            : (req.getIconUrl() == null && req.getName() != null
                ? itemIds.findItemId(req.getName()) : null);
        if (itemId != null || req.getIconUrl() != null) {
            applyRequirementIcon(slot, itemId, req.getIconUrl(), REQ_ICON_SIZE, obtained);
        } else {
            // No resolvable sprite (name miss / cache not yet loaded): a short
            // token still shows the requirement and its obtained/needed state.
            slot.setText(abbrevName(req.getName()));
            slot.setFont(FontManager.getRunescapeSmallFont());
            slot.setForeground(obtained ? DropTrackerTheme.GREEN : DropTrackerTheme.TEXT_MUTED);
        }
        return slot;
    }

    /** Same struck-through obtained styling as the row tooltip, per item. */
    private static String requirementTooltip(EventState.Requirement req, boolean obtained) {
        StringBuilder html = new StringBuilder("<html>");
        if (obtained) {
            html.append("<strike><font color='#8a7c5e'>");
        }
        html.append(escape(req.getName()));
        if (req.getQuantity() != null && req.getQuantity() > 1) {
            html.append(" ×").append(req.getQuantity());
        }
        if (req.getPoints() != null) {
            html.append(" <i>(").append(req.getPoints()).append(" pts)</i>");
        }
        if (obtained) {
            html.append("</font></strike> ✓");
        }
        html.append("</html>");
        return html.toString();
    }

    /** Item sprite or allowlisted remote icon into a requirement slot, marked
     *  obtained (full colour + green tick) or still needed (dimmed). */
    private void applyRequirementIcon(JLabel target, @Nullable Integer itemId,
                                      @Nullable String iconUrl, int size, boolean obtained) {
        if (itemId != null && itemId > 0) {
            AsyncBufferedImage itemImage = itemManager.getImage(itemId);
            Runnable apply = () -> {
                target.setIcon(new ImageIcon(styleRequirementImage(itemImage, size, obtained)));
                target.revalidate();
                target.repaint();
            };
            itemImage.onLoaded(apply);
            apply.run();
        } else if (iconUrl != null) {
            BufferedImage remote = remoteImages.get(iconUrl,
                () -> SwingUtilities.invokeLater(this::rebuild));
            if (remote != null) {
                target.setIcon(new ImageIcon(styleRequirementImage(remote, size, obtained)));
            }
        }
    }

    /** Fit into the slot, then either dim (still needed) or stamp a corner
     *  tick (banked) so obtained state reads at a glance on a small sprite. */
    private static BufferedImage styleRequirementImage(BufferedImage source, int size, boolean obtained) {
        BufferedImage fitted = fitImage(source, size);
        if (!obtained) {
            return ImageUtil.alphaOffset(fitted, 0.35f);
        }
        Graphics2D g = fitted.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(FontManager.getRunescapeSmallFont());
        String tick = "✓";
        java.awt.FontMetrics fm = g.getFontMetrics();
        int tx = size - fm.stringWidth(tick);
        int ty = size - fm.getDescent();
        g.setColor(Color.BLACK);
        g.drawString(tick, tx + 1, ty + 1);
        g.setColor(DropTrackerTheme.GREEN);
        g.drawString(tick, tx, ty);
        g.dispose();
        return fitted;
    }

    /** First few letters of a name, for the no-sprite fallback token. */
    private static String abbrevName(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        return name.length() <= 3 ? name : name.substring(0, 3);
    }

    private JPanel rollBanner() {
        JPanel banner = new JPanel(new BorderLayout());
        banner.setBackground(DropTrackerTheme.SURFACE_2);
        banner.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(DropTrackerTheme.GOLD, 1),
            new EmptyBorder(6, 8, 6, 8)));
        banner.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel label = new JLabel("Your team can roll the dice!", SwingConstants.CENTER);
        label.setFont(FontManager.getRunescapeBoldFont());
        label.setForeground(DropTrackerTheme.GOLD_BRIGHT);
        banner.add(label, BorderLayout.CENTER);
        return banner;
    }

    /* ===================== task list ===================== */

    private JPanel taskListPanel(EventState.Entry entry, List<EventState.TaskInfo> tasks,
                                 @Nullable EventNotificationService.DisplayTask display,
                                 boolean pickable) {
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(DropTrackerTheme.SURFACE_1);
        list.setAlignmentX(Component.LEFT_ALIGNMENT);
        int displayedId = display != null ? display.id : -1;
        boolean manual = display != null && display.tracked;
        for (EventState.TaskInfo task : tasks) {
            list.add(taskRow(entry, task, displayedId, manual, pickable));
            list.add(vgap(2));
        }
        if (pickable) {
            JLabel hint = new JLabel("Click a task to track it on the HUD.");
            hint.setFont(FontManager.getRunescapeSmallFont());
            hint.setForeground(DropTrackerTheme.TEXT_MUTED);
            hint.setBorder(new EmptyBorder(2, 2, 0, 0));
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);
            list.add(hint);
        }
        return list;
    }

    private JPanel taskRow(EventState.Entry entry, EventState.TaskInfo task,
                           int displayedId, boolean displayedIsManual, boolean pickable) {
        boolean isDisplayed = task.getId() == displayedId;

        JPanel row = new JPanel(new BorderLayout(5, 0)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        Color baseBg = isDisplayed ? DropTrackerTheme.SURFACE_3 : DropTrackerTheme.SURFACE_2;
        row.setBackground(baseBg);
        row.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 2, 0, 0,
                isDisplayed ? DropTrackerTheme.GOLD : DropTrackerTheme.SURFACE_3),
            new EmptyBorder(3, 4, 3, 4)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(20, 20));
        applyTaskIcon(icon, task.getIconItemId(), task.getIconUrl(), 20);
        row.add(icon, BorderLayout.WEST);

        JLabel label = new JLabel(truncate(task.getLabel(), 30));
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(task.isCompleted() ? DropTrackerTheme.TEXT_MUTED
            : (isDisplayed ? DropTrackerTheme.GOLD_BRIGHT : DropTrackerTheme.TEXT));
        row.add(label, BorderLayout.CENTER);

        JLabel state;
        if (task.isCompleted()) {
            state = new JLabel("✓");
            state.setForeground(DropTrackerTheme.GREEN);
        } else if (task.getNeed() > 1 || task.getHave() > 0) {
            state = new JLabel(ValueFormat.abbrev(task.getHave())
                + "/" + ValueFormat.abbrev(task.getNeed()));
            state.setForeground(DropTrackerTheme.TEXT_MUTED);
        } else {
            state = new JLabel("");
        }
        state.setFont(FontManager.getRunescapeSmallFont());
        row.add(state, BorderLayout.EAST);

        row.setToolTipText(taskTooltip(task));

        if (pickable && !task.isCompleted()) {
            row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            row.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    int eventId = entry.getEvent().getId();
                    // Clicking the manually tracked task reverts to auto.
                    if (isDisplayed && displayedIsManual) {
                        service.setTrackedTask(eventId, 0);
                    } else {
                        service.setTrackedTask(eventId, task.getId());
                        // Tracking a task is an act of focus: point the HUD at
                        // this event too, otherwise picks made in a second
                        // event never show anywhere (the HUD keeps rendering
                        // the pinned/first event).
                        config.setPinnedEventId(eventId);
                    }
                    rebuild();
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    row.setBackground(DropTrackerTheme.SURFACE_3);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    row.setBackground(baseBg);
                }
            });
        }
        return row;
    }

    /** Tooltip explaining the task: full label, badge, description,
     *  requirements (with per-item quantity/points) and progress. */
    private String taskTooltip(EventState.TaskInfo task) {
        StringBuilder html = new StringBuilder("<html><p style='width:200px;'>");
        html.append("<b>").append(escape(task.getLabel())).append("</b>");
        if (task.getBadge() != null) {
            html.append(" &nbsp;<i>[").append(escape(task.getBadge()));
            if (task.getValue() != null) {
                html.append(" — ").append(escape(task.getValue()));
            }
            html.append("]</i>");
        }
        if (task.getDescription() != null) {
            html.append("<br/>").append(escape(task.getDescription()));
        }
        List<EventState.Requirement> requirements = task.getRequirements();
        if (requirements != null && !requirements.isEmpty()) {
            html.append("<br/>");
            for (EventState.Requirement req : requirements) {
                // Struck through = the team already banked this item and
                // re-receiving it can't advance the task (server-decided;
                // never set on point/any-of tasks where re-receives count).
                boolean obtained = Boolean.TRUE.equals(req.getObtained());
                html.append("<br/>• ");
                if (obtained) {
                    html.append("<strike><font color='#8a7c5e'>");
                }
                html.append(escape(req.getName()));
                if (req.getQuantity() != null && req.getQuantity() > 1) {
                    html.append(" ×").append(req.getQuantity());
                }
                if (req.getPoints() != null) {
                    html.append(" <i>(").append(req.getPoints()).append(" pts)</i>");
                }
                if (obtained) {
                    html.append("</font></strike> ✓");
                }
            }
        }
        if (!task.isCompleted() && (task.getNeed() > 1 || task.getHave() > 0)) {
            html.append("<br/><br/>Progress: ")
                .append(ValueFormat.progress(task.getHave(), task.getNeed()));
        }
        if (task.isCompleted()) {
            html.append("<br/><br/>Completed ✓");
        }
        if (task.getPoints() > 0) {
            html.append("<br/>Worth ").append(task.getPoints()).append(" points");
        }
        html.append("</p></html>");
        return html.toString();
    }

    /* ===================== standings table ===================== */

    private JPanel standingsTable(EventState.Entry entry, List<EventState.Standing> standings,
                                  int ownTeamId, boolean boardAvailable) {
        JPanel table = new JPanel(new GridBagLayout());
        table.setBackground(DropTrackerTheme.SURFACE_1);
        table.setAlignmentX(Component.LEFT_ALIGNMENT);

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(1, 0, 1, 0);
        c.gridy = 0;

        for (EventState.Standing standing : standings) {
            boolean own = standing.getTeamId() == ownTeamId;
            Color rowBg = own ? DropTrackerTheme.SURFACE_3 : DropTrackerTheme.SURFACE_2;

            JPanel rowPanel = new JPanel(new BorderLayout(5, 0));
            rowPanel.setBackground(rowBg);
            rowPanel.setBorder(new EmptyBorder(3, 5, 3, 4));

            JLabel rank = new JLabel(String.valueOf(standing.getRank()));
            rank.setFont(FontManager.getRunescapeSmallFont());
            rank.setForeground(own ? DropTrackerTheme.GOLD_BRIGHT : DropTrackerTheme.TEXT_MUTED);
            rank.setPreferredSize(new Dimension(16, 16));
            rowPanel.add(rank, BorderLayout.WEST);

            JPanel nameCol = new JPanel(new BorderLayout(4, 0));
            nameCol.setBackground(rowBg);
            JLabel swatch = new JLabel("■");
            swatch.setFont(FontManager.getRunescapeSmallFont());
            swatch.setForeground(parseColor(standing.getColor(), DropTrackerTheme.STONE));
            nameCol.add(swatch, BorderLayout.WEST);
            JLabel name = new JLabel(truncate(standing.getName(), 18) + (own ? " (you)" : ""));
            name.setFont(own ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont());
            name.setForeground(own ? DropTrackerTheme.GOLD_BRIGHT : DropTrackerTheme.TEXT);
            nameCol.add(name, BorderLayout.CENTER);
            rowPanel.add(nameCol, BorderLayout.CENTER);

            JPanel eastCol = new JPanel(new BorderLayout(5, 0));
            eastCol.setBackground(rowBg);
            JLabel score = new JLabel(ValueFormat.commas(standing.getScore()));
            score.setFont(FontManager.getRunescapeSmallFont());
            score.setForeground(own ? DropTrackerTheme.GOLD : DropTrackerTheme.TEXT_MUTED);
            score.setToolTipText(standing.getScore() + " points");
            eastCol.add(score, BorderLayout.CENTER);

            if (boardAvailable) {
                JLabel boardButton = new JLabel(PanelElements.getBoardIcon());
                boardButton.setToolTipText("View " + escape(standing.getName()) + "'s board");
                boardButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                final int teamId = standing.getTeamId();
                final String teamName = standing.getName();
                boardButton.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        openBoard(entry, teamId, teamName);
                    }
                });
                eastCol.add(boardButton, BorderLayout.EAST);
            }
            rowPanel.add(eastCol, BorderLayout.EAST);

            c.weightx = 1.0;
            c.gridx = 0;
            table.add(rowPanel, c);
            c.gridy++;
        }

        return table;
    }

    private void openBoard(EventState.Entry entry, int teamId, String teamName) {
        String playerName = client.getLocalPlayer() != null
            ? client.getLocalPlayer().getName() : null;
        if (playerName == null) {
            return;
        }
        String url = api.eventBoardImageUrl(entry.getEvent().getId(), teamId,
            playerName, client.getAccountHash());
        PanelElements.showRemoteImage(client,
            entry.getEvent().getName() + " — " + teamName, url);
    }

    /* ===================== members ===================== */

    private JComponent membersBox(EventState.Entry entry, List<EventState.Member> members,
                                  Color teamColor) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(members.get(i).getName());
        }
        int hidden = entry.getMembersTotal() - members.size();
        if (hidden > 0) {
            text.append("  … and ").append(hidden).append(" more");
        }
        JTextArea area = new JTextArea(text.toString());
        area.setWrapStyleWord(true);
        area.setLineWrap(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(true);
        area.setBackground(DropTrackerTheme.SURFACE_2);
        area.setForeground(DropTrackerTheme.TEXT_MUTED);
        area.setFont(FontManager.getRunescapeSmallFont());
        area.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 2, 0, 0, teamColor),
            new EmptyBorder(4, 6, 4, 6)));
        return area;
    }

    /* ===================== shared bits ===================== */

    /**
     * Vertical spacer safe for BoxLayout columns. Every child of a vertical
     * BoxLayout must share the same alignmentX — one centered (default 0.5)
     * child shifts every left-aligned sibling right by half its width, which
     * is exactly the "rows indented off the left edge" bug.
     */
    private static Component vgap(int height) {
        Box.Filler gap = (Box.Filler) Box.createVerticalStrut(height);
        gap.setAlignmentX(Component.LEFT_ALIGNMENT);
        return gap;
    }

    /** Lightweight collapsible section: tiny header row + body. */
    private JPanel section(String title, JComponent body, boolean startCollapsed) {
        JPanel wrap = new JPanel();
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.setBackground(DropTrackerTheme.SURFACE_1);
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel head = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.setBackground(DropTrackerTheme.SURFACE_1);
        head.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, DropTrackerTheme.SURFACE_3),
            new EmptyBorder(0, 0, 3, 0)));
        head.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(FontManager.getRunescapeBoldFont());
        titleLabel.setForeground(DropTrackerTheme.TEXT);
        head.add(titleLabel, BorderLayout.WEST);

        JLabel chevron = new JLabel(startCollapsed
            ? PanelElements.getCollapsedIcon() : PanelElements.getExpandedIcon());
        head.add(chevron, BorderLayout.EAST);

        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.setVisible(!startCollapsed);
        final boolean[] collapsed = {startCollapsed};
        head.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                collapsed[0] = !collapsed[0];
                chevron.setIcon(collapsed[0]
                    ? PanelElements.getCollapsedIcon() : PanelElements.getExpandedIcon());
                body.setVisible(!collapsed[0]);
                wrap.revalidate();
                wrap.repaint();
            }
        });

        wrap.add(head);
        wrap.add(vgap(3));
        wrap.add(body);
        return wrap;
    }

    /** Slim custom progress bar: bronze frame, green fill, centered count. */
    private JComponent progressBar(long have, long need) {
        long max = Math.max(need, 1);
        long value = Math.min(Math.max(have, 0), max);
        String text = ValueFormat.progress(have, need);
        JComponent bar = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                int w = getWidth();
                int h = getHeight();
                g2.setColor(DropTrackerTheme.SURFACE_0);
                g2.fillRect(0, 0, w, h);
                int fill = (int) Math.round((double) value / max * (w - 2));
                g2.setColor(new Color(0x2e5c33));
                g2.fillRect(1, 1, fill, h - 2);
                g2.setColor(DropTrackerTheme.GREEN);
                g2.fillRect(1, 1, fill, 2);
                g2.setColor(DropTrackerTheme.BRONZE);
                g2.setStroke(new BasicStroke(1));
                g2.drawRect(0, 0, w - 1, h - 1);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(FontManager.getRunescapeSmallFont());
                java.awt.FontMetrics fm = g2.getFontMetrics();
                int tx = (w - fm.stringWidth(text)) / 2;
                int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
                g2.setColor(Color.BLACK);
                g2.drawString(text, tx + 1, ty + 1);
                g2.setColor(DropTrackerTheme.TEXT);
                g2.drawString(text, tx, ty);
                g2.dispose();
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(PluginPanel.PANEL_WIDTH - 40, 14);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, 14);
            }
        };
        bar.setToolTipText(ValueFormat.commas(have) + " / " + ValueFormat.commas(need));
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        return bar;
    }

    /** Item sprite (rendered locally) or allowlisted remote icon, scaled to
     *  fit the slot with its aspect ratio preserved (item sprites are 36x32 —
     *  naive square scaling squishes them, raw addTo clips them). */
    private void applyTaskIcon(JLabel target, @Nullable Integer iconItemId,
                               @Nullable String iconUrl, int size) {
        if (iconItemId != null && iconItemId > 0) {
            AsyncBufferedImage itemImage = itemManager.getImage(iconItemId);
            Runnable apply = () -> {
                target.setIcon(fitIcon(itemImage, size));
                target.revalidate();
                target.repaint();
            };
            itemImage.onLoaded(apply);
            apply.run();
        } else if (iconUrl != null) {
            BufferedImage remote = remoteImages.get(iconUrl,
                () -> SwingUtilities.invokeLater(this::rebuild));
            if (remote != null) {
                target.setIcon(fitIcon(remote, size));
            }
        }
    }

    /** Scale into a size×size box, centered, aspect ratio preserved. */
    private static ImageIcon fitIcon(BufferedImage source, int size) {
        return new ImageIcon(fitImage(source, size));
    }

    /** {@link #fitIcon} as a raw image, for callers that post-process it. */
    private static BufferedImage fitImage(BufferedImage source, int size) {
        int w = Math.max(source.getWidth(), 1);
        int h = Math.max(source.getHeight(), 1);
        float scale = Math.min((float) size / w, (float) size / h);
        int nw = Math.max(Math.round(w * scale), 1);
        int nh = Math.max(Math.round(h * scale), 1);
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, (size - nw) / 2, (size - nh) / 2, nw, nh, null);
        g.dispose();
        return out;
    }

    private static String kindLabel(String kind) {
        if (kind == null) {
            return "Event";
        }
        switch (kind) {
            case "bingo":
                return "Bingo";
            case "board_game":
                return "Board Game";
            case "clan_vs_clan":
                return "Clan vs Clan";
            default:
                return Character.toUpperCase(kind.charAt(0)) + kind.substring(1).replace('_', ' ');
        }
    }

    /** "Ends in 2d 4h" from the server's UTC ISO timestamp, or null. */
    @Nullable
    private static String endsIn(@Nullable String endsAtIso) {
        if (endsAtIso == null || endsAtIso.isEmpty()) {
            return null;
        }
        try {
            Instant ends = LocalDateTime.parse(endsAtIso).toInstant(ZoneOffset.UTC);
            Duration left = Duration.between(Instant.now(), ends);
            if (left.isNegative()) {
                return "Ending…";
            }
            long days = left.toDays();
            long hours = left.toHours() % 24;
            long minutes = left.toMinutes() % 60;
            if (days > 0) {
                return "Ends in " + days + "d " + hours + "h";
            }
            if (hours > 0) {
                return "Ends in " + hours + "h " + minutes + "m";
            }
            return "Ends in " + Math.max(minutes, 1) + "m";
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static String escape(String value) {
        return value == null ? "" : value
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Color parseColor(String hex, Color fallback) {
        if (hex == null || hex.isEmpty()) {
            return fallback;
        }
        try {
            return Color.decode(hex);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String ordinal(int n) {
        if (n % 100 >= 11 && n % 100 <= 13) {
            return n + "th";
        }
        switch (n % 10) {
            case 1: return n + "st";
            case 2: return n + "nd";
            case 3: return n + "rd";
            default: return n + "th";
        }
    }

    /**
     * A vertical column that always matches the scroll viewport's width. A
     * plain JPanel inside a JScrollPane is laid out at its preferred width —
     * wider content silently overflows past the right edge (the horizontal
     * scrollbar is disabled), narrower content leaves a gutter.
     */
    private static class ScrollableColumn extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
