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

/**
 * Static utility methods and constants for NPC/boss name standardization and kill-count parsing.
 *
 * <p><b>Name standardization:</b> {@link #getStandardizedSource} resolves the canonical source name
 * for a loot event, handling edge cases such as Corrupted Gauntlet (reported as "The Gauntlet" by
 * the loot-tracker API) and raid variants (ToA entry/expert, ToB hard mode, CoX challenge mode)
 * whose mode-specific names arrive via chat before the loot event.</p>
 *
 * <p><b>Kill-count parsing:</b> {@link #parseBoss} matches two game-message formats:
 * <ul>
 *   <li>{@link #PRIMARY_REGEX} — {@code "Your <boss> kill count is: N"}</li>
 *   <li>{@link #SECONDARY_REGEX} — {@code "Your kill <boss> count is: N"}</li>
 * </ul>
 * Results are passed back to {@link io.droptracker.service.KCService} for caching.</p>
 */
public class NpcUtilities {

    /** Display name and boss NPC name for The Gauntlet (normal variant). */
    public static final String GAUNTLET_NAME = "Gauntlet", GAUNTLET_BOSS = "Crystalline Hunllef";

    /** Display name and boss NPC name for The Corrupted Gauntlet. */
    public static final String CG_NAME = "Corrupted Gauntlet", CG_BOSS = "Corrupted Hunllef";

    /** Canonical prefix used in the raid variant names sent from chat messages. */
    public static final String TOA = "Tombs of Amascut";
    public static final String TOB = "Theatre of Blood";
    public static final String COX = "Chambers of Xeric";

    /**
     * NPCs that require the chat-message kill-count because their loot event does not carry
     * a reliable NPC reference (e.g. The Whisperer), or because variant identification
     * is needed (e.g. Araxxor P4).
     */
    public static final Set<String> SPECIAL_NPC_NAMES = Set.of("The Whisperer", "Araxxor", "Branda the Fire Queen",
            "Eldric the Ice King", "Dusk");

    /**
     * NPCs whose loot events can arrive up to several ticks after the kill; extra tick
     * tolerance is applied when processing drops from these sources.
     */
    public static final Set<String> LONG_TICK_NPC_NAMES = Set.of("Grotesque Guardians", "Yama");

    /**
     * Matches the primary kill-count game message format:
     * {@code "Your <boss> kill count is: N"} — covers most standard NPCs.
     * Named groups: {@code key} (boss/activity name), {@code type} (kill/chest/completion/success),
     * {@code value} (the numeric count, possibly with commas).
     */
    public static final Pattern PRIMARY_REGEX = Pattern.compile(
            "Your (?<key>[\\w\\s:'-]+) (?<type>kill|chest|completion|success) count is:? (?<value>[\\d,]+)");

    /**
     * Matches the secondary kill-count game message format:
     * {@code "Your kill <boss> count is: N"} — used by raids and some activities.
     * Named groups: {@code type}, {@code key}, {@code value}.
     */
    public static final Pattern SECONDARY_REGEX = Pattern
            .compile("Your (?<type>kill|chest|completed) (?<key>[\\w\\s:]+) count is:? (?<value>[\\d,]+)");

    /**
     * Returns the canonical source name for a {@link LootReceived} event.
     *
     * <p>Handles three cases:
     * <ol>
     *   <li>Corrupted Gauntlet — the loot API reports the name as "The Gauntlet"; this
     *       method detects the CG variant from {@code plugin.lastDrop} and returns
     *       {@link #CG_NAME} instead.</li>
     *   <li>Raid variants (ToA/ToB/CoX) — the mode-specific name (e.g. "Theatre of Blood Hard Mode")
     *       was parsed from chat and stored in {@code plugin.lastDrop}; returned in preference
     *       to the generic event name.</li>
     *   <li>All other NPCs — the event's own {@link LootReceived#getName()} is used.</li>
     * </ol>
     * </p>
     *
     * @param event  the loot received event
     * @param plugin the main plugin instance (provides {@code lastDrop} context)
     * @return the standardized source name suitable for use as a kill-count cache key
     */
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

    /**
     * Attempts to extract a boss name and kill count from a game chat message by trying
     * {@link #PRIMARY_REGEX} then {@link #SECONDARY_REGEX}.
     *
     * <p>On a successful match, {@code plugin.ticksSinceNpcDataUpdate} is reset to 0 so that
     * {@link io.droptracker.events.DropHandler} can correlate the kill-count with the
     * immediately following loot event.</p>
     *
     * @param message the sanitized game chat message text
     * @param plugin  the main plugin instance (updated on match)
     * @return an {@link Optional} containing a {@code (bossName, killCount)} pair, or empty
     */
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
