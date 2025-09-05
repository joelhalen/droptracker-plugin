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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.SwingUtilities;

import io.droptracker.api.DropTrackerApi;
import io.droptracker.api.UrlManager;
import io.droptracker.events.CaHandler;
import io.droptracker.events.ClogHandler;
import io.droptracker.events.DropHandler;
import io.droptracker.events.ExperienceHandler;
import io.droptracker.events.PbHandler;
import io.droptracker.events.QuestHandler;
import io.droptracker.events.WidgetEventHandler;
import io.droptracker.models.submissions.Drop;
import io.droptracker.service.KCService;
import io.droptracker.service.SubmissionManager;
import io.droptracker.ui.DropTrackerPanel;
import io.droptracker.util.ChatMessageUtil;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
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

	private DropTrackerPanel panel;

	@Inject
	private ConfigManager configManager;

	private NavigationButton navButton;

	@Inject
	private Gson gson;

	@Inject
	private OkHttpClient httpClient;
	
	@Inject
	private ChatMessageUtil chatMessageUtil;

	@Inject
	private KCService kcService;

	/* Event Handlers */
	@Inject
	private DropHandler dropHandler;
	@Inject
	private QuestHandler questHandler;
	@Inject
	public ClogHandler clogHandler;
	@Inject
	public CaHandler caHandler;
	@Inject
	public PbHandler pbHandler;

	@Inject
	public ExperienceHandler experienceHandler;
	@Inject
	private SubmissionManager submissionManager;

	@Inject
	private ScheduledExecutorService executor;

	@Nullable
	public Drop lastDrop = null;

	private boolean statsLoaded = false;

	public Boolean isTracking = true;
	public Integer ticksSinceNpcDataUpdate = 0;

	private static final BufferedImage PANEL_ICON = ImageUtil.loadImageResource(DropTrackerPlugin.class, "icon.png");


	@Inject
	public WidgetEventHandler widgetEventHandler;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private UrlManager urlManager;

	@Inject
	private Client client;

	public String pluginVersion = "5.0.1";

	// Add a new flag to track when we need to update on next available tick
	private boolean needsPanelUpdateOnLogin = false;

	@Override
	protected void startUp() {
		api = new DropTrackerApi(config, gson, httpClient, this, client);
		if(config.showSidePanel()) {
			createSidePanel();
		}
		// Preload webhook URLs asynchronously
		executor.submit(() -> urlManager.loadEndpoints());

	}


	private void createSidePanel() {

		panel = injector.getInstance(DropTrackerPanel.class);
		panel.init();

		// Trigger initial UI refreshes so the panel is populated immediately
		SwingUtilities.invokeLater(() -> {
			if (panel != null) {
				panel.updateSentSubmissions();
				panel.updateHomePlayerButton();
				panel.updatePlayerPanel();
			}
		});

		submissionManager.setUpdateCallback(() -> {
			SwingUtilities.invokeLater(() -> {
				if (panel != null) {
					panel.updateSentSubmissions();
				}
			});
		});
		submissionManager.setUpdatesEnabled(true);

		navButton = NavigationButton.builder()
				.tooltip("DropTracker")
				.icon(PANEL_ICON)
				.priority(1)
				.panel(panel)
				.build();

		clientToolbar.addNavigation(navButton);
	}

	// public void updateSubmissionsPanel() {
	// 	if (panel != null) {
	// 		panel.updateSentSubmissions();
	// 	}
	// }




	public void updatePanelOnLogin(String chatMessage) {
		if (chatMessage.contains("Welcome to Old School RuneScape.")) {
			// Instead of updating immediately, set a flag to update when player is available
			needsPanelUpdateOnLogin = true;
		}
	}


	public String itemImageUrl(int itemId) {
		return "https://static.runelite.net/cache/item/icon/" + itemId + ".png";
	}

	@Override
	protected void shutDown() {
		if (navButton != null) {
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		if (panel != null) {
			panel.deinit();
			panel = null;
		}
		// Disable updates while panel is not present
		submissionManager.setUpdatesEnabled(false);
	}

	@Provides
	DropTrackerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DropTrackerConfig.class);
	}



	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged) {
		if (configChanged.getGroup().equalsIgnoreCase(DropTrackerConfig.GROUP)) {
			if (configChanged.getKey().equals("useApi")) {
				// Recreate the side panel which will reset the callback
				if (panel != null) {
					clientToolbar.removeNavigation(navButton);
					panel.deinit();
					panel = null;
					// Disable updates when removing the panel
					submissionManager.setUpdatesEnabled(false);
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
				if (!config.showSidePanel()) {
					if (panel != null) {
						clientToolbar.removeNavigation(navButton);
						panel.deinit();
						panel = null;
						// Disable updates when panel is removed
						submissionManager.setUpdatesEnabled(false);
					}
				} else {
					if (panel == null) {
						createSidePanel();
					}
				}
			}


			//sendChatReminder();
		}
	}


	@Subscribe
	public void onScriptPreFired(ScriptPreFired event) {
		if(clogHandler.isEnabled()) {
			clogHandler.onScript(event.getScriptId());
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded widget) {
		widgetEventHandler.onWidgetLoaded(widget);
		// Also check for quest completion widgets
		questHandler.onWidgetLoaded(widget);
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
						// Refresh the API panel to show updated group configs
						// Since loading is async, we need to delay the refresh
						if (panel != null) {
							// Initial refresh to show loading state
							SwingUtilities.invokeLater(() -> {
								panel.updateSentSubmissions();
							});

							// Schedule a panel submission update on the executor instead of directly waiting on the thread

							executor.schedule(() -> { panel.updateSentSubmissions(); }, 2, TimeUnit.SECONDS); // Wait 2 seconds for async load

						}
					} catch (IOException e) {
						log.debug("Couldn't refresh api Panel");
					}
				}
				break;
			case GAMEMESSAGE:
				if(pbHandler.isEnabled()){
					pbHandler.onGameMessage(chatMessage);
				}
				if(caHandler.isEnabled()){
					caHandler.onGameMessage(chatMessage);
				}
				if(clogHandler.isEnabled()) {
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
			needsPanelUpdateOnLogin = false; // Clear the flag

			// Use SwingUtilities to ensure UI updates happen on EDT
			String playerName = client.getLocalPlayer().getName();
			if (config.lastAccountName() != null && !config.lastAccountName().equals(playerName)) {
				/* In the case that the player has changed from the last time we stored their name/hash, we need to call the reset method on KCService... */
				kcService.reset();
			}
			configManager.setConfiguration("droptracker", "lastAccountName", playerName);
			configManager.setConfiguration("droptracker", "lastAccountHash", String.valueOf(client.getAccountHash()));
			SwingUtilities.invokeLater(() -> {
				try {
					if (panel != null) {
						panel.updatePlayerPanel();

						// Also update the home player button since config might now have player name
						panel.updateHomePlayerButton();
						statsLoaded = true;
					}
				} catch (Exception e) {
					log.debug("Couldn't Load Side Panel UI");
					statsLoaded = true;
				}
			});
		}

		/* Call individual event handlers */
		experienceHandler.onTick();
		pbHandler.onTick();
		widgetEventHandler.onGameTick(event);

		// Also tick the experience handler
		if (config.trackExperience()) {
			experienceHandler.onTick();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged statChanged) {
		if (!isTracking || !config.trackExperience()) {
			return;
		}
		experienceHandler.onStatChanged(statChanged);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		if (!isTracking || !config.trackExperience()) {
			return;
		}
		experienceHandler.onGameStateChanged(gameStateChanged);
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
