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

	/* Loot related Tracking */
	@ConfigSection(
			name = "Loot Tracking",
			description = "Define what rules you want set for loot",
			position = 1,
			closedByDefault = false
	) String LootSection = "Loot Tracking";
	@ConfigItem(
			keyName = "lootEmbeds",
			name = "Enable Loot Tracking",
			description = "Should we send your drops to the DropTracker?",
			position = 0,
			section = LootSection

	) default boolean lootEmbeds(){ return true;}
	@ConfigItem(
			keyName = "valueableDrops",
			name = "Screenshot Drops",
			description = "Do you want to submit screenshots when a drop<br />" +
					"Exceeds the threshold you set?",
			position = 1,
			section = LootSection
	) default boolean screenshotDrops() { return true; }
	@ConfigItem(
			keyName = "screenshotValue",
			name = "Screenshot minimum",
			description = "What minimum value would you like drops to be sent with an attached image for?",
			position = 2,
			section = LootSection
	)default int screenshotValue() { return 250000; }

	/* Personal Best related Tracking */
	@ConfigSection(
			name = "Personal Bests",
			description = "Should we send your personal bests to the DropTracker?",
			position = 2,
			closedByDefault = false
	) String PbSection = "Personal Bests";
	@ConfigItem(
			keyName = "pbEmbeds",
			name = "Enable PBs",
			description = "Do you want DropTracker to track your PBs?",
			position = 1,
			section = PbSection
	) default boolean pbEmbeds() { return true; }
	@ConfigItem(
			keyName = "screenshotPB",
			name = "Screenshot PBs",
			description = "Do you want a screenshot to be sent\n" +
					"when you acquire a new Personal Best?",
			position = 2,
			section = PbSection
	) default boolean screenshotPBs() { return true; }

	/* Collection Log related Tracking */
	@ConfigSection(
			name = "Collection Logs",
			description = "<html>Define what rules you want set for Collection Log <br>" +
			"<b>Note</b>: Requires Collection Log Notification and popup Enabled in OSRS settings</html>",
			position = 3,
			closedByDefault = false
	) String ClogSection = "Collection Logs";
	@ConfigItem(
			keyName = "clogEmbeds",
			name = "Enable Clogs",
			description = "Should we send new collection log slot unlocks to the DropTracker?",
			position = 1,
			section = ClogSection
	) default boolean clogEmbeds() { return true; }
	@ConfigItem(
			keyName = "screenshotClog",
			name = "Screenshot Clogs",
			description = "Do you want screenshots to be sent when you\n" +
					"receive new collection log slots?",
			position = 2,
			section = ClogSection
	) default boolean screenshotNewClogs() { return true; }

	/* Combat Achievement related Tracking */
	@ConfigSection(
			name = "Combat Achievements",
			description = "Define what rules you want set for Combat Achievements",
			position = 4,
			closedByDefault = false
	) String CaSection = "Combat Achievements";
	@ConfigItem(
			keyName = "caEmbeds",
			name = "Enable CAs",
			description = "Should we send your drops to the DropTracker?",
			position = 3,
			section = CaSection
	) default boolean caEmbeds() { return true; }
	@ConfigItem(
			keyName = "screenshotCAs",
			name = "Screenshot CAs",
			description = "Do you want a screenshot to be sent\n" +
					"when you complete a Combat Task?",
			position = 3,
			section = CaSection
	) default boolean screenshotCAs() { return true; }


	@ConfigSection(
			name = "Additonal Settings",
			description = "Configure your client settings for the DropTracker database",
			position= 5 ,
			closedByDefault = false
	) String apiSection = "Additional Settings";
	/* Settings for Hiding Split Chat, Side Panel and API connections */
	@ConfigItem(
			keyName = "hideWhispers",
			name = "Hide PMs",
			description = "Do you want your private chat to be\n" +
					"hidden when screenshots are taken?",
			position = -1,
			section = apiSection
	) default boolean hideDMs() { return false; }
	@ConfigItem(
			name = "Show Side Panel",
			keyName = "showSidePanel",
			description = "<html>Do you want to render the <br>side-panel to lookup players, etc?<br>" +
					"<b>Note</b>: Requires the API to be enabled.</html>",
			position = 0,
			section = apiSection
	) default boolean showSidePanel() { return true; }

	@ConfigItem(
			name="Use API Connections",
			keyName = "useApi",
			description = "Enables external connections to the DropTracker database, for panel data.<br />" +
					"<b>Note</b>: The API is currently <b>required</b> for participation in events!",
			position = 1,
			section = apiSection,
			warning = "<html><b>WARNING</b>: Enabling this feature will send external<br>connections" +
					"to the DropTracker server, which" +
					"can not<br> be verified by the RuneLite Developers.<br>" +
					"<b>Are you sure that you want to enable external connections?</b><br></html>"
	) default boolean useApi() { return false; }

}
