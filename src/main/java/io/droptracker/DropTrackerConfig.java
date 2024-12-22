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
	/* PvP is pretty much completely ignored by the server
	@ConfigItem(

			keyName = "screenshotPKs",
			name = "Player vs Player",
			description = "Do you want a screenshot to be sent\n" +
					"when you kill another player?",
			position = 4,
			section = screenshotSection
	)*/
	default boolean screenshotPKs() { return true; }
	/* We are only going to focus on pets that are new collection log slots, for now... */
//	@ConfigItem(
//			keyName = "screenshotPets",
//			name = "Pets",
//			description = "Do you want a screenshot to be sent\n" +
//					"when you acquire a new pet?",
//			position = 5,
//			section = screenshotSection
//	)
//	default boolean screenshotPets() { return true; }

	@ConfigItem(
			name = "Show Side Panel",
			keyName = "showSidePanel",
			description = "<html>Do you want to render the <br>side-panel to lookup players, etc?<br>" +
					"<b>Note</b>: Requires the API to be enabled.</html>",
			position = 2
	)
	default boolean showSidePanel() { return true; }
	@ConfigSection(
			name = "DropTracker Account",
			description = "Configure your client settings for the DropTracker database",
			position= 5 ,
			closedByDefault = false
	)
	String apiSection = "DropTracker Account";
	@ConfigItem(
			name="Use API Connections",
			keyName = "useApi",
			description = "Enables external connections to the DropTracker database, for panel data.<br />" +
					"<b>Note</b>: The API is currently <b>required</b> for participation in events!",
			position = -1,
			section = apiSection,
			warning = "<html><b>WARNING</b>: Enabling this feature will send external<br>connections" +
					"to the DropTracker server, which" +
					"can not<br> be verified by the RuneLite Developers.<br>" +
					"<b>Are you sure that you want to enable external connections?</b><br></html>"
	)
	default boolean useApi() { return false; }

}
