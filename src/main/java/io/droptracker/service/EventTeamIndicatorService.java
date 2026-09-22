package io.droptracker.service;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.TeamIndicatorStyle;
import io.droptracker.models.api.EventRoster;
import io.droptracker.models.api.EventState;
import io.droptracker.models.api.Manifest;
import io.droptracker.util.ChatMessageUtil;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IndexedSprite;
import net.runelite.api.MessageNode;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import org.jetbrains.annotations.VisibleForTesting;

import javax.annotation.Nullable;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Badges clan-chat lines from players in the same live event with their team
 * (suggestion #150).
 *
 * The roster comes from the API, which is the whole difference from the plugin
 * this was modelled on: nobody types names into a panel, so teams stay right
 * when an admin moves someone, and a clanmate who has never opened the site is
 * still badged.
 *
 * <p>The badge is applied while a chat line is being <em>drawn</em>, through
 * the chat builder's {@code chatMessageBuilding} callback, and is never written
 * back to the {@link MessageNode}. The first version did write it back, and
 * that broke private messages:
 * <ul>
 * <li>{@code MessageNode.setName} changes more than the text. RuneLite also
 * rebuilds the node's sender identity from whatever follows the last '>', so a
 * name ending in {@code </col>} left the line with an empty sender. The game
 * decides "is this from a friend?" from that identity, so with Private set to
 * Friends a teammate's PMs vanished, and with Clan set to Friends so did their
 * clan lines.</li>
 * <li>The walk over {@code client.getMessages()} that badged lines already on
 * screen reached every chat type, DMs included, which is how names in the PM
 * pane got recoloured.</li>
 * </ul>
 * Now each line's type is checked before it is badged. The node keeps the name
 * the server sent, other plugins read it unchanged, and switching the feature
 * off clears every badge on the next redraw.
 *
 * <p>Whatever the badge adds must disappear under the game's
 * {@code removetags}. The chat scripts strip tags from the drawn name to find
 * the clan rank icon and to target Report, Kick, Ban, Add friend and Message.
 * {@code <img>} and {@code <col>} strip cleanly. Literal text such as "[RR]"
 * does not, so the tag is drawn into the team's sprite rather than typed into
 * the name.
 *
 * <p>Two things keep it cheap. The roster is fetched only when the
 * {@code roster_version} on the /event_state poll changes. And the mod-icon
 * slots are claimed once per client lifetime and refilled in place, because
 * growing that array repeatedly leaks slots and stomps on icons other plugins
 * appended after us.
 */
@Slf4j
@Singleton
public class EventTeamIndicatorService {

    /**
     * Sprite slots reserved for team badges, one per team. Sized to the
     * server's cap on teams per roster ({@code ROSTER_TEAMS_LIMIT}), so every
     * team it sends can have one. A team that still ends up without a slot
     * falls back to a coloured name.
     */
    static final int MAX_TEAM_ICONS = 32;

    /** Orb sprite size, matching the game's own chat icons. */
    private static final int ORB_SIZE = 12;

    /** Columns between the orb and the tag when a badge shows both. */
    private static final int PIECE_GAP = 2;

    /** The chat builders' per-line callback (ChatBuilder and ChatSplitBuilder). */
    private static final String CHAT_MESSAGE_BUILDING = "chatMessageBuilding";

    /**
     * Tag text for a team with no colour of its own. The server always sends
     * an orb colour, so this is a backstop, picked to read on both the opaque
     * and the transparent chatbox.
     */
    private static final Color FALLBACK_TAG_COLOR = new Color(0x9f9f9f);

    /** A colour tag, open or close — a coloured name replaces any already there. */
    private static final Pattern COLOR_TAG = Pattern.compile("</?col(=[0-9a-fA-F]*)?>");

    /**
     * A moderator crown leading the name. The chat builder offers "Crown Info"
     * only when the name starts with one, so a badge goes after it.
     */
    private static final Pattern LEADING_CROWN = Pattern.compile("^<img=[01]>");

    /** Runs of whitespace, folded to one — see {@link #normalize(String)}. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final Client client;
    private final ClientThread clientThread;
    private final EventBus eventBus;
    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final ManifestService manifestService;
    private final EventNotificationService eventNotificationService;
    private final ScheduledExecutorService executor;

    /**
     * Normalized RSN to badge, replaced wholesale, never mutated in place: the
     * chat builder reads it on the client thread while the executor builds the
     * next one.
     */
    private volatile Map<String, TeamBadge> badgesByName = Collections.emptyMap();

    /** The loaded roster's teams in roster order, kept to redraw their sprites. */
    private volatile List<TeamBadge> loadedTeams = Collections.emptyList();

    /** Roster version currently reflected in {@link #badgesByName}. */
    @Nullable
    private volatile String loadedRosterVersion;

    /** Event the loaded roster belongs to; 0 when nothing is loaded. */
    private volatile int loadedEventId;

    /**
     * Bumped by every {@link #clear()}. A roster fetched before a clear must
     * not be installed after it, or an event that just ended would put its
     * badges back.
     */
    private final AtomicInteger generation = new AtomicInteger();

    /** First slot of our reserved block, or -1 before it is claimed. Client thread. */
    private int iconOffset = -1;

    /** team id -> sprite slot, valid only while {@link #iconOffset} >= 0. */
    private volatile Map<Integer, Integer> slotByTeam = Collections.emptyMap();

    private final Runnable stateListener = this::onEventStateUpdated;

    @Inject
    public EventTeamIndicatorService(Client client,
                                     ClientThread clientThread,
                                     EventBus eventBus,
                                     DropTrackerConfig config,
                                     DropTrackerApi api,
                                     ManifestService manifestService,
                                     EventNotificationService eventNotificationService,
                                     ScheduledExecutorService executor) {
        this.client = client;
        this.clientThread = clientThread;
        this.eventBus = eventBus;
        this.config = config;
        this.api = api;
        this.manifestService = manifestService;
        this.eventNotificationService = eventNotificationService;
        this.executor = executor;
    }

    public void startUp() {
        eventBus.register(this);
        eventNotificationService.addStateUpdatedListener(stateListener);
    }

    public void shutDown() {
        eventBus.unregister(this);
        eventNotificationService.removeStateUpdatedListener(stateListener);
        clear();
        slotByTeam = Collections.emptyMap();
    }

    /**
     * The client rebuilds its mod-icon array on a restart, so the slot block is
     * claimed fresh each time. Call from GameState STARTING (reset) and
     * LOGIN_SCREEN (claim).
     */
    public void onGameStateChanged(GameState state) {
        if (state == GameState.STARTING) {
            iconOffset = -1;
            slotByTeam = Collections.emptyMap();
        } else if (state == GameState.LOGIN_SCREEN && iconOffset < 0) {
            claimIconSlots();
            // A roster held across the restart needs its sprites again.
            List<TeamBadge> teams = loadedTeams;
            if (!teams.isEmpty()) {
                assignIconSlots(teams, config.eventTeamIndicators());
            }
        }
    }

    /** True when the feature can badge anything at all right now. */
    private boolean active() {
        if (!config.useApi() || config.eventTeamIndicators() == TeamIndicatorStyle.OFF) {
            return false;
        }
        Manifest manifest = manifestService.getManifest();
        // A manifest we could not read leaves the feature on: the switch exists
        // to turn it off deliberately, not to fail closed on a fetch error.
        return manifest == null || manifest.getTeamIndicators().isEnabled();
    }

    /**
     * Fresh /event_state landed: refetch the roster if the event we are
     * badging, or its roster, changed. Runs off-EDT on the service's own
     * callback thread, so the fetch is handed to the executor.
     */
    private void onEventStateUpdated() {
        if (!active()) {
            if (!badgesByName.isEmpty()) {
                clear();
            }
            return;
        }
        EventState.Entry entry = eventNotificationService.hudEntry();
        if (entry == null || entry.getEvent() == null) {
            if (!badgesByName.isEmpty()) {
                clear();
            }
            return;
        }
        // A player can be in several live events at once, and a clanmate can be
        // on different teams in two of them. Resolve with the same pinned-event
        // order the HUD uses, so the badge and the HUD never disagree.
        int eventId = entry.getEvent().getId();
        String version = entry.getRosterVersion();
        if (version == null) {
            // Server predates the stamp: no badges rather than a roster we
            // would have no way to know had gone stale.
            clear();
            return;
        }
        if (eventId == loadedEventId && version.equals(loadedRosterVersion)) {
            return;
        }
        final int fetchGeneration = generation.get();
        executor.execute(() -> loadRoster(eventId, version, fetchGeneration));
    }

    private void loadRoster(int eventId, String version, int fetchGeneration) {
        String playerName = client.getLocalPlayer() != null
            ? client.getLocalPlayer().getName() : null;
        long accountHash = client.getAccountHash();
        if (playerName == null || accountHash == -1L) {
            return;
        }
        EventRoster roster = api.fetchEventRoster(playerName, accountHash);
        if (roster == null || roster.getEvents() == null) {
            return;
        }
        EventRoster.Entry entry = null;
        for (EventRoster.Entry candidate : roster.getEvents()) {
            if (candidate.getEventId() == eventId) {
                entry = candidate;
                break;
            }
        }
        if (entry == null || entry.getTeams() == null || entry.getMembers() == null) {
            clear();
            return;
        }

        Map<Integer, TeamBadge> badgeByTeam = new HashMap<>();
        List<TeamBadge> teams = new ArrayList<>();
        for (EventRoster.Team team : entry.getTeams()) {
            TeamBadge badge = new TeamBadge(team.getId(), team.getShortTag(),
                colorOf(team.getOrbColor()), colorOf(team.getColor()));
            badgeByTeam.put(team.getId(), badge);
            teams.add(badge);
        }
        Map<String, TeamBadge> next = new HashMap<>();
        for (Map.Entry<String, List<String>> row : entry.getMembers().entrySet()) {
            Integer teamId = parseTeamId(row.getKey());
            TeamBadge badge = teamId == null ? null : badgeByTeam.get(teamId);
            if (badge == null || row.getValue() == null) {
                continue;
            }
            for (String name : row.getValue()) {
                // Server-normalized already; normalize again so one changed
                // implementation can never split the two sides silently.
                String key = normalize(name);
                if (!key.isEmpty()) {
                    next.put(key, badge);
                }
            }
        }
        clientThread.invoke(() -> install(next, teams, eventId, version, fetchGeneration));
    }

    /**
     * Swap a freshly fetched roster in and redraw the chatbox with it. Client
     * thread, so the chat builder never sees names without their sprites.
     */
    private void install(Map<String, TeamBadge> badges, List<TeamBadge> teams,
                         int eventId, String version, int fetchGeneration) {
        if (fetchGeneration != generation.get()) {
            // Cleared while this roster was in flight. Leaving the loaded
            // version unset lets the next state poll fetch again if it should.
            return;
        }
        assignIconSlots(teams, config.eventTeamIndicators());
        loadedTeams = teams;
        badgesByName = badges;
        loadedRosterVersion = version;
        loadedEventId = eventId;
        client.refreshChat();
    }

    /** A loaded roster without the fetch or the sprite drawing. */
    @VisibleForTesting
    void useBadges(Map<String, TeamBadge> badges, Map<Integer, Integer> slots) {
        badgesByName = badges;
        slotByTeam = slots;
    }

    private void clear() {
        generation.incrementAndGet();
        boolean hadBadges = !badgesByName.isEmpty();
        badgesByName = Collections.emptyMap();
        loadedTeams = Collections.emptyList();
        loadedRosterVersion = null;
        loadedEventId = 0;
        if (hadBadges) {
            // Badges exist only while a line is drawn, so a redraw removes them.
            clientThread.invoke(client::refreshChat);
        }
    }

    /**
     * Redraw on the display settings. They only change how a line is drawn,
     * so nothing is refetched. A new style also needs new sprites.
     */
    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!DropTrackerConfig.GROUP.equals(event.getGroup())) {
            return;
        }
        String key = event.getKey();
        if (!"eventTeamIndicators".equals(key)
            && !"eventTeamIndicatorColorNames".equals(key)
            && !"eventTeamIndicatorsPublicChat".equals(key)) {
            return;
        }
        clientThread.invoke(() -> {
            List<TeamBadge> teams = loadedTeams;
            if (teams.isEmpty()) {
                return;
            }
            if ("eventTeamIndicators".equals(key)) {
                assignIconSlots(teams, config.eventTeamIndicators());
            }
            client.refreshChat();
        });
    }

    /**
     * Badge one chat line as the chatbox draws it, the same way RuneLite's own
     * chat-channel rank icons are added: replace the name on the script stack
     * and leave the {@link MessageNode} alone. The stack carries the line's
     * id and split-PM flag (ints, top two) and its channel, name, message and
     * timestamp (objects, top four).
     *
     * <p>Deliberately not gated on the plugin's {@code isTracking} flag: that
     * is the webhook-exhaustion kill switch for <em>submissions</em>, and
     * hiding a display feature behind it would silently drop badges for anyone
     * whose webhook list failed to replenish.
     */
    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent event) {
        if (!CHAT_MESSAGE_BUILDING.equals(event.getEventName())) {
            return;
        }
        Map<String, TeamBadge> badges = badgesByName;
        if (badges.isEmpty()) {
            return;
        }
        TeamIndicatorStyle style = config.eventTeamIndicators();
        if (style == null || style == TeamIndicatorStyle.OFF) {
            return;
        }
        int[] intStack = client.getIntStack();
        int intStackSize = client.getIntStackSize();
        Object[] objectStack = client.getObjectStack();
        int objectStackSize = client.getObjectStackSize();
        if (intStack == null || objectStack == null || intStackSize < 2 || objectStackSize < 4) {
            return;
        }
        // The split private-chat pane builds through the same callback. It
        // holds nothing but DMs, which are never ours to touch.
        if (intStack[intStackSize - 2] == 1) {
            return;
        }
        MessageNode node = client.getMessages().get(intStack[intStackSize - 1]);
        if (node == null || !isBadgedChannel(node.getType())) {
            return;
        }
        // Identify the sender by the node's own name, not the drawn one, which
        // another plugin may already have decorated.
        String sender = node.getName();
        if (sender == null || sender.isEmpty()) {
            return;
        }
        // Our own Discord->game bridge renders relayed lines as real chat
        // messages. A Discord user is not a roster member and must never be
        // badged as one.
        if (ChatMessageUtil.isDiscordBridgeSender(sender)) {
            return;
        }
        TeamBadge badge = badges.get(normalize(sender));
        if (badge == null) {
            return;
        }
        Object shown = objectStack[objectStackSize - 3];
        if (!(shown instanceof String)) {
            return;
        }
        String decorated = badge.render((String) shown, iconSlot(badge.teamId),
            config.eventTeamIndicatorColorNames());
        if (decorated != null) {
            objectStack[objectStackSize - 3] = decorated;
        }
    }

    /**
     * The chat channels a badge belongs on. Everything else, including private
     * messages, trade requests and broadcasts, is never touched.
     */
    boolean isBadgedChannel(@Nullable ChatMessageType type) {
        if (type == null) {
            return false;
        }
        switch (type) {
            case CLAN_CHAT:
            case CLAN_GUEST_CHAT:
            case CLAN_GIM_CHAT:
            case FRIENDSCHAT:
                return true;
            case PUBLICCHAT:
                return config.eventTeamIndicatorsPublicChat();
            default:
                return false;
        }
    }

    // ── sprites ──────────────────────────────────────────────────────────────

    /**
     * Reserve the whole slot block once. Growing {@code modIcons} again on
     * every roster change would leak slots and can stomp on icons a plugin
     * loaded after us appended.
     */
    private void claimIconSlots() {
        if (iconOffset >= 0) {
            return;
        }
        final IndexedSprite[] modIcons = client.getModIcons();
        if (modIcons == null) {
            return;
        }
        final IndexedSprite[] grown = Arrays.copyOf(modIcons, modIcons.length + MAX_TEAM_ICONS);
        iconOffset = modIcons.length;
        // The new slots stay null until a roster assigns them. Nothing
        // dereferences them meanwhile: a slot is only ever named in an
        // <img=> tag after assignIconSlots has filled it, which is the same
        // order RuneLite's own pet icons are loaded in.
        client.setModIcons(grown);
    }

    /** Draw each team's badge for the style into our reserved slots. Client thread. */
    private void assignIconSlots(List<TeamBadge> teams, TeamIndicatorStyle style) {
        Map<Integer, Integer> slots = new HashMap<>();
        if (iconOffset < 0) {
            claimIconSlots();
        }
        IndexedSprite[] modIcons = iconOffset < 0 ? null : client.getModIcons();
        if (modIcons != null && modIcons.length >= iconOffset + MAX_TEAM_ICONS) {
            Font font = FontManager.getRunescapeFont();
            int used = 0;
            for (TeamBadge team : teams) {
                if (used >= MAX_TEAM_ICONS) {
                    break;
                }
                BadgeArt art = drawBadge(team, style, font);
                if (art == null) {
                    continue;
                }
                IndexedSprite sprite = ImageUtil.getImageIndexedSprite(art.image, client);
                // The font sits an <img> with its bottom row on the text
                // baseline, measured by originalHeight. Rows past that hang
                // below it, as a bracket's tail does.
                sprite.setOriginalHeight(art.ascent);
                int slot = iconOffset + used;
                modIcons[slot] = sprite;
                slots.put(team.teamId, slot);
                used++;
            }
        }
        // No slot at all still works: every team falls back to a coloured name.
        slotByTeam = slots;
    }

    private int iconSlot(int teamId) {
        Integer slot = slotByTeam.get(teamId);
        return slot == null ? -1 : slot;
    }

    /** A badge's pixels, and how many of its rows sit above the text baseline. */
    static final class BadgeArt {
        final BufferedImage image;
        final int ascent;

        BadgeArt(BufferedImage image, int ascent) {
            this.image = image;
            this.ascent = ascent;
        }
    }

    /**
     * A team's badge for a style: its orb, its tag, or both side by side.
     *
     * <p>ORB degrades to the tag for a team without an orb colour, which is
     * why the default style can be ORB without every team needing one.
     *
     * @param font the chat font; with none, the tag is left out
     * @return null when the style draws nothing for this team
     */
    @Nullable
    static BadgeArt drawBadge(TeamBadge badge, TeamIndicatorStyle style, @Nullable Font font) {
        if (style == null || style == TeamIndicatorStyle.OFF) {
            return null;
        }
        List<BadgeArt> pieces = new ArrayList<>(2);
        boolean orb = style.showsOrb() && badge.orbColor != null;
        if (orb) {
            pieces.add(drawOrb(badge.orbColor));
        }
        if ((style.showsTag() || !orb) && badge.tag != null && !badge.tag.isEmpty() && font != null) {
            BadgeArt tag = drawText("[" + badge.tag + "]", badge.tagColor(), font);
            if (tag != null) {
                pieces.add(tag);
            }
        }
        if (pieces.isEmpty()) {
            return null;
        }
        return pieces.size() == 1 ? pieces.get(0) : sideBySide(pieces);
    }

    /**
     * A filled circle in the team's color, sized like the game's chat icons.
     *
     * Antialiasing is deliberately off: an IndexedSprite carries a palette and
     * a single transparent index, so soft edges would either bloat the palette
     * or come out as a fringe of near-transparent pixels. Two colors at 12px
     * is also simply what the game's own chat icons look like.
     */
    private static BadgeArt drawOrb(Color color) {
        BufferedImage image = new BufferedImage(ORB_SIZE, ORB_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(spriteSafe(color));
            g.fillOval(1, 1, ORB_SIZE - 2, ORB_SIZE - 2);
        } finally {
            g.dispose();
        }
        // The whole square sits above the baseline, as the game's icons do.
        return new BadgeArt(image, ORB_SIZE);
    }

    /**
     * Text drawn in the chat font, cropped to its ink.
     *
     * <p>RuneLite's RuneScape font is the game's own chat glyphs, and with
     * antialiasing off it lands on whole pixels exactly as the chatbox draws
     * it. Its metrics are the catch: the glyphs stand a few rows above the
     * baseline FontMetrics reports. So the baseline is measured instead, as
     * the row under a capital.
     */
    @Nullable
    static BadgeArt drawText(String text, Color color, Font font) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D probeGraphics = probe.createGraphics();
        FontMetrics metrics;
        try {
            metrics = probeGraphics.getFontMetrics(font);
        } finally {
            probeGraphics.dispose();
        }
        int width = metrics.stringWidth(text);
        int height = metrics.getAscent() + metrics.getDescent();
        if (width <= 0 || height <= 0) {
            return null;
        }
        BufferedImage canvas = renderLine(text, color, font, width, height, metrics.getAscent());
        BufferedImage capital = renderLine("H", color, font, width, height, metrics.getAscent());
        int top = firstInkedRow(canvas);
        int bottom = lastInkedRow(canvas);
        int baseline = lastInkedRow(capital);
        if (top < 0 || baseline < top) {
            return null;
        }
        BufferedImage cropped = new BufferedImage(width, bottom - top + 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = cropped.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(canvas, 0, -top, null);
        } finally {
            g.dispose();
        }
        return new BadgeArt(cropped, baseline - top + 1);
    }

    private static BufferedImage renderLine(String text, Color color, Font font,
                                            int width, int height, int baseline) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            g.setFont(font);
            g.setColor(spriteSafe(color));
            g.drawString(text, 0, baseline);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Pieces laid out left to right, standing on one shared baseline. */
    private static BadgeArt sideBySide(List<BadgeArt> pieces) {
        int ascent = 0;
        int descent = 0;
        int width = PIECE_GAP * (pieces.size() - 1);
        for (BadgeArt piece : pieces) {
            ascent = Math.max(ascent, piece.ascent);
            descent = Math.max(descent, piece.image.getHeight() - piece.ascent);
            width += piece.image.getWidth();
        }
        BufferedImage image = new BufferedImage(width, ascent + descent, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            int x = 0;
            for (BadgeArt piece : pieces) {
                g.drawImage(piece.image, x, ascent - piece.ascent, null);
                x += piece.image.getWidth() + PIECE_GAP;
            }
        } finally {
            g.dispose();
        }
        return new BadgeArt(image, ascent);
    }

    private static int firstInkedRow(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            if (rowHasInk(image, y)) {
                return y;
            }
        }
        return -1;
    }

    private static int lastInkedRow(BufferedImage image) {
        for (int y = image.getHeight() - 1; y >= 0; y--) {
            if (rowHasInk(image, y)) {
                return y;
            }
        }
        return -1;
    }

    private static boolean rowHasInk(BufferedImage image, int y) {
        for (int x = 0; x < image.getWidth(); x++) {
            if ((image.getRGB(x, y) >>> 24) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * An opaque colour the sprite can hold. The indexed-sprite conversion
     * only keeps fully opaque pixels, and it reads pure black as its
     * transparent index, so black is nudged off zero.
     */
    static Color spriteSafe(Color color) {
        int rgb = color.getRGB() & 0xFFFFFF;
        return new Color(rgb == 0 ? 0x010101 : rgb);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * The comparison key for an RSN. {@link Text#standardize} folds the
     * non-breaking space the game uses inside names and lowercases; the
     * underscore fold matches the server's
     * {@code normalize_player_display_equivalence}, which is what the roster
     * names were built with.
     */
    static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String stripped = Text.standardize(Text.removeTags(name));
        // Collapse runs of whitespace as well as folding the separators: the
        // server's normalizer does, and two implementations that disagree by
        // one space badge nobody while looking perfectly correct on both sides.
        return WHITESPACE.matcher(
            stripped.replace('_', ' ').replace('-', ' ').trim()).replaceAll(" ");
    }

    @Nullable
    private static Integer parseTeamId(String key) {
        try {
            return Integer.valueOf(key);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "#rrggbb" to a Color, or null for anything unusable. */
    @Nullable
    static Color colorOf(@Nullable String hex) {
        if (hex == null) {
            return null;
        }
        String value = hex.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() != 6) {
            return null;
        }
        try {
            return new Color(Integer.parseInt(value, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One team's rendering inputs, resolved once per roster load. */
    static final class TeamBadge {
        private final int teamId;
        @Nullable
        private final String tag;
        @Nullable
        private final Color orbColor;
        @Nullable
        private final Color accent;

        TeamBadge(int teamId, @Nullable String tag, @Nullable Color orbColor,
                  @Nullable Color accent) {
            this.teamId = teamId;
            this.tag = tag;
            this.orbColor = orbColor;
            this.accent = accent;
        }

        /** The team's colour for text: the admin-set accent, else the orb's. */
        @Nullable
        Color nameColor() {
            return accent != null ? accent : orbColor;
        }

        Color tagColor() {
            Color color = nameColor();
            return color != null ? color : FALLBACK_TAG_COLOR;
        }

        /**
         * The sender name as the line should draw it, or null to leave it as
         * it is.
         *
         * <p>Only an {@code <img>} and a colour are ever added, both of which
         * the game's {@code removetags} strips. The tag itself lives in the
         * sprite. The badge leads the name, except that a moderator crown
         * stays first, where the chat builder looks for it.
         *
         * @param shown     the name on the script stack; another plugin may
         *                  already have given it an icon or a colour
         * @param iconSlot  this team's sprite, or -1 when it has none
         * @param colorName whether to draw the name in the team colour
         */
        @Nullable
        String render(String shown, int iconSlot, boolean colorName) {
            Color color = colorName ? nameColor() : null;
            if (iconSlot < 0 && color == null) {
                return null;
            }
            // Drop any colour the name already carries (RuneLite's chat colour
            // config, applied by a subscriber that may run before this one) so
            // the team colour is the one that shows. </col> resets to the chat
            // default rather than to an enclosing tag, so the result must be
            // one flat span, never a nested one.
            String name = color != null ? COLOR_TAG.matcher(shown).replaceAll("") : shown;
            Matcher crown = LEADING_CROWN.matcher(name);
            String lead = crown.lookingAt() ? crown.group() : "";
            String rest = name.substring(lead.length());

            StringBuilder out = new StringBuilder(lead);
            if (iconSlot >= 0) {
                out.append("<img=").append(iconSlot).append('>');
            }
            if (color != null) {
                out.append("<col=")
                    .append(String.format("%06x", color.getRGB() & 0xFFFFFF))
                    .append('>')
                    .append(rest)
                    .append("</col>");
            } else {
                out.append(rest);
            }
            return out.toString();
        }
    }
}
