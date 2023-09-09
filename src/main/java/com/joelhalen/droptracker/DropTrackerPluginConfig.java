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
				name = "DropTracker Group ID",
				description = "Input your clan's DropTracker ServerID. Ask a staff member if you're unsure."
		)
		default String serverId()
		{
			return "";
		}

		@ConfigItem(
				keyName = "authKey",
				name = "Verification Key",
				secret = true,
				description = "<html>DO NOT SHARE THIS TOKEN!<br>If you do not have one yet, enter a valid serverID and refresh the DropPanel!</html>"
		)

		default String authKey() { return ""; }
		@ConfigItem(
				keyName = "permPlayerName",
				name = "Permanent player name",
				description = "If you play multiple accounts, you can enter the RSN you use on the DropTracker here to avoid issues!"
		)
		default String permPlayerName() { return ""; }
		@ConfigItem(
				keyName = "showOverlay",
				name = "Show onscreen overlay",
				description = "Show the in-game overlay (mainly for events)"
		)
		default boolean showOverlay() { return true; }
		@ConfigItem(
				keyName = "sendChatMessages",
				name = "Send Chat Messages?",
				description = "Would you like to receive messages in your chatbox when drops are added to the panel/uploaded?"
		)
		default boolean sendChatMessages() { return true; }
		@ConfigItem(
				keyName = "sendScreenshots",
				name = "[Beta] Send Screenshots",
				description = "<html> Should we send a screenshot to the database along with your drop?<br><b>WARNING<b>: <em>May cause slight frame latency</em></html>"
		)
		default boolean sendScreenshots()
		{
			return false;
		}
	}