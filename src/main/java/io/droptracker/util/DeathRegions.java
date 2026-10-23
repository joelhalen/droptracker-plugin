package io.droptracker.util;

import java.util.*;

/**
 * Minigames where the other players are the opponents, so a player can be
 * the killer outside the Wilderness. Whether a death cost anything is decided
 * on the server (utils/death_regions.py), which holds the full region policy.
 */
public final class DeathRegions {

    private DeathRegions() {
    }

    private static final Set<Integer> PVP_MINIGAMES = new HashSet<>(Arrays.asList(
        9520, 9620, // Castle Wars
        12621, 12622, 12623, 13130, 13131, 13133, 13134, 13135, 13386, 13387, 13390, 13641, 13642, 13643,
        13644, 13645, 13646, 13647, 13899, 13900, 14155, 14156, // Clan Wars
        13362, 13363, // Emir's Arena
        13658, 13659, 13660, 13914, 13915, 13916, 13918, 13919, 13920, 14174, 14175, 14176, 14430, 14431,
        14432, // Last Man Standing
        8493, 8748, 8749, 9005)); // Soul Wars

    public static boolean isPvpMinigame(int regionId) {
        return PVP_MINIGAMES.contains(regionId);
    }
}
