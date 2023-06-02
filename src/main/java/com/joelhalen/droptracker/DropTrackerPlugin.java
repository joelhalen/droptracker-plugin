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

		``` This plugin is meant to provide an automated integration with server Loot Leaderboards, using Discord webhooks.
		A decent bit of this code was pulled from pre-existing repositories for RuneLite plugins.
		Mainly:
		- Discord Rare Drop Notificator (onLootReceived events, sending webhooks+creating embeds)
		- COX and TOB data tracker - learned how to create a panel, borrowed some code
		For support, contact me on GitHub: https://github.com/joelhalen

 */
package com.joelhalen.droptracker;

import com.google.inject.Provides;

import net.runelite.api.*;
import net.runelite.api.events.AccountHashChanged;
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
import org.json.JSONArray;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@PluginDescriptor(
		name = "DropTracker",
		description = "Automatically uploads your drops to the DropTracker discord bot!",
		tags = {"droptracker", "drop", "webhook"}
)

public class DropTrackerPlugin extends Plugin {
	//TODO: Implement pet queues and collection log slot queues
	private static final String PET_RECEIVED_MESSAGE = "You have a strange feeling like you're";
	private static final String RAID_COMPLETE_MESSAGE = "You have a strange feeling like you would have";
	private static final String COLLECTION_LOG_STRING = "Collection log";
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
	@Inject
	private ClientThread clientThread;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private DrawManager drawManager;
	private long accountHash = -1;
	private Map<String, String> serverIdToWebhookUrlMap;
	private Map<String, Integer> serverMinimumLootVarMap;
	private Map<String, Long> clanServerDiscordIDMap;
	private Map<String, String> serverIdToClanNameMap;
	private Map<String, Boolean> serverIdToConfirmedOnlyMap;
	private boolean prepared = false;
	private static final Logger log = LoggerFactory.getLogger(DropTrackerPlugin.class);
	private static final BufferedImage ICON = ImageUtil.loadImageResource(DropTrackerPlugin.class, "icon.png");

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived npcLootReceived) {
		// handles drops from NPCs that are obtained on the floor (mostly)
		NPC npc = npcLootReceived.getNpc();
		String npcName = npc.getName();
		int npcCombatLevel = npc.getCombatLevel();
		String playerName = client.getLocalPlayer().getName();
		Collection<ItemStack> items = npcLootReceived.getItems();
		for (ItemStack item : items) {
			int itemId = item.getId();
			int quantity = item.getQuantity();
			List<CompletableFuture<Boolean>> futures = new ArrayList<>();
			// Make sure the quantity is 1, so that we aren't submitting item stacks that are above the specified value
			if (quantity > 1) {
				return;
			}
			//If quantity < 1 and geValue > clanValue, then we can assume the drop will be trackable.
			int geValue = itemManager.getItemPrice(itemId);
			int haValue = itemManager.getItemComposition(itemId).getHaPrice();
			ItemComposition itemComp = itemManager.getItemComposition(itemId);
			String itemName = itemComp.getName();
			boolean ignoreDrops = config.ignoreDrops();
			SwingUtilities.invokeLater(() -> {
				shouldSendItem(geValue).thenAccept(shouldSend -> {
					String serverId = config.serverId();
					Integer clanMinimumLoot = serverMinimumLootVarMap.get(serverId);
					if (shouldSend) {
						if (geValue < clanMinimumLoot) {

						} else {
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
							// Is the discord server accepting a non-confirmed stream of items?
							// otherwise, we won't send an embed until the item is "submitted" from the panel.
							if (serverIdToConfirmedOnlyMap.get(serverId) != true) {
								sendEmbedWebhook(playerName, npcName, npcCombatLevel, itemId, quantity, geValue, haValue);
							}
							DropEntry entry = new DropEntry();
							entry.setPlayerName(playerName);
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
					}
				});
			});
		}
	}

	private NavigationButton navButton;

	@Subscribe
	public void onLootReceived(LootReceived lootReceived) {
		//ignore regular NPC drops; since onNpcLootReceived contains more data on the source of the drop
		if (lootReceived.getType() == LootRecordType.NPC) {
			return;
		}

		String eventName = lootReceived.getName();
		Collection<ItemStack> items = lootReceived.getItems();
		for (ItemStack item : items) {
			int itemId = item.getId();
			int quantity = item.getQuantity();
			List<CompletableFuture<Boolean>> futures = new ArrayList<>();
			// Get the item's value
			boolean ignoreDrops = config.ignoreDrops();
			ItemComposition itemComp = itemManager.getItemComposition(itemId);
			String itemName = itemComp.getName();
			if (quantity > 1) {
				return;
			}
			int geValue = itemManager.getItemPrice(itemId);
			int haValue = itemManager.getItemComposition(itemId).getHaPrice();
			shouldSendItem(geValue).thenAccept(shouldSend -> {
				if (shouldSend) {
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
					sendEmbedWebhook(client.getLocalPlayer().getName(), eventName, 0, itemId, quantity, geValue, haValue);
					DropEntry entry = new DropEntry();
					entry.setPlayerName(client.getLocalPlayer().getName());
					entry.setNpcOrEventName(eventName);
					entry.setNpcCombatLevel(0);
					entry.setGeValue(geValue);
					entry.setHaValue(haValue);
					entry.setItemName(itemName);
					entry.setItemId(itemId);
					entry.setQuantity(quantity);
					panel.addDrop(entry);
				}
			});
		}
	}

	@Subscribe
	public void onAccountHashChanged(AccountHashChanged e) {
		if (accountHash == client.getAccountHash()) {
			return;
		}
		accountHash = client.getAccountHash();
		SwingUtilities.invokeLater(panel::refreshPanel);
	}

	//There is probably a better way of doing this, but this will work for now.
	@Subscribe
	public void onGameTick(GameTick event) {
		/* Refresh the panel every time the game state changes, or the player's local name changes */
		if ((client.getGameState() == GameState.LOGGED_IN) && (client.getLocalPlayer().getName() != null) && (!client.getLocalPlayer().getName().equals(currentPlayerName))) {
			currentPlayerName = client.getLocalPlayer().getName();
			if (!panelRefreshed) {
				log.debug("[DropTracker] Updating panel due to new player state");
				panel.refreshPanel();
				panelRefreshed = true;
			}
		} else {
			panelRefreshed = false;
		}
	}

	public void onConfigChanged(ConfigChanged event) {
		if (event.getGroup().equals(CONFIG_GROUP)) {
			SwingUtilities.invokeLater(() -> panel.refreshPanel());
		}
	}


	@Provides
	DropTrackerPluginConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DropTrackerPluginConfig.class);
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
		initializeServerIdToWebhookUrlMap();

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

	public String getIconUrl(int id) {
		return String.format("https://static.runelite.net/cache/item/icon/%d.png", id);
	}

	public ItemComposition getItemComposition(int itemId) {
		// Must ensure this is being called on the client thread
		return itemManager.getItemComposition(itemId);
	}

	private void initializeServerIdToWebhookUrlMap() {
		CompletableFuture.runAsync(() -> {

			Request request = new Request.Builder()
					//for now, store the server IDs and corresponding webhook URLs in a simple JSON-formatted file
					//this way we can add servers simply, without having to push updates to the plugin for each new server.
					//there is probably a much better way of approaching this, but I don't find that the server IDs/URLs are important to keep safe.
					.url("http://instinctmc.world/data/server_settings.json")
					.build();

			try {
				Response response = httpClient.newCall(request).execute();
				String jsonData = response.body().string();
				JSONArray jsonArray = new JSONArray(jsonData);
				serverIdToWebhookUrlMap = new HashMap<>();
				serverIdToClanNameMap = new HashMap<>();
				serverMinimumLootVarMap = new HashMap<>();
				serverIdToConfirmedOnlyMap = new HashMap<>();
				clanServerDiscordIDMap = new HashMap<>();
				//TODO: Implement a PHP request to get webhookUrls, so that they're not easily read
				for (int i = 0; i < jsonArray.length(); i++) {
					JSONObject jsonObject = jsonArray.getJSONObject(i);
					serverIdToWebhookUrlMap.put(
							jsonObject.getString("serverId"),
							jsonObject.getString("webhookUrl")
					);
					serverIdToClanNameMap.put(
							jsonObject.getString("serverId"),
							jsonObject.getString("serverName")
					);
					serverMinimumLootVarMap.put(
							jsonObject.getString("serverId"),
							jsonObject.getInt("minimumLoot")
					);
					serverIdToConfirmedOnlyMap.put(
							jsonObject.getString("serverId"),
							jsonObject.getBoolean("confOnly")
					);
					clanServerDiscordIDMap.put(
							jsonObject.getString("serverId"),
							jsonObject.getLong("discordServerId")
					);
				}
			} catch (JSONException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
	}

	public CompletableFuture<Void> sendConfirmedWebhook(String playerName, String npcName, int npcCombatLevel, int itemId, String itemName, String memberList, int quantity, int geValue, int nonMembers, String authKey, String imageUrl) {
		ChatMessageBuilder messageResp = new ChatMessageBuilder();
		messageResp.append(ChatColorType.NORMAL);
		CompletableFuture<String> uploadFuture = getScreenshot(playerName, itemId);
		return CompletableFuture.runAsync(() -> {
			String serverId = config.serverId();
			String webhookUrl = serverIdToWebhookUrlMap.get(serverId);
			int minimumClanLoot = serverMinimumLootVarMap.get(serverId);
			if (webhookUrl == null) {
				return;
			}
			if (config.ignoreDrops()) {
				return;
			}
			if (geValue < minimumClanLoot) {
				ChatMessageBuilder belowValueResp = new ChatMessageBuilder();
				// TODO:Send a chat message in-game informing the player their drop didn't qualify?
				belowValueResp.append(ChatColorType.NORMAL)
						.append("Your submission (")
						.append(ChatColorType.HIGHLIGHT)
						.append(itemName)
						.append(ChatColorType.NORMAL)
						.append(") (")
						.append(String.valueOf(geValue))
						.append(") did not meet the required ")
						.append(ChatColorType.HIGHLIGHT)
						.append(String.valueOf(minimumClanLoot))
						.append(ChatColorType.NORMAL)
						.append("gp minimum value for ")
						.append(serverIdToClanNameMap.get(serverId))
						.append(ChatColorType.NORMAL)
						.append("'s loot leaderboard.");
				chatMessageManager.queue(QueuedMessage.builder()
						.type(ChatMessageType.CONSOLE)
						.runeLiteFormattedMessage(belowValueResp.build())
						.build());
			} else {
				JSONObject json = new JSONObject();
				JSONObject embedJson = new JSONObject();
				embedJson.put("title", "```CONFIRMED DROP```");
				embedJson.put("description", "");
				embedJson.put("color", 15258703);

				JSONObject memberField = new JSONObject();
				memberField.put("name", "Clan Members");
				memberField.put("value", memberList);
				memberField.put("inline", true);

				JSONObject nonMemberField = new JSONObject();
				nonMemberField.put("name", "Non Member Count");
				nonMemberField.put("value", nonMembers);
				nonMemberField.put("inline", true);

				JSONObject itemNameField = new JSONObject();
				itemNameField.put("name", "Item name");
				itemNameField.put("value", "```\n" + itemName + "```");
				itemNameField.put("inline", true);

				JSONObject geValueField = new JSONObject();
				geValueField.put("name", "Value");
				geValueField.put("value", geValue);
				geValueField.put("inline", true);

				JSONObject playerAuthToken = new JSONObject();
				playerAuthToken.put("name", "auth");
				playerAuthToken.put("value", "`" + authKey + "`");
				playerAuthToken.put("inline", false);

				JSONObject npcOrEventField = new JSONObject();
				npcOrEventField.put("name", "From");
				if (npcCombatLevel > 0) {
					npcOrEventField.put("value", "```" + npcName + "(lvl: " + npcCombatLevel + ")```");
				} else {
					npcOrEventField.put("value", "```" + npcName + "```");
				}
				npcOrEventField.put("inline", true);
				String serverName = serverIdToClanNameMap.get(serverId);
				JSONObject footer = new JSONObject();
				footer.put("text", serverName + " (ID #" + serverId + ") Support: http://discord.gg/instinct");

				JSONObject author = new JSONObject();
				author.put("name", "" + playerName);

				JSONObject thumbnail = new JSONObject();
				if(imageUrl != "none") {
					thumbnail.put("url", imageUrl);
					embedJson.put("thumbnail", thumbnail);
				}

				// Add fields to embed
				embedJson.append("fields", playerAuthToken);
				embedJson.append("fields", itemNameField);
				embedJson.append("fields", geValueField);
				if (!memberList.equals("")) {
					embedJson.append("fields", memberField);
				}
				if (nonMembers > 0) {
					embedJson.append("fields", nonMemberField);
				}
				embedJson.append("fields", npcOrEventField);
				embedJson.put("footer", footer);
				embedJson.put("author", author);
				json.append("embeds", embedJson);

				RequestBody body = RequestBody.create(
						MediaType.parse("application/json; charset=utf-8"),
						json.toString()
				);

				Request request = new Request.Builder()
						.url(webhookUrl)
						.post(body)
						.build();
				//ChatMessageBuilder messageResp = new ChatMessageBuilder();
				try {
					Response response = httpClient.newCall(request).execute();
					response.close();
					messageResp.append("[")
							.append(ChatColorType.HIGHLIGHT)
							.append("DropTracker")
							.append(ChatColorType.NORMAL)
							.append("] Successfully submitted ")
							.append(ChatColorType.HIGHLIGHT)
							.append(itemName)
							.append(ChatColorType.NORMAL)
							.append(" to ")
							.append(ChatColorType.HIGHLIGHT)
							.append(serverIdToClanNameMap.get(serverId))
							.append(ChatColorType.NORMAL)
							.append("'s loot leaderboard!");
					chatMessageManager.queue(QueuedMessage.builder()
							.type(ChatMessageType.CONSOLE)
							.runeLiteFormattedMessage(messageResp.build())
							.build());
				} catch (IOException e) {
					ChatMessageBuilder errorMessageResp = new ChatMessageBuilder();
					errorMessageResp.append("[")
							.append(ChatColorType.HIGHLIGHT)
							.append("DropTracker")
							.append(ChatColorType.NORMAL)
							.append("] Your drop: ")
							.append(ChatColorType.HIGHLIGHT)
							.append(itemName)
							.append(ChatColorType.NORMAL)
							.append(" could not be submitted to ")
							.append(serverIdToClanNameMap.get(serverId))
							.append("'s loot leaderboard. Try again later!");
					chatMessageManager.queue(QueuedMessage.builder()
							.type(ChatMessageType.CONSOLE)
							.runeLiteFormattedMessage(errorMessageResp.build())
							.build());
					e.printStackTrace();
				}
			}
		});
	}

	public CompletableFuture<Void> sendEmbedWebhook(String playerName, String npcName, int npcCombatLevel, int itemId, int quantity, int geValue, int haValue) {
		AtomicReference<String> itemNameRef = new AtomicReference<>();
		CompletableFuture<String> uploadFuture = getScreenshot(playerName, itemId);
		return CompletableFuture.runAsync(() -> {
			String serverId = config.serverId();
			Integer serverMinimum = serverMinimumLootVarMap.get(serverId);
			//Check if the serverId has specified in our properties file
			//that they want to >only< send confirmed drops from the plugin panel.
			//if so, cancel the webhook being sent
			String webhookUrl = serverIdToWebhookUrlMap.get(serverId);
			//return in the case that the drop doesn't meet requirements
			if (webhookUrl == null) {
				return;
			}
			if (config.ignoreDrops()) {
				return;
			}
			if (geValue < serverMinimum) {
				return;
			}
			boolean sendHooks = serverIdToConfirmedOnlyMap.get(serverId);
			//grab the item name
			clientThread.invokeLater(() -> {
				ItemComposition itemComp = itemManager.getItemComposition(itemId);
				String itemName = itemComp.getName();
				itemNameRef.set(itemName);
			});
			if (sendHooks) {
				//cancel the operation if the server the user has defined doesn't want submissions that aren't confirmed
				//send a message letting them know the drop was added to their panel.

				return;
			}

			JSONObject json = new JSONObject();
			JSONObject embedJson = new JSONObject();
			// Setting up the embed.
			embedJson.put("title", "DropTracker Plugin Submission");
			embedJson.put("description", "");
			embedJson.put("color", 15258703);

			JSONObject itemNameField = new JSONObject();
			itemNameField.put("name", "Item name");
			itemNameField.put("value", "```\n" + itemNameRef.get() + "```");
			itemNameField.put("inline", true);

			JSONObject geValueField = new JSONObject();
			geValueField.put("name", "Value");
			geValueField.put("value", "```fix\n" + geValue + " GP```");
			geValueField.put("inline", true);

			JSONObject npcOrEventField = new JSONObject();
			npcOrEventField.put("name", "From");
			if (npcCombatLevel > 0) {
				npcOrEventField.put("value", "```" + npcName + "(lvl: " + npcCombatLevel + ")```");
			} else {
				npcOrEventField.put("value", "```" + npcName + "```");
			}
			npcOrEventField.put("inline", true);

			JSONObject footer = new JSONObject();
			footer.put("text", "(DropTracker Server ID #" + serverId + ") http://discord.gg/instinct");

			JSONObject author = new JSONObject();
			author.put("name", "" + playerName);

			String img_url = uploadFuture.join(); // Wait for the CompletableFuture to complete
			JSONObject thumbnail = new JSONObject();
			thumbnail.put("url", img_url);

			// Add fields to embed
			//embedJson.append("fields", quantityField);
			embedJson.append("fields", itemNameField);
			embedJson.append("fields", geValueField);
			embedJson.append("fields", npcOrEventField);
			embedJson.put("footer", footer);
			embedJson.put("author", author);
			embedJson.put("thumbnail", thumbnail);
			json.append("embeds", embedJson);

			RequestBody body = RequestBody.create(
					MediaType.parse("application/json; charset=utf-8"),
					json.toString()
			);

			Request request = new Request.Builder()
					.url(webhookUrl)
					.post(body)
					.build();

			try {
				if (config.sendChatMessages()) {
					ChatMessageBuilder addedDropToPanelMessage = new ChatMessageBuilder();
					addedDropToPanelMessage.append("[")
							.append(ChatColorType.HIGHLIGHT)
							.append("DropTracker")
							.append(ChatColorType.NORMAL)
							.append("] Sent ")
							.append(ChatColorType.HIGHLIGHT)
							.append(itemNameRef.get())
							.append(ChatColorType.NORMAL)
							.append(" automatically to your server's discord webhook.");
					chatMessageManager.queue(QueuedMessage.builder()
							.type(ChatMessageType.CONSOLE)
							.runeLiteFormattedMessage(addedDropToPanelMessage.build())
							.build());
				}
				Response response = httpClient.newCall(request).execute();
				response.close();
			} catch (IOException e) {
				e.printStackTrace();
			}

		});
	}

	private CompletableFuture<Boolean> shouldSendItem(int geValue) {
		String serverId = config.serverId();
		int minimumClanValue = serverMinimumLootVarMap.get(serverId);
		boolean ignoreDrops = config.ignoreDrops();
		if (!ignoreDrops) {
			if (geValue > minimumClanValue) {
				return CompletableFuture.completedFuture(true);
			} else {
				return CompletableFuture.completedFuture(false);
			}
		} else return CompletableFuture.completedFuture(false);
	}


	// `` Send screenshots directly to the DropTracker dedicated server
	// `` This allows the DropTracker discord bot to store actual images of players' drops
	private CompletableFuture<String> getScreenshot(String playerName, int itemId) {
		CompletableFuture<String> future = new CompletableFuture<>();

		try {
			String serverId = config.serverId();
			String webhookUrl = serverIdToWebhookUrlMap.get(serverId);

			if (webhookUrl == null) {
				future.complete(null);
			} else {
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
									.url("http://instinctmc.world/upload/upload.php") // PHP upload script for screenshots (temporary implementation)
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
			}
		} catch (Exception e) {
			future.completeExceptionally(e);
			executor.shutdown();
		}
		return future;
	}
}