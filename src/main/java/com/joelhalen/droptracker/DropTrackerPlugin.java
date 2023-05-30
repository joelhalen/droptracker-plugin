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
import net.runelite.api.events.MenuOpened;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
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
//	@Setter
//	private FileReadWriter fw = new FileReadWriter();
	//private boolean writerStarted = false;
	@Inject
	private DropTrackerPluginConfig config;
	@Inject
	private OkHttpClient httpClient;
	@Inject
	private ItemManager itemManager;
	@Inject
	private Client client;
	private DropTrackerPanel panel;
	@Inject
	private ChatMessageManager chatMessageManager;
	@Inject
	private ClientThread clientThread;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private DrawManager drawManager;
	private long accountHash = -1;
	private Map<String, String> serverIdToWebhookUrlMap;
	private Map<String, Integer> serverMinimumLootVarMap;
	private Map<String, String> serverIdToClanNameMap;
	private Map<String, Boolean> serverIdToConfirmedOnlyMap;
	private boolean prepared = false;
	private static final Logger log = LoggerFactory.getLogger(DropTrackerPlugin.class);
	private static final BufferedImage ICON = ImageUtil.loadImageResource(DropTrackerPlugin.class, "icon.png");
	private DropTrackerPlugin dropTrackerPluginInst;

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
			// Get the item's value
			int geValue = itemManager.getItemPrice(itemId) * quantity;
			int haValue = itemManager.getItemComposition(itemId).getHaPrice() * quantity;
			ItemComposition itemComp = itemManager.getItemComposition(itemId);
			String itemName = itemComp.getName();
			boolean ignoreDrops = config.ignoreDrops();
			shouldSendItem(item.getId(), item.getQuantity()).thenAccept(shouldSend -> {
				String serverId = config.serverId();
				Integer clanMinimumLoot = serverMinimumLootVarMap.get(serverId);
				if (shouldSend) {
					if (geValue < clanMinimumLoot) {
						// TODO: Move this logic to shouldSendItem?
						System.out.println("Drop skipped -- below clan threshold of " + clanMinimumLoot);
					} else {
						if(config.sendChatMessages()) {
							ChatMessageBuilder addedDropToPanelMessage = new ChatMessageBuilder();
							addedDropToPanelMessage.append("[")
									.append(ChatColorType.HIGHLIGHT)
									.append("DropTracker")
									.append(ChatColorType.NORMAL)
									.append("] your ")
									.append(ChatColorType.HIGHLIGHT)
									.append(itemName)
									.append(ChatColorType.NORMAL)
									.append(" has been added to the RuneLite side panel for your review.");
							chatMessageManager.queue(QueuedMessage.builder()
									.type(ChatMessageType.CONSOLE)
									.runeLiteFormattedMessage(addedDropToPanelMessage.build())
									.build());
						}
						sendEmbedWebhook(playerName, npcName, npcCombatLevel, itemId, quantity, geValue, haValue);
						DropEntry entry = new DropEntry();
						entry.setPlayerName(playerName);
						entry.setNpcOrEventName(npcName);
						entry.setNpcCombatLevel(npcCombatLevel);
						entry.setGeValue(geValue);
						entry.setHaValue(haValue);
						entry.setItemName(itemName);
						entry.setItemId(itemId);
						entry.setQuantity(quantity);
						panel.addDrop(entry);
					}
				}
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
			int geValue = itemManager.getItemPrice(itemId) * quantity;
			int haValue = itemManager.getItemComposition(itemId).getHaPrice() * quantity;
			boolean ignoreDrops = config.ignoreDrops();
			ItemComposition itemComp = itemManager.getItemComposition(itemId);
			String itemName = itemComp.getName();
			shouldSendItem(item.getId(), item.getQuantity()).thenAccept(shouldSend -> {
				if (shouldSend) {
					if(config.sendChatMessages()) {
						ChatMessageBuilder addedDropToPanelMessage = new ChatMessageBuilder();
						addedDropToPanelMessage.append("[")
								.append(ChatColorType.HIGHLIGHT)
								.append("DropTracker")
								.append(ChatColorType.NORMAL)
								.append("] your ")
								.append(ChatColorType.HIGHLIGHT)
								.append(itemName)
								.append(ChatColorType.NORMAL)
								.append(" has been added to the RuneLite side panel for your review.");
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
	public void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals(CONFIG_GROUP))
		{
			return;
		} else {
			SwingUtilities.invokeLater(() -> panel.refreshPanel());
		}
	}

	@Provides
	DropTrackerPluginConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DropTrackerPluginConfig.class);
	}


	@Override
	protected void shutDown() throws Exception
	{
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
		accountHash = -1;
	}
	@Override
	protected void startUp() {
		initializeServerIdToWebhookUrlMap();
		panel = new DropTrackerPanel(this, config, itemManager);
		navButton = NavigationButton.builder()
				.tooltip("Drop Tracker")
				.icon(ICON)
				.priority(6)
				.panel(panel)
				.build();

		clientToolbar.addNavigation(navButton);

		accountHash = client.getAccountHash();

		if (!prepared)
		{
			clientThread.invoke(() ->
			{
				switch (client.getGameState())
				{
					case LOGIN_SCREEN:
					case LOGIN_SCREEN_AUTHENTICATOR:
					case LOGGING_IN:
					case LOADING:
					case LOGGED_IN:
					case CONNECTION_LOST:
					case HOPPING:
						prepared = true;
						return true;
					default:
						return false;
				}
			});
		}
	}
	public String getServerName(String serverId) {
		return serverIdToClanNameMap.get(serverId);
	}
	public int getServerMinimumLoot(String serverId) {
		return serverMinimumLootVarMap.get(serverId);
	}
	public boolean getConfirmedOnlySetting(boolean serverId) {
		return serverIdToConfirmedOnlyMap.get(serverId);
	}
	public String getIconUrl(int id)
	{
		return String.format("https://static.runelite.net/cache/item/icon/%d.png", id);
	}
	public ItemComposition getItemComposition(int itemId) {
		// Must ensure this is being called on the client thread
		return itemManager.getItemComposition(itemId);
	}
	private void initializeServerIdToWebhookUrlMap() {

		Request request = new Request.Builder()
				//for now, store the server IDs and corresponding webhook URLs in a simple JSON-formatted file
				//this way we can add servers simply, without having to push updates to the plugin for each new server.
				//there is probably a much better way of approaching this, but I don't find that the server IDs/URLs are important to keep safe.
				.url("http://instinctmc.world/data/serverSettings.json")
				.build();

		try {
			Response response = httpClient.newCall(request).execute();
			String jsonData = response.body().string();
			JSONArray jsonArray = new JSONArray(jsonData);
			serverIdToWebhookUrlMap = new HashMap<>();
			serverIdToClanNameMap = new HashMap<>();
			serverMinimumLootVarMap = new HashMap<>();
			serverIdToConfirmedOnlyMap = new HashMap<>();

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
			}
		} catch (JSONException e) {
			e.printStackTrace();
		} catch	(IOException e) {
			e.printStackTrace();
		}
	}

	public CompletableFuture<Void> sendConfirmedWebhook(String playerName, String npcName, int npcCombatLevel, int itemId, String itemName, String memberList, int quantity, int geValue, int nonMembers) {
		//SwingUtilities.invokeLater(() -> {
		//System.out.println("Grabbing item name using ID.");
		ChatMessageBuilder messageResp = new ChatMessageBuilder();
		messageResp.append(ChatColorType.NORMAL);

		CompletableFuture<String> uploadFuture = getScreenshot(playerName, itemId);
		return CompletableFuture.runAsync(() -> {
			String serverId = config.serverId();
			String webhookUrl = serverIdToWebhookUrlMap.get(serverId);
			int minimumClanLoot = serverMinimumLootVarMap.get(serverId);
			//System.out.println(thisItem);
			//String itemName = itemComp.getName();
			//System.out.println("item ID " + itemId +"'s  name is " + itemName);
			if (webhookUrl == null) {
				System.out.println("No webhook URL assigned!");
				return;
			}
			if (config.ignoreDrops()) {
				System.out.println("Tracker is disabled in configuration!");
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
				System.out.println("Drop received (" + geValue + "gp) is below the CLAN threshold set of " + minimumClanLoot);
			} else {
				//System.out.println("Sending webhook to " + webhookUrl);
				JSONObject json = new JSONObject();
				JSONObject embedJson = new JSONObject();
				// Setting up the embed.
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
				String serverName = serverIdToClanNameMap.get(serverId);
				JSONObject footer = new JSONObject();
				footer.put("text", serverName + " (ID #" + serverId + ") Support: http://discord.gg/instinct");

				JSONObject author = new JSONObject();
				author.put("name", "" + playerName);

				String img_url = uploadFuture.join(); // Wait for the CompletableFuture to complete
				JSONObject thumbnail = new JSONObject();
				thumbnail.put("url", img_url);

				// Add fields to embed
				//embedJson.append("fields", quantityField);
				embedJson.append("fields", itemNameField);
				embedJson.append("fields", geValueField);
				if(!memberList.equals("")) {
					embedJson.append("fields", memberField);
				}
				if(nonMembers > 0) {
					embedJson.append("fields", nonMemberField);
				}
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
				//ChatMessageBuilder messageResp = new ChatMessageBuilder();
				try {
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
					Response response = httpClient.newCall(request).execute();
					response.close();
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
					System.err.println("Failed to send webhook: " + e.getMessage());
					e.printStackTrace();
				}
			}
		});
	}

	public CompletableFuture<Void> sendEmbedWebhook(String playerName, String npcName, int npcCombatLevel, int itemId, int quantity, int geValue, int haValue) {
		//SwingUtilities.invokeLater(() -> {
		//System.out.println("Grabbing item name using ID.");
		AtomicReference<String> itemNameRef = new AtomicReference<>();
		CompletableFuture<String> uploadFuture = getScreenshot(playerName, itemId);
		return CompletableFuture.runAsync(() -> {
			String serverId = config.serverId();
			Integer serverMinimum = serverMinimumLootVarMap.get(serverId);
			//Check if the serverId has specified in our properties file
			//that they want to >only< send confirmed drops from the plugin panel.
			//if so, cancel the webhook being sent
			String webhookUrl = serverIdToWebhookUrlMap.get(serverId);
			//System.out.println(thisItem);
			//String itemName = itemComp.getName();
			//System.out.println("item ID " + itemId +"'s  name is " + itemName);
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
				if(sendHooks) {
					//cancel the operation if the server the user has defined doesn't want submissions that aren't confirmed
					//send a message letting them know the drop was added to their panel.

					return;
				}

				//System.out.println("Sending webhook to " + webhookUrl);
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
					if(config.sendChatMessages()) {
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
					System.err.println("Failed to send webhook: " + e.getMessage());
				}

		});
	}

	private CompletableFuture<Boolean> shouldSendItem(int itemId, int quantity) {
		int gePrice = itemManager.getItemPrice(itemId);
		int geValue = gePrice * quantity;
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
		if (!config.sendScreenshots()) {
			String wikiUrl = getIconUrl(itemId);
			future.complete(wikiUrl);
		} else {
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
							ExecutorService executor = Executors.newSingleThreadExecutor();
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
							executor.shutdown();
						} catch (IOException e) {
							future.completeExceptionally(e);
						}
					});
				}
			} catch (Exception e) {
				future.completeExceptionally(e);
			}
		}
		return future;
	}
}