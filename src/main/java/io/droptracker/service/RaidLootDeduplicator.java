package io.droptracker.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import io.droptracker.util.DebugLogger;
import io.droptracker.util.ItemStacks;
import io.droptracker.util.NpcUtilities;
import net.runelite.api.Client;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.TimeUnit;

/**
 * Suppresses the second loot event fired when a raid reward chest is opened
 * twice for the same completion. RuneLite records raid loot when the reward
 * interface OPENS, not when items are picked up — so a player who opens the
 * chest in the loot room, leaves the loot inside, and later claims it from the
 * collection chest at the bank produces two identical {@link LootReceived}
 * events minutes (or hours) apart. Without suppression the second event
 * submits a duplicate drop AND bumps {@link KCService}'s cached kill count,
 * which the backend then credits as a phantom kill.
 *
 * <p>Keyed per account + raid family, storing the signature of the last chest
 * bundle seen. A raid's next completion chat message re-arms it (see
 * {@link #onRaidCompletion}), so back-to-back raids that happen to roll
 * identical loot are never suppressed: the dedup scope is "since the last
 * completion of this raid", with a generous TTL as a memory backstop.
 *
 * <p>Must gate BOTH {@code DropHandler.onLootReceived} and
 * {@code KCService.onLoot} — suppressing only the drop submission would leave
 * the client's cached kill count permanently one ahead of reality. The single
 * call site in {@code DropTrackerPlugin.onLootReceived} sits before that
 * fan-out for exactly this reason.
 */
@Singleton
public class RaidLootDeduplicator {

    private final Client client;

    private final Cache<String, String> lastChestSignature = CacheBuilder.newBuilder()
            .expireAfterWrite(2, TimeUnit.HOURS)
            .maximumSize(32L)
            .build();

    @Inject
    public RaidLootDeduplicator(Client client) {
        this.client = client;
    }

    /**
     * Whether this loot event repeats a raid chest bundle already seen for the
     * current completion. First sight of a bundle records it and returns
     * false; an identical bundle for the same raid (until the next completion
     * message re-arms it) returns true.
     */
    public boolean isDuplicateRaidLoot(LootReceived event) {
        if (event.getType() != LootRecordType.EVENT) {
            return false;
        }
        boolean duplicate = isDuplicate(client.getAccountHash(), event.getName(),
                ItemStacks.signature(event.getItems()));
        if (duplicate) {
            DebugLogger.log("[RaidLootDeduplicator] suppressing re-looted raid chest; source="
                    + event.getName());
        }
        return duplicate;
    }

    /**
     * Re-arms the dedup for the raid named in a completion chat message
     * ("Your completed Theatre of Blood count is: ..."), so the next chest
     * open is always treated as new loot even if its bundle happens to match
     * the previous completion's.
     */
    public void onRaidCompletion(String bossName) {
        invalidate(client.getAccountHash(), bossName);
    }

    @VisibleForTesting
    boolean isDuplicate(long accountHash, String sourceName, String signature) {
        String family = raidFamily(sourceName);
        if (family == null) {
            return false;
        }
        String key = accountHash + "|" + family;
        String previous = lastChestSignature.getIfPresent(key);
        if (signature.equals(previous)) {
            return true;
        }
        lastChestSignature.put(key, signature);
        return false;
    }

    @VisibleForTesting
    void invalidate(long accountHash, String bossName) {
        String family = raidFamily(bossName);
        if (family != null) {
            lastChestSignature.invalidate(accountHash + "|" + family);
        }
    }

    /**
     * Collapses a raid source or chat-message boss name (any mode variant,
     * e.g. "Theatre of Blood: Entry Mode", "Chambers of Xeric Challenge Mode")
     * to its base raid name, or null for anything that is not a raid. The
     * loot event always carries the base name while the completion message may
     * carry a mode suffix; folding both to the family keys them identically.
     */
    @Nullable
    @VisibleForTesting
    static String raidFamily(String name) {
        if (name == null) {
            return null;
        }
        if (name.startsWith(NpcUtilities.TOB)) {
            return NpcUtilities.TOB;
        }
        if (name.startsWith(NpcUtilities.TOA)) {
            return NpcUtilities.TOA;
        }
        if (name.startsWith(NpcUtilities.COX)) {
            return NpcUtilities.COX;
        }
        return null;
    }
}
