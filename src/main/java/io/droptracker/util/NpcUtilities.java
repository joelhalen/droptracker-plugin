package io.droptracker.util;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import net.runelite.api.gameval.NpcID;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.VisibleForTesting;

import io.droptracker.DropTrackerPlugin;

import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;

public class NpcUtilities {

    public static final String GAUNTLET_NAME = "Gauntlet", GAUNTLET_BOSS = "Crystalline Hunllef";
    public static final String CG_NAME = "Corrupted Gauntlet", CG_BOSS = "Corrupted Hunllef";

    public static final String TOA = "Tombs of Amascut";
    public static final String TOB = "Theatre of Blood";
    public static final String COX = "Chambers of Xeric";

    public static final Set<String> SPECIAL_NPC_NAMES = Set.of("The Whisperer", "Araxxor", "Branda the Fire Queen",
            "Eldric the Ice King", "Dusk", "Corrupted Hunllef", "Crystalline Hunllef","Maggot King");
    public static final Set<String> LONG_TICK_NPC_NAMES = Set.of("Grotesque Guardians", "Yama");

    /* Use a list for all special npc names, since Sailing added a number of them */
    public static final Set<Integer> SERVER_LOOT_NPC_IDS = Set.of(
                NpcID.YAMA,
                NpcID.HESPORI,
                NpcID.SAILING_BULL_SHARK_DEAD,
                NpcID.SAILING_HAMMERHEAD_SHARK_DEAD,
                NpcID.SAILING_TIGER_SHARK_DEAD,
                NpcID.SAILING_GREAT_WHITE_SHARK_DEAD,
                NpcID.SAILING_NARWHAL_DEAD,
                NpcID.SAILING_ORCA_DEAD,
                NpcID.SAILING_PYGMY_KRAKEN_DEAD,
                NpcID.SAILING_SPINED_KRAKEN_DEAD,
                NpcID.SAILING_ARMOURED_KRAKEN_DEAD,
                NpcID.SAILING_VAMPYRE_KRAKEN_DEAD,
                NpcID.SAILING_EAGLE_RAY_DEAD,
                NpcID.SAILING_BUTTERFLY_RAY_DEAD,
                NpcID.SAILING_STINGRAY_DEAD,
                NpcID.SAILING_MANTA_RAY_DEAD,
                NpcID.SAILING_OSPREY_DEAD,
                NpcID.SAILING_ALBATROSS_DEAD,
                NpcID.SAILING_FRIGATEBIRD_DEAD,
                NpcID.SAILING_TERN_DEAD,
                NpcID.SAILING_SEA_MOGRE_DEAD,
                NpcID.SAILING_DOLPHIN_DEAD,
                NpcID.SAILING_VEILED_KRAKEN_DEAD,
                NpcID.MAGGOT_KING,
                NpcID.MAGGOT_KING_CORPSE
        );

    /* Canonical encounter names that a multi-part boss's sub-NPCs are remapped
     * to (see canonicalizeSpecialSource). Loot for these can arrive via more
     * than one RuneLite loot event, so they need cross-handler de-duplication. */
    public static final Set<String> REMAP_TARGET_NAMES = Set.of(
            "Royal Titans", "Grotesque Guardians", "The Corrupted Gauntlet", "The Gauntlet");

    /**
     * Collapse the raw sub-NPC name of a multi-part boss to its canonical
     * encounter name (e.g. "Dusk" -> "Grotesque Guardians"). Names without a
     * remapping are returned unchanged. Centralises the remapping that used to
     * live inline in {@code DropHandler.onLootReceived} so every loot path
     * (NpcLootReceived / ServerNpcLoot / LootReceived) agrees on one name — a
     * prerequisite for reliably de-duplicating the same kill across handlers.
     */
    public static String canonicalizeSpecialSource(String name) {
        if (name == null) {
            return null;
        }
        switch (name) {
            case "Branda the Fire Queen":
            case "Eldric the Ice King":
                return "Royal Titans";
            case "Dusk":
                return "Grotesque Guardians";
            case "Corrupted Hunllef":
                return "The Corrupted Gauntlet";
            case "Crystalline Hunllef":
                return "The Gauntlet";
            default:
                return name;
        }
    }

    /**
     * Whether loot from this (already canonicalised) source can be delivered by
     * more than one RuneLite loot event for a single kill — the bosses in
     * {@link #SPECIAL_NPC_NAMES} (Whisperer/Araxxor/Maggot King and the raw
     * sub-NPC names) plus the {@link #REMAP_TARGET_NAMES} they collapse to.
     * These are the only sources that need duplicate submissions suppressed;
     * ordinary NPCs (which can legitimately be multi-killed in one tick with
     * identical loot) are intentionally excluded.
     */
    public static boolean isMultiPathLootSource(String canonicalName) {
        return canonicalName != null
                && (SPECIAL_NPC_NAMES.contains(canonicalName) || REMAP_TARGET_NAMES.contains(canonicalName));
    }

    public static final Pattern PRIMARY_REGEX = Pattern.compile(
            "Your (?<key>[\\w\\s:'-]+) (?<type>kill|chest|completion|success) count is:? (?<value>[\\d,]+)");
    public static final Pattern SECONDARY_REGEX = Pattern
            .compile("Your (?<type>kill|chest|completed) (?<key>[\\w\\s:]+) count is:? (?<value>[\\d,]+)");

    @SuppressWarnings("null")
    public static String getStandardizedSource(LootReceived event, DropTrackerPlugin plugin) {
        if (isCorruptedGauntlet(event, plugin)) {
            return CG_NAME;
        }
        if (plugin.lastDrop == null) {
            return event.getName();
        } else if (shouldUseChatName(event, plugin) && plugin.lastDrop.getSource() != null) {
            return plugin.lastDrop.getSource(); // distinguish entry/expert/challenge modes
        }
        return event.getName();
    }

    @SuppressWarnings("null")
    private static boolean isCorruptedGauntlet(LootReceived event, DropTrackerPlugin plugin) {
        return event.getType() == LootRecordType.EVENT && plugin.lastDrop != null
                && "The Gauntlet".equals(event.getName())
                && (CG_NAME.equals(plugin.lastDrop.getSource()) || CG_BOSS.equals(plugin.lastDrop.getSource()));
    }

    private static boolean shouldUseChatName(LootReceived event, DropTrackerPlugin plugin) {
        assert plugin.lastDrop != null;
        String lastSource = plugin.lastDrop.getSource();
        Predicate<String> coincides = source -> source.equals(event.getName()) && lastSource.startsWith(source);
        return coincides.test(TOA) || coincides.test(TOB) || coincides.test(COX);
    }

    public static Optional<Pair<String, Integer>> parseBoss(String message, DropTrackerPlugin plugin) {
        Matcher primary = PRIMARY_REGEX.matcher(message);
        Matcher secondary = SECONDARY_REGEX.matcher(message);
        Pair<String, Integer> mostRecentNpcData = null;

        if (primary.find()) {
            String boss = parsePrimaryBoss(primary.group("key"), primary.group("type"));
            String count = primary.group("value");
            if (boss != null) {
                try {
                    int killCount = Integer.parseInt(count.replace(",", ""));
                    mostRecentNpcData = Pair.of(boss, killCount);
                    plugin.ticksSinceNpcDataUpdate = 0;
                    return Optional.of(mostRecentNpcData);
                } catch (NumberFormatException ignored) {
                }
            }
        } else if (secondary.find()) {
            String key = parseSecondary(secondary.group("key"));
            String value = secondary.group("value");
            if (key != null) {
                try {
                    int killCount = Integer.parseInt(value.replace(",", ""));
                    mostRecentNpcData = Pair.of(key, killCount);
                    plugin.ticksSinceNpcDataUpdate = 0;
                    return Optional.of(mostRecentNpcData);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return Optional.empty();
    }

    @Nullable
    @VisibleForTesting
    static String parsePrimaryBoss(String boss, String type) {
        switch (type.toLowerCase()) {
            case "chest":
                if ("Barrows".equalsIgnoreCase(boss))
                    return boss;
                if ("Lunar".equals(boss))
                    return boss + " " + type;
                return null;

            case "completion":
                if (GAUNTLET_NAME.equalsIgnoreCase(boss))
                    return GAUNTLET_BOSS;
                if (CG_NAME.equalsIgnoreCase(boss))
                    return CG_BOSS;
                return null;

            case "kill":
                return boss;

            case "success":
                return boss;

            default:
                return null;
        }
    }

    @VisibleForTesting
    static String parseSecondary(String boss) {
        if (boss == null || "Wintertodt".equalsIgnoreCase(boss))
            return boss;

        int modeSeparator = boss.lastIndexOf(':');
        String raid = modeSeparator > 0 ? boss.substring(0, modeSeparator) : boss;
        if (raid.equalsIgnoreCase("Theatre of Blood")
                || raid.equalsIgnoreCase("Tombs of Amascut")
                || raid.equalsIgnoreCase("Chambers of Xeric")
                || raid.equalsIgnoreCase("Chambers of Xeric Challenge Mode"))
            return boss;

        return null;
    }

}
