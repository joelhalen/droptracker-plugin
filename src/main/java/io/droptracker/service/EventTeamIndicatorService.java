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
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IndexedSprite;
import net.runelite.api.MessageNode;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

import javax.annotation.Nullable;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
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
 * <p>Three things keep it cheap. The roster is fetched only when the
 * {@code roster_version} on the /event_state poll changes. A decoration touches
 * the one {@link MessageNode} that just arrived rather than walking the whole
 * chat buffer — the buffer is re-walked only when the roster or the config
 * actually changes. And the mod-icon slots are claimed once per client
 * lifetime and refilled in place, because growing that array repeatedly leaks
 * slots and stomps on icons other plugins appended after us.
 */
@Slf4j
@Singleton
public class EventTeamIndicatorService {

    /**
     * Sprite slots reserved for team orbs. Teams past this fall back to their
     * tag — a client's mod-icon array is shared with every other plugin, so
     * this is a budget, not a target.
     */
    static final int MAX_TEAM_ICONS = 12;

    /** Orb sprite size, matching the game's own chat icons. */
    private static final int ORB_SIZE = 12;

    /**
     * A leading coloured tag we wrote, e.g. {@code <col=cc3333>[RR]</col>}.
     * Recognising our own output is what keeps decoration idempotent.
     */
    private static final Pattern TAG_PREFIX =
        Pattern.compile("^<col=[0-9a-fA-F]{6}>\\[[A-Za-z0-9 ]{1,8}\\]</col>");

    /** Runs of whitespace, folded to one — see {@link #normalize(String)}. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final Client client;
    private final ClientThread clientThread;
    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final ItemManager itemManager;
    private final ManifestService manifestService;
    private final EventNotificationService eventNotificationService;
    private final ScheduledExecutorService executor;

    /**
     * Normalized RSN to badge, replaced wholesale on every roster load. Never
     * mutated in place: the chat hook reads it from the client thread while the
     * executor rebuilds it.
     */
    private volatile Map<String, TeamBadge> badgesByName = new HashMap<>();

    /** Roster version currently reflected in {@link #badgesByName}. */
    @Nullable
    private volatile String loadedRosterVersion;

    /** Event the loaded roster belongs to; 0 when nothing is loaded. */
    private volatile int loadedEventId;

    /** First slot of our reserved block, or -1 before it is claimed. */
    private int iconOffset = -1;

    /** team id -> sprite slot, valid only while {@link #iconOffset} >= 0. */
    private final Map<Integer, Integer> slotByTeam = new HashMap<>();

    private final Runnable stateListener = this::onEventStateUpdated;

    @Inject
    public EventTeamIndicatorService(Client client,
                                     ClientThread clientThread,
                                     DropTrackerConfig config,
                                     DropTrackerApi api,
                                     ItemManager itemManager,
                                     ManifestService manifestService,
                                     EventNotificationService eventNotificationService,
                                     ScheduledExecutorService executor) {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.api = api;
        this.itemManager = itemManager;
        this.manifestService = manifestService;
        this.eventNotificationService = eventNotificationService;
        this.executor = executor;
    }

    public void startUp() {
        eventNotificationService.addStateUpdatedListener(stateListener);
    }

    public void shutDown() {
        eventNotificationService.removeStateUpdatedListener(stateListener);
        badgesByName = new HashMap<>();
        loadedRosterVersion = null;
        loadedEventId = 0;
        slotByTeam.clear();
    }

    /**
     * The client rebuilds its mod-icon array on a restart, so the slot block is
     * claimed fresh each time. Call from GameState STARTING (reset) and
     * LOGIN_SCREEN (claim).
     */
    public void onGameStateChanged(GameState state) {
        if (state == GameState.STARTING) {
            iconOffset = -1;
            slotByTeam.clear();
        } else if (state == GameState.LOGIN_SCREEN) {
            claimIconSlots();
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
        executor.execute(() -> loadRoster(eventId, version));
    }

    private void loadRoster(int eventId, String version) {
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

        Map<Integer, EventRoster.Team> teamsById = new HashMap<>();
        for (EventRoster.Team team : entry.getTeams()) {
            teamsById.put(team.getId(), team);
        }
        Map<String, TeamBadge> next = new HashMap<>();
        for (Map.Entry<String, List<String>> row : entry.getMembers().entrySet()) {
            Integer teamId = parseTeamId(row.getKey());
            EventRoster.Team team = teamId == null ? null : teamsById.get(teamId);
            if (team == null || row.getValue() == null) {
                continue;
            }
            TeamBadge badge = new TeamBadge(team.getId(), team.getShortTag(),
                colorOf(team.getOrbColor()), colorOf(team.getColor()));
            for (String name : row.getValue()) {
                // Server-normalized already; normalize again so one changed
                // implementation can never split the two sides silently.
                String key = normalize(name);
                if (!key.isEmpty()) {
                    next.put(key, badge);
                }
            }
        }
        badgesByName = next;
        loadedRosterVersion = version;
        loadedEventId = eventId;
        final List<EventRoster.Team> teams = entry.getTeams();
        clientThread.invoke(() -> {
            assignIconSlots(teams);
            redecorateBuffer();
        });
    }

    private void clear() {
        badgesByName = new HashMap<>();
        loadedRosterVersion = null;
        loadedEventId = 0;
        clientThread.invoke(this::redecorateBuffer);
    }

    /**
     * Badge one incoming chat line. Called from the plugin's ChatMessage
     * subscriber on the client thread.
     *
     * <p>Deliberately not gated on the plugin's {@code isTracking} flag: that
     * is the webhook-exhaustion kill switch for <em>submissions</em>, and
     * hiding a display feature behind it would silently drop badges for anyone
     * whose webhook list failed to replenish.
     */
    public void decorate(MessageNode node) {
        if (node == null) {
            return;
        }
        TeamIndicatorStyle style = config.eventTeamIndicators();
        if (style == TeamIndicatorStyle.OFF) {
            return;
        }
        Map<String, TeamBadge> badges = badgesByName;
        if (badges.isEmpty()) {
            return;
        }
        String rawName = node.getName();
        if (rawName == null || rawName.isEmpty()) {
            return;
        }
        // Our own Discord->game bridge renders relayed lines as real chat
        // messages, which come back through this subscriber. A Discord user is
        // not a roster member and must never be badged as one.
        if (ChatMessageUtil.isDiscordBridgeSender(rawName)) {
            return;
        }
        if (applyBadge(node, badges, style)) {
            client.refreshChat();
        }
    }

    /**
     * Prefix one node's name with its team badge, if it needs one.
     *
     * <p>The badge is <em>prefixed</em>, never rebuilt from a stripped name:
     * the game bakes a clan member's rank icon into the same field, and
     * rebuilding would throw it away. The result is just
     * {@code <img=T><img=25>Name}, which is also how RuneLite's own rank icons
     * sit beside each other.
     *
     * @return true when the node was changed
     */
    private boolean applyBadge(MessageNode node, Map<String, TeamBadge> badges,
                               TeamIndicatorStyle style) {
        String rawName = node.getName();
        if (rawName == null || rawName.isEmpty() || hasBadge(rawName)) {
            return false;
        }
        // Our own Discord->game bridge renders relayed lines as real chat
        // messages, which come back through this subscriber. A Discord user is
        // not a roster member and must never be badged as one.
        if (ChatMessageUtil.isDiscordBridgeSender(rawName)) {
            return false;
        }
        TeamBadge badge = badges.get(normalize(rawName));
        if (badge == null) {
            return false;
        }
        String decorated = badge.render(style, rawName, iconSlot(badge.teamId),
            config.eventTeamIndicatorColorNames());
        if (decorated == null || decorated.equals(rawName)) {
            return false;
        }
        node.setName(decorated);
        return true;
    }

    /**
     * Badge the lines already on screen when a roster first lands — once, not
     * per incoming line, which is what the plugin this was modelled on did.
     * Must run on the client thread.
     *
     * <p>Additive only. Un-badging would mean reconstructing a name we did not
     * author, and the buffer turns over on its own within a few minutes, so
     * turning the feature off stops new badges rather than rewriting history.
     */
    public void redecorateBuffer() {
        Map<String, TeamBadge> badges = badgesByName;
        TeamIndicatorStyle style = config.eventTeamIndicators();
        if (badges.isEmpty() || style == TeamIndicatorStyle.OFF) {
            return;
        }
        boolean changed = false;
        for (MessageNode node : client.getMessages()) {
            if (node != null && applyBadge(node, badges, style)) {
                changed = true;
            }
        }
        if (changed) {
            client.refreshChat();
        }
    }

    /**
     * Whether a name already carries a badge of ours — a sprite from our
     * reserved block, or a leading coloured tag. Both the live hook and the
     * retro-walk can reach the same node, and a name may be re-decorated after
     * a roster refresh; without this the badges would stack.
     */
    private boolean hasBadge(String name) {
        if (iconOffset >= 0 && name.startsWith("<img=")) {
            int close = name.indexOf('>');
            if (close > 5) {
                try {
                    int slot = Integer.parseInt(name.substring(5, close));
                    if (slot >= iconOffset && slot < iconOffset + MAX_TEAM_ICONS) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    // Someone else's tag; fall through to the tag check.
                }
            }
        }
        return TAG_PREFIX.matcher(name).find();
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

    /** Draw each team's orb into our reserved slots. Client thread. */
    private void assignIconSlots(List<EventRoster.Team> teams) {
        slotByTeam.clear();
        if (iconOffset < 0) {
            claimIconSlots();
        }
        if (iconOffset < 0) {
            return;  // no sprites available; TAG rendering still works
        }
        IndexedSprite[] modIcons = client.getModIcons();
        if (modIcons == null || modIcons.length < iconOffset + MAX_TEAM_ICONS) {
            return;
        }
        int used = 0;
        for (EventRoster.Team team : teams) {
            if (used >= MAX_TEAM_ICONS) {
                break;
            }
            Color color = colorOf(team.getOrbColor());
            if (color == null) {
                continue;
            }
            int slot = iconOffset + used;
            modIcons[slot] = orbSprite(color);
            slotByTeam.put(team.getId(), slot);
            used++;
        }
    }

    private int iconSlot(int teamId) {
        Integer slot = slotByTeam.get(teamId);
        return slot == null ? -1 : slot;
    }

    /**
     * A filled circle in the team's color, sized like the game's chat icons.
     *
     * Antialiasing is deliberately off: an IndexedSprite carries a palette and
     * a single transparent index, so soft edges would either bloat the palette
     * or come out as a fringe of near-transparent pixels. Two colors at 12px
     * is also simply what the game's own chat icons look like.
     */
    private IndexedSprite orbSprite(Color color) {
        BufferedImage image = new BufferedImage(ORB_SIZE, ORB_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(color);
            g.fillOval(1, 1, ORB_SIZE - 2, ORB_SIZE - 2);
        } finally {
            g.dispose();
        }
        return ImageUtil.getImageIndexedSprite(image, client);
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
        // A tag badge survives tag-stripping as literal "[rr]" text, and an
        // already-decorated name has to key the same as an undecorated one —
        // otherwise a second pass over the buffer would miss every player it
        // had already badged. No RSN can contain a bracket, so a leading
        // bracketed group is always ours.
        if (stripped.startsWith("[")) {
            int close = stripped.indexOf(']');
            if (close >= 0) {
                stripped = stripped.substring(close + 1);
            }
        }
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

        /**
         * The decorated sender name.
         *
         * <p>ORB degrades to the tag when no sprite slot was available, which
         * is why the default style can be ORB without the feature depending on
         * winning the mod-icon race.
         */
        @Nullable
        String render(TeamIndicatorStyle style, String rawName, int iconSlot, boolean colorName) {
            StringBuilder out = new StringBuilder();
            boolean drewOrb = style.showsOrb() && iconSlot >= 0;
            if (drewOrb) {
                out.append("<img=").append(iconSlot).append('>');
            }
            if ((style.showsTag() || (style.showsOrb() && !drewOrb)) && tag != null && !tag.isEmpty()) {
                appendColored(out, "[" + tag + "]", accent);
            }
            // No prefix means nothing marks this as our work, and the
            // idempotency check would not recognise it on a second pass — so a
            // name-only recolor is not offered. In practice unreachable: the
            // server always sends a tag, derived when no admin set one.
            if (out.length() == 0) {
                return null;
            }
            if (colorName) {
                // </col> resets to the chat default rather than to an enclosing
                // tag, so the color must wrap the name as a sibling and never
                // be nested inside another color span.
                appendColored(out, rawName, accent != null ? accent : orbColor);
            } else {
                out.append(rawName);
            }
            return out.toString();
        }

        private static void appendColored(StringBuilder out, String text, @Nullable Color color) {
            if (color == null) {
                out.append(text);
                return;
            }
            out.append("<col=")
                .append(String.format("%06x", color.getRGB() & 0xFFFFFF))
                .append('>')
                .append(text)
                .append("</col>");
        }
    }
}
