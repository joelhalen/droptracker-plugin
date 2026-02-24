package io.droptracker.service;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Varbits;
import net.runelite.api.WorldView;
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

    @Inject
    public NearbyPlayerTracker(Client client, ClientThread clientThread, PartyService partyService)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.partyService = partyService;
    }

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
        boolean inRaidContext = (toaTeamCount > 1) || (tobTeamCount > 1);
        int recentRaidMembersBeforePurge = recentRaidMembers.size();
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

        if (!inRaidContext)
        {
            recentRaidMembers.clear();
            NearbyPlayerTrace trace = new NearbyPlayerTrace(
                new ArrayList<>(names),
                effectiveRadius,
                localName,
                center,
                false,
                "none",
                toaTeamCount,
                tobTeamCount,
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

        if (partyService.isInParty())
        {
            for (PartyMember member : partyService.getMembers())
            {
                String memberName = normalizePlayerName(member.getDisplayName());
                if (memberName == null || memberName.equals(localName))
                {
                    continue;
                }
                recentRaidMembers.put(memberName, nowMs);
                names.add(memberName);
            }
        }

        for (Map.Entry<String, Long> entry : recentRaidMembers.entrySet())
        {
            String memberName = entry.getKey();
            if (!memberName.equals(localName))
            {
                names.add(memberName);
            }
        }

        NearbyPlayerTrace trace = new NearbyPlayerTrace(
            new ArrayList<>(names),
            effectiveRadius,
            localName,
            center,
            true,
            toaTeamCount > 1 ? "toa" : "tob",
            toaTeamCount,
            tobTeamCount,
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
