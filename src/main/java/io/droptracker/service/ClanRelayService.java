/*
 * Adapted from the TrackScape Connector
 * (github.com/fatfingers23/trackscape-connector-plugin),
 * Copyright (c) 2023, Bailey Townsend, BSD 2-Clause License (see LICENSE).
 */
package io.droptracker.service;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.clan.ClanChannel;
import net.runelite.client.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Relays clan chat to the DropTracker API for the clan features:
 * <ul>
 *   <li>{@code CLAN_MESSAGE} system broadcasts ("X received a drop: ...") as
 *       {@code clan_broadcast} submissions — server-side parsing tracks
 *       clanmates who don't run the plugin;</li>
 *   <li>{@code CLAN_CHAT} player lines as {@code clan_chat} submissions — the
 *       game→Discord half of the two-way chat bridge.</li>
 * </ul>
 *
 * The plugin stays a dumb pipe on purpose: no game-message parsing happens
 * client-side, so pattern fixes never wait on a plugin-hub review. Lines are
 * batched (2s debounce, capped batch) into one payload, and each embed
 * carries the standard relayer identity fields — the server authenticates the
 * RELAYER and dedupes across multiple relaying clanmates, so this can be
 * enabled by any number of members safely.
 *
 * API-only by contract: raw chat text must never ride the Discord-webhook
 * fallback transport. Both the queue methods here and the
 * {@link SubmissionManager} dispatch cases enforce it.
 */
@Slf4j
@Singleton
public class ClanRelayService {

    private static final int FLUSH_DELAY_SECONDS = 2;
    private static final int MAX_LINES_PER_FLUSH = 10;
    /** Backstop so a pathological chat flood can't grow the queue unbounded. */
    private static final int MAX_QUEUED_LINES = 200;
    private static final int MAX_MESSAGE_CHARS = 250;

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

    /** Whether the Discord→game direction should be live (poll + display). */
    public boolean discordChatActive() {
        return config.useApi() && config.receiveDiscordChat() && currentClanName != null;
    }

    /** A CLAN_MESSAGE system broadcast (already tag-sanitized by the caller). */
    public void onClanBroadcast(String message) {
        if (!config.useApi() || !config.relayClanBroadcasts()) {
            return;
        }
        queueLine(new PendingLine(SubmissionType.CLAN_BROADCAST, null, message));
    }

    /** A CLAN_CHAT player line (sender may still carry icon tags). */
    public void onClanChat(String senderName, String message) {
        if (!config.useApi() || !config.relayClanChat()) {
            return;
        }
        String sender = senderName != null ? Text.removeTags(Text.toJagexName(senderName)) : null;
        if (sender == null || sender.trim().isEmpty()) {
            return;
        }
        queueLine(new PendingLine(SubmissionType.CLAN_CHAT, sender.trim(), message));
    }

    private void queueLine(PendingLine line) {
        refreshClanNameFromClient();
        if (currentClanName == null || line.message == null || line.message.trim().isEmpty()) {
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
            embed.addField("player_name", plugin.getLocalPlayerName(), true);
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

        private PendingLine(SubmissionType type, String sender, String message) {
            this.type = type;
            this.sender = sender;
            this.message = message;
        }
    }
}
