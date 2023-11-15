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
/*

		``` @joelhalen and @droptracker.io on discord
		A decent bit of this code was pulled from pre-existing repositories for RuneLite plugins.
		Mainly:
		- Discord Rare Drop Notificator (onLootReceived events)
		- COX and TOB data tracker - learned how to create a panel, borrowed some code
		For support, contact me on GitHub: https://github.com/joelhalen
		Or via the DropTracker website: https://www.droptracker.io/

		~~~SHARES THE USER'S IP ADDRESS WITH THE AUTHENTICATION/DROP SUBMISSION SERVER~~~

 */
package com.joelhalen.droptracker;

import com.google.inject.Provides;

import net.runelite.api.*;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.loottracker.LootRecordType;
import okhttp3.*;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@PluginDescriptor(
		name = "DropTracker",
		description = "Automatically uploads your drops to the DropTracker discord bot!",
		tags = {"droptracker", "drop", "leaderboard", "tracking"}
)

public class DropTrackerPlugin extends Plugin {
	//TODO: Implement pet queues and collection log slot queues
	public static final String CONFIG_GROUP = "droptracker";
	private final ExecutorService executor = Executors.newCachedThreadPool();
	@Inject
	private DropTrackerPluginConfig config;
	@Inject
	private OkHttpClient httpClient;
	private boolean panelRefreshed = false;
	private String currentPlayerName = "";
	@Inject
	private ItemManager itemManager;
	@Inject
	public Client client;
	private DropTrackerPanel panel;
	@Inject
	public ChatMessageManager chatMessageManager;
	List<DropEntryStream> storedDrops = new CopyOnWriteArrayList<>();
	@Inject
	private ClientThread clientThread;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private DrawManager drawManager;
	private long accountHash = -1;
	private Map<String, Integer> serverMinimumLootVarMap;
	private Map<String, Long> clanServerDiscordIDMap;
	private Map<String, String> serverIdToClanNameMap;
	private Map<String, Boolean> serverIdToConfirmedOnlyMap;
	public Map<String, Integer> clanWiseOldManGroupIDMap;
	public Map<String, Boolean> clanEventActiveMap;
	private Set<DropEntryStream> sentDrops = new HashSet<>();
	private boolean prepared = false;
	private static final Logger log = LoggerFactory.getLogger(DropTrackerPlugin.class);
	private static final BufferedImage ICON = ImageUtil.loadImageResource(DropTrackerPlugin.class, "icon.png");
	private NavigationButton navButton;

	private String[] groupMembers = new String[0];
	private final Object groupMembersLock = new Object();

	@Inject
	private GroupMemberClient wiseOldManClient;
	@Subscribe
	public void onNpcLootReceived(NpcLootReceived npcLootReceived) {
		// handles drops from NPCs that are obtained on the floor (mostly)
		NPC npc = npcLootReceived.getNpc();
		String npcName = npc.getName();
		int npcCombatLevel = npc.getCombatLevel();
		String playerName;
		if(config.permPlayerName().equals("")) {
			playerName = client.getLocalPlayer().getName();
		} else {
			playerName = config.permPlayerName();
		}
		Collection<ItemStack> items = npcLootReceived.getItems();
		for (ItemStack item : items) {
			// ItemStack is a RuneLite class that defines items and contains all of their eh "parameters"
			// we're sayng here, for each item (of the ItemStack object type) in the items dictionary of ItemStack objects
			// which is defined by the items received by the npcLootReceived event that calls the function
			int itemId = item.getId();
			int quantity = item.getQuantity();
			List<CompletableFuture<Boolean>> futures = new ArrayList<>();
			// Make sure the quantity is 1, so that we aren't submitting item stacks that are above the specified value

			int geValue = itemManager.getItemPrice(itemId);
			int haValue = itemManager.getItemComposition(itemId).getHaPrice();
			if(geValue == 0 && haValue == 0) {
				continue;
			}
			ItemComposition itemComp = itemManager.getItemComposition(itemId);
			String itemName = itemComp.getName();
			boolean ignoreDrops = config.ignoreDrops();
			SwingUtilities.invokeLater(() -> {
				if (geValue < serverMinimumLootVarMap.get(config.serverId())) {
					String serverId = config.serverId();
							//Is the discord server accepting a non-confirmed stream of items?
							//This means they track every single drop a player receives
							if (serverIdToConfirmedOnlyMap.get(serverId) != true) {
								sendDropData(playerName, npcName, itemId, itemName, "", quantity, geValue, 0, config.authKey(), "");
							}
						} else {
							/* Create the screenshot object so that we can upload it, saving the url to the drop object */
							CompletableFuture<String> uploadFuture = getScreenshot(playerName, itemId);
							if (config.sendChatMessages()) {
								ChatMessageBuilder addedDropToPanelMessage = new ChatMessageBuilder();
								addedDropToPanelMessage.append("[")
										.append(ChatColorType.HIGHLIGHT)
										.append("DropTracker")
										.append(ChatColorType.NORMAL)
										.append("] Added ")
										.append(ChatColorType.HIGHLIGHT)
										.append(itemName)
										.append(ChatColorType.NORMAL)
										.append(" to the RuneLite side panel for review");
								chatMessageManager.queue(QueuedMessage.builder()
										.type(ChatMessageType.CONSOLE)
										.runeLiteFormattedMessage(addedDropToPanelMessage.build())
										.build());
							}
							DropEntry entry = new DropEntry();
							if(config.permPlayerName().equals("")) {
								entry.setPlayerName(playerName);
							} else {
								entry.setPlayerName(config.permPlayerName());
							}
							entry.setNpcOrEventName(npcName);
							entry.setNpcCombatLevel(npcCombatLevel);
							entry.setGeValue(geValue);
							entry.setHaValue(haValue);
							entry.setItemName(itemName);
							entry.setItemId(itemId);
							entry.setQuantity(quantity);
							if (config.sendScreenshots()) {
								getScreenshot(playerName, itemId).thenAccept(imageUrl -> {
									SwingUtilities.invokeLater(() -> {
										entry.setImageLink(imageUrl);
									});
								});
							} else {
								entry.setImageLink("none");
							}
							panel.addDrop(entry);
						}
			});
		}
	}
	public void loadGroupMembersAsync() {
		executor.execute(() -> {
			while (true) {
				try {
					String[] newGroupMembers = wiseOldManClient.getGroupMembers(Long.valueOf(config.serverId()), getLocalPlayerName());
					synchronized (groupMembersLock) {
						groupMembers = newGroupMembers;
					}
					Thread.sleep(180 * 60 * 1000); // update every 180 minutes
				} catch (IOException | InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
	}
	@Subscribe
	public void onLootReceived(LootReceived lootReceived) {
		//ignore regular NPC drops; since onNpcLootReceived contains more data on the source of the drop
		if (lootReceived.getType() == LootRecordType.NPC) {
			//if the drop was an NPC, it's already been handled in onNpcLootReceived
			return;
		}
		Collection<ItemStack> items = lootReceived.getItems();
		for (ItemStack item : items) {
			int itemId = item.getId();
			int quantity = item.getQuantity();
			List<CompletableFuture<Boolean>> futures = new ArrayList<>();
			//If quantity < 1 and geValue > clanValue, then we can assume the drop will be trackable.
			int geValue = itemManager.getItemPrice(itemId);
			int haValue = itemManager.getItemComposition(itemId).getHaPrice();
			if(geValue == 0 && haValue == 0) {
				return;
			}
			ItemComposition itemComp = itemManager.getItemComposition(itemId);
			String itemName = itemComp.getName();
			boolean ignoreDrops = config.ignoreDrops();
			SwingUtilities.invokeLater(() -> {
					String serverId = config.serverId();
					Integer clanMinimumLoot = serverMinimumLootVarMap.get(serverId);
					String submissionPlayer = "";
					if(!config.permPlayerName().equals("")) {
						submissionPlayer = config.permPlayerName();
					} else {
						submissionPlayer = client.getLocalPlayer().getName();
					}
					if (geValue < clanMinimumLoot) {
							if (serverIdToConfirmedOnlyMap.get(serverId) != true) {
								sendDropData(submissionPlayer, lootReceived.getName(), itemId, itemName, "", quantity, geValue, 0, config.authKey(), "");
							}
						} else {

							if (quantity > 1) {
								return;
							}
							CompletableFuture<String> uploadFuture = getScreenshot(submissionPlayer, itemId);
							if (config.sendChatMessages()) {
								ChatMessageBuilder addedDropToPanelMessage = new ChatMessageBuilder();
								addedDropToPanelMessage.append("[")
										.append(ChatColorType.HIGHLIGHT)
										.append("DropTracker")
										.append(ChatColorType.NORMAL)
										.append("] Added ")
										.append(ChatColorType.HIGHLIGHT)
										.append(itemName)
										.append(ChatColorType.NORMAL)
										.append(" to the RuneLite side panel for review");
								chatMessageManager.queue(QueuedMessage.builder()
										.type(ChatMessageType.CONSOLE)
										.runeLiteFormattedMessage(addedDropToPanelMessage.build())
										.build());
							}
							DropEntry entry = new DropEntry();
							if(config.permPlayerName().equals("")) {
								entry.setPlayerName(submissionPlayer);
							} else {
								entry.setPlayerName(config.permPlayerName());
							}
							entry.setNpcOrEventName(lootReceived.getName());
							entry.setNpcCombatLevel(0);
							entry.setGeValue(geValue);
							entry.setHaValue(haValue);
							entry.setItemName(itemName);
							entry.setItemId(itemId);
							entry.setQuantity(quantity);
							if (config.sendScreenshots()) {
								uploadFuture.thenAccept(imageUrl -> {
									SwingUtilities.invokeLater(() -> {
										entry.setImageLink(imageUrl);
									});
								}).exceptionally(ex -> {
									log.error("Failed to get screenshot", ex);
									return null;
								});
							} else {
								entry.setImageLink("none");
							}
							panel.addDrop(entry);
						}
				});
		}
	}

	//There is probably a better way of doing this, but this will work for now.
	@Subscribe
	public void onGameTick(GameTick event) {

		/* Refresh the panel every time the game state changes, or the player's local name changes */
		if ((client.getGameState() == GameState.LOGGED_IN) && (client.getLocalPlayer().getName() != null) && (!client.getLocalPlayer().getName().equals(currentPlayerName))) {
			currentPlayerName = client.getLocalPlayer().getName();
			if (!panelRefreshed) {
				log.debug("[DropTracker] Updating panel due to a new player state");
				panel.refreshPanel();
				panelRefreshed = true;
			}
		} else {
			panelRefreshed = false;
		}
	}

	public void onConfigChanged(ConfigChanged event) {
		panel.refreshPanel();
	}


	@Provides
	DropTrackerPluginConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DropTrackerPluginConfig.class);
	}
	public String[] getGroupMembers() {
		synchronized (groupMembersLock) {
			return groupMembers;
		}
	}
	public String getLocalPlayerName() {
		if (client != null && client.getLocalPlayer() != null) {
			return client.getLocalPlayer().getName();
		}
		return null;
	}

	@Override
	protected void shutDown() throws Exception {
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
		accountHash = -1;
	}

	@Override
	protected void startUp() {
		grabServerConfiguration().thenRun(this::loadGroupMembersAsync);

		accountHash = client.getAccountHash();
		panel = new DropTrackerPanel(this, config, itemManager, chatMessageManager);
		navButton = NavigationButton.builder()
				.tooltip("Drop Tracker")
				.icon(ICON)
				.priority(6)
				.panel(panel)
				.build();
		clientToolbar.addNavigation(navButton);
		if (!prepared) {
			clientThread.invoke(() ->
			{
				switch (client.getGameState()) {
					case LOGGED_IN:
						// If the user is registered as a "LOGGED_IN" state, we can render the panel & check auth.
						if (config.serverId().equals("")) {
							//TODO: Send a chat message letting the user know the plugin is not yet set up
						} else if (config.authKey().equals("")) {
							//TODO: Message the user that their auth key has been left empty
						} else {
							//If we enter this else statement, the serverId is configured, and an auth key is entered.
							//Now, we can check authentication and render the dropPanel.
							prepared = true;
						}
					case LOGIN_SCREEN:
					case LOGIN_SCREEN_AUTHENTICATOR:
					case LOGGING_IN:
					case LOADING:
					case CONNECTION_LOST:
					case HOPPING:
						prepared = false;
						return false;
					default:
						return false;
				}
			});
		}
	}

	public String getServerName(String serverId) {
		if (serverId == "" || !serverIdToClanNameMap.containsKey(serverId)) {
			return "None!";
		}
		return serverIdToClanNameMap.get(serverId);
	}

	public int getServerMinimumLoot(String serverId) {
		if (serverId == "" || !serverMinimumLootVarMap.containsKey(serverId)) {
			return 0;
		}
		return serverMinimumLootVarMap.get(serverId);
	}

	public long getClanDiscordServerID(String serverId) {
		if (serverId == "" || !clanServerDiscordIDMap.containsKey(serverId)) {
			return 0;
		}
		return clanServerDiscordIDMap.get(serverId);
	}

	public int getServerWiseOldManGroupID(String serverId) {
		/* If empty serverId or the mapping doesn't contain the server ID, then return 0 */
		if (serverId == "" | !clanWiseOldManGroupIDMap.containsKey(serverId)) {
			return 0;
		}
		return clanWiseOldManGroupIDMap.get(serverId);
	}

	public Boolean clanEventActive(String serverId) {
		if (serverId == "" | !clanEventActiveMap.containsKey(serverId)) {
			return false;
		}
		return clanEventActiveMap.get(serverId);
	}
	public String getIconUrl(int id) {
		return String.format("https://static.runelite.net/cache/item/icon/%d.png", id);
	}

	public ItemComposition getItemComposition(int itemId) {
		// Must ensure this is being called on the client thread
		return itemManager.getItemComposition(itemId);
	}
	private CompletableFuture<Void> grabServerConfiguration() {
		return CompletableFuture.runAsync(() -> {
			String playerName = "";
			if(config.permPlayerName().equals("")) {
				playerName = client.getLocalPlayer().getName();
			} else {
				playerName = config.permPlayerName();
			}
			// Prepare the POST request with authToken and playerName
			MediaType mediaType = MediaType.parse("application/x-www-form-urlencoded");
			RequestBody body = RequestBody.create(mediaType,
					"auth_token=" + config.authKey() + "&player_name=" + playerName);

			Request request = new Request.Builder()
					.url("http://data.droptracker.io/admin/api/runelite_client_settings.php") // Update this URL
					.post(body)
					.addHeader("Content-Type", "application/x-www-form-urlencoded")
					.build();
			try {
				Response response = httpClient.newCall(request).execute();
				String jsonData = response.body().string();
				JSONObject jsonObject = new JSONObject(jsonData);
				serverIdToClanNameMap = new HashMap<>();
				serverMinimumLootVarMap = new HashMap<>();
				serverIdToConfirmedOnlyMap = new HashMap<>();
				clanWiseOldManGroupIDMap = new HashMap<>();
				clanEventActiveMap = new HashMap<>();

				// Extract server name directly
				String serverName = jsonObject.optString("server_name");

				// Assuming 'serverId' is provided some other way
				String serverId = config.serverId(); // This needs to be set appropriately

				// Parse the config field, which is a JSON string, into a JSONObject
				JSONObject configObject = new JSONObject(jsonObject.optString("config"));

				// Now extract data from the configObject

				serverIdToClanNameMap.put(
						serverId,
						serverName
				);
				serverMinimumLootVarMap.put(
						serverId,
						configObject.optInt("minimum_loot_for_notifications",1000000)
				);
				serverIdToConfirmedOnlyMap.put(
						serverId,
						configObject.optBoolean("store_only_confirmed_drops")
				);
				clanEventActiveMap.put(
						serverId,
						configObject.optBoolean("is_event_currently_active")
				);
				clanWiseOldManGroupIDMap.put(
						serverId,
						configObject.optInt("wise_old_man_group_id")
				);
				// Add other mappings as needed...

			} catch (JSONException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
	}

public CompletableFuture<Void> sendDropData(String playerName, String npcName, int itemId, String itemName, String memberList, int quantity, int geValue, int nonMembers, String authKey, String imageUrl) {
		HttpUrl url = HttpUrl.parse("http://droptracker.io/admin/api/store_drop_data.php"); // Replace with your PHP script URL
		String serverId = config.serverId();
		String notified_str = "1";
		if (geValue > getServerMinimumLoot(config.serverId())) {
			notified_str = "0";
		}
		FormBody.Builder formBuilder = new FormBody.Builder()
				.add("auth_token", authKey) // Use "auth_token" if using authToken
				.add("item_name", itemName)
				.add("item_id", String.valueOf(itemId))
				.add("player_name", playerName) // Make sure this is the correct ID format
				.add("server_id", serverId)
				.add("quantity", String.valueOf(quantity))
				.add("value", String.valueOf(geValue))
				.add("nonmember", String.valueOf(nonMembers))
				.add("member_list", memberList)
				.add("image_url", imageUrl)
				.add("npc_name", npcName)
				.add("notified", notified_str);

		Request request = new Request.Builder()
				.url(url)
				.post(formBuilder.build())
				.build();

		return CompletableFuture.runAsync(() -> {
			try (Response response = httpClient.newCall(request).execute()) {
				if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

				// Handle response
				String responseData = response.body().string();

				// Further processing based on the response
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
	}

	// `` Send screenshots directly to the DropTracker dedicated server
	// `` This allows the DropTracker discord bot to store actual images of players' drops
	private CompletableFuture<String> getScreenshot(String playerName, int itemId) {
		CompletableFuture<String> future = new CompletableFuture<>();

		try {
			String serverId = config.serverId();
			drawManager.requestNextFrameListener(image -> {
				try {
					ItemComposition itemComp = itemManager.getItemComposition(itemId);
					String itemName = itemComp.getName();
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					ImageIO.write((RenderedImage) image, "png", baos);
					byte[] imageData = baos.toByteArray();
					//remove spaces to write the filename nicely to the server.
					String nicePlayerName = playerName.replace(" ", "_");

					RequestBody requestBody = new MultipartBody.Builder()
							.setType(MultipartBody.FORM)
							.addFormDataPart("file", nicePlayerName + "_" + itemName + ".png",
									RequestBody.create(MediaType.parse("image/png"), imageData))
							.build();
						executor.submit(() -> {
							Request request = new Request.Builder()
									.url("http://data.droptracker.io/upload/upload.php") // PHP upload script for screenshots (temporary implementation)
									.post(requestBody)
									.build();
							try (Response response = httpClient.newCall(request).execute()) {
								if (!response.isSuccessful()) {
									throw new IOException("Unexpected response code: " + response);
								}

								String responseBody = response.body().string();
								future.complete(responseBody.trim());
							} catch (IOException e) {
								future.completeExceptionally(e);  // if there's an exception, complete the future exceptionally
							}
						});
					} catch (IOException e) {
						future.completeExceptionally(e);
					}
				});
		} catch (Exception e) {
			future.completeExceptionally(e);
			executor.shutdown();
		}
		return future;
	}
}