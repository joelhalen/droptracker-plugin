package io.droptracker.ui.pages;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.api.EventState;
import io.droptracker.service.EventNotificationService;
import io.droptracker.ui.DropTrackerTheme;
import io.droptracker.ui.components.PanelElements;
import io.droptracker.util.RemoteImageCache;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * "Events" side-panel tab: every active event the player is in — team
 * standing, the focus task, full standings, the "Show on HUD" pick (which
 * event the Enhanced Display renders), and a server-rendered board pop-out
 * with a team switcher (reuses the lootboard image-dialog machinery).
 */
public class EventsPanel {
    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final EventNotificationService service;
    private final Client client;
    private final ItemManager itemManager;
    private final RemoteImageCache remoteImages;

    private JPanel root;
    private JPanel listPanel;

    public EventsPanel(DropTrackerConfig config, DropTrackerApi api,
                       EventNotificationService service, Client client,
                       ItemManager itemManager, RemoteImageCache remoteImages) {
        this.config = config;
        this.api = api;
        this.service = service;
        this.client = client;
        this.itemManager = itemManager;
        this.remoteImages = remoteImages;
    }

    public JPanel create() {
        root = new JPanel(new BorderLayout());
        root.setBackground(DropTrackerTheme.SURFACE_0);

        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(DropTrackerTheme.SURFACE_0);
        listPanel.setBorder(new javax.swing.border.EmptyBorder(5, 0, 5, 0));

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
        header.setBorder(new javax.swing.border.EmptyBorder(4, 6, 4, 6));

        root.add(header, BorderLayout.NORTH);
        root.add(listPanel, BorderLayout.CENTER);

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
            for (EventState.Entry entry : entries) {
                listPanel.add(eventCard(entry, multiple));
                listPanel.add(Box.createRigidArea(new Dimension(0, 6)));
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
        label.setBorder(new javax.swing.border.EmptyBorder(12, 8, 12, 8));
        return label;
    }

    private JPanel eventCard(EventState.Entry entry, boolean showHudPick) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(DropTrackerTheme.SURFACE_1);
        card.setBorder(DropTrackerTheme.cardBorder(8, 8, 8, 8));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        EventState.EventInfo event = entry.getEvent();
        EventState.TeamInfo team = entry.getTeam();

        JLabel name = new JLabel(event.getName());
        name.setFont(FontManager.getRunescapeBoldFont());
        name.setForeground(DropTrackerTheme.GOLD);
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(name);

        if (team != null) {
            String standing = team.getRank() != null
                ? " — " + ordinal(team.getRank()) + " of " + team.getTeamCount()
                : "";
            JLabel teamLine = new JLabel(team.getName() + standing + " (" + team.getScore() + " pts)");
            teamLine.setFont(FontManager.getRunescapeSmallFont());
            teamLine.setForeground(parseColor(team.getColor(), DropTrackerTheme.TEXT));
            teamLine.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(teamLine);
        }

        JLabel tasksLine = new JLabel("Tasks completed: "
            + entry.getTasksCompleted() + " / " + entry.getTasksTotal());
        tasksLine.setFont(FontManager.getRunescapeSmallFont());
        tasksLine.setForeground(DropTrackerTheme.TEXT_MUTED);
        tasksLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(tasksLine);

        EventState.FocusTask task = entry.getFocusTask();
        if (task != null) {
            card.add(Box.createRigidArea(new Dimension(0, 4)));
            card.add(focusTaskRow(task));
        } else if ("awaiting_roll".equals(entry.getBoardStatus())) {
            JLabel roll = new JLabel("Your team can roll the dice!");
            roll.setFont(FontManager.getRunescapeSmallFont());
            roll.setForeground(DropTrackerTheme.GOLD_BRIGHT);
            roll.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(roll);
        }

        List<EventState.Standing> standings = entry.getStandings();
        if (standings != null && !standings.isEmpty()) {
            card.add(Box.createRigidArea(new Dimension(0, 6)));
            card.add(standingsBox(standings, team != null ? team.getId() : -1));
        }

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setBackground(DropTrackerTheme.SURFACE_1);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);

        if (showHudPick) {
            boolean pinned = config.pinnedEventId() == event.getId();
            JButton pin = new JButton(pinned ? "On HUD ✓" : "Show on HUD");
            DropTrackerTheme.styleButton(pin);
            pin.setEnabled(!pinned);
            pin.addActionListener(e -> {
                config.setPinnedEventId(event.getId());
                onUpdated();
            });
            actions.add(pin);
        }

        if (entry.getBoard() != null && entry.getBoard().isAvailable()
                && standings != null && !standings.isEmpty()) {
            JComboBox<TeamChoice> teamPick = new JComboBox<>();
            for (EventState.Standing s : standings) {
                teamPick.addItem(new TeamChoice(s.getTeamId(), s.getName()));
                if (team != null && s.getTeamId() == team.getId()) {
                    teamPick.setSelectedIndex(teamPick.getItemCount() - 1);
                }
            }
            teamPick.setFont(FontManager.getRunescapeSmallFont());
            teamPick.setMaximumSize(new Dimension(110, 24));
            JButton viewBoard = new JButton("View board");
            DropTrackerTheme.styleButton(viewBoard);
            viewBoard.addActionListener(e -> {
                TeamChoice choice = (TeamChoice) teamPick.getSelectedItem();
                String playerName = client.getLocalPlayer() != null
                    ? client.getLocalPlayer().getName() : null;
                if (playerName == null) {
                    return;
                }
                String url = api.eventBoardImageUrl(event.getId(),
                    choice != null ? choice.teamId : null,
                    playerName, client.getAccountHash());
                PanelElements.showRemoteImage(client,
                    event.getName() + (choice != null ? " — " + choice.name : ""), url);
            });
            actions.add(viewBoard);
            actions.add(teamPick);
        }

        if (actions.getComponentCount() > 0) {
            card.add(Box.createRigidArea(new Dimension(0, 6)));
            card.add(actions);
        }
        return card;
    }

    private JPanel focusTaskRow(EventState.FocusTask task) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setBackground(DropTrackerTheme.SURFACE_1);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel iconLabel = new JLabel();
        iconLabel.setPreferredSize(new Dimension(24, 24));
        if (task.getIconItemId() != null && task.getIconItemId() > 0) {
            AsyncBufferedImage itemImage = itemManager.getImage(task.getIconItemId());
            itemImage.addTo(iconLabel);
        } else if (task.getIconUrl() != null) {
            BufferedImage remote = remoteImages.get(task.getIconUrl(),
                () -> SwingUtilities.invokeLater(this::rebuild));
            if (remote != null) {
                iconLabel.setIcon(new ImageIcon(ImageUtil.resizeImage(remote, 24, 24)));
            }
        }
        row.add(iconLabel);

        String progress = task.getNeed() > 1 || task.getHave() > 0
            ? "  (" + task.getHave() + "/" + task.getNeed() + ")" : "";
        JLabel label = new JLabel("<html>" + escape(task.getLabel()) + progress + "</html>");
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(DropTrackerTheme.TEXT);
        row.add(label);
        return row;
    }

    private JPanel standingsBox(List<EventState.Standing> standings, int ownTeamId) {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBackground(DropTrackerTheme.SURFACE_2);
        box.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        int shown = 0;
        for (EventState.Standing standing : standings) {
            if (shown >= 8) {
                JLabel more = new JLabel("... and " + (standings.size() - shown) + " more teams");
                more.setFont(FontManager.getRunescapeSmallFont());
                more.setForeground(DropTrackerTheme.TEXT_MUTED);
                box.add(more);
                break;
            }
            boolean own = standing.getTeamId() == ownTeamId;
            JLabel line = new JLabel(standing.getRank() + ". " + standing.getName()
                + " — " + standing.getScore() + (own ? "  ←" : ""));
            line.setFont(FontManager.getRunescapeSmallFont());
            line.setForeground(own ? DropTrackerTheme.GOLD_BRIGHT
                : parseColor(standing.getColor(), DropTrackerTheme.TEXT_MUTED));
            box.add(line);
            shown++;
        }
        return box;
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

    private static class TeamChoice {
        final int teamId;
        final String name;

        TeamChoice(int teamId, String name) {
            this.teamId = teamId;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
