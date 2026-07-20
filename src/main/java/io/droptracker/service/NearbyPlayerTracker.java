package io.droptracker.service;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Varbits;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.util.Text;
import io.droptracker.util.DebugLogger;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Service for resolving the participants attached to submissions.
 *
 * <p>For raid content (ToB / ToA / CoX) the roster is captured from the game's
 * authoritative party sources rather than inferred from proximity:
 * <ul>
 *   <li><b>ToB</b>: health-orb name varcstrings {@code TOB_CLIENT_NAME0..4}</li>
 *   <li><b>ToA</b>: health-orb name varcstrings {@code TOA_CLIENT_NAME0..7}</li>
 *   <li><b>CoX</b>: the raiding-party sidepanel list widget
 *       ({@code InterfaceID.RaidsSidepanel.LIST})</li>
 * </ul>
 * These are server-pushed and independent of render distance, so the roster is
 * exactly the raid party — not whoever happened to be standing nearby.
 *
 * <p>Non-raid submissions fall back to a plain proximity scan, and the raid
 * roster is never merged into them, so a retained ToB roster can no longer be
 * attached to an unrelated kill minutes after the raid ended.
 */
@Singleton
public class NearbyPlayerTracker
{
    private final Client client;
    private final ClientThread clientThread;
    private final PartyService partyService;

    public static final String RAID_TOB = "tob";
    public static final String RAID_TOA = "toa";
    public static final String RAID_COX = "cox";

    /**
     * Names read from the authoritative roster source of the active raid,
     * accumulated over the raid so members who disconnect or leave early are
     * still credited. Includes the local player (needed for spectator
     * detection); the local name is filtered out at emission time.
     */
    private final Set<String> authoritativeRoster = new LinkedHashSet<>();

    /**
     * Last-resort roster accumulated from proximity scans and PartyService
     * while inside a raid. Only consulted when the authoritative source
     * yielded nothing (e.g. plugin enabled mid-raid after the widgets/varcs
     * stopped updating), so drops never regress to an empty participant list
     * (issue #43).
     */
    private final Set<String> fallbackRoster = new LinkedHashSet<>();

    /** Raid the rosters above belong to: {@code "tob"|"toa"|"cox"}, or null. */
    private String activeRaidType = null;

    /** Last time (ms) the raid context was observed active. */
    private long rosterLastActiveMs = 0;

    /**
     * How long the roster survives after the raid context ends. Raid
     * completion resets the raid varbits before the player opens the loot
     * chest, so the roster must outlive the raid; players can idle in the
     * treasure room for several minutes before opening the chest.
     */
    private static final long RAID_ROSTER_EXPIRY_MS = TimeUnit.MINUTES.toMillis(10);

    /** Roster scans while inside a raid run every N game ticks (~0.6s each). */
    private static final int ROSTER_SCAN_TICK_INTERVAL = 5;

    /** Radius used for the fallback in-raid proximity scan; raid rooms are large. */
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
     * Maps a submission's source name (drop {@code source} field / PB
     * {@code boss_name}) to the raid it belongs to, or null for non-raid
     * content. Contains-matching covers every mode variant ("Theatre of
     * Blood: Hard Mode", "Chambers of Xeric Challenge Mode", ...).
     */
    public static String raidTypeForSource(String sourceName)
    {
        if (sourceName == null)
        {
            return null;
        }
        String source = sourceName.toLowerCase(Locale.ROOT);
        if (source.contains("theatre of blood"))
        {
            return RAID_TOB;
        }
        if (source.contains("tombs of amascut"))
        {
            return RAID_TOA;
        }
        if (source.contains("chambers of xeric"))
        {
            return RAID_COX;
        }
        return null;
    }

    /**
     * Called every game tick (client thread) so the raid roster is accumulated
     * continuously while a raid is in progress, instead of only being sampled
     * when a submission happens. By loot-chest time the raid varbits have
     * already reset, so a point-in-time scan is not enough (issue #43: ToB/ToA
     * drops submitted with an empty participant list).
     */
    public void onGameTick()
    {
        String raidType = currentRaidType();
        long nowMs = System.currentTimeMillis();

        if (raidType == null)
        {
            // Retain the roster for chest-time submissions, then expire it as
            // a whole so a stale raid can't leak into a later session.
            wasInRaid = false;
            if (activeRaidType != null && nowMs - rosterLastActiveMs > RAID_ROSTER_EXPIRY_MS)
            {
                clearRoster();
            }
            return;
        }

        if (!wasInRaid || !raidType.equals(activeRaidType))
        {
            // Entering a new raid (fresh entry — even back-to-back runs of the
            // same raid — or a different raid than the retained roster): drop
            // leftovers so a previous team can't leak into this raid's
            // submissions.
            clearRoster();
            activeRaidType = raidType;
            ticksSinceRosterScan = ROSTER_SCAN_TICK_INTERVAL; // scan immediately
        }
        wasInRaid = true;
        rosterLastActiveMs = nowMs;

        if (++ticksSinceRosterScan < ROSTER_SCAN_TICK_INTERVAL)
        {
            return;
        }
        ticksSinceRosterScan = 0;

        captureAuthoritativeRoster(raidType, authoritativeRoster);
        captureFallbackRoster();
    }

    private void clearRoster()
    {
        authoritativeRoster.clear();
        fallbackRoster.clear();
        activeRaidType = null;
    }

    /**
     * Which raid the local player is currently inside, from the raid *state*
     * varbits rather than teammate health orbs — the orbs read zero in
     * lobbies, during transitions, and after completion, which is exactly when
     * we still need to know a raid is (or was) in progress.
     */
    private String currentRaidType()
    {
        // ToB: 0=outside, 1=in party (lobby), 2=inside raid, 3=spectating
        if (client.getVarbitValue(THEATRE_OF_BLOOD_STATE) >= 2)
        {
            return RAID_TOB;
        }
        // ToA: 0=outside, 1=in party (lobby), 2=inside raid
        if (client.getVarbitValue(VarbitID.TOA_CLIENT_PARTYSTATUS) >= 2)
        {
            return RAID_TOA;
        }
        if (client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1)
        {
            return RAID_COX;
        }
        return null;
    }

    /** Reads the authoritative roster source for the given raid into {@code into}. */
    private void captureAuthoritativeRoster(String raidType, Set<String> into)
    {
        switch (raidType)
        {
            case RAID_TOB:
                captureVarcNames(VarClientID.TOB_CLIENT_NAME0, VarClientID.TOB_CLIENT_NAME4, into);
                break;
            case RAID_TOA:
                captureVarcNames(VarClientID.TOA_CLIENT_NAME0, VarClientID.TOA_CLIENT_NAME7, into);
                break;
            case RAID_COX:
                captureCoxSidepanelNames(into);
                break;
            default:
                break;
        }
    }

    /**
     * ToB/ToA publish the party roster in varcstrings (health-orb name slots),
     * which stay populated for the whole raid — and usually beyond — regardless
     * of where teammates are standing. Empty slots read as "" or "-".
     */
    private void captureVarcNames(int firstVarcId, int lastVarcId, Set<String> into)
    {
        for (int varc = firstVarcId; varc <= lastVarcId; varc++)
        {
            String name = normalizePlayerName(client.getVarcStrValue(varc));
            if (name != null && !"-".equals(name))
            {
                into.add(name);
            }
        }
    }

    /**
     * CoX has no roster varcstrings; the authoritative source is the
     * raiding-party sidepanel list, whose child widgets carry member names.
     * The widget stays loaded while in the party/raid even when another side
     * tab is selected.
     */
    private void captureCoxSidepanelNames(Set<String> into)
    {
        Widget list = client.getWidget(InterfaceID.RaidsSidepanel.LIST);
        if (list == null)
        {
            return;
        }
        Widget[] children = list.getChildren();
        if (children == null)
        {
            return;
        }
        for (Widget child : children)
        {
            if (child == null)
            {
                continue;
            }
            String name = normalizePlayerName(child.getName());
            if (name != null && !"-".equals(name))
            {
                into.add(name);
            }
        }
    }

    /**
     * Accumulates the proximity/PartyService fallback roster while inside a
     * raid. Inside an instanced raid the only nearby players are the team, so
     * this is a reasonable stand-in when the authoritative source is
     * unavailable — but it is never preferred over it.
     */
    private void captureFallbackRoster()
    {
        Player localPlayer = client.getLocalPlayer();
        String localName = localPlayer != null ? normalizePlayerName(localPlayer.getName()) : null;

        if (localPlayer != null && localPlayer.getWorldLocation() != null)
        {
            WorldPoint center = localPlayer.getWorldLocation();
            WorldView topLevel = client.getTopLevelWorldView();
            if (topLevel != null)
            {
                ScanStats ignored = new ScanStats();
                collectNamesFromWorldView(topLevel, center, ROSTER_SCAN_RADIUS_TILES, localPlayer, fallbackRoster, ignored);
                for (WorldView subWorldView : topLevel.worldViews())
                {
                    collectNamesFromWorldView(subWorldView, center, ROSTER_SCAN_RADIUS_TILES, localPlayer, fallbackRoster, ignored);
                }
            }
        }

        // RuneLite party members often mirror the raid team, but the party is
        // opt-in and can include non-raiders — fallback only, never authoritative.
        if (partyService.isInParty())
        {
            for (PartyMember member : partyService.getMembers())
            {
                String memberName = normalizePlayerName(member.getDisplayName());
                if (memberName != null && !memberName.equals(localName))
                {
                    fallbackRoster.add(memberName);
                }
            }
        }
    }

    public List<String> getNearbyPlayerNames(int radiusTiles)
    {
        return getNearbyPlayerTrace(radiusTiles).getNearbyPlayers();
    }

    /** Participant trace with no submission source: plain proximity scan. */
    public NearbyPlayerTrace getNearbyPlayerTrace(int radiusTiles)
    {
        return getParticipantsTrace(null, radiusTiles);
    }

    /**
     * Resolves the participants to attach to a submission from
     * {@code sourceName} (drop {@code source} / PB {@code boss_name}).
     * Raid sources get the authoritative raid roster; everything else gets a
     * proximity scan with no raid-roster merge.
     */
    public NearbyPlayerTrace getParticipantsTrace(String sourceName, int radiusTiles)
    {
        if (client.isClientThread())
        {
            return collectParticipantsTrace(sourceName, radiusTiles);
        }

        CompletableFuture<NearbyPlayerTrace> future = new CompletableFuture<>();
        clientThread.invoke(() ->
        {
            try
            {
                future.complete(collectParticipantsTrace(sourceName, radiusTiles));
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

    private NearbyPlayerTrace collectParticipantsTrace(String sourceName, int radiusTiles)
    {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || localPlayer.getWorldLocation() == null)
        {
            return NearbyPlayerTrace.empty(Math.max(1, radiusTiles), "local player unavailable");
        }

        WorldPoint center = localPlayer.getWorldLocation();
        int effectiveRadius = Math.max(1, radiusTiles);
        String localName = normalizePlayerName(localPlayer.getName());
        String submissionRaidType = raidTypeForSource(sourceName);

        int toaTeamCount = getToaTeamCount();
        int tobTeamCount = getTobTeamCount();
        int coxTeamCount = getCoxTeamCount();
        boolean inRaidContext = (toaTeamCount > 1) || (tobTeamCount > 1) || (coxTeamCount > 1) || currentRaidType() != null;

        Set<String> names = new LinkedHashSet<>();
        String rosterSource;
        boolean localPlayerInRoster = false;
        ScanStats scanStats = new ScanStats();

        if (submissionRaidType != null)
        {
            // Raid submission: participants come from the authoritative roster
            // only. Start from the accumulated roster (survives the varbit
            // reset at completion) and merge a live read, since the ToB/ToA
            // varcstrings and CoX sidepanel usually remain populated at
            // loot-chest time.
            Set<String> roster = new LinkedHashSet<>();
            if (submissionRaidType.equals(activeRaidType))
            {
                roster.addAll(authoritativeRoster);
            }
            captureAuthoritativeRoster(submissionRaidType, roster);
            if (submissionRaidType.equals(activeRaidType))
            {
                authoritativeRoster.addAll(roster);
            }

            localPlayerInRoster = localName != null && roster.contains(localName);
            if (localName != null)
            {
                roster.remove(localName);
            }

            if (!roster.isEmpty())
            {
                names.addAll(roster);
                rosterSource = "authoritative";
            }
            else
            {
                // Authoritative source yielded nothing (solo raid, or capture
                // never ran this raid): fall back to the accumulated proximity
                // roster plus a live scan so the participant list isn't empty.
                if (submissionRaidType.equals(activeRaidType))
                {
                    names.addAll(fallbackRoster);
                }
                int fallbackRadius = Math.max(effectiveRadius, ROSTER_SCAN_RADIUS_TILES);
                scanWorldViews(center, fallbackRadius, localPlayer, names, scanStats);
                rosterSource = "proximity-fallback";
            }
        }
        else
        {
            // Non-raid submission: plain proximity scan. The raid roster is
            // deliberately NOT merged here.
            scanWorldViews(center, effectiveRadius, localPlayer, names, scanStats);
            rosterSource = "proximity";
        }

        String raidType = submissionRaidType != null
            ? submissionRaidType
            : (activeRaidType != null ? activeRaidType : "none");

        NearbyPlayerTrace trace = new NearbyPlayerTrace(
            new ArrayList<>(names),
            effectiveRadius,
            localName,
            center,
            inRaidContext,
            raidType,
            sourceName,
            rosterSource,
            localPlayerInRoster,
            toaTeamCount,
            tobTeamCount,
            coxTeamCount,
            partyService.isInParty(),
            partyService.isInParty() ? partyService.getMembers().size() : 0,
            scanStats.worldViewsScanned,
            scanStats.playersSeen,
            scanStats.playersWithinRadius,
            scanStats.playersAdded,
            authoritativeRoster.size(),
            fallbackRoster.size(),
            System.currentTimeMillis(),
            null
        );
        DebugLogger.log("[NearbyPlayerTracker] " + trace.toDebugSummary());
        return trace;
    }

    private void scanWorldViews(WorldPoint center, int radiusTiles, Player localPlayer, Set<String> names, ScanStats scanStats)
    {
        WorldView topLevel = client.getTopLevelWorldView();
        if (topLevel == null)
        {
            return;
        }
        collectNamesFromWorldView(topLevel, center, radiusTiles, localPlayer, names, scanStats);
        for (WorldView subWorldView : topLevel.worldViews())
        {
            collectNamesFromWorldView(subWorldView, center, radiusTiles, localPlayer, names, scanStats);
        }
    }

    private void collectNamesFromWorldView(
        WorldView worldView,
        WorldPoint center,
        int radiusTiles,
        Player localPlayer,
        Set<String> names,
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

            String normalizedName = normalizePlayerName(playerName);
            if (normalizedName != null && names.add(normalizedName))
            {
                scanStats.playersAdded++;
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static final int THEATRE_OF_BLOOD_STATE = Varbits.THEATRE_OF_BLOOD;

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
        private final String sourceName;
        private final String rosterSource;
        private final boolean localPlayerInRoster;
        private final int toaTeamCount;
        private final int tobTeamCount;
        private final int coxTeamCount;
        private final boolean inParty;
        private final int partySize;
        private final int worldViewsScanned;
        private final int playersSeen;
        private final int playersWithinRadius;
        private final int uniquePlayersAdded;
        private final int authoritativeRosterSize;
        private final int fallbackRosterSize;
        private final long capturedAtMs;
        private final String fallbackReason;

        private NearbyPlayerTrace(
            List<String> nearbyPlayers,
            int radiusTiles,
            String localPlayer,
            WorldPoint localWorldPoint,
            boolean inRaidContext,
            String raidType,
            String sourceName,
            String rosterSource,
            boolean localPlayerInRoster,
            int toaTeamCount,
            int tobTeamCount,
            int coxTeamCount,
            boolean inParty,
            int partySize,
            int worldViewsScanned,
            int playersSeen,
            int playersWithinRadius,
            int uniquePlayersAdded,
            int authoritativeRosterSize,
            int fallbackRosterSize,
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
            this.sourceName = sourceName;
            this.rosterSource = rosterSource;
            this.localPlayerInRoster = localPlayerInRoster;
            this.toaTeamCount = toaTeamCount;
            this.tobTeamCount = tobTeamCount;
            this.coxTeamCount = coxTeamCount;
            this.inParty = inParty;
            this.partySize = partySize;
            this.worldViewsScanned = worldViewsScanned;
            this.playersSeen = playersSeen;
            this.playersWithinRadius = playersWithinRadius;
            this.uniquePlayersAdded = uniquePlayersAdded;
            this.authoritativeRosterSize = authoritativeRosterSize;
            this.fallbackRosterSize = fallbackRosterSize;
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
                null,
                "none",
                false,
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
                + ", sourceName=" + (sourceName != null ? sourceName : "none")
                + ", rosterSource=" + rosterSource
                + ", localPlayerInRoster=" + localPlayerInRoster
                + ", toaTeamCount=" + toaTeamCount
                + ", tobTeamCount=" + tobTeamCount
                + ", coxTeamCount=" + coxTeamCount
                + ", inParty=" + inParty
                + ", partySize=" + partySize
                + ", worldViewsScanned=" + worldViewsScanned
                + ", playersSeen=" + playersSeen
                + ", playersWithinRadius=" + playersWithinRadius
                + ", uniquePlayersAdded=" + uniquePlayersAdded
                + ", authoritativeRosterSize=" + authoritativeRosterSize
                + ", fallbackRosterSize=" + fallbackRosterSize
                + ", nearbyPlayers=" + nearbyPlayers
                + ", capturedAtMs=" + capturedAtMs
                + (fallbackReason != null ? ", fallbackReason=" + fallbackReason : "");
        }
    }
}
