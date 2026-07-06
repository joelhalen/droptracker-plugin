package io.droptracker.service;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Varbits;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.util.Text;
import io.droptracker.util.DebugLogger;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Service for collecting nearby player names from active world views.
 */
@Singleton
public class NearbyPlayerTracker
{
    private final Client client;
    private final ClientThread clientThread;
    private final PartyService partyService;
    private final Map<String, Long> recentRaidMembers = new HashMap<>();

    /**
     * How long a raid member stays in {@link #recentRaidMembers} after last being seen.
     * Raid completion resets the raid varbits before the player opens the loot chest,
     * so the roster must survive past the end of the raid context. Players can idle in
     * the treasure room for several minutes before opening the chest.
     */
    private static final long RAID_MEMBER_EXPIRY_MS = TimeUnit.MINUTES.toMillis(10);

    /** Roster scans while inside a raid run every N game ticks (~0.6s each). */
    private static final int ROSTER_SCAN_TICK_INTERVAL = 5;

    /** Radius used for the periodic in-raid roster scan; raid rooms are large. */
    private static final int ROSTER_SCAN_RADIUS_TILES = 40;

    private int ticksSinceRosterScan = 0;
    private boolean wasInRaid = false;

    @Inject
    public NearbyPlayerTracker(Client client, ClientThread clientThread, PartyService partyService)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.partyService = partyService;
    }

    /**
     * Called every game tick (client thread) so the raid roster is accumulated
     * continuously while a raid is in progress, instead of only being sampled
     * when a submission happens. By loot-chest time the raid varbits have already
     * reset and teammates may have left, so a point-in-time scan is not enough
     * (issue #43: ToB/ToA drops submitted with an empty participant list).
     */
    public void onGameTick()
    {
        boolean inRaid = isInRaidContext();
        if (inRaid && !wasInRaid)
        {
            // Entering a new raid: drop any roster left over from a previous run so
            // stale names can't leak into this raid's submissions.
            recentRaidMembers.clear();
            ticksSinceRosterScan = ROSTER_SCAN_TICK_INTERVAL; // scan immediately
        }
        wasInRaid = inRaid;

        if (!inRaid)
        {
            return;
        }

        if (++ticksSinceRosterScan < ROSTER_SCAN_TICK_INTERVAL)
        {
            return;
        }
        ticksSinceRosterScan = 0;

        long nowMs = System.currentTimeMillis();
        Player localPlayer = client.getLocalPlayer();
        String localName = localPlayer != null ? normalizePlayerName(localPlayer.getName()) : null;

        recordNearbyPlayers(localPlayer, localName, nowMs);
        recordTobPartyNames(localName, nowMs);
        recordPartyServiceMembers(localName, nowMs);
    }

    private void recordNearbyPlayers(Player localPlayer, String localName, long nowMs)
    {
        if (localPlayer == null || localPlayer.getWorldLocation() == null)
        {
            return;
        }
        WorldPoint center = localPlayer.getWorldLocation();
        WorldView topLevel = client.getTopLevelWorldView();
        if (topLevel == null)
        {
            return;
        }
        ScanStats ignored = new ScanStats();
        Set<String> names = new LinkedHashSet<>();
        collectNamesFromWorldView(topLevel, center, ROSTER_SCAN_RADIUS_TILES, localPlayer, names, nowMs, true, ignored);
        for (WorldView subWorldView : topLevel.worldViews())
        {
            collectNamesFromWorldView(subWorldView, center, ROSTER_SCAN_RADIUS_TILES, localPlayer, names, nowMs, true, ignored);
        }
    }

    /**
     * ToB publishes the party roster in varcstrings (health-orb name slots), which
     * stay populated for the whole raid regardless of where teammates are standing.
     */
    private void recordTobPartyNames(String localName, long nowMs)
    {
        for (int varc = VarClientID.TOB_CLIENT_NAME0; varc <= VarClientID.TOB_CLIENT_NAME4; varc++)
        {
            String name = normalizePlayerName(client.getVarcStrValue(varc));
            if (name != null && !name.equals(localName) && !"-".equals(name))
            {
                recentRaidMembers.put(name, nowMs);
            }
        }
    }

    private void recordPartyServiceMembers(String localName, long nowMs)
    {
        if (!partyService.isInParty())
        {
            return;
        }
        for (PartyMember member : partyService.getMembers())
        {
            String memberName = normalizePlayerName(member.getDisplayName());
            if (memberName != null && !memberName.equals(localName))
            {
                recentRaidMembers.put(memberName, nowMs);
            }
        }
    }

    /**
     * Whether the local player is currently inside a raid. Uses the raid *state*
     * varbits rather than teammate health orbs — the orbs read zero in lobbies,
     * during transitions, and after completion, which is exactly when we still
     * need to know a raid is (or was) in progress.
     */
    private boolean isInRaidContext()
    {
        // ToB: 0=outside, 1=in party (lobby), 2=inside raid, 3=spectating
        if (client.getVarbitValue(THEATRE_OF_BLOOD_STATE) >= 2)
        {
            return true;
        }
        // ToA: 0=outside, 1=in party (lobby), 2=inside raid
        if (client.getVarbitValue(VarbitID.TOA_CLIENT_PARTYSTATUS) >= 2)
        {
            return true;
        }
        return client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1;
    }

    @SuppressWarnings("deprecation")
    private static final int THEATRE_OF_BLOOD_STATE = Varbits.THEATRE_OF_BLOOD;

    public List<String> getNearbyPlayerNames(int radiusTiles)
    {
        return getNearbyPlayerTrace(radiusTiles).getNearbyPlayers();
    }

    public NearbyPlayerTrace getNearbyPlayerTrace(int radiusTiles)
    {
        if (client.isClientThread())
        {
            return collectNearbyPlayerTrace(radiusTiles);
        }

        CompletableFuture<NearbyPlayerTrace> future = new CompletableFuture<>();
        clientThread.invoke(() ->
        {
            try
            {
                future.complete(collectNearbyPlayerTrace(radiusTiles));
            }
            catch (Exception e)
            {
                future.complete(NearbyPlayerTrace.empty(Math.max(1, radiusTiles), "client-thread collection exception: " + e.getMessage()));
            }
        });

        try
        {
            return future.get(500, TimeUnit.MILLISECONDS);
        }
        catch (Exception e)
        {
            return NearbyPlayerTrace.empty(Math.max(1, radiusTiles), "future timeout/exception: " + e.getMessage());
        }
    }

    private NearbyPlayerTrace collectNearbyPlayerTrace(int radiusTiles)
    {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || localPlayer.getWorldLocation() == null)
        {
            return NearbyPlayerTrace.empty(Math.max(1, radiusTiles), "local player unavailable");
        }

        WorldPoint center = localPlayer.getWorldLocation();
        int effectiveRadius = Math.max(1, radiusTiles);
        Set<String> names = new LinkedHashSet<>();
        long nowMs = System.currentTimeMillis();
        String localName = normalizePlayerName(localPlayer.getName());
        int toaTeamCount = getToaTeamCount();
        int tobTeamCount = getTobTeamCount();
        int coxTeamCount = getCoxTeamCount();
        boolean inRaidContext = (toaTeamCount > 1) || (tobTeamCount > 1) || (coxTeamCount > 1) || isInRaidContext();
        int recentRaidMembersBeforePurge = recentRaidMembers.size();
        recentRaidMembers.values().removeIf(lastSeenMs -> nowMs - lastSeenMs > RAID_MEMBER_EXPIRY_MS);
        // The ToB roster varcstrings can outlive the raid-state varbits, so merge them
        // at submission time too in case tick accumulation missed this raid.
        recordTobPartyNames(localName, nowMs);
        ScanStats scanStats = new ScanStats();

        WorldView topLevel = client.getTopLevelWorldView();
        if (topLevel != null)
        {
            collectNamesFromWorldView(topLevel, center, effectiveRadius, localPlayer, names, nowMs, inRaidContext, scanStats);
            for (WorldView subWorldView : topLevel.worldViews())
            {
                collectNamesFromWorldView(subWorldView, center, effectiveRadius, localPlayer, names, nowMs, inRaidContext, scanStats);
            }
        }

        if (inRaidContext && partyService.isInParty())
        {
            for (PartyMember member : partyService.getMembers())
            {
                String memberName = normalizePlayerName(member.getDisplayName());
                if (memberName == null || memberName.equals(localName))
                {
                    continue;
                }
                recentRaidMembers.put(memberName, nowMs);
            }
        }

        // Recent raid members are included even when the raid varbits have already
        // reset — completion clears them before the loot chest is opened.
        for (Map.Entry<String, Long> entry : recentRaidMembers.entrySet())
        {
            String memberName = entry.getKey();
            if (!memberName.equals(localName))
            {
                names.add(memberName);
            }
        }

        String raidType;
        if (toaTeamCount > 1)
        {
            raidType = "toa";
        }
        else if (tobTeamCount > 1)
        {
            raidType = "tob";
        }
        else if (coxTeamCount > 1)
        {
            raidType = "cox";
        }
        else
        {
            raidType = "none";
        }

        NearbyPlayerTrace trace = new NearbyPlayerTrace(
            new ArrayList<>(names),
            effectiveRadius,
            localName,
            center,
            inRaidContext,
            raidType,
            toaTeamCount,
            tobTeamCount,
            coxTeamCount,
            partyService.isInParty(),
            partyService.isInParty() ? partyService.getMembers().size() : 0,
            scanStats.worldViewsScanned,
            scanStats.playersSeen,
            scanStats.playersWithinRadius,
            scanStats.playersAdded,
            recentRaidMembersBeforePurge,
            recentRaidMembers.size(),
            nowMs,
            null
        );
        DebugLogger.log("[NearbyPlayerTracker] " + trace.toDebugSummary());
        return trace;
    }

    private void collectNamesFromWorldView(
        WorldView worldView,
        WorldPoint center,
        int radiusTiles,
        Player localPlayer,
        Set<String> names,
        long nowMs,
        boolean inRaidContext,
        ScanStats scanStats
    )
    {
        if (worldView == null)
        {
            return;
        }
        scanStats.worldViewsScanned++;

        for (Player player : worldView.players())
        {
            scanStats.playersSeen++;
            if (player == null || player == localPlayer)
            {
                continue;
            }

            String playerName = player.getName();
            WorldPoint playerLocation = player.getWorldLocation();
            if (playerName == null || playerLocation == null)
            {
                continue;
            }

            if (playerLocation.getPlane() != center.getPlane())
            {
                continue;
            }

            if (center.distanceTo2D(playerLocation) > radiusTiles)
            {
                continue;
            }
            scanStats.playersWithinRadius++;

            String normalizedName = Text.toJagexName(Text.removeTags(playerName)).trim();
            if (!normalizedName.isEmpty())
            {
                if (names.add(normalizedName))
                {
                    scanStats.playersAdded++;
                }
                if (inRaidContext)
                {
                    recentRaidMembers.put(normalizedName, nowMs);
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private int getToaTeamCount()
    {
        return Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_0_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_1_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_2_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_3_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_4_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_5_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_6_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_7_HEALTH), 1);
    }

    @SuppressWarnings("deprecation")
    private int getTobTeamCount()
    {
        return Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB1), 1) +
            Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB2), 1) +
            Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB3), 1) +
            Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB4), 1) +
            Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB5), 1);
    }

    private int getCoxTeamCount()
    {
        if (client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 0)
        {
            return 0;
        }
        return Math.max(client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE), 1);
    }

    private String normalizePlayerName(String rawName)
    {
        if (rawName == null)
        {
            return null;
        }

        String normalized = Text.toJagexName(Text.removeTags(rawName)).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static final class ScanStats
    {
        int worldViewsScanned;
        int playersSeen;
        int playersWithinRadius;
        int playersAdded;
    }

    public static final class NearbyPlayerTrace
    {
        private final List<String> nearbyPlayers;
        private final int radiusTiles;
        private final String localPlayer;
        private final WorldPoint localWorldPoint;
        private final boolean inRaidContext;
        private final String raidType;
        private final int toaTeamCount;
        private final int tobTeamCount;
        private final int coxTeamCount;
        private final boolean inParty;
        private final int partySize;
        private final int worldViewsScanned;
        private final int playersSeen;
        private final int playersWithinRadius;
        private final int uniquePlayersAdded;
        private final int recentRaidMembersBeforePurge;
        private final int recentRaidMembersAfterPurge;
        private final long capturedAtMs;
        private final String fallbackReason;

        private NearbyPlayerTrace(
            List<String> nearbyPlayers,
            int radiusTiles,
            String localPlayer,
            WorldPoint localWorldPoint,
            boolean inRaidContext,
            String raidType,
            int toaTeamCount,
            int tobTeamCount,
            int coxTeamCount,
            boolean inParty,
            int partySize,
            int worldViewsScanned,
            int playersSeen,
            int playersWithinRadius,
            int uniquePlayersAdded,
            int recentRaidMembersBeforePurge,
            int recentRaidMembersAfterPurge,
            long capturedAtMs,
            String fallbackReason
        )
        {
            this.nearbyPlayers = nearbyPlayers;
            this.radiusTiles = radiusTiles;
            this.localPlayer = localPlayer;
            this.localWorldPoint = localWorldPoint;
            this.inRaidContext = inRaidContext;
            this.raidType = raidType;
            this.toaTeamCount = toaTeamCount;
            this.tobTeamCount = tobTeamCount;
            this.coxTeamCount = coxTeamCount;
            this.inParty = inParty;
            this.partySize = partySize;
            this.worldViewsScanned = worldViewsScanned;
            this.playersSeen = playersSeen;
            this.playersWithinRadius = playersWithinRadius;
            this.uniquePlayersAdded = uniquePlayersAdded;
            this.recentRaidMembersBeforePurge = recentRaidMembersBeforePurge;
            this.recentRaidMembersAfterPurge = recentRaidMembersAfterPurge;
            this.capturedAtMs = capturedAtMs;
            this.fallbackReason = fallbackReason;
        }

        public static NearbyPlayerTrace empty(int radiusTiles, String fallbackReason)
        {
            return new NearbyPlayerTrace(
                Collections.emptyList(),
                radiusTiles,
                null,
                null,
                false,
                "none",
                0,
                0,
                0,
                false,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                System.currentTimeMillis(),
                fallbackReason
            );
        }

        public List<String> getNearbyPlayers()
        {
            return nearbyPlayers;
        }

        public String toDebugSummary()
        {
            String localPointSummary = localWorldPoint == null
                ? "unknown"
                : localWorldPoint.getX() + "," + localWorldPoint.getY() + "," + localWorldPoint.getPlane();
            return "radius=" + radiusTiles
                + ", localPlayer=" + (localPlayer != null ? localPlayer : "unknown")
                + ", localPoint=" + localPointSummary
                + ", inRaidContext=" + inRaidContext
                + ", raidType=" + raidType
                + ", toaTeamCount=" + toaTeamCount
                + ", tobTeamCount=" + tobTeamCount
                + ", coxTeamCount=" + coxTeamCount
                + ", inParty=" + inParty
                + ", partySize=" + partySize
                + ", worldViewsScanned=" + worldViewsScanned
                + ", playersSeen=" + playersSeen
                + ", playersWithinRadius=" + playersWithinRadius
                + ", uniquePlayersAdded=" + uniquePlayersAdded
                + ", recentRaidMembersBeforePurge=" + recentRaidMembersBeforePurge
                + ", recentRaidMembersAfterPurge=" + recentRaidMembersAfterPurge
                + ", nearbyPlayers=" + nearbyPlayers
                + ", capturedAtMs=" + capturedAtMs
                + (fallbackReason != null ? ", fallbackReason=" + fallbackReason : "");
        }
    }
}
