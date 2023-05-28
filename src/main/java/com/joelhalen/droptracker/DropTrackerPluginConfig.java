package com.joelhalen.droptracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("example")
public interface DropTrackerPluginConfig extends Config
{
		@ConfigItem(
				keyName = "server_id",
				name = "Server ID",
				description = "The server ID for the Discord webhook"
		)
		default String serverId()
		{
			return "";
		}
		@Range(min = 1, max = 5000000)
		@ConfigItem(
			name = "Minimum Item Price",
			keyName = "minimumValue",
			description = "Minimum item value for submissions to be sent to the webhook."
		)
		default double minimumValue()
		{
			return 2;
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
				keyName = "sendScreenshots",
				name = "[WILL CAUSE LAG] Send Screenshots",
				description = "BETA FEATURE -- Send a screenshot to the database along with your drop?"
		)
	default boolean sendScreenshots()
		{
			return false;
		}
	}