/*
BSD 2-Clause License

		Copyright (c) 2022, Jake Barter
		All rights reserved.

		Copyright (c) 2022, pajlads

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
		OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package io.droptracker;

import com.google.gson.*;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.*;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.SwingUtilities;

import io.droptracker.api.DropTrackerApi;
import io.droptracker.api.UrlManager;
import io.droptracker.events.CaHandler;
import io.droptracker.events.ClogHandler;
import io.droptracker.events.DropHandler;
import io.droptracker.events.PbHandler;
import io.droptracker.events.WidgetEvent;
import io.droptracker.models.Drop;
import io.droptracker.service.SubmissionManager;
import io.droptracker.ui.DropTrackerPanelNew;
import io.droptracker.util.ChatMessageUtil;
import io.droptracker.util.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;


import net.runelite.client.util.ImageUtil;
import okhttp3.*;

@Slf4j
@PluginDescriptor(
		name = "DropTracker",
		description = "Track your drops, compete in events, and send Discord webhooks!",
		tags = {"droptracker", "drop", "webhook", "events"}
)
public class DropTrackerPlugin extends Plugin {
	@Inject
	private DropTrackerConfig config;
	@Inject
	public static DropTrackerApi api;

	private DropTrackerPanelNew newPanel;

	@Inject
	private ConfigManager configManager;

	private NavigationButton navButton;
	private NavigationButton newNavButton;

	@Inject
	private Gson gson;

	@Inject
	private OkHttpClient httpClient;


	@Inject
	private ChatMessageUtil chatMessageUtil;

	@Inject
	private KCService kcService;

	@Inject
	private DropHandler dropHandler;
	
	@Inject
	private SubmissionManager submissionManager;

	@Nullable
    public Drop lastDrop = null;

	private boolean statsLoaded = false;

	public Boolean isTracking = true;
	public Integer ticksSinceNpcDataUpdate = 0;

	private final ExecutorService executor = new ThreadPoolExecutor(
		2, // core pool size
		10, // maximum pool size
		60L, TimeUnit.SECONDS, // keep alive time
		new LinkedBlockingQueue<>(),
		new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r);
				t.setUncaughtExceptionHandler((thread, ex) -> {
					log.error("Uncaught exception in executor thread", ex);
					DebugLogger.logSubmission("Executor thread died: " + ex.getMessage());
				});
				return t;
			}
		}
	);

	private static final BufferedImage PANEL_ICON = ImageUtil.loadImageResource(DropTrackerPlugin.class, "icon.png");
	@Inject
	public ClogHandler clogHandler;
	@Inject
	public CaHandler caHandler;
	@Inject
	public PbHandler pbHandler;

	@Inject
	public WidgetEvent widgetEventHandler;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private UrlManager urlManager;

	@Inject
	private Client client;

	public String pluginVersion = "4.0";

	// Add a new flag to track when we need to update on next available tick
	private boolean needsPanelUpdateOnLogin = false;

	@Override
	protected void startUp() {
		api = new DropTrackerApi(config, gson, httpClient, this, client);
		if(config.showSidePanel()) {
			createSidePanel();
		}
		// Preload webhook URLs asynchronously
		if (!executor.isShutdown() && !executor.isTerminated()) {
			executor.submit(() -> urlManager.loadEndpoints());
		}

		chatMessageUtil.registerCommands();
	}
	private void createSidePanel() {
		System.out.println("createSidePanel called");
		
		newPanel = injector.getInstance(DropTrackerPanelNew.class);
		newPanel.init();

		// Set up callback for automatic UI updates when submissions are added
		System.out.println("Setting up submission manager callback");
		System.out.println("SubmissionManager instance in plugin: " + submissionManager.hashCode());
		submissionManager.setUpdateCallback(() -> {
			System.out.println("SubmissionManager update callback called");
			SwingUtilities.invokeLater(() -> {
				if (newPanel != null) {
					System.out.println("Updating submissions panel");
					newPanel.updateSentSubmissions();
				}
			});
		});

		newNavButton = NavigationButton.builder()
				.tooltip("DropTracker")
				.icon(PANEL_ICON)
				.priority(1)
				.panel(newPanel)
				.build();

		clientToolbar.addNavigation(newNavButton);
		System.out.println("createSidePanel completed");
	}

	public void updateSubmissionsPanel() {
		if (newPanel != null) {
			newPanel.updateSentSubmissions();
		}
	}




	public void updatePanelOnLogin(String chatMessage) {
		if (chatMessage.contains("Welcome to Old School RuneScape.")) {
			System.out.println("Welcome message detected, setting flag for panel update");
			// Instead of updating immediately, set a flag to update when player is available
			needsPanelUpdateOnLogin = true;
		}
	}


	public String itemImageUrl(int itemId) {
		return "https://static.runelite.net/cache/item/icon/" + itemId + ".png";
	}

	@Override
	protected void shutDown() {
		if(navButton != null) {
			clientToolbar.removeNavigation(navButton);
		}
		chatMessageUtil.unregisterCommands();
		if (newPanel != null) {
			newPanel.deinit();
			newPanel = null;
		}
		// Clear the callback to prevent memory leaks
		submissionManager.setUpdateCallback(null);
		executor.shutdown();
	}

	@Provides
	DropTrackerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DropTrackerConfig.class);
	}

	

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged) {
		if (configChanged.getGroup().equalsIgnoreCase(DropTrackerConfig.GROUP)) {
			if (configChanged.getKey().equals("useApi")) {
				System.out.println("Config changed: useApi");
				if(navButton != null) {
					clientToolbar.removeNavigation(navButton);
				}
				// Recreate the side panel which will reset the callback
				if (newPanel != null) {
					clientToolbar.removeNavigation(newNavButton);
					newPanel.deinit();
					newPanel = null;
					// Only clear callback when actually removing the panel
					submissionManager.setUpdateCallback(null);
				}
				if (config.showSidePanel()) {
					createSidePanel();
				}
				// panel.refreshData();
				if (client.getAccountHash() != -1) {
					try {
						api.lookupPlayer(client.getLocalPlayer().getName());
					} catch (Exception e) {
						log.debug("Couldn't look the current player up in the DropTracker database");
					}
				}
			} else if (configChanged.getKey().equals("showSidePanel")) {
				System.out.println("Config changed: showSidePanel to " + configChanged.getNewValue());
				if (!config.showSidePanel()) {
					System.out.println("Disabling side panel - clearing callback");
					if(navButton != null) {
						clientToolbar.removeNavigation(navButton);
					}
					if (newPanel != null) {
						clientToolbar.removeNavigation(newNavButton);
						newPanel.deinit();
						newPanel = null;
						// Clear the callback when panel is removed
						submissionManager.setUpdateCallback(null);
					}
				} else {
					System.out.println("Enabling side panel");
					if (newPanel == null) {
						createSidePanel();
					}
				}
			}


			//sendChatReminder();
		}
	}


	@Subscribe
	public void onScriptPreFired(ScriptPreFired event) {
		System.out.println("onScriptPreFired called");
		if(config.clogEmbeds()) {
			clogHandler.onScript(event.getScriptId());
			System.out.println("onScriptPreFired completed");
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded widget) {
		widgetEventHandler.onWidgetLoaded(widget);
	}

	@Subscribe(priority=1)
	public void onNpcLootReceived(NpcLootReceived npcLootReceived) {
		dropHandler.onNpcLootReceived(npcLootReceived);
		kcService.onNpcKill(npcLootReceived);
	}

	@Subscribe(priority=1)
	public void onPlayerLootReceived(PlayerLootReceived playerLootReceived) {
		dropHandler.onPlayerLootReceived(playerLootReceived);
		kcService.onPlayerKill(playerLootReceived);
	}

	@Subscribe(priority=1)
	public void onLootReceived(LootReceived lootReceived) {
		dropHandler.onLootReceived(lootReceived);
		kcService.onLoot(lootReceived);
	}

	@Subscribe(priority = 1)
	public void onChatMessage(ChatMessage message) {
		if (!isTracking) {
			return;
		}
        clogHandler.sendFakeLogData("Twisted Bow");
		String chatMessage = submissionManager.sanitize(message.getMessage());
		switch (message.getType()) {
			case WELCOME:
				if (!statsLoaded) {
					updatePanelOnLogin(chatMessage);
				}
				/* Welcome should only be called a single time on each login, 
					so we can call this regardless of whether statsLoaded is true,
					to load/refresh configurations respective to the current player logged in
				*/
				if (config.useApi()) {
					try {
						api.loadGroupConfigs(getLocalPlayerName());
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				break;
			case GAMEMESSAGE:
				if(config.pbEmbeds()){
					pbHandler.onGameMessage(chatMessage);
				}
				if(config.caEmbeds()){
					caHandler.onGameMessage(chatMessage);
				}
				if(config.clogEmbeds()) {
					clogHandler.onChatMessage(chatMessage);
				}
			case FRIENDSCHATNOTIFICATION:
				pbHandler.onFriendsChatNotification(chatMessage);
			default:
				break;
		}
		kcService.onGameMessage(chatMessage);
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		if (!isTracking) {
			return;
		}
		
		// Check if we need to update panel on login and player is now available
		if (needsPanelUpdateOnLogin && client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null) {
			System.out.println("Player is now available, updating panel");
			needsPanelUpdateOnLogin = false; // Clear the flag
			
			// Use SwingUtilities to ensure UI updates happen on EDT
			SwingUtilities.invokeLater(() -> {
				try {
					System.out.println("About to call updatePlayerPanel...");
					if (newPanel != null) {
						String playerName = client.getLocalPlayer().getName();
						System.out.println("Updating config with player name: " + playerName);
						configManager.setConfiguration("droptracker", "lastAccountName", playerName);
						newPanel.updatePlayerPanel();
						
						// Also update the home player button since config might now have player name
						newPanel.updateHomePlayerButton();
						System.out.println("updatePlayerPanel and updateHomePlayerButton called successfully");
						statsLoaded = true;
					} else {
						System.out.println("newPanel is null!");
					}
				} catch (Exception e) {
					System.err.println("Error updating player panel: " + e.getMessage());
					e.printStackTrace();
					statsLoaded = true;
				}
			});
		}
		
		pbHandler.onTick();
		widgetEventHandler.onGameTick(event);
	}

	

	
	public String getLocalPlayerName() {
		if (client.getLocalPlayer() != null) {
			return client.getLocalPlayer().getName();
		} else {
			return "";
		}
	}

	public void sendRankChangeChatMessage(String rankChangeType, Integer currentRankNpc, Integer currentRankAll, Integer totalRankChange, String totalLootReceived,
			Integer totalRankChangeAtNpc, String totalLootNpc, Integer totalMembers, Integer totalMembersNpc,
			String totalReceivedAllTime, String totalLootNpcAllTime) {

	}



	
}
