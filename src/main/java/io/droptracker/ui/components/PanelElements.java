package io.droptracker.ui.components;

import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.api.DropTrackerUrls;
import io.droptracker.models.submissions.RecentSubmission;
import io.droptracker.ui.DropTrackerTheme;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.StrokeBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class PanelElements {


    private static final ImageIcon COLLAPSED_ICON;
    private static final ImageIcon EXPANDED_ICON;
    private static final ImageIcon BOARD_ICON;
    private static final ImageIcon EXTERNAL_LINK_ICON;
    public static @Nullable BufferedImage cachedLootboardImage;
    public static String cachedGroupName = "All Players";
    private static Integer cachedGroupId = null; // Track which group's lootboard is currently cached
    private static long cachedLootboardAtMs = 0;

    /**
     * Shared RuneLite OkHttpClient, set once at panel construction. These UI
     * helpers are static, so the client is injected via {@link #setHttpClient}
     * rather than a constructor.
     *
     * <p>Redirects are disabled: every URL we fetch is built from a hardcoded base
     * in {@link DropTrackerUrls}, and following a redirect would hand that decision
     * back to the server.
     */
    private static OkHttpClient httpClient;

    /**
     * Whether the user has switched on API connections. Lootboards are served from
     * our own host, so every fetch below is gated on this: with the API integration
     * disabled the plugin makes no request to a droptracker.io host at all.
     *
     * <p>Kept in sync by the panel being rebuilt whenever {@code useApi} changes,
     * which calls {@link #setHttpClient} again.
     */
    private static boolean apiEnabled;

    public static void setHttpClient(OkHttpClient client, boolean apiEnabled) {
        httpClient = client == null ? null : client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
        PanelElements.apiEnabled = apiEnabled;
        // Preload the default global group (2) lootboard now that a client exists.
        // The static initializer can't do this because it runs before the client is set.
        loadLootboardForGroup(2);
    }

    /**
     * Fetches an image through the shared OkHttpClient. Takes an {@link HttpUrl}
     * rather than a String so that a raw value from an API response cannot reach
     * here — callers must go through {@link DropTrackerUrls} to get one.
     * A non-image body decodes to null; any failure returns null.
     */
    @Nullable
    private static BufferedImage fetchImage(@Nullable HttpUrl imageUrl) {
        if (httpClient == null || imageUrl == null) {
            return null;
        }
        Request request = new Request.Builder().url(imageUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                return null;
            }
            return ImageIO.read(body.byteStream());
        } catch (Exception e) {
            log.debug("Image fetch failed for {}: {}", imageUrl, e.getMessage());
            return null;
        }
    }

    /** Lootboards regenerate server-side; refetch after this long instead of caching forever. */
    private static final long LOOTBOARD_CACHE_TTL_MS = 10 * 60 * 1000;

    private static boolean isLootboardCacheValid(int groupId) {
        return cachedGroupId != null && cachedGroupId == groupId && cachedLootboardImage != null
            && (System.currentTimeMillis() - cachedLootboardAtMs) < LOOTBOARD_CACHE_TTL_MS;
    }

    static {
        Image collapsedImg = ImageUtil.loadImageResource(DropTrackerPlugin.class, "util/collapse.png");
        Image expandedImg = ImageUtil.loadImageResource(DropTrackerPlugin.class, "util/expand.png");
        Image boardIcon = ImageUtil.loadImageResource(DropTrackerPlugin.class, "util/board.png");
        Image extLinkIcon = ImageUtil.loadImageResource(DropTrackerPlugin.class, "util/external-link.png");
        Image boardResized = boardIcon.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
        Image extRecolored = ImageUtil.recolorImage(extLinkIcon, DropTrackerTheme.TEXT_MUTED);
        Image extLinkResized = extRecolored.getScaledInstance(16, 16, Image.SCALE_SMOOTH);

        Image collapsedResized = collapsedImg.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
        Image expandedResized = expandedImg.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
        Image collapsedRecolored = ImageUtil.recolorImage(collapsedResized, DropTrackerTheme.TEXT_MUTED);
        Image expandedRecolored = ImageUtil.recolorImage(expandedResized, DropTrackerTheme.TEXT_MUTED);
        COLLAPSED_ICON = new ImageIcon(collapsedRecolored);
        EXPANDED_ICON = new ImageIcon(expandedRecolored);
        BOARD_ICON = new ImageIcon(boardResized);
        EXTERNAL_LINK_ICON = new ImageIcon(extLinkResized);
    }

    /**
     * Get the collapsed icon for collapsible panels
     *
     * @return ImageIcon for collapsed state
     */
    public static ImageIcon getCollapsedIcon() {
        return COLLAPSED_ICON;
    }

    /**
     * Get the expanded icon for collapsible panels
     *
     * @return ImageIcon for expanded state
     */
    public static ImageIcon getExpandedIcon() {
        return EXPANDED_ICON;
    }

    /** Small board icon (16x16) used by board pop-out buttons. */
    public static ImageIcon getBoardIcon() {
        return BOARD_ICON;
    }

    /** Small "opens externally" icon (16x16), for buttons that leave the client. */
    public static ImageIcon getExternalLinkIcon() {
        return EXTERNAL_LINK_ICON;
    }

    // Method to load lootboard for a specific group ID
    public static void loadLootboardForGroup(int groupId) {
        loadLootboardForGroup(groupId, null);
    }

    /**
     * {@code /img/clans/<groupId>/lb/lootboard.png} on the hardcoded website host,
     * or null when the API integration is switched off — the single choke point
     * that keeps a disabled plugin from touching our host.
     */
    @Nullable
    private static HttpUrl lootboardUrl(int groupId) {
        if (!apiEnabled) {
            return null;
        }
        return DropTrackerUrls.image("clans/" + groupId + "/lb/lootboard.png");
    }

    // Method to load lootboard for a specific group ID with callback
    public static void loadLootboardForGroup(int groupId, Runnable onComplete) {
        // Check if we already have this group cached
        if (isLootboardCacheValid(groupId)) {
            if (onComplete != null) {
                SwingUtilities.invokeLater(onComplete);
            }
            return;
        }

        HttpUrl imageUrl = lootboardUrl(groupId);
        if (imageUrl == null) {
            if (onComplete != null) {
                SwingUtilities.invokeLater(onComplete);
            }
            return;
        }

        CompletableFuture.supplyAsync(() -> fetchImage(imageUrl)).thenAccept(image -> {
            SwingUtilities.invokeLater(() -> {
                cachedLootboardImage = image;
                cachedGroupId = groupId;
                cachedLootboardAtMs = System.currentTimeMillis();
                // Call the completion callback
                if (onComplete != null) {
                    onComplete.run();
                }
            });
        });
    }

    /**
     * Creates a styled container for submission icons with border and background,
     * with optional enter/exit effects (if an image is provided for the submission)
     */
    public static JLabel createStyledIconContainer(boolean withEffects) {
        JLabel container = new JLabel();
        container.setVerticalAlignment(SwingConstants.CENTER);
        container.setHorizontalAlignment(SwingConstants.CENTER);
        container.setPreferredSize(new Dimension(32, 32));
        container.setMinimumSize(new Dimension(32, 32));
        container.setMaximumSize(new Dimension(32, 32));

        // Add styling with border and background
        container.setOpaque(true);
        container.setBackground(DropTrackerTheme.SURFACE_2);
        container.setBorder(new StrokeBorder(new BasicStroke(1), DropTrackerTheme.SURFACE_3));

        // Add hover effect
        if (withEffects) {
            container.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    container.setBackground(DropTrackerTheme.SURFACE_3);
                    container.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    container.setBackground(DropTrackerTheme.SURFACE_2);
                    container.setCursor(Cursor.getDefaultCursor());
                }
            });
        }

        return container;
    }

    private static JDialog imageDialog(JFrame parentFrame, String title) {
        JDialog imageDialog = new JDialog(parentFrame, title, false);
        imageDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        return imageDialog;
    }

    /** Fills a fresh image dialog with a click-to-close loading message. */
    private static JLabel showLoading(JDialog imageDialog, JFrame parentFrame, String text) {
        JLabel loadingLabel = DropTrackerTheme.label(text, FontManager.getRunescapeBoldFont(), DropTrackerTheme.TEXT);
        loadingLabel.setHorizontalAlignment(JLabel.CENTER);
        loadingLabel.setVerticalAlignment(JLabel.CENTER);
        loadingLabel.setPreferredSize(new Dimension(400, 300));
        loadingLabel.setBackground(DropTrackerTheme.SURFACE_1);
        loadingLabel.setOpaque(true);
        addCloseListener(loadingLabel, imageDialog);

        imageDialog.add(loadingLabel);
        imageDialog.pack();
        imageDialog.setLocationRelativeTo(parentFrame);
        return loadingLabel;
    }

    /** Swaps the loading message for the image, or turns it into {@code failure}. */
    private static void showLoaded(JDialog imageDialog, JLabel loadingLabel, JFrame parentFrame,
                                   @Nullable BufferedImage image, String failure) {
        if (image != null) {
            imageDialog.getContentPane().removeAll();
            displayImageInDialog(imageDialog, image, parentFrame);
        } else {
            loadingLabel.setText(failure);
            loadingLabel.setForeground(DropTrackerTheme.RED);
        }
        imageDialog.revalidate();
        imageDialog.repaint();
    }

    // Method to show lootboard popup for a specific group ID
    public static void showLootboardForGroup(Client client, int groupId) {
        if (cachedGroupName == null) {
            cachedGroupName = "All Players";
        }
        final JFrame parentFrame = getParentFrame(client);
        JDialog imageDialog = imageDialog(parentFrame, cachedGroupName + " - Lootboard");

        // Check if we already have the right group cached
        if (isLootboardCacheValid(groupId)) {
            displayImageInDialog(imageDialog, cachedLootboardImage, parentFrame);
            imageDialog.revalidate();
            imageDialog.repaint();
            imageDialog.setVisible(true);
            return;
        }

        JLabel loadingLabel = showLoading(imageDialog, parentFrame, "Loading group " + groupId + " lootboard...");

        // Start loading BEFORE showing the dialog to avoid modality blocking
        loadLootboardForGroup(groupId, () -> showLoaded(imageDialog, loadingLabel, parentFrame,
            cachedLootboardImage, "Failed to load group " + groupId + " lootboard"));

        imageDialog.setVisible(true);
    }

    /**
     * Generic remote-image pop-out (same machinery as the lootboard dialog):
     * loading placeholder, async fetch via ImageIO (non-image bodies decode to
     * null and show an error instead), click/Esc to close. Used by the Events
     * tab for server-rendered board images.
     */
    public static void showRemoteImage(Client client, String title, @Nullable HttpUrl imageUrl) {
        showUrlImage(client, title, "Loading " + title + "...", imageUrl);
    }

    // Method to show submission image popup
    public static void showSubmissionImage(Client client, String submissionType, @Nullable HttpUrl submissionImageUrl) {
        showUrlImage(client, getSubmissionDialogTitle(submissionType), "Loading " + submissionType + " image...", submissionImageUrl);
    }

    private static void showUrlImage(Client client, String title, String loadingText, @Nullable HttpUrl imageUrl) {
        final JFrame parentFrame = getParentFrame(client);
        JDialog imageDialog = imageDialog(parentFrame, title);
        JLabel loadingLabel = showLoading(imageDialog, parentFrame, loadingText);
        loadUrlImage(imageUrl, imageDialog, loadingLabel, parentFrame);
        imageDialog.setVisible(true);
    }

    private static String getSubmissionDialogTitle(String submissionType) {
        switch (submissionType.toLowerCase()) {
            case "drop":
                return "Drop Submission";
            case "clog":
                return "Collection Log Submission";
            case "pb":
                return "Personal Best Submission";
            default:
                return "Submission";
        }
    }


    private static void loadUrlImage(@Nullable HttpUrl imageUrl, JDialog imageDialog, JLabel loadingLabel, JFrame parentFrame) {
        if (imageUrl == null) {
            loadingLabel.setText("No image URL available");
            loadingLabel.setForeground(DropTrackerTheme.RED);
            return;
        }

        CompletableFuture.supplyAsync(() -> fetchImage(imageUrl)).thenAccept(image ->
            SwingUtilities.invokeLater(() -> showLoaded(imageDialog, loadingLabel, parentFrame, image,
                "Failed to load image... (likely a bug on our end)")));
    }

    /**
     * Stat tile mirroring the web stat tiles: small muted label above a bold gold value.
     * Sized by the surrounding grid; intended for 2-column stat grids.
     */
    public static JPanel createStatBox(String label, String value) {
        JPanel box = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_2);
        box.setBorder(DropTrackerTheme.cardBorder(5, 5, 5, 5));

        JLabel nameLabel = DropTrackerTheme.label(label, FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
        nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel valueLabel = DropTrackerTheme.label(value, FontManager.getRunescapeBoldFont(), DropTrackerTheme.GOLD);
        valueLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        box.add(nameLabel);
        box.add(valueLabel);

        return box;
    }

    public static JPanel getLatestWelcomeContent(DropTrackerApi api) {
        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setBackground(DropTrackerTheme.SURFACE_1);

        // Start with default welcome text
        JTextArea textArea = collapsibleSubText("Welcome to the DropTracker!");
        contentPanel.add(textArea, BorderLayout.CENTER);

        /* getLatestWelcomeString method contains a check for whether config enabled API connections */
        api.getLatestWelcomeString(welcomeText -> {
            // Update the text area with the loaded content
            textArea.setText(welcomeText != null ? welcomeText : "Welcome to the DropTracker!");
            textArea.revalidate();
            textArea.repaint();
        });
        

        return contentPanel;
    }

    public static JPanel getLatestUpdateContent(DropTrackerConfig config, DropTrackerApi api) {
        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setBackground(DropTrackerTheme.SURFACE_1);

        String defaultUpdateText = "- Implemented support for tracking Personal Bests from a POH adventure log.\n\n" +
                "- Added pet collection submissions when adventure logs are opened.\n\n" +
                "- Fixed various personal best tracking bugs.\n\n" +
                "- A new side panel & stats functionality";

        // Start with default or fallback text
        String initialText = (config != null && config.useApi()) ? "Loading updates..." : defaultUpdateText;
        JTextArea textArea = collapsibleSubText(initialText);
        contentPanel.add(textArea, BorderLayout.CENTER);

        /* getLatestUpdateString method contains a check for whether config enabled API connections */
        api.getLatestUpdateString(updateText -> {
            // Update the text area with the loaded content
            textArea.setText(updateText != null ? updateText : "No updates found.");
            textArea.revalidate();
            textArea.repaint();
        });
        

        return contentPanel;
    }

    public static JTextArea collapsibleSubText(String inputString) {
        JTextArea textArea = new JTextArea();
        textArea.setText(inputString);
        textArea.setWrapStyleWord(true);
        textArea.setLineWrap(true);
        textArea.setOpaque(false);
        textArea.setEditable(false);
        textArea.setFocusable(false);
        textArea.setBackground(DropTrackerTheme.SURFACE_1);
        textArea.setForeground(DropTrackerTheme.TEXT_MUTED);
        Font textAreaFont = FontManager.getRunescapeSmallFont();
        textArea.setFont(textAreaFont);
        textArea.setBorder(new EmptyBorder(5, 5, 5, 5));

        return textArea;
    }

    /**
     * A panel that never grows past its preferred height. In a vertical
     * BoxLayout a plain JPanel reports an unbounded maximum size and swallows
     * every leftover pixel — the cause of cards (and their headers) stretching
     * far below their content.
     */
    public static JPanel heightCappedPanel() {
        return new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(PluginPanel.PANEL_WIDTH, getPreferredSize().height);
            }
        };
    }

    public static JPanel createCollapsiblePanel(String title, JPanel content, boolean isUnderlined) {
        JPanel panel = heightCappedPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(DropTrackerTheme.SURFACE_1);
        panel.setBorder(BorderFactory.createLineBorder(DropTrackerTheme.SURFACE_3, 1));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Header strip: darker bar with the title and chevron; the whole strip
        // toggles, and its height is pinned to its content.
        JPanel headerPanel = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        headerPanel.setBackground(DropTrackerTheme.SURFACE_2);
        headerPanel.setBorder(new EmptyBorder(6, 8, 6, 8));
        headerPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel titleLabel = DropTrackerTheme.label(title, FontManager.getRunescapeBoldFont(), isUnderlined ? DropTrackerTheme.GOLD : DropTrackerTheme.TEXT);

        JLabel toggleIcon = new JLabel(EXPANDED_ICON);

        headerPanel.add(titleLabel, BorderLayout.WEST);
        headerPanel.add(toggleIcon, BorderLayout.EAST);

        JPanel contentWrapper = new JPanel(new BorderLayout());
        contentWrapper.setBackground(DropTrackerTheme.SURFACE_1);
        contentWrapper.setBorder(new EmptyBorder(4, 6, 6, 6));
        contentWrapper.add(content, BorderLayout.CENTER);

        final boolean[] isCollapsed = {false};
        headerPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                isCollapsed[0] = !isCollapsed[0];
                toggleIcon.setIcon(isCollapsed[0] ? COLLAPSED_ICON : EXPANDED_ICON);
                // Height follows automatically: the panel's maximum size is
                // derived from its preferred size, which excludes hidden content.
                contentWrapper.setVisible(!isCollapsed[0]);
                panel.revalidate();
                panel.repaint();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                headerPanel.setBackground(DropTrackerTheme.SURFACE_3);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                headerPanel.setBackground(DropTrackerTheme.SURFACE_2);
            }
        });

        panel.add(headerPanel);
        panel.add(contentWrapper);

        return panel;
    }

    public static JFrame getParentFrame(Client client) {
        try {
            if (SwingUtilities.getWindowAncestor(client.getCanvas()) instanceof JFrame) {
                return (JFrame) SwingUtilities.getWindowAncestor(client.getCanvas());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static JButton createExternalLinkButton(String text, String tooltip, boolean withIcon, Runnable action) {
        JButton button = new JButton(text);
        if (withIcon) {
            button.setIcon(EXTERNAL_LINK_ICON);
        }
        button.setToolTipText(tooltip);
        DropTrackerTheme.styleButton(button);
        button.setPreferredSize(new Dimension(150, 30));
        button.addActionListener(e -> action.run());
        return button;
    }

    public static JButton createLootboardButton(String text, String tooltip, Runnable action) {
        // No remote <img> tags here: Swing HTML would fetch them over the network.
        // The board icon already signals "opens externally"; the old " ⇱"
        // suffix had no glyph in any bundled font.
        JButton button = new JButton(text);
        button.setIcon(BOARD_ICON);
        button.setToolTipText(tooltip);
        DropTrackerTheme.styleButton(button);
        button.addActionListener(e -> action.run());
        return button;
    }

    public static JPanel createRecentSubmissionPanel(List<RecentSubmission> recentSubmissions,
                                                     ItemManager itemManager, Client client, boolean forGroup) {
        return createRecentSubmissionPanel(recentSubmissions, itemManager, client, forGroup, true);
    }

    /**
     * {@link #createRecentSubmissionPanel(List, ItemManager, Client, boolean)} with
     * {@code withTitle} off for callers that already sit under a heading of their
     * own (the Events tab's collapsible sections) — the grid alone, no second
     * "Recent Submissions" caption and no space reserved for it.
     */
    public static JPanel createRecentSubmissionPanel(List<RecentSubmission> recentSubmissions,
                                                     ItemManager itemManager, Client client,
                                                     boolean forGroup, boolean withTitle) {
        // Without the title block the container is just the 80px grid inside
        // the 10px top/bottom border.
        final int containerHeight = withTitle ? 150 : 100;

        // Main container with title and submissions
        JPanel container = submissionsContainer(containerHeight);

        // Title panel to ensure centering
        JPanel titlePanel = new JPanel();
        titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS)); // Changed to vertical layout
        titlePanel.setBackground(DropTrackerTheme.SURFACE_1);
        titlePanel.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 50)); // Increased from 20 to 50
        titlePanel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 50));

        JLabel title = DropTrackerTheme.label("Recent Submissions", FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT);
        title.setAlignmentX(Component.CENTER_ALIGNMENT); // Center the title

        titlePanel.add(title);
        titlePanel.add(DropTrackerTheme.gap(3));

        // Alternative: Single HTML label combining warning and text
        JLabel combinedLabel = new JLabel("<html><div style='text-align: center;'><font color='orange'>!</font> <font color='#C0C0C0'>Clicking an icon opens a screenshot, if available.</font></div></html>");
        combinedLabel.setFont(FontManager.getRunescapeSmallFont());
        combinedLabel.setHorizontalAlignment(SwingConstants.CENTER);
        combinedLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        titlePanel.add(combinedLabel);
        titlePanel.add(DropTrackerTheme.gap(8));

        // Submissions panel - use FlowLayout wrapper to center the GridBagLayout
        JPanel submissionWrapper = centeredRow(80);

        JPanel panel = new JPanel();
        panel.setLayout(new GridBagLayout());
        panel.setBackground(DropTrackerTheme.SURFACE_1);
        panel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 80));

        submissionWrapper.add(updateValidSubmissionPanel(panel, recentSubmissions, itemManager, client, forGroup));

        // Add components to container
        if (withTitle) {
            container.add(titlePanel);
            container.add(DropTrackerTheme.gap(5)); // Small gap between title and submissions
        }
        container.add(submissionWrapper);

        return container;
    }

    /**
     * Placeholder shown in place of the recent submissions grid when there is nothing to
     * display (or when the data is still loading). Matches the dimensions of
     * {@link #createRecentSubmissionPanel} so the layout doesn't jump when data arrives.
     */
    public static JPanel createRecentSubmissionsPlaceholder(String message) {
        JPanel container = submissionsContainer(120);

        JPanel titlePanel = centeredRow(20);
        titlePanel.add(DropTrackerTheme.label("Recent Submissions", FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT));

        JPanel contentWrapper = centeredRow(80);

        JLabel messageLabel = DropTrackerTheme.label(message, FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
        messageLabel.setHorizontalAlignment(JLabel.CENTER);
        contentWrapper.add(messageLabel);

        container.add(titlePanel);
        container.add(DropTrackerTheme.gap(5));
        container.add(contentWrapper);

        return container;
    }

    private static JPanel submissionsContainer(int height) {
        JPanel container = DropTrackerTheme.vbox(DropTrackerTheme.SURFACE_1);
        container.setBorder(new EmptyBorder(10, 0, 10, 0));
        container.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, height));
        container.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, height));
        container.setAlignmentX(Component.LEFT_ALIGNMENT); // Keep consistent with parent
        return container;
    }

    /** A fixed-height, panel-wide row that centers what it holds. */
    private static JPanel centeredRow(int height) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        row.setBackground(DropTrackerTheme.SURFACE_1);
        row.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, height));
        row.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, height));
        return row;
    }

    private static JPanel updateValidSubmissionPanel(JPanel panel, List<RecentSubmission> recentSubmissions, ItemManager itemManager, Client client, boolean forGroup) {
        panel.removeAll();


        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.CENTER;
        c.weightx = 0.2; // Equal weight for 5 columns
        c.weighty = 0.5; // Equal weight for 2 rows
        c.gridx = 0;
        c.gridy = 0;
        c.insets = new Insets(1, 1, 1, 1); // Padding around each icon

        int successfullyAdded = 0;
        final int ITEMS_PER_ROW = 5;
        final int MAX_ITEMS = 10;

        // Add each submission icon to the panel (limit to 10 items)
        for (int i = 0; i < Math.min(recentSubmissions.size(), MAX_ITEMS); i++) {
            RecentSubmission submission = recentSubmissions.get(i);
            boolean effects = false;
            if (submission.submissionImageUrl() != null) {
                effects = true;
            }
            try {
                JLabel iconContainer = null;
                if (submission.getSubmissionType().equalsIgnoreCase("drop")) {
                    // Handle drops
                    Integer itemId = submission.getDropItemId();
                    Integer quantity = submission.getDropQuantity();

                    if (itemId != null && quantity != null && itemManager != null) {
                        iconContainer = itemIcon(itemManager.getImage(itemId, quantity, quantity > 1),
                            quantity > 0 ? 1.0f : 0.5f, effects, submission, forGroup, true);
                    }
                } else if (submission.getSubmissionType().equalsIgnoreCase("clog")) {
                    // Handle collection log items
                    Integer itemId = submission.getClogItemId();

                    if (itemId != null && itemManager != null) {
                        iconContainer = itemIcon(itemManager.getImage(itemId, 1, false),
                            1.0f, effects, submission, forGroup, false);
                    }
                } else {
                    // Personal bests, and event completions credited by any
                    // other source type (manual, kc, …): the API's image_path
                    // is the only icon there is.
                    boolean isPb = submission.getSubmissionType().equalsIgnoreCase("pb");
                    HttpUrl imageUrl = submission.imageUrl();

                    if (imageUrl != null) {
                        final int size = isPb ? 16 : 28;
                        final JLabel pbContainer = PanelElements.createStyledIconContainer(effects);
                        pbContainer.setToolTipText(buildSubmissionTooltip(submission, forGroup));
                        pbContainer.setText(isPb ? "PB" : "");
                        pbContainer.setFont(FontManager.getRunescapeSmallFont());
                        pbContainer.setForeground(DropTrackerTheme.TEXT);
                        iconContainer = pbContainer;

                        // Load image asynchronously
                        CompletableFuture.supplyAsync(() -> {
                            BufferedImage image = fetchImage(imageUrl);
                            if (image != null) {
                                Image scaled = image.getScaledInstance(size, size, Image.SCALE_SMOOTH);
                                return new ImageIcon(scaled);
                            }
                            return null;
                        }).thenAccept(imageIcon -> {
                            if (imageIcon != null) {
                                SwingUtilities.invokeLater(() -> {
                                    pbContainer.setText(""); // Remove text
                                    pbContainer.setIcon(imageIcon);
                                    pbContainer.revalidate();
                                    pbContainer.repaint();
                                });
                            }
                        });
                    }
                }


                // Add the icon container if it was created successfully
                if (iconContainer != null) {
                    if (submission.submissionImageUrl() != null) {
                        // Capture submission data for the click listener
                        final String submissionTypeForListener = submission.getSubmissionType();
                        final HttpUrl submissionImageUrlForListener = submission.submissionImageUrl();
                        // Add hover effect and click listener
                        iconContainer.addMouseListener(new MouseAdapter() {
                            @Override
                            public void mouseClicked(MouseEvent e) {
                                PanelElements.showSubmissionImage(client, submissionTypeForListener, submissionImageUrlForListener);
                            }
                        });
                    }

                    panel.add(iconContainer, c);
                    successfullyAdded++;

                    // Move to next position
                    c.gridx++;
                    if (c.gridx >= ITEMS_PER_ROW) {
                        c.gridx = 0;
                        c.gridy++;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed updating submission panel", e);
            }
        }


        // If no icons were added, show a message
        if (successfullyAdded == 0) {
            JLabel debugLabel = DropTrackerTheme.label("No recent submissions to display", FontManager.getRunescapeSmallFont(), DropTrackerTheme.TEXT_MUTED);
            c.gridx = 0;
            c.gridy = 0;
            c.gridwidth = ITEMS_PER_ROW;
            c.gridheight = 2;
            panel.add(debugLabel, c);
        }

        panel.revalidate();
        panel.repaint();
        return panel;
    }

    /**
     * A 28px item icon that starts blank and fills in once the sprite loads.
     * {@code retip} rebuilds the tooltip then, refreshing its "time ago".
     */
    private static JLabel itemIcon(AsyncBufferedImage originalImage, float alpha, boolean effects,
                                   RecentSubmission submission, boolean forGroup, boolean retip) {
        // Create a scaled version of the image for initial display
        BufferedImage scaledImage = new BufferedImage(28, 28, BufferedImage.TYPE_INT_ARGB);
        BufferedImage opaque = ImageUtil.alphaOffset(scaledImage, alpha);
        final JLabel container = PanelElements.createStyledIconContainer(effects);
        container.setToolTipText(buildSubmissionTooltip(submission, forGroup));
        container.setIcon(new ImageIcon(opaque));

        // onLoaded runs on the client thread (and re-posts there when
        // the sprite is already loaded): scale and relayout on the EDT.
        originalImage.onLoaded(() -> SwingUtilities.invokeLater(() -> {
            // Scale the loaded image to 28x28
            Image scaled = originalImage.getScaledInstance(28, 28, Image.SCALE_SMOOTH);
            BufferedImage scaledBuffered = new BufferedImage(28, 28, BufferedImage.TYPE_INT_ARGB);
            Graphics g = scaledBuffered.getGraphics();
            g.drawImage(scaled, 0, 0, null);
            g.dispose(); // Clean up graphics resources

            BufferedImage finalImage = ImageUtil.alphaOffset(scaledBuffered, alpha);
            container.setIcon(new ImageIcon(finalImage));
            if (retip) {
                container.setToolTipText(buildSubmissionTooltip(submission, forGroup));
            }
            container.revalidate();
            container.repaint();
        }));
        return container;
    }

    public static String buildSubmissionTooltip(RecentSubmission submission, boolean forGroup) {
        try {
            String tooltip = "<html><p style='font-size:10px;'>";
            if (submission.getSubmissionType().equalsIgnoreCase("pb")) {
                String pbTime = sanitizeTxt(submission.getPbTime());
                tooltip += "<b>" + pbTime + "</b> at " + sanitizeTxt(submission.getSourceName()) + "<br>" +
                        sanitizeTxt(submission.getPlayerName()) + " - new personal best!<br><br>" +
                        "<i>" + sanitizeTxt(submission.timeSinceReceived()) + "</i>";
            } else if (submission.getSubmissionType().equalsIgnoreCase("drop")) {
                String itemName = sanitizeTxt(submission.getDropItemName());
                tooltip += "<b>" + itemName + "</b><br>" +
                        sanitizeTxt(submission.getPlayerName()) + "<br>" +
                        "from: <i>" + sanitizeTxt(submission.getSourceName()) + "</i><br>" +
                        "<i>" + sanitizeTxt(submission.timeSinceReceived()) + "</i>";
            } else if (submission.getSubmissionType().equalsIgnoreCase("clog")) {
                String itemName = sanitizeTxt(submission.getClogItemName());
                // Group tooltips also name where the item came from.
                tooltip += sanitizeTxt(submission.getPlayerName()) + " - New Collection Log:<br>" +
                        "<b>" + itemName + "</b><br>" +
                        (forGroup ? "<i>from: " + sanitizeTxt(submission.getSourceName()) + "</i><br>" : "") +
                        "<i>" + sanitizeTxt(submission.timeSinceReceived()) + "</i>";
            } else {
                tooltip += genericSubmissionTooltip(submission);
            }
            tooltip += "</p></html>";
            return tooltip;

        } catch (Exception e) {
            return sanitizeTxt(submission.getPlayerName() + " - " + submission.getSubmissionType() + " - " + submission.getSourceName());
        }
    }

    /**
     * Fallback body for submission types the tabs have no bespoke wording for —
     * an event completion credited by kc, xp or a moderator's hand. The API's
     * own display name is the only thing that describes those, and an empty
     * tooltip would be worse than a plain one.
     */
    private static String genericSubmissionTooltip(RecentSubmission submission) {
        String body = "<b>" + sanitizeTxt(submission.getDisplayName()) + "</b><br>" +
                sanitizeTxt(submission.getPlayerName()) + "<br>";
        if (submission.getSourceName() != null) {
            body += "for: <i>" + sanitizeTxt(submission.getSourceName()) + "</i><br>";
        }
        if (submission.getValue() != null) {
            body += sanitizeTxt(submission.getValue()) + "<br>";
        }
        return body + "<i>" + sanitizeTxt(submission.timeSinceReceived()) + "</i>";
    }

    private static String sanitizeTxt(String tooltip) {
        return escapeHtml(tooltip);
    }

    /**
     * Escapes text that is about to be interpolated into a Swing {@code <html>}
     * string. Swing's HTML renderer resolves {@code <img src>} over the network, so
     * any server-supplied string reaching a JLabel or tooltip must come through
     * here — otherwise the API could make the client fetch an arbitrary URL without
     * a single line of request code being involved.
     */
    public static String escapeHtml(String value) {
        return value == null ? "" : value
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void displayImageInDialog(JDialog imageDialog, BufferedImage originalImage, JFrame parentFrame) {
        // Calculate display size based on screen dimensions
        Dimension screenSize = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        int maxWidth = (int) (screenSize.width * 0.9);
        int maxHeight = (int) (screenSize.height * 0.9);

        int originalWidth = originalImage.getWidth();
        int originalHeight = originalImage.getHeight();

        int displayWidth = originalWidth;
        int displayHeight = originalHeight;

        // Scale down if image is too large for screen
        if (originalWidth > maxWidth || originalHeight > maxHeight) {
            double scaleX = (double) maxWidth / originalWidth;
            double scaleY = (double) maxHeight / originalHeight;
            double scale = Math.min(scaleX, scaleY);

            displayWidth = (int) (originalWidth * scale);
            displayHeight = (int) (originalHeight * scale);
        }

        // Create image label
        JLabel imageLabel = new JLabel();
        imageLabel.setPreferredSize(new Dimension(displayWidth, displayHeight));
        imageLabel.setBorder(new StrokeBorder(new BasicStroke(2), DropTrackerTheme.SURFACE_3));
        imageLabel.setHorizontalAlignment(JLabel.CENTER);
        imageLabel.setVerticalAlignment(JLabel.CENTER);
        imageLabel.setBackground(DropTrackerTheme.SURFACE_1);
        imageLabel.setOpaque(true);

        // Scale image if needed
        Image displayImage;
        if (displayWidth == originalWidth && displayHeight == originalHeight) {
            displayImage = originalImage;
        } else {
            displayImage = originalImage.getScaledInstance(displayWidth, displayHeight, Image.SCALE_SMOOTH);
        }

        imageLabel.setIcon(new ImageIcon(displayImage));

        // Add listeners
        addCloseListener(imageLabel, imageDialog);
        addEscapeKeyListener(imageLabel, imageDialog);

        // Set up dialog
        imageDialog.add(imageLabel);
        imageDialog.pack();
        imageDialog.setLocationRelativeTo(parentFrame);

        // Request focus for escape key
        SwingUtilities.invokeLater(() -> imageLabel.requestFocusInWindow());

    }

    // Helper methods to reduce code duplication
    private static void addCloseListener(JLabel label, JDialog dialog) {
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                dialog.dispose();
            }
        });
    }

    private static void addEscapeKeyListener(JLabel label, JDialog dialog) {
        label.setFocusable(true);
        label.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) {
                    dialog.dispose();
                }
            }
        });
    }

}
