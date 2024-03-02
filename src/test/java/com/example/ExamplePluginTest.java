package com.example;

import io.droptracker.DropTrackerPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ExamplePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(DropTrackerPlugin.class);
		RuneLite.main(args);
	}
}