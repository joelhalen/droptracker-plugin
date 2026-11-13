package io.droptracker;

import io.droptracker.models.*;
import net.runelite.client.config.*;

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
        description = "<html>Send new collection log slots to DropTracker. Needs the game's collection<br>"
            + "log notification and pop-up enabled.</html>",
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
        description = "<html>Notify when a level-99 skill crosses an XP milestone. Your group's settings<br />"
            + "pick which milestones are announced (default every 25M).</html>",
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
        keyName = "slayerEmbeds",
        name = "Slayer Tasks",
        description = "<html>Send your completed slayer tasks to the DropTracker.<br />"
            + "Events can count them as a goal.</html>",
        position = 11,
        section = trackingSection
    )
    default boolean slayerEmbeds() {
        return true;
    }

    @ConfigItem(
        keyName = "trackActivities",
        name = "Activity Tracking",
        description = "<html>Track items from activities RuneLite's loot tracker misses:<br />"
            + "deep sea trawling catches and Pharaoh's sceptres.</html>",
        position = 12,
        section = trackingSection
    )
    default boolean trackActivities() {
        return true;
    }

    /*
     * Replaces relayClanBroadcasts + relayClanChat (both default off) with one
     * setting under a NEW keyName, so every player starts on the new default.
     * Nothing is relayed unless one of the player's own groups set up their
     * current clan on droptracker.io (see ClanRelayService#relayTargets).
     */
    @ConfigItem(
            keyName = "clanChatSync",
            name = "Clan chat sync",
            description = "<html>Sync your clan chat and broadcasts with your group's Discord. Only active in<br />"
                    + "a clan set up on droptracker.io; otherwise nothing is sent. API not required.</html>",
            position = 13,
            section = trackingSection
    )
    default boolean clanChatSync() {
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
        description = "<html>Screenshot 0gp drops on DropTracker's notable list: champion scrolls, boss heads<br />"
            + "and jars, ornament and raid kits, slayer boss parts. Ancient shards, totem parts and<br />"
            + "crystal shards are not on it. Items an active event needs are always screenshotted.</html>",
        position = 3,
        section = screenshotSection
    )
    default boolean screenshotUntradeables() {
        return true;
    }

    @ConfigItem(
        keyName = "minLevelToScreenshot",
        name = "Minimum level to screenshot",
        description = "<html>Screenshot level-ups from this level up; lower ones still send without an image.</html>",
        position = 4,
        section = screenshotSection
    )
    default int minLevelToScreenshot() {
        return 75;
    }

    @ConfigItem(
        keyName = "privacyMode",
        name = "Privacy mode",
        description = "<html>What a screenshot hides. Hide DMs: private messages (the whole transcript<br />"
            + "when split chat is off). Hide messages + DMs: transcript and typed input.<br />"
            + "Hide entire chatbox: the chat area; fixed mode widens the game view instead.</html>",
        position = 5,
        section = screenshotSection
    )
    default PrivacyMode privacyMode() {
        return PrivacyMode.NONE;
    }

    /** Retired in favor of {@link #privacyMode()}; read once at startup to
     * migrate players who had it enabled over to {@code HIDE_DMS}. */
    @ConfigItem(
        keyName = "hideWhispers",
        name = "Hide PMs",
        description = "Replaced by Privacy mode",
        hidden = true,
        position = 5,
        section = screenshotSection
    )
    default boolean hideDMs() {
        return false;
    }

    @ConfigItem(
        keyName = "compressImages",
        name = "Compress screenshots",
        description = "<html>Convert large screenshots to JPEG before sending; off = always lossless PNG.</html>",
        position = 6,
        section = screenshotSection
    )
    default boolean compressImages() {
        return true;
    }

    /* Renamed from imageCompressionThresholdKb (6.0.18) so every player is
     * reset to the 1500 KB default. */
    @ConfigItem(
        keyName = "screenshotCompressionKb",
        name = "Compression threshold (KB)",
        description = "<html>Screenshots above this size (KB) are sent as JPEG, smaller ones as PNG.<br>"
            + "0 = always JPEG.</html>",
        position = 7,
        section = screenshotSection
    )
    default int imageCompressionThresholdKb() {
        return 1500;
    }

    @ConfigItem(
        keyName = "xpMilestoneScreenshots",
        name = "Screenshot XP milestones",
        description = "<html>Screenshot each 1M XP milestone past 99. Off: the milestone still sends,<br />"
            + "without the image (and without the flicker every million XP).</html>",
        position = 8,
        section = screenshotSection
    )
    default boolean xpMilestoneScreenshots() {
        return true;
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
        description = "<html>In-game notifications for your DropTracker events (completions, lead changes,<br />"
            + "board turns). Needs the API; choose the types on the website.</html>",
        position = 1,
        section = eventSection
    )
    default boolean eventNotifications() {
        return true;
    }

    @ConfigItem(
        keyName = "eventDisplayMode",
        name = "Display type",
        description = "<html><b>Chat messages only</b>: chatbox lines. <b>Chat + text pop-ups</b>: plus a slim<br />"
            + "banner per update. <b>Enhanced display (HUD)</b>: showcase pop-ups and a movable<br />"
            + "overlay with your task, progress and standing (Alt-drag to move).</html>",
        position = 2,
        section = eventSection
    )
    default EventDisplayMode eventDisplayMode() {
        return EventDisplayMode.POPUP;
    }

    @ConfigItem(
        keyName = "eventTaskProgressNotifications",
        name = "Task progress notifications",
        description = "<html>Notify when teammates progress (not just complete) your team's tasks. The chattiest type.</html>",
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

    @ConfigItem(
        keyName = "eventTeamIndicators",
        name = "Team indicators in chat",
        description = "<html>Badge clan chat lines from your event's players with their team: a<br />"
            + "<b>color orb</b> (their Discord channel's color) or a short <b>tag</b> like [RR].<br />"
            + "Only during a live event, only for its participants.</html>",
        position = 5,
        section = eventSection
    )
    default TeamIndicatorStyle eventTeamIndicators() {
        return TeamIndicatorStyle.ORB;
    }

    @ConfigItem(
        keyName = "eventTeamIndicatorColorNames",
        name = "Color teammates' names",
        description = "<html>Also draw the sender's name in their team's color. Handy in a clan-vs-clan;<br />"
            + "turn it off if it looks busy.</html>",
        position = 6,
        section = eventSection
    )
    default boolean eventTeamIndicatorColorNames() {
        return true;
    }

    @ConfigItem(
        keyName = "eventTeamIndicatorsPublicChat",
        name = "Also badge public chat",
        description = "<html>Badge public chat as well as clan chat.<br />"
            + "Useful at a shared boss or raid during a clan-vs-clan; noisy in a city.</html>",
        position = 7,
        section = eventSection
    )
    default boolean eventTeamIndicatorsPublicChat() {
        return false;
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
        description = "<html>Connect to DropTracker for panel data, lookups and in-game event updates.</html>",
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

    // keyName changed from "receiveInGameMessages" in 6.1.8 so everyone starts
    // from the new default (off): RuneLite finds saved values by keyName.
    @ConfigItem(
        keyName = "inGameMessages",
        name = "Receive in-game messages",
        description = "Show chat messages from the plugin, such as drop confirmations and notices about your submissions",
        position = 2,
        section = advancedSection
    )
    default boolean receiveInGameMessages() {
        return false;
    }

    @ConfigItem(
        keyName = "dropConfirmations",
        name = "Drop confirmations",
        description = "<html>Show the \"Drop processed\" chat line. Warnings (a drop held back for a<br />"
            + "missing screenshot) still show.</html>",
        position = 3,
        section = advancedSection
    )
    default boolean dropConfirmations() {
        return true;
    }

    @ConfigItem(
            keyName = "receiveDiscordChat",
            name = "Show Discord messages in game",
            description = "<html>Show your group's Discord bridge channel in your clan chat box (only you see<br />"
                    + "it; nothing goes to the game server). Needs Clan chat sync.</html>",
            position = 4,
            section = advancedSection
    )
    default boolean receiveDiscordChat() {
        return true;
    }

    @ConfigItem(
            keyName = "showGuestBroadcasts",
            name = "Show broadcasts as a clan guest",
            description = "<html>As a clan chat guest, see that clan's broadcasts (drops, pets, PBs) relayed by<br />"
                    + "its members' plugins. Only for clans that turned this on. Needs the API.</html>",
            position = 4,
            section = advancedSection
    )
    default boolean showGuestBroadcasts() {
        return true;
    }

    @ConfigItem(
        keyName = "syncAccountState",
        name = "Sync account progress",
        description = "<html>Periodically send skills, quests, diaries, combat achievements and collection<br>"
            + "log for your profile page. <b>Progress only</b>: never bank, inventory or location.</html>",
        position = 5,
        section = advancedSection
    )
    default boolean syncAccountState() {
        return true;
    }

    @ConfigItem(
        keyName = "uploadCharacterModel",
        name = "Send character model & gear",
        description = "<html>Send your character (and pet) model so your profile can show it and your<br>"
            + "gear can appear beside personal bests. Sent once per outfit while standing still.</html>",
        position = 6,
        section = advancedSection
    )
    default boolean uploadCharacterModel() {
        return true;
    }

    @ConfigItem(
        keyName = "showSidePanel",
        name = "Show Side Panel",
        description = "<html>Show the side panel (player lookups and more). Needs the API.</html>",
        position = 7,
        section = advancedSection
    )
    default boolean showSidePanel() {
        return true;
    }

    @ConfigItem(
        keyName = "debugLogging",
        name = "Debug Logging",
        description = "Do you want the DropTracker to log data locally to your machine for debugging purposes?",
        position = 8,
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
