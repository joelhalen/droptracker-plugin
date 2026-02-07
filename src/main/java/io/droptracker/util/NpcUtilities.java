package io.droptracker.util;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.apache.commons.lang3.tuple.Pair;

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
            "Eldric the Ice King", "Dusk");
    public static final Set<String> LONG_TICK_NPC_NAMES = Set.of("Grotesque Guardians", "Yama");

    public static final Pattern PRIMARY_REGEX = Pattern.compile(
            "Your (?<key>[\\w\\s:'-]+) (?<type>kill|chest|completion|success) count is:? (?<value>[\\d,]+)");
    public static final Pattern SECONDARY_REGEX = Pattern
            .compile("Your (?<type>kill|chest|completed) (?<key>[\\w\\s:]+) count is:? (?<value>[\\d,]+)");

    @SuppressWarnings("null")
    public static String getStandardizedSource(LootReceived event, DropTrackerPlugin plugin) {
        if (isCorruptedGauntlet(event, plugin)) {
            System.out.println("[DT-DEBUG] getStandardizedSource: corrupted gauntlet detected -> " + CG_NAME);
            return CG_NAME;
        }
        if (plugin.lastDrop == null) {
            System.out.println("[DT-DEBUG] getStandardizedSource: lastDrop=null, using eventName=" + event.getName());
            return event.getName();
        } else if (shouldUseChatName(event, plugin) && plugin.lastDrop.getSource() != null) {
            System.out.println("[DT-DEBUG] getStandardizedSource: using chatName=" + plugin.lastDrop.getSource() + " for event=" + event.getName());
            return plugin.lastDrop.getSource(); // distinguish entry/expert/challenge modes
        }
        System.out.println("[DT-DEBUG] getStandardizedSource: fallthrough, using eventName=" + event.getName() + " (lastDrop.source=" + plugin.lastDrop.getSource() + ")");
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
                } catch (NumberFormatException e) {
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
                } catch (NumberFormatException e) {
                }
            }
        }
        return Optional.empty();
    }

    @Nullable
    private static String parsePrimaryBoss(String boss, String type) {
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

    private static String parseSecondary(String boss) {
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
