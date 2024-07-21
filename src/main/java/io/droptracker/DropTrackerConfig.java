package io.droptracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(DropTrackerConfig.GROUP)
public interface DropTrackerConfig extends Config
	/* Section Positions:
	1 (0) - General Settings
	2 (1) - Values
	2 (2) - Screenshots
	 */
{
	String GROUP = "droptracker";
	@ConfigSection(
			name = "Screenshots",
			description = "Define what events you want to send screenshots for",
			position = 2,
			closedByDefault = false
	)
	String screenshotSection = "Screenshots";
	/* Screenshot Section Items */
	@ConfigItem(
			keyName = "screenshotValue",
			name = "Minimum Screenshot Value",
			description = "What minimum value would you like drops to be sent with an attached image for?",
			position = -1,
			section = screenshotSection
	)
	default int screenshotValue() { return 250000; }
	@ConfigItem(
			keyName = "valueableDrops",
			name = "Valueable Drops",
			description = "Do you want to take screenshots when a drop<br />" +
					"Exceeds the threshold you set?",
			position = 0,
			section = screenshotSection
	)
	default boolean screenshotDrops() { return true; }

	@ConfigItem(
			keyName = "screenshotCLog",
			name = "Collection Logs",
			description = "Do you want screenshots to be sent when you\n" +
					"receive new collection log items?",
			position = 1,
			section = screenshotSection
	)
	default boolean screenshotNewClogs() { return true; }
	@ConfigItem(
			keyName = "screenshotPB",
			name = "Personal Bests",
			description = "Do you want a screenshot to be sent\n" +
					"when you acquire a new Personal Best?",
			position = 2,
			section = screenshotSection
	)
	default boolean screenshotPBs() { return true; }
	@ConfigItem(
			keyName = "screenshotCAs",
			name = "Combat Tasks",
			description = "Do you want a screenshot to be sent\n" +
					"when you complete a Combat Task?",
			position = 3,
			section = screenshotSection
	)
	default boolean screenshotCAs() { return true; }
	@ConfigItem(
			keyName = "screenshotPKs",
			name = "Player vs Player",
			description = "Do you want a screenshot to be sent\n" +
					"when you kill another player?",
			position = 4,
			section = screenshotSection
	)
	default boolean screenshotPKs() { return true; }
	@ConfigItem(
			keyName = "screenshotPets",
			name = "Pets",
			description = "Do you want a screenshot to be sent\n" +
					"when you acquire a new pet?",
			position = 5,
			section = screenshotSection
	)
	default boolean screenshotPets() { return true; }
	@ConfigItem(
			name = "Receive Reminders",
			keyName = "sendReminders",
			description = "Do you want to receive news/updates & reminders in chat messages?",
			position = 1
	)
	default boolean sendReminders() { return false; }
	@ConfigItem(
			name = "Show Side Panel",
			keyName = "sidePanel",
			description = "<html>Do you want to render the <br>side-panel for events, etc?<br>" +
					"<b>Note</b>: Requires the API to be enabled.</html>",
			position = 2
	)
	default boolean showSidePanel() { return false; }
	@ConfigSection(
			name = "DropTracker API",
			description = "Configure your account settings for the DropTracker API",
			position= 5 ,
			closedByDefault = false
	)
	String apiSection = "DropTracker API";
	@ConfigItem(
			name="Use API Connections",
			keyName = "useApi",
			description = "Enables external connections to the DropTracker database.<br />" +
					"<b>Note</b>: The API is <em>currently</em> <b>required</b> for participation in events!",
			position = -1,
			section = apiSection,
			warning = "<html><b>WARNING</b>: Using this feature sends your IP address to a 3rd party server<br>" +
					"that is not verified by the RuneLite Developers.<br>" +
					"<b>Are you sure you want to toggle 'Use API Connections'?</b><br></html>"
	)
	default boolean useApi() { return false; }
	@ConfigItem(
			name="Server ID",
			keyName = "serverID",
			description = "Enter your DropTracker server ID here.<br/>" +
					"Visit the Loot Leaderboard channel in your clan's discord to retrieve this ID.<br />" +
					"Join our Discord for help: discord.gg/droptracker",
			position = 0,
			section = apiSection
	)
	default String serverId() { return ""; }
	@ConfigItem(
			name = "Token",
			keyName = "authKey",
			description = "Enter your token (/token on discord) here<br />to authenticate your submissions, if you have one.",
			position = 1,
			section = apiSection,
			secret = true
	)
	default String authKey() { return ""; }
	@ConfigItem(
			name = "Track Account Data",
			keyName = "trackAccData",
			description = "Store information about your account like your log " +
					"<br />slots and personal bests in our database.",
			position = 4,
			section = apiSection
	)
	default boolean trackAccData() { return true; }
	@ConfigItem(
			name = "Drop Confirmation Messages",
			keyName = "chatMessages",
			description = "Do you want to receive confirmation chat messages for submitted drops?",
			position = 5,
			section = apiSection
	)
	default boolean chatMessages() { return true; }
	@ConfigSection(
			name = "Personal Settings",
			description = "We allow any user to use our Google Sheets and Discord Webhook integration<br />" +
					"To learn more, visit our Discord.",
			position=3,
			closedByDefault = true
	)
			String personalSection = "Personal Config";
	@ConfigItem(
			keyName = "webhook",
			name = "Custom Webhook",
			description = "If you want to specify your own custom webhook URL to send your drops, you can do so here",
			section = personalSection,
			position = 1
	)
	default String webhookUrl() { return ""; };
	@ConfigItem(
			keyName = "webhookValue",
			name = "Webhook Value",
			description = "Drops exceeding this value will<br />" +
					"be sent to the Discord Webhook URL provided above",
			section = personalSection,
			position = 1
	)
	default Integer webhookMinValue() { return 250000; };

	@ConfigItem(
			keyName = "sheet",
			name = "Google Spreadsheet",
			description = "If you want your drops to also be inserted into a Google Sheet, enter the <b>SHEET ID</b> here.<br/>" +
					"Join our Discord for help.",
			section = personalSection,
			position = 3
	)
	default String sheetID() { return ""; };
}
