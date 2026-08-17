package io.droptracker;

import io.droptracker.models.EventDisplayMode;
import io.droptracker.models.EventHudDetail;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * Four sections: what we track, when we screenshot, events, and everything
 * advanced. Clan Chat keeps its own section because it relays other people's
 * messages and deserves to stay obvious.
 * <p>
 * <b>keyName is a data migration.</b> A player's stored value is looked up by
 * keyName, so renaming one silently resets that setting for everybody who had
 * configured it. Section, position, display name and description are all free
 * to change; keyName is not. Options retired from the UI keep their keyName and
 * become {@code hidden = true} so the value still applies and existing code
 * still reads it.
 */
@ConfigGroup(DropTrackerConfig.GROUP)
public interface DropTrackerConfig extends Config {

    String GROUP = "droptracker";

    // ==================== Tracking ====================

    @ConfigSection(
        name = "Tracking",
        description = "What the DropTracker records and sends for you",
        position = 1,
        closedByDefault = false
    )
    String trackingSection = "Tracking";

    @ConfigItem(
        keyName = "lootEmbeds",
        name = "Drops",
        description = "Should we send your drops to the DropTracker?",
        position = 1,
        section = trackingSection
    )
    default boolean lootEmbeds() {
        return true;
    }

    @ConfigItem(
        keyName = "pbEmbeds",
        name = "Personal Bests",
        description = "Do you want DropTracker to track your PBs?",
        position = 2,
        section = trackingSection
    )
    default boolean pbEmbeds() {
        return true;
    }

    @ConfigItem(
        keyName = "clogEmbeds",
        name = "Collection Logs",
        description = "<html>Should we send new collection log slot unlocks to the DropTracker?<br>"
            + "<b>Note</b>: Requires Collection Log Notification and popup Enabled in OSRS settings</html>",
        position = 3,
        section = trackingSection
    )
    default boolean clogEmbeds() {
        return true;
    }

    @ConfigItem(
        keyName = "caEmbeds",
        name = "Combat Achievements",
        description = "Should we send your Combat Achievements to the DropTracker?",
        position = 4,
        section = trackingSection
    )
    default boolean caEmbeds() {
        return true;
    }

    @ConfigItem(
        keyName = "petEmbeds",
        name = "Pets",
        description = "Do you want DropTracker to track your Pets?",
        position = 5,
        section = trackingSection
    )
    default boolean petEmbeds() {
        return true;
    }

    @ConfigItem(
        keyName = "levelEmbed",
        name = "Levels",
        description = "Do you want to send level gains to the DropTracker",
        position = 6,
        section = trackingSection
    )
    default boolean levelEmbed() {
        return true;
    }

    @ConfigItem(
        keyName = "xpMilestoneEmbeds",
        name = "XP Milestones",
        description = "<html>Send a notification when a level-99 skill crosses an XP milestone.<br />"
            + "Your group's settings determine which milestones are announced (default: every 25M XP).</html>",
        position = 7,
        section = trackingSection
    )
    default boolean xpMilestoneEmbeds() {
        return true;
    }

    @ConfigItem(
        keyName = "questsEmbed",
        name = "Quests",
        description = "Do you want to send quest completions to the DropTracker?",
        position = 8,
        section = trackingSection
    )
    default boolean questsEmbed() {
        return true;
    }

    @ConfigItem(
        keyName = "deathEmbeds",
        name = "Deaths",
        description = "Do you want to send player deaths to the DropTracker?",
        position = 9,
        section = trackingSection
    )
    default boolean deathEmbeds() {
        return true;
    }

    @ConfigItem(
        keyName = "diaryEmbeds",
        name = "Achievement Diaries",
        description = "Do you want to send achievement diary completions to the DropTracker?",
        position = 10,
        section = trackingSection
    )
    default boolean diaryEmbeds() {
        return true;
    }

    @ConfigItem(
        keyName = "trackActivities",
        name = "Activity Tracking",
        description = "<html>Track items from activities RuneLite's loot tracker misses:<br />"
            + "Mage Training Arena reward purchases, Agility Pyramid tops and<br />"
            + "deep sea trawling catches.</html>",
        position = 11,
        section = trackingSection
    )
    default boolean trackActivities() {
        return true;
    }

    // ==================== Screenshots ====================

    @ConfigSection(
        name = "Screenshots",
        description = "When the plugin attaches a screenshot to a submission",
        position = 2,
        closedByDefault = false
    )
    String screenshotSection = "Screenshots";

    @ConfigItem(
        keyName = "screenshots",
        name = "Enable Screenshots",
        description = "<html>Attach screenshots to the things you track above.<br />"
            + "Turn this off to submit everything without images.</html>",
        position = 1,
        section = screenshotSection
    )
    default boolean screenshots() {
        return true;
    }

    @ConfigItem(
        keyName = "screenshotValue",
        name = "Loot screenshot value",
        description = "What minimum value would you like drops to be sent with an attached image for?",
        position = 2,
        section = screenshotSection
    )
    default int screenshotValue() {
        return 250000;
    }

    @ConfigItem(
        keyName = "screenshotUntradeables",
        name = "Screenshot untradeables",
        description = "<html>Take screenshots of notable untradeable drops (champion scrolls, boss heads,<br />"
            + "raid kits, etc.) even though they arrive with no GE value.<br />"
            + "Note: with the API enabled, an item required by one of your active events<br />"
            + "is always screenshotted for proof, regardless of this setting.</html>",
        position = 3,
        section = screenshotSection
    )
    default boolean screenshotUntradeables() {
        return true;
    }

    @ConfigItem(
        keyName = "minLevelToScreenshot",
        name = "Minimum level to screenshot",
        description = "<html>Only screenshot level-ups at or above this level.<br />"
            + "Levels below it are still tracked and sent - just without an image.</html>",
        position = 4,
        section = screenshotSection
    )
    default int minLevelToScreenshot() {
        return 75;
    }

    @ConfigItem(
        keyName = "hideWhispers",
        name = "Hide PMs",
        description = "Do you want your private chat to be hidden when screenshots are taken?",
        position = 5,
        section = screenshotSection
    )
    default boolean hideDMs() {
        return false;
    }

    @ConfigItem(
        keyName = "compressImages",
        name = "Compress screenshots",
        description = "<html>Convert large screenshots to JPEG before sending.<br />"
            + "Turn this off to always send lossless PNG, whatever the size.</html>",
        position = 6,
        section = screenshotSection
    )
    default boolean compressImages() {
        return true;
    }

    @ConfigItem(
        keyName = "imageCompressionThresholdKb",
        name = "Compression threshold (KB)",
        description = "<html>Maximum screenshot size (in KB) before JPEG compression is applied.<br>"
            + "Screenshots smaller than this threshold are sent as lossless PNG.<br>"
            + "Set to 0 to always compress to JPEG.</html>",
        position = 7,
        section = screenshotSection
    )
    default int imageCompressionThresholdKb() {
        return 1500;
    }

    // ==================== Events ====================

    @ConfigSection(
        name = "Events",
        description = "In-game notifications and HUD for DropTracker events (requires the API)",
        position = 3,
        closedByDefault = false
    )
    String eventSection = "Events";

    @ConfigItem(
        keyName = "eventNotifications",
        name = "Receive notifications",
        description = "<html>Show in-game notifications about your DropTracker events<br />"
            + "(task completions, lead changes, board turns...).<br />"
            + "Requires 'Use API Connections'. Fine-tune which types you receive on the website.</html>",
        position = 1,
        section = eventSection
    )
    default boolean eventNotifications() {
        return true;
    }

    @ConfigItem(
        keyName = "eventDisplayMode",
        name = "Display type",
        description = "<html>How event notifications appear:<br />"
            + "<b>Chat messages only</b> - lines in your chatbox.<br />"
            + "<b>Chat + text pop-ups</b> - also shows brief on-screen pop-ups.<br />"
            + "<b>Enhanced display (HUD)</b> - also shows a movable overlay with your<br />"
            + "current task, progress and team standing (hold Alt to drag it).</html>",
        position = 2,
        section = eventSection
    )
    default EventDisplayMode eventDisplayMode() {
        return EventDisplayMode.POPUP;
    }

    @ConfigItem(
        keyName = "eventTaskProgressNotifications",
        name = "Task progress notifications",
        description = "<html>Notify when teammates progress (not just complete) your team's tasks.<br />"
            + "The chattiest type - this is the mute switch for it.</html>",
        position = 3,
        section = eventSection
    )
    default boolean eventTaskProgressNotifications() {
        return true;
    }

    @ConfigItem(
        keyName = "eventHudDetail",
        name = "HUD details",
        description = "<html>Enhanced display only:<br />"
            + "<b>Compact</b> - task icon, name and progress.<br />"
            + "<b>Detailed</b> - adds your team name, rank and score.</html>",
        position = 4,
        section = eventSection
    )
    default EventHudDetail eventHudDetail() {
        return EventHudDetail.DETAILED;
    }

    // ==================== Clan Chat ====================

    @ConfigSection(
        name = "Clan Chat",
        description = "Relay your clan's broadcasts and chat to your group's DropTracker features.<br />"
            + "Requires the API connection, and your group must configure its clan name on droptracker.io.",
        position = 4,
        closedByDefault = true
    )
    String clanSection = "Clan Chat";

    @ConfigItem(
        keyName = "relayClanBroadcasts",
        name = "Relay clan broadcasts",
        description = "<html>Send your clan's broadcast messages (drops, pets, collection log slots) to the<br />"
            + "DropTracker so clanmates WITHOUT the plugin can be tracked by your group.<br />"
            + "One relaying member covers the whole clan; duplicates are handled server-side.</html>",
        position = 1,
        section = clanSection
    )
    default boolean relayClanBroadcasts() {
        return false;
    }

    @ConfigItem(
        keyName = "relayClanChat",
        name = "Relay clan chat to Discord",
        description = "<html>Mirror your clan chat into your group's configured Discord bridge channel.<br />"
            + "Only takes effect for groups that enabled the clan chat bridge on droptracker.io.</html>",
        position = 2,
        section = clanSection
    )
    default boolean relayClanChat() {
        return false;
    }

    @ConfigItem(
        keyName = "receiveDiscordChat",
        name = "Show Discord messages in game",
        description = "<html>Display messages sent in your group's Discord bridge channel inside your<br />"
            + "clan chat box (visible only to you; nothing is sent to the game server).</html>",
        position = 3,
        section = clanSection
    )
    default boolean receiveDiscordChat() {
        return true;
    }

    // ==================== Advanced ====================

    @ConfigSection(
        name = "Advanced",
        description = "API connection, account sync and debugging",
        position = 5,
        closedByDefault = true
    )
    String advancedSection = "Advanced";

    @ConfigItem(
        keyName = "useApi",
        name = "Use API",
        description = "<html>Enables external connections to the DropTracker database, for panel data.<br />"
            + "<b>Note</b>: The API is currently <b>required</b> for participation in events!</html>",
        position = 1,
        section = advancedSection,
        warning = "<html><b>WARNING</b>: In order to connect to the DropTracker API,<br>"
            + "your client must make out-going connections to the developer's server.<br>"
            + "This server can not be verified by the RuneLite developers.<br>"
            + "<b>Are you sure?</b></html>"
    )
    default boolean useApi() {
        return false;
    }

    @ConfigItem(
        keyName = "receiveInGameMessages",
        name = "Receive in-game messages",
        description = "Do you want to see chat messages from the plugin to confirm your submissions/etc?",
        position = 2,
        section = advancedSection
    )
    default boolean receiveInGameMessages() {
        return true;
    }

    @ConfigItem(
        keyName = "syncAccountState",
        name = "Sync account progress",
        description = "<html>Periodically send your skills, quests, achievement diaries,<br>"
            + "combat achievements and collection log to DropTracker, so your<br>"
            + "profile page can show your current progress.<br>"
            + "This sends <b>progress data only</b> - never your bank, inventory or location.</html>",
        position = 3,
        section = advancedSection
    )
    default boolean syncAccountState() {
        return true;
    }

    @ConfigItem(
        keyName = "uploadCharacterModel",
        name = "Send character model & gear",
        description = "<html>Send a 3D model of your character (and pet) so your profile page<br>"
            + "can show it, and so the gear and inventory you were carrying can be<br>"
            + "pictured alongside your personal bests.<br>"
            + "The model is sent once per outfit, while you are standing still - never during combat.</html>",
        position = 4,
        section = advancedSection
    )
    default boolean uploadCharacterModel() {
        return true;
    }

    @ConfigItem(
        keyName = "showSidePanel",
        name = "Show Side Panel",
        description = "<html>Do you want to render the <br>side-panel to lookup players, etc?<br>"
            + "<b>Note</b>: Requires the API to be enabled.</html>",
        position = 5,
        section = advancedSection
    )
    default boolean showSidePanel() {
        return true;
    }

    @ConfigItem(
        keyName = "debugLogging",
        name = "Debug Logging",
        description = "Do you want the DropTracker to log data locally to your machine for debugging purposes?",
        position = 6,
        section = advancedSection
    )
    default boolean debugLogging() {
        return false;
    }

    // ==================== Retired from the UI ====================
    /*
     * Kept (not deleted) so a player's existing value still applies and the
     * code reading it keeps working. Un-hide by removing `hidden = true`.
     */

    /** Master gate for the whole XP/level pipeline; "Levels" and "XP Milestones" sit under it. */
    @ConfigItem(
        keyName = "trackExperience",
        name = "Track Experience",
        description = "Do you want to send experience gains to the DropTracker?",
        hidden = true
    )
    default boolean trackExperience() {
        return true;
    }

    /** Folded into "Activity Tracking"; an existing opt-out still wins. */
    @ConfigItem(
        keyName = "trackMta",
        name = "Mage Training Arena",
        description = "Track reward shop purchases (and the Bones to Peaches unlock) at the Mage Training Arena.",
        hidden = true
    )
    default boolean trackMta() {
        return true;
    }

    @ConfigItem(
        keyName = "trackAgilityPyramid",
        name = "Agility Pyramid",
        description = "Track pyramid tops obtained at the Agility Pyramid.",
        hidden = true
    )
    default boolean trackAgilityPyramid() {
        return true;
    }

    @ConfigItem(
        keyName = "trackTrawling",
        name = "Deep Sea Trawling",
        description = "Track fish you catch while deep sea trawling.",
        hidden = true
    )
    default boolean trackTrawling() {
        return true;
    }

    /** Folded into "Send character model & gear"; an existing opt-out still wins. */
    @ConfigItem(
        keyName = "sendLoadoutWithPbs",
        name = "Send gear with personal bests",
        description = "Include the gear and inventory you carried when you set a personal best.",
        hidden = true
    )
    default boolean sendLoadoutWithPbs() {
        return true;
    }

    /** Nuance of "Display type"; retired to keep the Events section to four options. */
    @ConfigItem(
        keyName = "eventImportantPopupsOnly",
        name = "Pop-ups: important only",
        description = "Only pop up the big moments; ordinary completions stay in chat.",
        hidden = true
    )
    default boolean eventImportantPopupsOnly() {
        return false;
    }

    @ConfigItem(
        keyName = "pollUpdates",
        name = "Polling Updates",
        description = "Auto-update the side panel content/stats periodically.",
        hidden = true
    )
    default boolean pollUpdates() {
        return true;
    }

    // ==================== Internal state ====================

    @ConfigItem(
        name = "pinnedEventId",
        keyName = "pinnedEventId",
        description = "pinnedEventId",
        hidden = true
    )
    default int pinnedEventId() {
        return 0;
    }

    // NOTE: config setters MUST carry their own @ConfigItem — RuneLite's
    // ConfigInvocationHandler reads the key name from the setter's annotation
    // and silently ignores the call (WARN "has no @ConfigItem!") without it.
    @ConfigItem(
        name = "pinnedEventId",
        keyName = "pinnedEventId",
        description = "pinnedEventId",
        hidden = true
    )
    void setPinnedEventId(int eventId);

    @ConfigItem(
        name = "lastVersionNotified",
        keyName = "lastVersionNotified",
        description = "lastVersionNotified",
        hidden = true
    )
    default String lastVersionNotified() {
        return "0";
    }

    @ConfigItem(
        name = "lastVersionNotified",
        keyName = "lastVersionNotified",
        description = "lastVersionNotified",
        hidden = true
    )
    public void setLastVersionNotified(String versionNotified);

    @ConfigItem(
        name = "lastAccountName",
        keyName = "lastAccountName",
        description = "lastAccountName",
        hidden = true
    )
    default String lastAccountName() {
        return null;
    }

    @ConfigItem(
        name = "lastAccountName",
        keyName = "lastAccountName",
        description = "lastAccountName",
        hidden = true
    )
    void setLastAccountName(String accountName);

    @ConfigItem(
        name = "customApiEndpoint",
        keyName = "customApiEndpoint",
        description = "customApiEndpoint",
        hidden = true
    )
    default String customApiEndpoint() {
        return "";
    }

    @ConfigItem(
        name = "customApiEndpoint",
        keyName = "customApiEndpoint",
        description = "customApiEndpoint",
        hidden = true
    )
    void setCustomApiEndpoint(String customApiEndpoint);

    @ConfigItem(
        name = "lastAccountHash",
        keyName = "lastAccountHash",
        description = "lastAccountHash",
        hidden = true
    )
    default String lastAccountHash() {
        return null;
    }

    @ConfigItem(
        name = "lastAccountHash",
        keyName = "lastAccountHash",
        description = "lastAccountHash",
        hidden = true
    )
    void setLastAccountHash(String accountHash);
}
