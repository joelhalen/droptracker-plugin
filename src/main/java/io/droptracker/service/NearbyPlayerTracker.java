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
    private static final int DEFAULT_RADIUS_TILES = 15;
    private static final long RAID_MEMBER_TTL_MS = 120_000L;

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

    public void printNearbyPlayersToConsole()
    {
        printNearbyPlayersToConsole(DEFAULT_RADIUS_TILES);
    }

    public void printNearbyPlayersToConsole(int radiusTiles)
    {
        clientThread.invoke(() ->
        {
            List<String> names = getNearbyPlayerNames(radiusTiles);
            if (names.isEmpty())
            {
                System.out.println("No nearby players found.");
                return;
            }

            System.out.println(String.join(", ", names));
        });
    }

    public List<String> getNearbyPlayerNames(int radiusTiles)
    {
        if (client.isClientThread())
        {
            return collectNearbyPlayerNames(radiusTiles);
        }

        CompletableFuture<List<String>> future = new CompletableFuture<>();
        clientThread.invoke(() ->
        {
            try
            {
                future.complete(collectNearbyPlayerNames(radiusTiles));
            }
            catch (Exception e)
            {
                future.complete(Collections.emptyList());
            }
        });

        try
        {
            return future.get(500, TimeUnit.MILLISECONDS);
        }
        catch (Exception e)
        {
            return Collections.emptyList();
        }
    }

    private List<String> collectNearbyPlayerNames(int radiusTiles)
    {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || localPlayer.getWorldLocation() == null)
        {
            return new ArrayList<>();
        }

        WorldPoint center = localPlayer.getWorldLocation();
        int effectiveRadius = Math.max(1, radiusTiles);
        Set<String> names = new LinkedHashSet<>();
        long nowMs = System.currentTimeMillis();
        String localName = normalizePlayerName(localPlayer.getName());
        boolean inRaidContext = isInRaidContext();

        WorldView topLevel = client.getTopLevelWorldView();
        if (topLevel != null)
        {
            collectNamesFromWorldView(topLevel, center, effectiveRadius, localPlayer, names, nowMs, inRaidContext);
            for (WorldView subWorldView : topLevel.worldViews())
            {
                collectNamesFromWorldView(subWorldView, center, effectiveRadius, localPlayer, names, nowMs, inRaidContext);
            }
        }

        if (!inRaidContext)
        {
            recentRaidMembers.clear();
            return new ArrayList<>(names);
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

        purgeExpiredRaidMembers(nowMs);
        for (Map.Entry<String, Long> entry : recentRaidMembers.entrySet())
        {
            String memberName = entry.getKey();
            if (!memberName.equals(localName))
            {
                names.add(memberName);
            }
        }

        return new ArrayList<>(names);
    }

    private void collectNamesFromWorldView(
        WorldView worldView,
        WorldPoint center,
        int radiusTiles,
        Player localPlayer,
        Set<String> names,
        long nowMs,
        boolean inRaidContext
    )
    {
        if (worldView == null)
        {
            return;
        }

        for (Player player : worldView.players())
        {
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

            String normalizedName = Text.removeTags(playerName).trim();
            if (!normalizedName.isEmpty())
            {
                names.add(normalizedName);
                if (inRaidContext)
                {
                    recentRaidMembers.put(normalizedName, nowMs);
                }
            }
        }
    }

    private void purgeExpiredRaidMembers(long nowMs)
    {
        recentRaidMembers.entrySet().removeIf(entry -> (nowMs - entry.getValue()) > RAID_MEMBER_TTL_MS);
    }

    @SuppressWarnings("deprecation")
    private boolean isInRaidContext()
    {
        int toaTeamCount = Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_0_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_1_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_2_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_3_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_4_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_5_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_6_HEALTH), 1) +
            Math.min(client.getVarbitValue(Varbits.TOA_MEMBER_7_HEALTH), 1);

        if (toaTeamCount > 1)
        {
            return true;
        }

        int tobTeamCount = Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB1), 1) +
            Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB2), 1) +
            Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB3), 1) +
            Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB4), 1) +
            Math.min(client.getVarbitValue(Varbits.THEATRE_OF_BLOOD_ORB5), 1);

        return tobTeamCount > 1;
    }

    private String normalizePlayerName(String rawName)
    {
        if (rawName == null)
        {
            return null;
        }

        String normalized = Text.removeTags(rawName).trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
