package io.droptracker;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

import net.runelite.client.config.ConfigItem;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins every config keyName.
 * <p>
 * A player's saved setting is looked up by keyName, so renaming one silently
 * resets that option for everyone who had configured it — there is no
 * migration and no warning, the old value simply stops being found. Section,
 * position, display name and description are all safe to change and are
 * deliberately not asserted here.
 * <p>
 * If this fails you either renamed a key (don't — keep the old keyName and
 * change the display name instead) or added/removed one, in which case update
 * the list below and make sure it's a deliberate choice.
 */
public class DropTrackerConfigKeyNamesTest {

    /** Every keyName the plugin is allowed to read or write. */
    private static final Set<String> EXPECTED = new TreeSet<>(Set.of(
        // Tracking
        "lootEmbeds", "pbEmbeds", "clogEmbeds", "caEmbeds", "petEmbeds",
        "levelEmbed", "xpMilestoneEmbeds", "questsEmbed", "deathEmbeds",
        "diaryEmbeds", "trackActivities",
        // Screenshots
        "screenshots", "screenshotValue", "screenshotUntradeables",
        "minLevelToScreenshot", "hideWhispers", "compressImages",
        "imageCompressionThresholdKb",
        // Events
        "eventNotifications", "eventDisplayMode", "eventTaskProgressNotifications",
        "eventHudDetail",
        // Clan Chat
        "relayClanBroadcasts", "relayClanChat", "receiveDiscordChat",
        // Advanced
        "useApi", "receiveInGameMessages", "syncAccountState",
        "uploadCharacterModel", "showSidePanel", "debugLogging",
        // Retired from the UI, kept so existing values still apply
        "trackExperience", "trackMta", "trackAgilityPyramid", "trackTrawling",
        "sendLoadoutWithPbs", "eventImportantPopupsOnly", "pollUpdates",
        // Internal state
        "pinnedEventId", "lastVersionNotified", "lastAccountName",
        "customApiEndpoint", "lastAccountHash"
    ));

    @Test
    public void configKeyNamesAreUnchanged() {
        Set<String> actual = new TreeSet<>();
        for (Method m : DropTrackerConfig.class.getMethods()) {
            ConfigItem item = m.getAnnotation(ConfigItem.class);
            if (item != null) {
                actual.add(item.keyName());
            }
        }
        assertEquals("config keyNames changed — a rename resets that setting for "
            + "every player who had configured it", EXPECTED, actual);
    }

    @Test
    public void everySettingHasAHomeOrIsDeliberatelyHidden() {
        // A visible item with no section renders loose at the top of the panel,
        // which is how options quietly escape the four-section layout.
        for (Method m : DropTrackerConfig.class.getMethods()) {
            ConfigItem item = m.getAnnotation(ConfigItem.class);
            if (item == null || item.hidden()) {
                continue;
            }
            assertEquals(item.keyName() + " is visible but has no section",
                false, item.section().isEmpty());
        }
    }
}
