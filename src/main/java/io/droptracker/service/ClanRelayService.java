/*
 * Adapted from the TrackScape Connector
 * (github.com/fatfingers23/trackscape-connector-plugin),
 * Copyright (c) 2023, Bailey Townsend, BSD 2-Clause License (see LICENSE).
 */
package io.droptracker.service;

import com.google.inject.*;
import io.droptracker.*;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.api.GroupConfig;
import io.droptracker.models.submissions.SubmissionType;
import io.droptracker.util.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.clan.ClanChannel;
import net.runelite.client.util.Text;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.regex.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Relays clan chat to the DropTracker for the clan features:
 * <ul>
 *   <li>{@code CLAN_MESSAGE} system broadcasts ("X received a drop: ...") as
 *       {@code clan_broadcast} submissions — server-side parsing tracks
 *       clanmates who don't run the plugin;</li>
 *   <li>{@code CLAN_CHAT} player lines as {@code clan_chat} submissions — the
 *       game→Discord half of the two-way chat bridge.</li>
 * </ul>
 *
 * On by default (Clan chat sync), but nothing leaves the client unless the
 * clan the player is in has been set up on droptracker.io — see
 * {@link #clanOptedIn(boolean)}. The server enforces the same rule again.
 *
 * The plugin stays a dumb pipe on purpose: no game-message parsing happens
 * client-side, so pattern fixes never wait on a plugin-hub review. Lines are
 * batched (2s debounce, capped batch) into one payload, and each embed
 * carries the standard relayer identity fields — the server authenticates the
 * RELAYER and dedupes across multiple relaying clanmates, so this can be
 * enabled by any number of members safely.
 *
 * Rides whichever transport the player uses: the API, or our Discord
 * webhooks, which the server's webhook bot reads. Only the Discord→game
 * direction needs the API, because it polls our server for lines.
 */
@Slf4j
@Singleton
public class ClanRelayService {

    private static final int FLUSH_DELAY_SECONDS = 2;
    private static final int MAX_LINES_PER_FLUSH = 10;
    /** Backstop so a pathological chat flood can't grow the queue unbounded. */
    private static final int MAX_QUEUED_LINES = 200;
    private static final int MAX_MESSAGE_CHARS = 250;

    /** A chat icon tag in a sender name, e.g. {@code <img=2>} for an ironman. */
    private static final Pattern ICON_TAG = Pattern.compile("<img=(\\d+)>");

    private final Client client;
    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final DropTrackerPlugin plugin;
    private final SubmissionManager submissionManager;
    private final ScheduledExecutorService executor;

    private final ConcurrentLinkedQueue<PendingLine> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    /**
     * The clan channel the local player currently sits in, maintained from
     * the client thread (ClanChannelChanged + relay call sites). Volatile so
     * the notification poller can attach it to presence heartbeats without
     * touching client state off-thread.
     */
    private volatile String currentClanName = null;

    /** How long the published opted-in clan list is trusted before a refetch. */
    private static final long PUBLISHED_CLANS_TTL_MS = TimeUnit.MINUTES.toMillis(10);

    /**
     * Opted-in clans from GitHub Pages ({@code hash -> "b"|"t"|"bt"}), the gate
     * for webhook-only clients. Null until the first successful fetch, and
     * then nothing is relayed.
     */
    private volatile Map<String, String> publishedClans = null;
    private volatile long publishedClansFetchedAt = 0L;
    private final AtomicBoolean publishedClansLoading = new AtomicBoolean(false);

    @Inject
    public ClanRelayService(Client client, DropTrackerConfig config, DropTrackerApi api,
                            DropTrackerPlugin plugin, SubmissionManager submissionManager,
                            ScheduledExecutorService executor) {
        this.client = client;
        this.config = config;
        this.api = api;
        this.plugin = plugin;
        this.submissionManager = submissionManager;
        this.executor = executor;
    }

    /** Called from the client thread whenever the clan channel changes. */
    public void updateClanChannel(ClanChannel channel) {
        currentClanName = channel != null ? Text.removeTags(channel.getName()) : null;
    }

    /** The current clan's name, or null when not in a clan. Thread-safe. */
    public String getCurrentClanName() {
        return currentClanName;
    }

    /**
     * Whether the Discord→game direction should be live (poll + display).
     * The one part that still needs the API: Discord lines reach the client
     * by polling our server, which a webhook-only client never contacts.
     */
    public boolean discordChatActive() {
        return config.useApi() && config.clanChatSync() && config.receiveDiscordChat()
            && clanOptedIn(true);
    }

    /**
     * The server's clan comparison key ({@code utils/clan_broadcasts.clan_slug}):
     * markup stripped, '-', '_' and non-breaking spaces folded to a space,
     * whitespace collapsed, lowercased.
     */
    static String clanSlug(String clanName) {
        if (clanName == null) {
            return "";
        }
        String s = Text.removeTags(clanName)
            .replace('\u00A0', ' ').replace('-', ' ').replace('_', ' ')
            .trim().replaceAll("\\s+", " ");
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * Whether one of the player's OWN groups has set up the clan they are in.
     *
     * <p>This is the privacy line for a setting that is on by default: a
     * player whose groups don't use the clan features, or who isn't in a
     * clan, sends nothing at all. Chat lines need a group running the Discord
     * bridge; broadcasts also go to a group that only tracks them. The group
     * list refreshes every couple of minutes, so a group switching a feature
     * on or off is picked up without a relog (the server drops anything that
     * arrives in between).</p>
     */
    boolean clanOptedIn(boolean chatLine) {
        if (config.useApi()) {
            return clanOptedIn(currentClanName, api.getGroupConfigs(), chatLine);
        }
        // Webhook-only: the group configs need the API, which this client may
        // not contact. Check the clan against the published list instead.
        refreshPublishedClansIfStale();
        return clanInPublishedList(currentClanName, publishedClans, chatLine);
    }

    /**
     * Whether the published list opts this clan in. Same rule as the group
     * check: chat needs a bridging group ({@code b}); broadcasts also go to a
     * tracking-only one ({@code t}).
     */
    static boolean clanInPublishedList(String clan, Map<String, String> published, boolean chatLine) {
        if (clan == null || published == null || published.isEmpty()) {
            return false;
        }
        String slug = clanSlug(clan);
        if (slug.isEmpty()) {
            return false;
        }
        String mode = published.get(publishedHash(slug));
        if (mode == null) {
            return false;
        }
        return mode.contains("b") || (!chatLine && mode.contains("t"));
    }

    /** First 16 hex chars of sha256(slug) (server: clan_relay_gate.published_hash). */
    static String publishedHash(String slug) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(slug.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    /** Refetch the published list off-thread when it is missing or stale. */
    private void refreshPublishedClansIfStale() {
        long now = System.currentTimeMillis();
        if (publishedClans != null && now - publishedClansFetchedAt < PUBLISHED_CLANS_TTL_MS) {
            return;
        }
        // A failed fetch retries no sooner than a minute later.
        if (now - publishedClansFetchedAt < TimeUnit.MINUTES.toMillis(1)
            || !publishedClansLoading.compareAndSet(false, true)) {
            return;
        }
        publishedClansFetchedAt = now;
        executor.execute(() -> {
            try {
                Map<String, String> fetched = api.fetchClanChatClans();
                if (fetched != null) {
                    publishedClans = fetched;
                }
            } finally {
                publishedClansLoading.set(false);
            }
        });
    }

    static boolean clanOptedIn(String clan, List<GroupConfig> configs, boolean chatLine) {
        if (clan == null) {
            return false;
        }
        if (configs == null || configs.isEmpty()) {
            return false;
        }
        String slug = clanSlug(clan);
        if (slug.isEmpty()) {
            return false;
        }
        for (GroupConfig group : configs) {
            if (group == null || !slug.equals(group.getClanChatSlug())) {
                continue;
            }
            if (group.isClanChatBridge() || (!chatLine && group.isClanBroadcastTracking())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A CLAN_MESSAGE system broadcast (already tag-sanitized by the caller).
     * Broadcasts feed both the Discord bridge (they are part of the chat box)
     * and broadcast tracking, under the one Clan chat sync setting.
     */
    public void onClanBroadcast(String message) {
        if (!config.clanChatSync()) {
            return;
        }
        queueLine(new PendingLine(SubmissionType.CLAN_BROADCAST, null, message));
    }

    /**
     * The game mode a chat line's sender badge shows, as the server's wire
     * string ({@code utils/account_types.py}). The game draws the badge as an
     * icon tag in the name, so this covers every clanmate, plugin or not.
     * No badge reads as {@code normal}.
     */
    static String accountTypeFromName(String rawName) {
        if (rawName == null) {
            return null;
        }
        Matcher m = ICON_TAG.matcher(rawName);
        while (m.find()) {
            switch (Integer.parseInt(m.group(1))) {
                case 2: return "ironman";                   // IconID.IRONMAN
                case 3: return "ultimate_ironman";          // IconID.ULTIMATE_IRONMAN
                case 10: return "hardcore_ironman";         // IconID.HARDCORE_IRONMAN
                case 41: return "group_ironman";            // IconID.GROUP_IRONMAN
                case 42: return "hardcore_group_ironman";   // IconID.HARDCORE_GROUP_IRONMAN
                case 43: return "unranked_group_ironman";   // IconID.UNRANKED_GROUP_IRONMAN
                default: break; // moderator crowns, league icons, ...
            }
        }
        return "normal";
    }

    /**
     * A CLAN_CHAT player line (sender may still carry icon tags).
     *
     * <p>Discord lines the bridge renders locally arrive here too —
     * {@code client.addChatMessage} posts a real {@code ChatMessage}, so the
     * Discord→game direction feeds straight back into the game→Discord one.
     * Unguarded that echoes every Discord message back into the channel it
     * was typed in, so lines wearing {@link
     * ChatMessageUtil#DISCORD_SENDER_MARKER} stop here.</p>
     */
    public void onClanChat(String senderName, String message) {
        if (!config.clanChatSync()) {
            return;
        }
        String sender = senderName != null ? Text.removeTags(Text.toJagexName(senderName)) : null;
        if (sender == null || sender.trim().isEmpty()) {
            return;
        }
        if (ChatMessageUtil.isDiscordBridgeSender(sender)) {
            return;
        }
        PendingLine line = new PendingLine(SubmissionType.CLAN_CHAT, sender.trim(), message);
        line.accountType = accountTypeFromName(senderName);
        queueLine(line);
    }

    private void queueLine(PendingLine line) {
        refreshClanNameFromClient();
        if (currentClanName == null || line.message == null || line.message.trim().isEmpty()) {
            return;
        }
        if (!clanOptedIn(line.type == SubmissionType.CLAN_CHAT)) {
            return;
        }
        if (queue.size() >= MAX_QUEUED_LINES) {
            return;
        }
        line.clanName = currentClanName;
        String trimmed = line.message.trim();
        line.message = trimmed.length() > MAX_MESSAGE_CHARS
            ? trimmed.substring(0, MAX_MESSAGE_CHARS) : trimmed;
        queue.add(line);
        if (flushScheduled.compareAndSet(false, true)) {
            executor.schedule(this::flush, FLUSH_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Chat events arrive on the client thread, so this read is safe here. */
    private void refreshClanNameFromClient() {
        ClanChannel channel = client.getClanChannel();
        currentClanName = channel != null ? Text.removeTags(channel.getName()) : null;
    }

    private void flush() {
        flushScheduled.set(false);
        try {
            List<PendingLine> lines = new ArrayList<>(MAX_LINES_PER_FLUSH);
            PendingLine next;
            while (lines.size() < MAX_LINES_PER_FLUSH && (next = queue.poll()) != null) {
                lines.add(next);
            }
            if (lines.isEmpty()) {
                return;
            }
            // One webhook per submission type so SubmissionManager's per-type
            // dispatch stays uniform; a mixed 2s window is two payloads.
            sendBatch(lines, SubmissionType.CLAN_BROADCAST);
            sendBatch(lines, SubmissionType.CLAN_CHAT);
        } catch (Exception e) {
            log.debug("Clan relay flush failed: {}", e.getMessage());
        } finally {
            // Anything still queued (overflow past the batch cap, or lines
            // added mid-flush) gets its own pass.
            if (!queue.isEmpty() && flushScheduled.compareAndSet(false, true)) {
                executor.schedule(this::flush, FLUSH_DELAY_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    private void sendBatch(List<PendingLine> lines, SubmissionType type) {
        /* The relayer's own identity is what the server authenticates the batch
           against, and it rejects a batch without one. Flushing happens off the
           client thread, so an unreadable name here is routine — resolve it the
           same way submissions do, and drop the batch rather than relay it
           anonymously or under an invented name. */
        String relayerName = PlayerIdentity.resolve(
            plugin.getLocalPlayerName(),
            String.valueOf(client.getAccountHash()),
            config.lastAccountName(),
            config.lastAccountHash());
        if (relayerName == null) {
            log.debug("Skipping clan relay batch: no resolvable player name");
            return;
        }

        CustomWebhookBody webhook = null;
        for (PendingLine line : lines) {
            if (line.type != type) {
                continue;
            }
            if (webhook == null) {
                webhook = new CustomWebhookBody();
                webhook.setContent("DropTracker Clan Relay");
            }
            CustomWebhookBody.Embed embed = new CustomWebhookBody.Embed();
            embed.setTitle("Clan relay");
            embed.addField("type", type == SubmissionType.CLAN_BROADCAST ? "clan_broadcast" : "clan_chat", true);
            embed.addField("clan_name", line.clanName, true);
            embed.addField("message", line.message, false);
            if (line.sender != null) {
                embed.addField("sender", line.sender, true);
            }
            if (line.accountType != null) {
                embed.addField("account_type", line.accountType, true);
            }
            embed.addField("player_name", relayerName, true);
            embed.addField("acc_hash", String.valueOf(client.getAccountHash()), true);
            embed.addField("p_v", plugin.pluginVersion != null ? plugin.pluginVersion : "unknown", true);
            embed.addField("guid", api.generateGuidForSubmission(), true);
            webhook.getEmbeds().add(embed);
        }
        if (webhook != null) {
            submissionManager.sendDataToDropTracker(webhook, type);
        }
    }

    private static final class PendingLine {
        private final SubmissionType type;
        private final String sender;
        private String clanName;
        private String message;
        private String accountType;

        private PendingLine(SubmissionType type, String sender, String message) {
            this.type = type;
            this.sender = sender;
            this.message = message;
        }
    }
}
