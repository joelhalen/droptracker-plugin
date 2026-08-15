package io.droptracker.util;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import org.jetbrains.annotations.VisibleForTesting;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Classifies a death location as safe (no items lost) or dangerous.
 *
 * This is policy, deliberately kept out of {@link RegionNameRegistry} so the
 * name data there stays a verbatim, regenerable extract. The region sets and
 * the ordering of the checks mirror Dink's {@code WorldUtils#getDangerLevel},
 * which is the most complete public treatment of this question.
 *
 * Not modelled here (both grant a one-off daily resurrection that makes an
 * otherwise-safe death dangerous, and both need extra varbit reads): the
 * Karamja elite diary in the Fight Caves and the Western Provinces elite diary
 * at Zulrah. Deaths there are reported as safe.
 */
public final class DeathRegions {

    public enum Danger {
        SAFE,
        DANGEROUS
    }

    private DeathRegions() {
    }

    private static final Set<Integer> BARBARIAN_ASSAULT = regions(7508, 7509, 10322);
    private static final Set<Integer> CASTLE_WARS = regions(9520, 9620);
    private static final Set<Integer> CHAMBERS_OF_XERIC = regions(12889, 13136, 13137, 13138, 13139, 13140,
            13141, 13145, 13393, 13394, 13395, 13396, 13397, 13401);
    private static final Set<Integer> CLAN_WARS = regions(12621, 12622, 12623, 13130, 13131, 13133, 13134,
            13135, 13386, 13387, 13390, 13641, 13642, 13643, 13644, 13645, 13646, 13647, 13899, 13900,
            14155, 14156);
    private static final Set<Integer> GAUNTLET = regions(7512, 7768, 12127);
    private static final Set<Integer> LAST_MAN_STANDING = regions(13658, 13659, 13660, 13914, 13915, 13916,
            13918, 13919, 13920, 14174, 14175, 14176, 14430, 14431, 14432);
    private static final Set<Integer> PLAYER_OWNED_HOUSE = regions(7257, 7534, 7535, 7790, 7791, 8046, 8047,
            8302, 8303);
    private static final Set<Integer> SOUL_WARS = regions(8493, 8748, 8749, 9005);
    private static final Set<Integer> TOMBS_OF_AMASCUT = regions(14160, 14162, 14164, 14674, 14676, 15184,
            15186, 15188, 15696, 15698, 15700);
    private static final Set<Integer> THEATRE_OF_BLOOD = regions(12611, 12612, 12613, 12867, 12869, 13122,
            13123, 13125, 13379);

    private static final int CLAN_HALL = 6997;
    private static final int CREATURE_GRAVEYARD = 13462;
    private static final int NIGHTMARE_ZONE = 9033;
    private static final int PEST_CONTROL_LANDER = 10536;
    private static final int TZHAAR_FIGHT_PIT = 9552;

    /* Deliberately absent from the safe sets: the Inferno (9043) and the
     * TzHaar Fight Caves (9551). Both are technically safe, but losing a run
     * there is the whole point of announcing it. */

    private static final int ACCOUNT_TYPE_HARDCORE_IRONMAN = 3;
    private static final int ACCOUNT_TYPE_HARDCORE_GROUP_IRONMAN = 5;

    private static Set<Integer> regions(int... ids) {
        Set<Integer> set = new HashSet<>(ids.length);
        for (int id : ids) {
            set.add(id);
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * @param client   the game client
     * @param regionId an instance-corrected map region id
     * @return whether items are at risk in this location
     */
    public static Danger classify(Client client, int regionId) {
        // Every PvM death is dangerous on a hardcore account — the status is
        // what is actually lost, so nothing below can downgrade it to safe.
        if (isHardcore(client)) {
            return Danger.DANGEROUS;
        }

        if (isSafeRegion(regionId) || isPestControl(client, regionId)) {
            return Danger.SAFE;
        }

        return Danger.DANGEROUS;
    }

    /**
     * The account-independent half of {@link #classify}: whether this region is
     * somewhere a non-hardcore death costs no items.
     *
     * Split out so the region policy can be tested directly — the rest of the
     * classification needs a live client for the account type and the Pest
     * Control status overlay.
     */
    public static boolean isSafeRegion(int regionId) {
        // Items cannot be carried in or out of these, so a death costs the run
        // rather than the inventory. ToA/ToB/CoX deaths inside a still-running
        // raid are likewise recoverable by the rest of the team.
        if (GAUNTLET.contains(regionId)
                || TOMBS_OF_AMASCUT.contains(regionId)
                || THEATRE_OF_BLOOD.contains(regionId)
                || CHAMBERS_OF_XERIC.contains(regionId)) {
            return true;
        }

        return BARBARIAN_ASSAULT.contains(regionId)
                || CASTLE_WARS.contains(regionId)
                || CLAN_WARS.contains(regionId)
                || LAST_MAN_STANDING.contains(regionId)
                || PLAYER_OWNED_HOUSE.contains(regionId)
                || SOUL_WARS.contains(regionId)
                || regionId == CLAN_HALL
                || regionId == CREATURE_GRAVEYARD
                || regionId == NIGHTMARE_ZONE
                || regionId == PEST_CONTROL_LANDER
                || regionId == TZHAAR_FIGHT_PIT;
    }

    public static boolean isSafe(Client client, int regionId) {
        return classify(client, regionId) == Danger.SAFE;
    }

    private static boolean isHardcore(Client client) {
        // VarbitID.IRONMAN is the account-type varbit: 0 normal, 1 ironman,
        // 2 ultimate, 3 hardcore, 4 group, 5 hardcore group, 6 unranked group.
        int accountType = client.getVarbitValue(VarbitID.IRONMAN);
        return accountType == ACCOUNT_TYPE_HARDCORE_IRONMAN
                || accountType == ACCOUNT_TYPE_HARDCORE_GROUP_IRONMAN;
    }

    /**
     * The Pest Control islands are ordinary-looking regions, so the lander
     * region alone does not cover a death during a game. The status overlay is
     * only built while a game is running, which is exactly the window that
     * matters.
     */
    private static boolean isPestControl(Client client, int regionId) {
        if (regionId == PEST_CONTROL_LANDER) {
            return true;
        }
        Widget widget = client.getWidget(InterfaceID.PestStatusOverlay.PEST_STATUS_PORT2);
        return widget != null && !widget.isHidden();
    }

    /* Region ids referenced by the tests, so a renumbering shows up there
     * rather than silently reclassifying deaths. */
    @VisibleForTesting
    static final int INFERNO = 9043;
    @VisibleForTesting
    static final int TZHAAR_FIGHT_CAVE = 9551;
}
