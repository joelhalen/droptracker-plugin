package io.droptracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(DropTrackerConfig.GROUP)
public interface DropTrackerConfig extends Config
{
	String GROUP = "droptracker";
	@ConfigItem(
			keyName = "sendScreenshot",
			name = "Send Screenshots",
			description = "Would you like to include screenshots when sending your drops?<br>" +
					"<b>Note</b>: Screenshots are <b>required</b> for participation in events!",
			position = -1
	)
	default boolean sendScreenshot()
	{
		return true;
	}
	@ConfigItem(
			keyName = "screenshotValue",
			name = "Minimum Screenshot Value",
			description = "What minimum value would you like drops to be sent with an attached image for?",
			position = 0
	)
	default int screenshotValue() { return 250000; }
	@ConfigItem(
			name = "Receive Reminders",
			keyName = "sendReminders",
			description = "Do you want to receive news/updates & reminders in chat messages?",
			position = 1
	)
	default boolean sendReminders() { return false; }
	@ConfigSection(
			name = "DropTracker API",
			description = "Configure your account settings for the DropTracker API/Discord Bot.",
			position= 2 ,
			closedByDefault = true
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
			name = "Authentication Key",
			keyName = "authKey",
			description = "Enter the authentication key generated by our Discord bot here to validate your drops.<br/>" +
					"Use /gettoken inside Discord to retrieve it, or join our Discord (!droptracker) to learn more.",
			position = 1,
			section = apiSection
	)
	default String authKey() { return ""; }
	@ConfigItem(
			name = "Registered name",
			description = "If you use multiple accounts, you can enter the name you registered under here<br>" +
					"to track all drops using this name.",
			keyName = "registeredName",
			position = 2,
			section = apiSection
	)
	default String registeredName() { return ""; }
	//	@ConfigItem( -- side panel will be added at some later date
//			name = "Use Side Panel",
//			keyName = "useSidePanel",
//			description = "Do you want the DropTracker side panel to be rendered, showing<br />" +
//					"recent submissions, total loot counters, etc?",
//			position = 3,
//			section = apiSection
//	)
//	default boolean useSidePanel() { return true; }
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
			name = "Personal Endpoints",
			description = "If you want your drops to be sent to a specific Sheet or Webhook, you can enter them here.",
			position=3,
			closedByDefault = true
	)

	/* Personal settings (personal webhook, google sheets, etc) */
			String personalSection = "Personal Config";
	@ConfigItem(
			keyName = "webhook",
			name = "Custom Webhook",
			description = "If you want to specify your own custom webhook URL to send your drops, you can do so here",
			section = personalSection,
			position = 1
	)
	default String webhook() { return ""; };
	@ConfigItem(
			keyName = "sheet",
			name = "Google Spreadsheet",
			description = "If you want your drops to also be inserted into a Google Sheet, enter the <b>SHEET ID</b> here.<br/>" +
					"Join our Discord for help.",
			section = personalSection,
			position = 2
	)
	default String sheetID() { return ""; };
	@ConfigItem(
			keyName = "colLogWebhooks",
			name = "Collection Log Webhooks",
			description = "<html>Do you want to send webhooks to your <br>" +
					"personally-configured URL for new <br>" +
					"collection log items?<br>" +
					"<b>Note</b>: To update the stored log slots you have in our database, <br>" +
					"open the Collection Log once with the plugin enabled.</html>",
			section = personalSection,
			position = 3
	)
	default boolean collectionLogWebhooks() { return false; }

}
