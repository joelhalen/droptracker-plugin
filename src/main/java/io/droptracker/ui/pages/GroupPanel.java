package io.droptracker.ui.pages;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.*;
import io.droptracker.models.api.*;
import io.droptracker.ui.*;
import io.droptracker.ui.components.*;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;
import javax.annotation.Nullable;

import okhttp3.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
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

    private JPanel showLeaderboard(TopGroupResult leaderboardData) {
        return LeaderboardComponents.createLeaderboardTable(
                "Top Groups",
                "Name",
                leaderboardData != null ? leaderboardData.getGroups() : null,
                new LeaderboardComponents.LeaderboardItemRenderer<TopGroupResult.TopGroup>() {
                    @Override
                    public String getName(TopGroupResult.TopGroup group) {
                        return group.getGroupName() != null && !group.getGroupName().trim().isEmpty()
                                ? group.getGroupName()
                                : "Unknown Group";
                    }

                    @Override
                    public String getLootValue(TopGroupResult.TopGroup group) {
                        String loot = group.getTotalLoot();
                        return (loot != null && !loot.trim().isEmpty()) ? loot : "0 GP";
                    }

                    @Override
                    public Integer getRank(TopGroupResult.TopGroup group) {
                        return group.getRank();
                    }

                    @Override
                    public void onItemClick(TopGroupResult.TopGroup group) {
                        // Render instantly from the row data; enrich asynchronously.
                        showGroupDetailsFromRow(group);
                    }
                }
        );
    }

    public void performGroupSearch(String directQuery) {
        String searchQuery = directQuery.equalsIgnoreCase("") ? searchField.getText().trim() : directQuery;

        if (searchQuery.isEmpty()) {
            JOptionPane.showMessageDialog(contentPanel, "Please enter a group name to search for.");
            return;
        }

        runSearch("Searching for group…", () -> api.searchGroup(searchQuery), result -> {
            showGroupDetails(result, false);
            // Load the lootboard image when group is found
            if (result.getGroupDropTrackerId() != null) {
                currentGroupId = result.getGroupDropTrackerId();
                PanelElements.loadLootboardForGroup(currentGroupId);
            }
            PanelElements.cachedGroupName = result.getGroupName();
        }, "Group '" + searchQuery + "' was not found.");
    }

    @Override
    protected void showSearchError(String message) {
        activeDetailGroupName = null;
        super.showSearchError(message);
    }

    /**
     * Instantly renders the group detail view from data already present in a top-groups
     * leaderboard row (name, rank, total loot, member count, top member), then
     * asynchronously enriches it with the full /group_search payload (description, icon,
     * discord link, recent submissions) once that arrives. Never blocks on the network.
     */
    private void showGroupDetailsFromRow(TopGroupResult.TopGroup row) {
        if (row == null || row.getGroupName() == null || row.getGroupName().trim().isEmpty()) {
            return;
        }
        final String groupName = row.getGroupName();

        // Build a partial result from the row's data and render it immediately.
        GroupSearchResult partial = new GroupSearchResult();
        partial.setGroupName(groupName);
        partial.setGroupDropTrackerId(row.getGroupId());
        GroupSearchResult.GroupStats stats = new GroupSearchResult.GroupStats();
        stats.setTotalMembers(row.getMemberCount() != null ? row.getMemberCount() : 0);
        stats.setGlobalRank(row.getRank() != null ? String.valueOf(row.getRank()) : null);
        stats.setMonthlyLoot(row.getTotalLoot());
        partial.setGroupStats(stats);
        partial.setGroupTopPlayer(row.getTopMemberString());
        showGroupDetails(partial, true);

        if (row.getGroupId() != null) {
            currentGroupId = row.getGroupId();
            PanelElements.loadLootboardForGroup(currentGroupId);
            PanelElements.cachedGroupName = groupName;
        }

        // Enrich in the background; drop the result if the user has navigated away.
        CompletableFuture.supplyAsync(() -> {
            try {
                return api.searchGroup(groupName);
            } catch (Exception e) {
                return null;
            }
        }).thenAccept(full -> SwingUtilities.invokeLater(() -> {
            if (full == null || !groupName.equals(activeDetailGroupName)) {
                return;
            }
            if (full.getGroupDropTrackerId() != null) {
                currentGroupId = full.getGroupDropTrackerId();
                PanelElements.loadLootboardForGroup(currentGroupId);
            }
            PanelElements.cachedGroupName = full.getGroupName();
            showGroupDetails(full, false);
        }));
    }

    /**
     * Renders the group detail card. When {@code partial} is true the view was built
     * from leaderboard-row data only, so the fields the row can't provide (description,
     * recent submissions) show loading placeholders instead of empty states.
     */
    private void showGroupDetails(GroupSearchResult groupResult, boolean partial) {
        activeDetailGroupName = groupResult.getGroupName();
        contentPanel.removeAll();

        // Group icon
        BufferedImage placeholderImg = new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB);
        JLabel groupIcon = new JLabel(new ImageIcon(placeholderImg));
        groupIcon.setBackground(DropTrackerTheme.SURFACE_2);
        groupIcon.setPreferredSize(new Dimension(50, 50));
        groupIcon.setMaximumSize(new Dimension(50, 50));
        groupIcon.setMinimumSize(new Dimension(50, 50));
        loadGroupIcon(groupIcon, groupResult.getGroupImagePath());

        JPanel groupNamePanel = nameColumn(groupResult.getGroupName());

        String description = groupResult.getGroupDescription() != null
            ? groupResult.getGroupDescription()
            : (partial ? "Loading details…" : "");
        // Escaped: Swing resolves <img src> in HTML labels, so an unescaped server
        // description is an outbound request to a host of the server's choosing.
        JLabel groupDescLabel = DropTrackerTheme.label("<html>" + PanelElements.escapeHtml(description) + "</html>",
            FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
        groupDescLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        groupNamePanel.add(groupDescLabel);

        GroupSearchResult.GroupStats groupStats = groupResult.getGroupStats();
        String memberText = groupStats != null ? String.valueOf(groupStats.getTotalMembers()) : "—";
        String rankText = (groupStats != null && groupStats.getGlobalRank() != null) ? "#" + groupStats.getGlobalRank() : "—";
        String lootText = (groupStats != null && groupStats.getMonthlyLoot() != null) ? groupStats.getMonthlyLoot() + " GP" : "—";
        String topPlayer = groupResult.getGroupTopPlayer();

        JPanel topPlayerBox = PanelElements.createStatBox("Top Player", topPlayer != null ? topPlayer : "—");
        if (topPlayer != null && !topPlayer.trim().isEmpty()) {
            topPlayerBox.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            topPlayerBox.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    panel.selectPanel("players");
                    panel.updatePlayerPanel(topPlayer.split("\\(")[0].trim());
                }
            });
        }
        JPanel statsPanel = statsGrid(
            PanelElements.createStatBox("Members", memberText),
            PanelElements.createStatBox("Global Rank", rankText),
            PanelElements.createStatBox("Monthly Loot", lootText),
            topPlayerBox);

        JPanel actionPanel = actionRow();

        // The API sends an invite code, not a link: the discord.gg host is ours to decide.
        final HttpUrl inviteUrl = DropTrackerUrls.discordInvite(groupResult.getDiscordInviteCode());
        if (inviteUrl != null) {
            JButton joinButton = actionButton("Discord");
            joinButton.addActionListener(e -> LinkBrowser.browse(inviteUrl.toString()));
            actionPanel.add(joinButton);
        }

        actionPanel.add(PanelElements.createLootboardButton("View Lootboard", "Click to view the lootboard", () -> PanelElements.showLootboardForGroup(client, currentGroupId)));

        showCard(detailHeader(groupIcon, groupNamePanel), statsPanel,
            recent(groupResult.getGroupRecentSubmissions(), true,
                partial ? "Loading recent activity…" : "No recent submissions available"),
            actionPanel);
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
