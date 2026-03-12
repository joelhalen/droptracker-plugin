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
import io.droptracker.events.PetHandler;
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
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;


import net.runelite.client.util.ImageUtil;
import okhttp3.*;

/**
 * Main plugin class for DropTracker. This is the entry point for the RuneLite plugin system
 * and is responsible for:
 * <ul>
 *   <li>Bootstrapping all event handlers and services via Guice dependency injection</li>
 *   <li>Subscribing to RuneLite game events and routing them to the appropriate handlers</li>
 *   <li>Managing the lifecycle of the optional side panel</li>
 *   <li>Persisting the current account name/hash to config for use between sessions</li>
 * </ul>
 *
 * <p><b>Architecture overview:</b> Game events arrive in {@code onChatMessage}, {@code onNpcLootReceived},
 * etc. and are forwarded to the relevant {@code *Handler} classes (e.g. {@link DropHandler},
 * {@link PbHandler}). Each handler builds a {@link io.droptracker.models.CustomWebhookBody} and
 * delegates submission to {@link SubmissionManager}, which validates against the player's
 * group configs and dispatches to Discord / the DropTracker API.</p>
 *
 * <p>Yama requires special handling because it fires both {@code ServerNpcLoot} and
 * {@code NpcLootReceived}; only the former is processed to avoid duplicate submissions.</p>
 */
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

	private NavigationButton navButton;

	@Inject
	private Gson gson;

	@Inject
	private OkHttpClient httpClient;

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
	public PetHandler petHandler;
	@Inject
	public ExperienceHandler experienceHandler;
	@Inject
	private SubmissionManager submissionManager;

	@Inject
	private ScheduledExecutorService executor;

	/**
	 * The most recently received loot drop, shared with {@link ClogHandler} so it can correlate
	 * a collection-log unlock with its originating NPC/source within a short time window.
	 */
	@Nullable
	public Drop lastDrop = null;

	/** True once the side panel has been populated with the logged-in player's data. */
	private boolean statsLoaded = false;

	/**
	 * Global tracking gate. When {@code false}, all event handlers silently skip processing.
	 * Set to {@code false} by {@link io.droptracker.api.UrlManager} when the webhook endpoint
	 * pool is exhausted and cannot be replenished.
	 */
	public Boolean isTracking = true;

	/**
	 * Tracks how many game ticks have elapsed since the last NPC kill count message was received.
	 * Some NPCs (e.g. Grotesque Guardians, Yama) have a larger tick gap between the kill-count
	 * chat message and the loot event, so this counter is decremented to keep the two in sync.
	 */
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

	/** Current plugin version string, embedded in every webhook embed as the {@code p_v} field. */
	public String pluginVersion = "5.1.0";

	/**
	 * Flag set when the WELCOME message is received but the local player object is not yet
	 * available. On the next game tick where the player is available, the panel is refreshed
	 * and this flag is cleared.
	 */
	private boolean needsPanelUpdateOnLogin = false;

	/**
	 * Called by RuneLite when the plugin is started or re-enabled. Initialises the API client,
	 * optionally creates the side panel, and kicks off asynchronous webhook URL pre-loading so
	 * that the first submission does not incur a blocking network call.
	 */
	@Override
	protected void startUp() {
		api = new DropTrackerApi(config, gson, httpClient, this, client);
		if(config.showSidePanel()) {
			createSidePanel();
		}
		// Preload webhook URLs asynchronously so the first drop submission doesn't have to wait
		executor.submit(() -> urlManager.loadEndpoints());

	}


	/**
	 * Instantiates and registers the DropTracker side panel. Also wires up the
	 * {@link SubmissionManager} callback so the panel refreshes whenever a submission's
	 * status changes (sent, failed, retrying, processed).
	 *
	 * <p>Must only be called from the Event Dispatch Thread or a context that is safe to create
	 * Swing components from.</p>
	 */
	private void createSidePanel() {

		panel = injector.getInstance(DropTrackerPanel.class);
		panel.init();

		// Trigger initial UI refreshes so the panel is populated immediately on creation
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




	/**
	 * Handles the login welcome message. Because the {@link net.runelite.api.Player} object is
	 * not guaranteed to be fully populated when the WELCOME chat message fires, we defer the
	 * panel update to the next game tick via {@link #needsPanelUpdateOnLogin}.
	 *
	 * @param chatMessage the sanitized chat message text
	 */
	public void updatePanelOnLogin(String chatMessage) {
		if (chatMessage.contains("Welcome to Old School RuneScape.")) {
			// Defer panel update to the next tick where the player object is available
			needsPanelUpdateOnLogin = true;
		}
	}

	/**
	 * Returns the RuneLite item cache icon URL for the given item ID.
	 * Used by event handlers to attach item images to webhook embeds.
	 *
	 * @param itemId the OSRS item ID
	 * @return a fully-qualified URL pointing to the item's icon
	 */
	public String itemImageUrl(int itemId) {
		return "https://static.runelite.net/cache/item/icon/" + itemId + ".png";
	}

	/**
	 * Called by RuneLite when the plugin is stopped or disabled. Removes the side panel,
	 * disables submission callbacks, and resets stateful services.
	 */
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
		// Disable submission update callbacks so no UI work happens after panel removal
		submissionManager.setUpdatesEnabled(false);
		this.resetAll();
	}

	@Provides
	DropTrackerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DropTrackerConfig.class);
	}

	/**
	 * Resets all stateful services. Called on plugin shutdown and on account switch to ensure
	 * kill counts and pet tracking state from the previous session are not carried forward.
	 */
	protected void resetAll() {
		kcService.reset();
		petHandler.reset();
	}

	/**
	 * Responds to configuration changes in the DropTracker config group. Handles two special
	 * keys that require live UI changes:
	 * <ul>
	 *   <li>{@code useApi} – tears down and recreates the panel so API-dependent pages refresh</li>
	 *   <li>{@code showSidePanel} – adds or removes the navigation button and panel</li>
	 * </ul>
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged) {
		if (configChanged.getGroup().equalsIgnoreCase(DropTrackerConfig.GROUP)) {
			if (configChanged.getKey().equals("useApi")) {
				// Recreate the side panel which will reset the callback
				if (panel != null) {
					clientToolbar.removeNavigation(navButton);
					panel.deinit();
					panel = null;
					navButton = null;
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
						navButton = null;
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


	/**
	 * Intercepts script pre-fire events to detect the collection-log popup notification.
	 * RuneLite fires {@link ScriptID#NOTIFICATION_START} / {@link ScriptID#NOTIFICATION_DELAY}
	 * when the in-game overlay notification fires; {@link ClogHandler} uses this to extract the
	 * item name without relying solely on the chat message (which may not appear in all modes).
	 */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event) {
		if(clogHandler.isEnabled()) {
			clogHandler.onScript(event.getScriptId());
		}
	}

	/**
	 * Dispatches widget-loaded events to the {@link WidgetEventHandler} (adventure log / boss log)
	 * and to the {@link QuestHandler} (quest completion scroll).
	 */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded widget) {
		widgetEventHandler.onWidgetLoaded(widget);
		// Also check for quest completion widgets
		questHandler.onWidgetLoaded(widget);
	}

	/**
	 * Handles Yama's unique server-authoritative loot event.
	 *
	 * <p>Yama fires both {@code ServerNpcLoot} and {@code NpcLootReceived}; we only process the
	 * server-side event (priority 1, first handler) and skip the client-side one in
	 * {@link #onNpcLootReceived} to avoid duplicate submissions.</p>
	 */
	@Subscribe(priority = 1)
    public void onServerNpcLoot(ServerNpcLoot event) {
        if (event.getComposition().getId() != NpcID.YAMA) {
            return;
        }
        kcService.onServerNpcLoot(event);
        dropHandler.onServerNpcLoot(event);
    }

	/**
	 * Processes loot from standard NPC kills. Skips Yama (handled by {@link #onServerNpcLoot}).
	 */
	@Subscribe(priority=1)
	public void onNpcLootReceived(NpcLootReceived npcLootReceived) {
		if (npcLootReceived.getNpc().getId() == NpcID.YAMA) {
			/* Handled by onServerNpcLoot to avoid duplicate processing */
            return;
        }
		dropHandler.onNpcLootReceived(npcLootReceived);
		kcService.onNpcKill(npcLootReceived);
	}

	/** Processes loot from PvP kills. */
	@Subscribe(priority=1)
	public void onPlayerLootReceived(PlayerLootReceived playerLootReceived) {
		dropHandler.onPlayerLootReceived(playerLootReceived);
		kcService.onPlayerKill(playerLootReceived);
	}

	/**
	 * Processes loot from events and pickpocketing (LootTracker plugin events).
	 * Some NPCs (e.g. The Whisperer) only fire this event rather than {@code NpcLootReceived}.
	 */
	@Subscribe(priority=1)
	public void onLootReceived(LootReceived lootReceived) {
		dropHandler.onLootReceived(lootReceived);
		kcService.onLoot(lootReceived);
	}

	/**
	 * Central chat message dispatcher. All relevant chat message types are routed here and
	 * forwarded to the appropriate handler(s).
	 *
	 * <p><b>Message type routing:</b></p>
	 * <ul>
	 *   <li>{@code WELCOME} – triggers panel login update and group config reload</li>
	 *   <li>{@code GAMEMESSAGE} – forwarded to PB, CA, collection log, and pet handlers; falls
	 *       through to {@code FRIENDSCHATNOTIFICATION} handling (intentional fall-through)</li>
	 *   <li>{@code FRIENDSCHATNOTIFICATION} – forwarded to PB handler for raid completion messages</li>
	 *   <li>{@code CLAN_MESSAGE} / {@code CLAN_GIM_MESSAGE} – forwarded to pet handler for
	 *       clan-wide pet announcement parsing</li>
	 * </ul>
	 * All messages are also passed to {@link KCService} to keep the kill-count cache current.
	 */
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
				/* WELCOME fires exactly once per login, so it's safe to (re)load group configs here
				   regardless of whether statsLoaded is true from a previous session in the same client. */
				if (config.useApi()) {
					try {
						api.loadGroupConfigs(getLocalPlayerName());
						// Refresh the API panel to show updated group configs.
						// loadGroupConfigs is async, so we schedule a delayed refresh after loading completes.
						if (panel != null) {
							// Immediate refresh to show the "loading" state in the panel
							SwingUtilities.invokeLater(() -> {
								panel.updateSentSubmissions();
							});
							// Delayed refresh to reflect the newly loaded configs (2s allows async fetch to complete)
							executor.schedule(() -> { panel.updateSentSubmissions(); }, 2, TimeUnit.SECONDS);
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
				if(petHandler.isEnabled()) {
					petHandler.onGameMessage(chatMessage);
				}
				// Intentional fall-through: raid completion messages arrive as FRIENDSCHATNOTIFICATION
			case FRIENDSCHATNOTIFICATION:
				pbHandler.onFriendsChatNotification(chatMessage);
				// Intentional fall-through: clan pet notifications share the same handler path
			case CLAN_MESSAGE:
			case CLAN_GIM_MESSAGE:
                petHandler.onClanChatNotification(chatMessage);
                break;
			default:
				break;
		}
		kcService.onGameMessage(chatMessage);
	}

	/**
	 * Per-tick update method. Handles deferred panel refresh on login, ticks individual event
	 * handlers that require periodic state evaluation ({@link ExperienceHandler},
	 * {@link PbHandler}, {@link WidgetEventHandler}, {@link PetHandler}), and ensures
	 * experience tracking is only active when enabled.
	 */
	@Subscribe
	public void onGameTick(GameTick event) {
		if (!isTracking) {
			return;
		}

		// Deferred panel update: the player object isn't available when WELCOME fires, so we
		// wait until the first tick where getName() returns a non-null value.
		if (needsPanelUpdateOnLogin && client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null) {
			needsPanelUpdateOnLogin = false; // Clear the flag

			// Use SwingUtilities to ensure UI updates happen on EDT
			String playerName = client.getLocalPlayer().getName();
			if (config.lastAccountName() != null && !config.lastAccountName().equals(playerName)) {
				/* In the case that the player has changed from the last time we stored their name/hash, we need to call the reset method on KCService... */
				kcService.reset();
			}
			config.setLastAccountName(playerName);
			config.setLastAccountHash(String.valueOf(client.getAccountHash()));
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

		petHandler.onTick();


		// Also tick the experience handler
		if (config.trackExperience()) {
			experienceHandler.onTick();
		}
	}

	/** Routes skill experience changes to {@link ExperienceHandler} when tracking is enabled. */
	@Subscribe
	public void onStatChanged(StatChanged statChanged) {
		if (!isTracking || !config.trackExperience()) {
			return;
		}
		experienceHandler.onStatChanged(statChanged);
	}

	/**
	 * Notifies {@link ExperienceHandler} of game state transitions (e.g. login screen, world hop)
	 * so it can reset its internal level baseline on account switches or special worlds.
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		if (!isTracking || !config.trackExperience()) {
			return;
		}
		experienceHandler.onGameStateChanged(gameStateChanged);
	}

	/**
	 * Returns the local player's display name, or an empty string if the player is not yet
	 * logged in / the player object is unavailable.
	 *
	 * @return the player name or {@code ""}
	 */
	public String getLocalPlayerName() {
		if (client.getLocalPlayer() != null) {
			return client.getLocalPlayer().getName();
		} else {
			return "";
		}
	}

	/**
	 * Placeholder for in-game rank-change chat notifications. Currently unused; retained for
	 * future implementation of rank-up messaging via the API response pipeline.
	 */
	public void sendRankChangeChatMessage(String rankChangeType, Integer currentRankNpc, Integer currentRankAll, Integer totalRankChange, String totalLootReceived,
										  Integer totalRankChangeAtNpc, String totalLootNpc, Integer totalMembers, Integer totalMembersNpc,
										  String totalReceivedAllTime, String totalLootNpcAllTime) {

	}




}
