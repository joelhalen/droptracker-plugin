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
				keyName = "ignoreDrops",
				name = "Ignore Drops",
				description = "Toggle sending your drops to the tracker entirely"
		)
		default boolean ignoreDrops()
		{
			return false;
		}
		@ConfigItem(
				keyName = "sendChatMessages",
				name = "Send Chat Messages?",
				description = "Would you like to receive messages in your chatbox when drops are added to the panel/uploaded?"
		)
		default boolean sendChatMessages() { return true; }
		@ConfigItem(
				keyName = "sendScreenshots",
				name = "[Beta] Send Screenshots",
				description = "WARNING: May cause frames to drop. Should we send a screenshot to the database along with your drop?"
		)
	default boolean sendScreenshots()
		{
			return false;
		}
	}