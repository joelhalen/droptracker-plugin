package io.droptracker.ui.pages;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.api.EventState;
import io.droptracker.models.submissions.RecentSubmission;
import io.droptracker.service.EventNotificationService;
import io.droptracker.service.EventTaskPrefs;
import io.droptracker.ui.DropTrackerTheme;
import io.droptracker.ui.components.PanelElements;
import io.droptracker.util.ItemIDSearch;
import io.droptracker.util.RemoteImageCache;
import io.droptracker.util.ValueFormat;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import okhttp3.HttpUrl;

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
import java.awt.Font;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * "Events" side-panel tab: one card per active event — themed header with the
 * time remaining, stat tiles, the tracked task (click any task in the list to
 * pin it; the top pin leads the HUD, and with none the server decides), a
 * full-width standings table with per-team board pop-outs, the team's recent
 * submissions and the team roster.
 */
public class EventsPanel {
    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final EventNotificationService service;
    private final Client client;
    private final ItemManager itemManager;
    private final RemoteImageCache remoteImages;
    private final ItemIDSearch itemIds;
    private final EventTaskPrefs taskPrefs;

    /* Tracked-task required-item strip: small wrapping grid of item sprites. */
    private static final int REQ_ICONS_PER_ROW = 5;
    private static final int REQ_ICON_SLOT = 28;
    private static final int REQ_ICON_SIZE = 26;

    private JPanel root;
    private JPanel listPanel;
    private JScrollPane scrollPane;
    /**
     * Expand/collapse choices per card section (key: eventId + section),
     * surviving rebuilds — clicking a task to track it re-renders the card
     * and must not fold the task list back to its size-based default.
     */
    private final java.util.Map<String, Boolean> sectionCollapsed = new java.util.HashMap<>();
    /**
     * Whether the "N hidden" group of a card is currently folded open (key:
     * eventId). Hiding is persisted; peeking at what you hid is not.
     */
    private final java.util.Map<String, Boolean> hiddenRevealed = new java.util.HashMap<>();

    public EventsPanel(DropTrackerConfig config, DropTrackerApi api,
                       EventNotificationService service, Client client,
                       ItemManager itemManager, RemoteImageCache remoteImages,
                       ItemIDSearch itemIds, ConfigManager configManager) {
        this.config = config;
        this.api = api;
        this.service = service;
        this.client = client;
        this.itemManager = itemManager;
        this.remoteImages = remoteImages;
        this.itemIds = itemIds;
        this.taskPrefs = new EventTaskPrefs(configManager, service);
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

        scrollPane = new JScrollPane(listPanel,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(DropTrackerTheme.SURFACE_0);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        root.add(header, BorderLayout.NORTH);
        root.add(scrollPane, BorderLayout.CENTER);

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
        // Keep the viewport where the user left it: a rebuild triggered by
        // clicking a task (or a background state refresh) must not jump the
        // panel back to the top.
        final int scrollValue = scrollPane != null
            ? scrollPane.getVerticalScrollBar().getValue() : 0;
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
        if (scrollPane != null && scrollValue > 0) {
            // After the new layout pass — setValue clamps to the new extent.
            SwingUtilities.invokeLater(() ->
                scrollPane.getVerticalScrollBar().setValue(scrollValue));
        }
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

        // The task being worked toward (top pin or server focus). Pins own the
        // canonical tracked task, so re-point it before asking what to headline.
        taskPrefs.syncFocus(entry);
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
            body.add(section(event.getId() + ":tasks", "Tasks (" + tasks.size() + ")",
                taskList, tasks.size() > 12));
            body.add(vgap(8));
        }

        List<EventState.Standing> standings = entry.getStandings();
        if (standings != null && !standings.isEmpty()) {
            boolean boardAvailable = entry.getBoard() != null && entry.getBoard().isAvailable();
            JPanel table = standingsTable(entry, standings,
                team != null ? team.getId() : -1, boardAvailable);
            body.add(section(event.getId() + ":standings", "Standings", table, false));
            body.add(vgap(8));
        }

        if (team != null) {
            // Same grid the Player and Group tabs use, fed by the team's own
            // feed. Older servers send no field at all — the placeholder keeps
            // the section honest instead of pretending the team is idle.
            List<RecentSubmission> submissions = entry.getTeamRecentSubmissions();
            boolean anySubmissions = submissions != null && !submissions.isEmpty();
            JComponent feed = anySubmissions
                ? PanelElements.createRecentSubmissionPanel(submissions, itemManager, client, true, false)
                : PanelElements.createRecentSubmissionsPlaceholder(submissions == null
                    ? "No team activity available" : "Nothing scored yet");
            body.add(section(event.getId() + ":submissions", "Team activity", feed, !anySubmissions));
            body.add(vgap(8));
        }

        List<EventState.Member> members = entry.getMembers();
        if (members != null && !members.isEmpty()) {
            String title = "Your team (" + Math.max(entry.getMembersTotal(), members.size()) + ")";
            body.add(section(event.getId() + ":team", title,
                membersBox(entry, members, teamColor), true));
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
        // With several pins the headline is simply the first one still open,
        // so say which of them the player is looking at.
        int pins = task.tracked ? taskPrefs.pinned(entry.getEvent().getId()).size() : 0;
        JLabel caption = new JLabel(!task.tracked ? "TRACKING (auto)"
            : (pins > 1 ? "TRACKING (pin 1 of " + pins + ")" : "TRACKING (your pick)"));
        caption.setFont(FontManager.getRunescapeSmallFont());
        caption.setForeground(task.tracked ? DropTrackerTheme.GOLD_BRIGHT : DropTrackerTheme.TEXT_MUTED);
        head.add(caption, BorderLayout.WEST);
        if (task.tracked) {
            JLabel reset = new JLabel("unpin ✕");
            reset.setFont(FontManager.getRunescapeSmallFont());
            reset.setForeground(DropTrackerTheme.TEXT_MUTED);
            reset.setToolTipText(pins > 1
                ? "Unpin this task and track the next pin instead"
                : "Unpin this task and let the server pick again");
            reset.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            reset.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    taskPrefs.unpin(entry, task.id);
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
        applyTaskIcon(icon, task.iconItemId, task.iconPath, 24);
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

        // Every requirement gets a slot — a "+N more" would hide exactly the
        // items the player came here to check off.
        for (EventState.Requirement requirement : requirements) {
            grid.add(requirementSlot(requirement), c);
            if (++c.gridx >= REQ_ICONS_PER_ROW) {
                c.gridx = 0;
                c.gridy++;
            }
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
            : (req.getIconPath() == null && req.getName() != null
                ? itemIds.findItemId(req.getName()) : null);
        if (itemId != null || req.getIconPath() != null) {
            applyRequirementIcon(slot, itemId, req.getIconPath(), REQ_ICON_SIZE, obtained,
                req.getPoints());
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
     *  obtained (full colour + green tick) or still needed (dimmed), with the
     *  item's point award stamped in the corner on point-based tasks. */
    private void applyRequirementIcon(JLabel target, @Nullable Integer itemId,
                                      @Nullable String iconPath, int size, boolean obtained,
                                      @Nullable Integer points) {
        if (itemId != null && itemId > 0) {
            AsyncBufferedImage itemImage = itemManager.getImage(itemId);
            Runnable apply = () -> {
                target.setIcon(new ImageIcon(styleRequirementImage(itemImage, size, obtained, points)));
                target.revalidate();
                target.repaint();
            };
            itemImage.onLoaded(apply);
            apply.run();
        } else if (iconPath != null) {
            BufferedImage remote = remoteImages.get(iconPath,
                () -> SwingUtilities.invokeLater(this::rebuild));
            if (remote != null) {
                target.setIcon(new ImageIcon(styleRequirementImage(remote, size, obtained, points)));
            }
        }
    }

    /**
     * Fit into the slot, then mark state so it reads at a glance on a small
     * sprite: dimmed = still needed, green tick (top-right) = banked, and on
     * point-based tasks the item's point award bottom-right in the in-game
     * quantity style — the corner opposite to where players read quantities,
     * so the two never get confused.
     */
    private static BufferedImage styleRequirementImage(BufferedImage source, int size,
                                                       boolean obtained, @Nullable Integer points) {
        BufferedImage fitted = fitImage(source, size);
        if (!obtained) {
            fitted = ImageUtil.alphaOffset(fitted, 0.35f);
        }
        Graphics2D g = fitted.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(FontManager.getRunescapeSmallFont());
        java.awt.FontMetrics fm = g.getFontMetrics();
        if (obtained) {
            String tick = "✓";
            int tx = size - fm.stringWidth(tick);
            int ty = fm.getAscent() - 1;
            g.setColor(Color.BLACK);
            g.drawString(tick, tx + 1, ty + 1);
            g.setColor(DropTrackerTheme.GREEN);
            g.drawString(tick, tx, ty);
        }
        if (points != null && points > 0) {
            // Full-alpha even on dimmed sprites: the award must stay readable.
            String pts = points < 1000 ? String.valueOf(points) : ValueFormat.abbrev(points);
            int px = size - fm.stringWidth(pts);
            int py = size - fm.getDescent();
            g.setColor(Color.BLACK);
            g.drawString(pts, px + 1, py + 1);
            g.setColor(OSRS_QUANTITY_YELLOW);
            g.drawString(pts, px, py);
        }
        g.dispose();
        return fitted;
    }

    /** The yellow OSRS renders item quantities in. */
    private static final Color OSRS_QUANTITY_YELLOW = new Color(0xFFFF00);

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
        if (!pickable) {
            // Board games force the current tile: nothing to pin or hide.
            for (EventState.TaskInfo task : tasks) {
                list.add(taskRow(entry, task, displayedId, false, false, false));
                list.add(vgap(2));
            }
            return list;
        }

        int eventId = entry.getEvent().getId();
        Set<Integer> pins = taskPrefs.pinned(eventId);
        Set<Integer> hides = taskPrefs.hidden(eventId);
        List<EventState.TaskInfo> pinned = new ArrayList<>();
        List<EventState.TaskInfo> rest = new ArrayList<>();
        List<EventState.TaskInfo> hidden = new ArrayList<>();
        // Pins lead the list in the order they were pinned — the first one
        // still open is the task the HUD is tracking.
        for (Integer id : pins) {
            EventState.TaskInfo task = taskInfoById(entry, id);
            if (task != null) {
                pinned.add(task);
            }
        }
        for (EventState.TaskInfo task : tasks) {
            if (pins.contains(task.getId())) {
                continue;
            }
            (hides.contains(task.getId()) ? hidden : rest).add(task);
        }

        for (EventState.TaskInfo task : pinned) {
            list.add(taskRow(entry, task, displayedId, true, true, false));
            list.add(vgap(2));
        }
        for (EventState.TaskInfo task : rest) {
            list.add(taskRow(entry, task, displayedId, true, false, false));
            list.add(vgap(2));
        }
        if (!hidden.isEmpty()) {
            String key = String.valueOf(eventId);
            boolean revealed = Boolean.TRUE.equals(hiddenRevealed.get(key));
            list.add(hiddenHeader(entry, key, hidden.size(), revealed));
            list.add(vgap(2));
            if (revealed) {
                for (EventState.TaskInfo task : hidden) {
                    list.add(taskRow(entry, task, displayedId, true, false, true));
                    list.add(vgap(2));
                }
            }
        }

        JLabel hint = new JLabel("<html><div style='width:"
            + (PluginPanel.PANEL_WIDTH - 60) + "px;'>"
            + "Click a task to pin it to the top and the HUD; ✕ hides it."
            + "</div></html>");
        hint.setFont(FontManager.getRunescapeSmallFont());
        hint.setForeground(DropTrackerTheme.TEXT_MUTED);
        hint.setBorder(new EmptyBorder(2, 2, 0, 0));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        list.add(hint);
        return list;
    }

    /** Fold-out header for the tasks the user hid, with a way back. */
    private JComponent hiddenHeader(EventState.Entry entry, String key, int count, boolean revealed) {
        JPanel row = new JPanel(new BorderLayout(5, 0)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setBackground(DropTrackerTheme.SURFACE_1);
        row.setBorder(new EmptyBorder(2, 2, 2, 4));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel chevron = new JLabel(revealed
            ? PanelElements.getExpandedIcon() : PanelElements.getCollapsedIcon());
        row.add(chevron, BorderLayout.WEST);

        JLabel label = new JLabel(count + " hidden");
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(DropTrackerTheme.TEXT_MUTED);
        row.add(label, BorderLayout.CENTER);

        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                hiddenRevealed.put(key, !revealed);
                rebuild();
            }
        });

        JLabel unhideAll = new JLabel("unhide all");
        unhideAll.setFont(FontManager.getRunescapeSmallFont());
        unhideAll.setForeground(DropTrackerTheme.TEXT_MUTED);
        unhideAll.setToolTipText("Show every hidden task again");
        unhideAll.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        unhideAll.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                taskPrefs.unhideAll(entry.getEvent().getId());
                rebuild();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                unhideAll.setForeground(DropTrackerTheme.GOLD_BRIGHT);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                unhideAll.setForeground(DropTrackerTheme.TEXT_MUTED);
            }
        });
        row.add(unhideAll, BorderLayout.EAST);
        return row;
    }

    /**
     * One task row. {@code controls} adds the pin/hide glyphs and click-to-pin
     * (off for board games, whose tile is forced); {@code pinned} marks a row
     * in the pin set; {@code hidden} renders it inside the folded-out hidden
     * group, where the only action is putting it back.
     */
    private JPanel taskRow(EventState.Entry entry, EventState.TaskInfo task,
                           int displayedId, boolean controls, boolean pinned, boolean hidden) {
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
                isDisplayed ? DropTrackerTheme.GOLD
                    : (pinned ? DropTrackerTheme.BRONZE : DropTrackerTheme.SURFACE_3)),
            new EmptyBorder(3, 4, 3, 4)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(20, 20));
        applyTaskIcon(icon, task.getIconItemId(), task.getIconPath(), 20);
        row.add(icon, BorderLayout.WEST);

        JLabel label = new JLabel(truncate(task.getLabel(), controls ? 22 : 30));
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(task.isCompleted() || hidden ? DropTrackerTheme.TEXT_MUTED
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

        if (!controls) {
            row.add(state, BorderLayout.EAST);
        } else {
            JPanel east = new JPanel(new BorderLayout(3, 0));
            east.setOpaque(false);
            east.add(state, BorderLayout.CENTER);

            JPanel glyphs = new JPanel(new BorderLayout(1, 0));
            glyphs.setOpaque(false);
            // A completed task can't lead the HUD, but one pinned before it
            // completed still needs its way out.
            if (!hidden && (!task.isCompleted() || pinned)) {
                glyphs.add(controlGlyph(pinned ? "★" : "☆",
                    pinned ? DropTrackerTheme.GOLD : DropTrackerTheme.STONE,
                    pinned ? "Unpin this task" : "Pin this task to the top and the HUD",
                    () -> togglePin(entry, task.getId(), pinned)), BorderLayout.WEST);
            }
            glyphs.add(controlGlyph(hidden ? "+" : "✕", DropTrackerTheme.STONE,
                hidden ? "Show this task again" : "Hide this task",
                () -> {
                    taskPrefs.toggleHidden(entry, task.getId());
                    rebuild();
                }), BorderLayout.EAST);
            east.add(glyphs, BorderLayout.EAST);
            row.add(east, BorderLayout.EAST);
        }

        row.setToolTipText(taskTooltip(task));

        if (controls && !hidden && (!task.isCompleted() || pinned)) {
            row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            row.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    togglePin(entry, task.getId(), pinned);
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

    /** Pin or unpin a task and redraw. Pinning is an act of focus: point the
     *  HUD at this event too, otherwise pins made in a second event never show
     *  anywhere (the HUD keeps rendering the pinned/first event). */
    private void togglePin(EventState.Entry entry, int taskId, boolean pinned) {
        taskPrefs.togglePin(entry, taskId);
        if (!pinned) {
            config.setPinnedEventId(entry.getEvent().getId());
        }
        rebuild();
    }

    /** Small clickable control in a task row. Sans-serif on purpose: the
     *  RuneScape bitmap fonts have no symbol coverage to fall back on. */
    private static JLabel controlGlyph(String text, Color color, String tooltip, Runnable action) {
        JLabel glyph = new JLabel(text);
        glyph.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        glyph.setForeground(color);
        glyph.setToolTipText(tooltip);
        glyph.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        glyph.setBorder(new EmptyBorder(0, 2, 0, 2));
        glyph.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                action.run();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                glyph.setForeground(DropTrackerTheme.GOLD_BRIGHT);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                glyph.setForeground(color);
            }
        });
        return glyph;
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
        HttpUrl url = api.eventBoardImageUrl(entry.getEvent().getId(), teamId,
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

    /**
     * Lightweight collapsible section: tiny header row + body. The user's
     * toggle is remembered under {@code key} so rebuilds (task tracked,
     * state refreshed) restore it; {@code defaultCollapsed} only applies the
     * first time a section is seen.
     */
    private JPanel section(String key, String title, JComponent body, boolean defaultCollapsed) {
        boolean startCollapsed = sectionCollapsed.getOrDefault(key, defaultCollapsed);
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
                sectionCollapsed.put(key, collapsed[0]);
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
                               @Nullable String iconPath, int size) {
        if (iconItemId != null && iconItemId > 0) {
            AsyncBufferedImage itemImage = itemManager.getImage(iconItemId);
            Runnable apply = () -> {
                target.setIcon(fitIcon(itemImage, size));
                target.revalidate();
                target.repaint();
            };
            itemImage.onLoaded(apply);
            apply.run();
        } else if (iconPath != null) {
            BufferedImage remote = remoteImages.get(iconPath,
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
        return PanelElements.escapeHtml(value);
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
