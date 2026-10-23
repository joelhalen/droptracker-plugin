package io.droptracker.ui.pages;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.AllArgsConstructor;
import io.droptracker.DropTrackerConfig;
import io.droptracker.api.*;
import io.droptracker.models.api.PanelCard;
import io.droptracker.models.submissions.RecentSubmission;
import io.droptracker.ui.DropTrackerTheme;
import io.droptracker.ui.components.*;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.*;
import net.runelite.client.util.LinkBrowser;

import javax.annotation.Nullable;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * The shell the Players and Groups pages share: a search header over a content
 * area that swaps between the default view, a loading state, an error and a
 * detail card. Both pages lay their detail cards out the same way.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
abstract class SearchPage {
    protected final Client client;
    protected final DropTrackerConfig config;
    protected final DropTrackerApi api;
    protected final ItemManager itemManager;

    // UI components that we need to update
    protected JPanel contentPanel;
    protected JTextField searchField;
    protected JPanel leaderboardPlaceholder;

    protected abstract void showDefaultState();

    protected JPanel create(String title, String searchHint, Runnable onSearch) {
        JPanel mainPanel = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);

        LeaderboardComponents.HeaderResult headerResult = LeaderboardComponents.createHeaderPanel(title, searchHint, onSearch);
        searchField = headerResult.searchField;

        // Content panel that will change based on state
        contentPanel = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);
        contentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        showDefaultState();

        mainPanel.add(headerResult.panel);
        mainPanel.add(DropTrackerTheme.gap(10));
        mainPanel.add(contentPanel);
        mainPanel.add(Box.createVerticalGlue());
        return mainPanel;
    }

    /** Replaces whatever the content area shows. */
    protected void show(JComponent view) {
        contentPanel.removeAll();
        contentPanel.add(view);
        contentPanel.revalidate();
        contentPanel.repaint();
    }

    /** Clears the search box and goes back to the default view. */
    protected void reset() {
        searchField.setText("");
        showDefaultState();
    }

    protected void showSearchError(String message) {
        show(StateViews.error(message, "Back to Search", this::reset));
    }

    /**
     * Shows {@code loadingText}, runs {@code lookup} off the EDT, then shows
     * the result. Keeps the failure cause so the user can tell "doesn't exist"
     * apart from "the API call failed".
     */
    protected <R> void runSearch(String loadingText, Callable<R> lookup, Consumer<R> onFound, String notFoundMessage) {
        show(StateViews.loading(loadingText));
        CompletableFuture.supplyAsync(() -> {
            try {
                return new SearchOutcome<>(lookup.call(), null);
            } catch (Exception e) {
                return new SearchOutcome<R>(null, e);
            }
        }).thenAccept(outcome -> SwingUtilities.invokeLater(() -> {
            if (outcome.result != null) {
                onFound.accept(outcome.result);
            } else if (outcome.error == null || isNotFound(outcome.error)) {
                showSearchError(notFoundMessage);
            } else {
                showSearchError("Search failed — the DropTracker API could not be reached. Please try again.");
            }
        }));
    }

    private static boolean isNotFound(Exception e) {
        String message = e.getMessage();
        return message != null && message.contains("status: 404");
    }

    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    private static class SearchOutcome<R> {
        final R result;
        final Exception error;

    }

    /* ===================== default view pieces ===================== */

    protected static JPanel defaultPanel() {
        JPanel panel = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_0);
        panel.setAlignmentX(Component.CENTER_ALIGNMENT);
        return panel;
    }

    protected static JLabel instructions(String text) {
        JLabel label = DropTrackerTheme.label(text, FontManager.getRunescapeFont(), DropTrackerTheme.TEXT_MUTED);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        label.setHorizontalAlignment(JLabel.CENTER);
        return label;
    }

    /** A full-width row holding one centered button. */
    protected static JPanel buttonRow(Component button, int gap) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, gap, gap));
        row.setBackground(DropTrackerTheme.SURFACE_0);
        row.setAlignmentX(Component.CENTER_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        row.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 30));
        row.add(button);
        return row;
    }

    /* ===================== detail card pieces ===================== */

    private static JPanel fixed(LayoutManager layout, int width, int height) {
        JPanel panel = new JPanel(layout);
        panel.setBackground(DropTrackerTheme.SURFACE_1);
        panel.setMaximumSize(new Dimension(width, height));
        panel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, height));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    /** The name column of a detail header, holding the bold name label. */
    protected static JPanel nameColumn(String name) {
        JPanel column = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
        JLabel nameLabel = DropTrackerTheme.label(name, FontManager.getRunescapeBoldFont(), DropTrackerTheme.TEXT);
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(nameLabel);
        return column;
    }

    /** Detail header: optional icon, the name column and a clear button. */
    protected JPanel detailHeader(@Nullable JComponent icon, JPanel names) {
        JPanel header = fixed(new BorderLayout(10, 0), PluginPanel.PANEL_WIDTH - 40, 60);
        if (icon != null) {
            header.add(icon, BorderLayout.WEST);
        }
        header.add(names, BorderLayout.CENTER);
        header.add(LeaderboardComponents.createClearButton(this::reset), BorderLayout.EAST);
        return header;
    }

    protected static JPanel statsGrid(JComponent... boxes) {
        JPanel stats = fixed(new GridLayout(2, 2, 5, 5), PluginPanel.PANEL_WIDTH, 100);
        stats.setBorder(new EmptyBorder(10, 0, 10, 0));
        for (JComponent box : boxes) {
            stats.add(box);
        }
        return stats;
    }

    protected static JPanel actionRow() {
        return fixed(new FlowLayout(FlowLayout.CENTER, 10, 0), PluginPanel.PANEL_WIDTH - 40, 40);
    }

    protected static JButton actionButton(String text) {
        JButton button = new JButton(text);
        DropTrackerTheme.styleButton(button);
        button.setMargin(new Insets(0, 5, 0, 5));
        return button;
    }

    /** Recent submissions, or a placeholder carrying {@code emptyText}. */
    protected JPanel recent(List<RecentSubmission> submissions, boolean group, String emptyText) {
        return submissions != null && !submissions.isEmpty()
            ? PanelElements.createRecentSubmissionPanel(submissions, itemManager, client, group)
            : PanelElements.createRecentSubmissionsPlaceholder(emptyText);
    }

    /* ===================== server-composed cards ===================== */

    /** The page's own actions: player, lootboard, refresh. */
    protected abstract void onCardAction(String type, @Nullable String value);

    /** The header icon, if this page's cards have one. */
    @Nullable
    protected JComponent cardIcon(PanelCard card) {
        return null;
    }

    /**
     * Draws a detail card the server composed (api/panel_cards.py): every
     * string is escaped or set as plain text, and links are built here, on
     * our own hosts.
     */
    protected void showCard(PanelCard card) {
        JPanel names = nameColumn(card.getTitle());
        PanelCard.Subtitle sub = card.getSubtitle();
        if (sub != null) {
            names.add(DropTrackerTheme.gap(5));
            JLabel label = DropTrackerTheme.label(sub.getText(), FontManager.getRunescapeSmallFont(),
                "good".equals(sub.getTone()) ? DropTrackerTheme.GREEN
                    : "warn".equals(sub.getTone()) ? DropTrackerTheme.EMBER : DropTrackerTheme.TEXT_MUTED);
            label.setToolTipText(html(sub.getTip()));
            names.add(left(label));
        }
        if (card.getDescription() != null) {
            names.add(left(DropTrackerTheme.label(html(card.getDescription()), FontManager.getRunescapeSmallFont(),
                DropTrackerTheme.TEXT_MUTED)));
        }
        if (!card.getList().isEmpty()) {
            JPanel list = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
            list.setAlignmentX(Component.LEFT_ALIGNMENT);
            if (card.getListTitle() != null) {
                list.add(left(DropTrackerTheme.label(card.getListTitle(), FontManager.getRunescapeSmallFont(),
                    DropTrackerTheme.TEXT_MUTED)));
            }
            for (String line : card.getList()) {
                list.add(left(DropTrackerTheme.label(line, FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED)));
            }
            names.add(list);
        }

        JComponent[] boxes = card.getStats().stream().map(this::statBox).toArray(JComponent[]::new);
        JPanel actions = actionRow();
        for (PanelCard.Button b : card.getActions()) {
            Runnable run = () -> perform(b.getAction());
            if ("lootboard".equals(b.getIcon())) {
                actions.add(PanelElements.createLootboardButton(b.getLabel(), "Click to view the lootboard", run));
                continue;
            }
            JButton button = actionButton(b.getLabel());
            if ("external".equals(b.getIcon())) {
                button.setIcon(PanelElements.getExternalLinkIcon());
            }
            button.addActionListener(e -> run.run());
            actions.add(button);
        }
        showCard(detailHeader(cardIcon(card), names), statsGrid(boxes),
            recent(card.getRecent(), card.isRecentGroup(), card.getRecentEmpty()), actions);
    }

    /** A stat tile; one with an action is clickable and lights up on hover. */
    private JComponent statBox(PanelCard.Stat stat) {
        JPanel box = PanelElements.createStatBox(stat.getLabel(), stat.getValue());
        if (stat.getAction() != null) {
            box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            box.setToolTipText(html(stat.getTip()));
            box.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    perform(stat.getAction());
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    box.setBackground(DropTrackerTheme.SURFACE_3);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    box.setBackground(DropTrackerTheme.SURFACE_2);
                }
            });
        }
        return box;
    }

    private void perform(@Nullable PanelCard.Action action) {
        if (action == null || action.getType() == null) {
            return;
        }
        String value = action.getValue();
        if ("web".equals(action.getType()) && value != null) {
            LinkBrowser.browse(DropTrackerUrls.web(value.split("/")).toString());
        } else if ("discord".equals(action.getType())) {
            okhttp3.HttpUrl invite = DropTrackerUrls.discordInvite(value);
            if (invite != null) {
                LinkBrowser.browse(invite.toString());
            }
        } else {
            onCardAction(action.getType(), value);
        }
    }

    /** Escaped for a Swing HTML label (it would fetch a remote {@code <img>}); newlines kept. */
    @Nullable
    private static String html(@Nullable String text) {
        return text == null ? null
            : "<html>" + PanelElements.escapeHtml(text).replace("\n", "<br/>") + "</html>";
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    /** Shows a detail card: header, stats, recent submissions, actions. */
    protected void showCard(JPanel header, JPanel stats, JPanel recent, JPanel actions) {
        JPanel card = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
        card.setBorder(DropTrackerTheme.cardBorder(10, 10, 10, 10));
        card.add(header);
        card.add(DropTrackerTheme.gap(10));
        card.add(stats);
        card.add(DropTrackerTheme.gap(10));
        card.add(recent);
        card.add(DropTrackerTheme.gap(10));
        card.add(actions);
        show(card);
    }
}
