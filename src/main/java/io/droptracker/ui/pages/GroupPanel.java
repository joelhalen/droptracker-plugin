package io.droptracker.ui.pages;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.*;
import io.droptracker.models.api.*;
import io.droptracker.ui.*;
import io.droptracker.ui.components.*;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.LinkBrowser;
import javax.annotation.Nullable;

import okhttp3.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;

public class GroupPanel extends SearchPage {
    private final DropTrackerPanel panel;
    private final OkHttpClient httpClient;

    private int currentGroupId = 2; // Track group ID instead of URL

    /**
     * Name of the group currently shown in the detail view. Used to make sure a
     * late-arriving async enrichment doesn't clobber a newer view.
     */
    private String activeDetailGroupName;

    public GroupPanel(Client client, DropTrackerConfig config, DropTrackerApi api, ItemManager itemManager, DropTrackerPanel panel, OkHttpClient httpClient) {
        super(client, config, api, itemManager);
        this.panel = panel;
        // Redirects off: group icons load from a hardcoded base, and following a
        // redirect would let the response choose the host instead.
        this.httpClient = httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    }

    public JPanel create() {
        return create("DropTracker - Groups", "Search for a group", () -> performGroupSearch(""));
    }

    @Override
    protected void showDefaultState() {
        activeDetailGroupName = null;
        contentPanel.removeAll();

        JPanel defaultPanel = defaultPanel();
        defaultPanel.add(instructions("Search for a group by name above"));
        defaultPanel.add(DropTrackerTheme.gap(5));

        if (config.useApi()) {
            leaderboardPlaceholder = StateViews.loading("Loading top groups…");
            defaultPanel.add(leaderboardPlaceholder);
            defaultPanel.add(DropTrackerTheme.gap(10));
            LeaderboardComponents.loadLeaderboardAsync(leaderboardPlaceholder, () -> {
                try {
                    return api.getTopGroups();
                } catch (Exception e) {
                    return null;
                }
            }, this::showLeaderboard);
        }

        defaultPanel.add(buttonRow(PanelElements.createExternalLinkButton("Create a Group", "Click to visit the group creation documentation", false, this::openCreateGroupPage), 0));
        defaultPanel.add(DropTrackerTheme.gap(5));
        defaultPanel.add(buttonRow(PanelElements.createExternalLinkButton("View All Groups", "Click to visit the group page", true, this::openGroupPage), 0));
        defaultPanel.add(Box.createVerticalGlue());
        show(defaultPanel);
    }

    private JPanel showLeaderboard(PanelCard.Top top) {
        // Render the preview instantly from the row's card; enrich asynchronously.
        return LeaderboardComponents.createLeaderboardTable("Top Groups", "Name", top.rows(),
            row -> showGroupDetailsFromRow(row));
    }

    public void performGroupSearch(String directQuery) {
        String searchQuery = directQuery.equalsIgnoreCase("") ? searchField.getText().trim() : directQuery;

        if (searchQuery.isEmpty()) {
            JOptionPane.showMessageDialog(contentPanel, "Please enter a group name to search for.");
            return;
        }

        runSearch("Searching for group…", () -> api.searchCard("/group_search", searchQuery), this::showGroupCard,
            "Group '" + searchQuery + "' was not found.");
    }

    @Override
    protected void showSearchError(String message) {
        activeDetailGroupName = null;
        super.showSearchError(message);
    }

    /**
     * Instantly shows the preview card a top-groups row carries (name, rank,
     * total loot, members, top member), then swaps in the full card from
     * /group_search once it arrives. Never blocks on the network.
     */
    private void showGroupDetailsFromRow(PanelCard.Row row) {
        final String groupName = row.getName();
        if (groupName == null || groupName.trim().isEmpty()) {
            return;
        }
        if (row.getCard() == null) {
            performGroupSearch(groupName);
            return;
        }
        showGroupCard(row.getCard());

        // Enrich in the background; drop the result if the user has navigated away.
        CompletableFuture.supplyAsync(() -> {
            try {
                return api.searchCard("/group_search", groupName);
            } catch (Exception e) {
                return null;
            }
        }).thenAccept(full -> SwingUtilities.invokeLater(() -> {
            if (full != null && groupName.equals(activeDetailGroupName)) {
                showGroupCard(full);
            }
        }));
    }

    private void showGroupCard(PanelCard card) {
        activeDetailGroupName = card.getTitle();
        if (card.getGroupId() != null) {
            currentGroupId = card.getGroupId();
            PanelElements.loadLootboardForGroup(currentGroupId);
        }
        PanelElements.cachedGroupName = card.getTitle();
        showCard(card);
    }

    @Override
    protected JComponent cardIcon(PanelCard card) {
        BufferedImage placeholderImg = new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB);
        JLabel groupIcon = new JLabel(new ImageIcon(placeholderImg));
        groupIcon.setBackground(DropTrackerTheme.SURFACE_2);
        groupIcon.setPreferredSize(new Dimension(50, 50));
        groupIcon.setMaximumSize(new Dimension(50, 50));
        groupIcon.setMinimumSize(new Dimension(50, 50));
        loadGroupIcon(groupIcon, card.getIconPath());
        return groupIcon;
    }

    @Override
    protected void onCardAction(String type, String value) {
        if ("player".equals(type) && value != null) {
            panel.selectPanel("players");
            panel.updatePlayerPanel(value);
        } else if ("lootboard".equals(type)) {
            PanelElements.showLootboardForGroup(client, currentGroupId);
        }
    }

    private void openGroupPage() {
        try {
            LinkBrowser.browse(DropTrackerUrls.web("groups").toString());
        } catch (Exception e) {
            // Fallback: copy URL to clipboard or show message
            JOptionPane.showMessageDialog(contentPanel,
                    "Could not open browser. Please visit:\nhttps://www.droptracker.io/groups",
                    "Group Page",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void openCreateGroupPage() {
        try {
            LinkBrowser.browse(DropTrackerUrls.web("wiki", "create-group").toString());
        } catch (Exception e) {
            // Fallback: copy URL to clipboard or show message
            JOptionPane.showMessageDialog(contentPanel,
                    "Could not open browser. Please visit:\nhttps://droptracker.io/wiki/create-group",
                    "Create Group Guide",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * Downloads the group icon, scales it to 50×50, then swaps it into the given label.
     * This runs off the EDT to avoid blocking the UI.
     *
     * @param imagePath path under {@code /img/} supplied by the API, e.g.
     *                  {@code "clans/2/icon.png"}. Not a URL — the host is decided
     *                  by {@link DropTrackerUrls#image}.
     */
    private void loadGroupIcon(JLabel iconLabel, String imagePath) {
        if (imagePath == null || imagePath.trim().isEmpty()) {
            return; // nothing to load
        }

        // we can't load gifs in swing panels natively, so we swap for a png alternative hoping it exists
        String pngPath = imagePath.replace(".gif", ".png");
        HttpUrl pngUrl = DropTrackerUrls.image(pngPath);
        HttpUrl originalUrl = DropTrackerUrls.image(imagePath);

        CompletableFuture.supplyAsync(() -> {
            ImageIcon icon = fetchScaledIcon(pngUrl);
            if (icon == null && !pngPath.equals(imagePath)) {
                // Fall back to the original path if the .png swap didn't exist.
                icon = fetchScaledIcon(originalUrl);
            }
            return icon;
        }).thenAccept(icon -> {
            if (icon != null) {
                SwingUtilities.invokeLater(() -> iconLabel.setIcon(icon));
            }
        });
    }

    private ImageIcon fetchScaledIcon(@Nullable HttpUrl url) {
        if (url == null) {
            return null;
        }
        Request request = new Request.Builder().url(url).build();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                return null;
            }
            BufferedImage img = ImageIO.read(body.byteStream());
            if (img == null) {
                return null;
            }
            Image scaled = img.getScaledInstance(50, 50, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (Exception e) {
            return null;
        }
    }

}
