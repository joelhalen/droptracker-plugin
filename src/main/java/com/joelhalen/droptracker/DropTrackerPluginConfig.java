/*      BSD 2-Clause License

		Copyright (c) 2023, joelhalen

		Redistribution and use in source and binary forms, with or without
		modification, are permitted provided that the following conditions are met:

		1. Redistributions of source code must retain the above copyright notice, this
		list of conditions and the following disclaimer.

		2. Redistributions in binary form must reproduce the above copyright notice,
		this list of conditions and the following disclaimer in the documentation
		and/or other materials provided with the distribution.

		THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
		AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
		IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
		DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
		FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
		DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
		SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
		CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
		OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
		OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.     */
package com.joelhalen.droptracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("droptracker")
public interface DropTrackerPluginConfig extends Config
{
	/* API-related config options */
		@ConfigSection(
				name= "API",
				description = "Enable connections to/from the DropTracker.io API and configure your client settings.",
				position=1,
				closedByDefault = true
		)
		String apiSection = "API";
		@ConfigSection(
				name= "Sheets",
				description="If you are not using the API, and your clan uses Google Sheets, configure them here",
				position=0,
				closedByDefault = true
		)
		String sheetsSection = "Sheets";
		/* With the use_api config setting disabled by default
		, requests will >never< be made to our server without the user's OK, preventing the installation warning while maintaining the ability to communicate for those
		who choose to use the extra features brought forth by the server */
		@ConfigItem(
				keyName = "use_api",
				name = "Make external connections",
				description = "<html><b>WARNING</b>:<br>" +
						"Enabling this feature sends bits of information about your player, stats and drops<br>" +
						"to an external, 3rd party server that has cannot be verified by RuneLite.<br>" +
						"Please ensure this is understood before continuing.</html>",
				section = apiSection,
				warning = "<html><b>WARNING</b>: Enabling this feature submits your IP address to a 3rd party server not controlled or verified by the RuneLite Developers.<br>" +
						"<b>Are you sure you want to enable the DropTracker API?</b><br></html>",
				position = -1
		)
		default boolean useApi() {
			return false;
		}
		@ConfigItem(
				keyName = "server_id",
				name = "Discord Server ID",
				description = "Insert your clan's discord server ID.",
				section = apiSection,
				position = 0
		)
		default String serverId()
		{
			return "";
		}
		@ConfigItem(
				keyName = "authKey",
				name = "Verification Key",
				secret = true,
				description = "<html>DO NOT SHARE THIS TOKEN!<br>Use <b>/gettoken</b> in discord to retrieve it once registered.</html>",
				section = apiSection,
				position = 1
		)

		default String authKey() { return ""; }
		@ConfigItem(
				/* This one won't need a warning -- since every time it's called the API bool is also checked! :) */
				keyName = "shareAccountData",
				name = "Send Account Data/Stats",
				description = "<html>Do you want to upload things such as your <b>Collections Logged</b>" +
						" and notable gear (void/elite void, capes, etc) to the DropTracker API?</html>",
				section = apiSection,
				position = 2
		)
		default boolean sendAccountData() { return false; }
		@ConfigItem(
				keyName = "permPlayerName",
				name = "Permanent player name",
				description = "<html>If you play on many accounts, you can submit all of your drops under this name.<br>" +
						"<em>Note: without API enabled, all drops will be sent using your current username regardless!</em></html>",
				section = apiSection,
				position = 3
		)
		default String permPlayerName() { return ""; }


		@ConfigItem(
				keyName = "sendChatMessages",
				name = "Send Chat Messages?",
				description = "Would you like to receive messages in response to drops being processed, etc?",
				section = apiSection,
				position = 4
		)
		default boolean sendChatMessages() { return true; }

		@ConfigItem(
				keyName = "showHelpText",
				name = "Show Helpful Descriptions?",
				description = "Do you want to be presented with how-to information on the DropTracker panel?",
				section = apiSection,
				position = 5
		)
		default boolean showHelpText() { return true; }
		@ConfigItem(
				keyName = "showEventPanel",
				name = "Show Event Panel?",
				description = "If your clan is running an event, do you want to render the additional RuneLite panel?",
				section = apiSection,
				position = 6
		)

		default boolean showEventPanel()
		{
			return false;
		}
		@ConfigItem(
				/* Also require a warning before turning on sendScreenshots, since
				* we use our own API endpoint to store these... */
				keyName = "sendScreenshots",
				name = "Send Screenshots",
				description = "<html>Optionally upload your drops as screenshots and send them to the Discord API.</html>",
				warning = "<html><b>WARNING</b>: Uploading screenshots to the DropTracker dedicated server inherently shares your IP address!<br>" +
						"<b>The RuneLite Developers have no control over this 3rd party server, and it is not verified.</b><br></html>"
		)
		default boolean sendScreenshots() {
				return false;
		}
		@ConfigItem(
				keyName = "sheetURL",
				name = "Spreadsheet URL",
				description = "<html>If your clan/group is using the Google Sheets functionality,<br>" +
						"enter the URL here.<br>If you're unsure, ask a staff member.</html>",
				section = sheetsSection
		)
		default String sheetURL() {
			return "";
		}
	}