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
import net.runelite.client.config.Range;

@ConfigGroup("droptracker")
public interface DropTrackerPluginConfig extends Config
{
		@ConfigItem(
				keyName = "server_id",
				name = "Discord Server ID",
				description = "Insert your clan's discord server ID."
		)
		default String serverId()
		{
			return "";
		}

		@ConfigItem(
				keyName = "authKey",
				name = "Verification Key",
				secret = true,
				description = "<html>DO NOT SHARE THIS TOKEN!<br>Use <b>/gettoken</b> in discord to retrieve it once registered.</html>"
		)

		default String authKey() { return ""; }
		@ConfigItem(
				keyName = "ignoreDrops",
				name = "Ignore Drops",
				description = "Checked = send your drops. Unchecked = ignore all drops."
		)

		default boolean ignoreDrops()
		{
			return false;
		}
		@ConfigItem(
				keyName = "permPlayerName",
				name = "Permanent player name",
				description = "If you play on many accounts, you can submit all of your drops under this name."
		)
		default String permPlayerName() { return ""; }
		@ConfigItem(
				keyName = "sendChatMessages",
				name = "Send Chat Messages?",
				description = "Would you like to receive various chat messages when drops are<br>received/added to your panel?"
		)
		default boolean sendChatMessages() { return true; }
		@ConfigItem(
				keyName = "sendScreenshots",
				name = "[Beta] Send Screenshots",
				description = "<html> Should we send a screenshot to the database along with your drop?<br><b>WARNING<b>: All uploads are PUBLICLY ACCESSIBLE!</html>"
		)
		default boolean sendScreenshots()
		{
			return false;
		}
	}