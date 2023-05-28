package com.joelhalen.droptracker;

import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.DrawManager;
import net.runelite.http.api.loottracker.LootRecordType;
import okhttp3.*;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;

import javax.inject.Inject;
import java.awt.image.RenderedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

@PluginDescriptor(
		name = "DropTracker",
		description = "Automatically uploads your drops to the DropTracker discord bot!",
		tags = {"droptracker", "drop", "webhook"}
)
public class DropTrackerPlugin extends Plugin {

	@Inject
	private DropTrackerPluginConfig config;
	@Inject
	private ItemManager itemManager;
	@Inject
	private Client client;
	@Inject
	private DrawManager drawManager;
	private Map<String, String> serverIdToWebhookUrlMap;
	private static final Logger log = LoggerFactory.getLogger(DropTrackerPlugin.class);

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived npcLootReceived) {
		// handles drops from NPCs that are obtained on the floor (mostly)
		NPC npc = npcLootReceived.getNpc();
		String npcName = npc.getName();
		int npcCombatLevel = npc.getCombatLevel();
		String playerName = client.getLocalPlayer().getName();
		Collection<ItemStack> items = npcLootReceived.getItems();
		Integer minimum_value = (int) config.minimumValue();
		for (ItemStack item : items) {
			int itemId = item.getId();
			int quantity = item.getQuantity();
			List<CompletableFuture<Boolean>> futures = new ArrayList<>();
			// Get the item's value
			int geValue = itemManager.getItemPrice(itemId) * quantity;
			int haValue = itemManager.getItemComposition(itemId).getHaPrice() * quantity;
			boolean ignoreDrops = config.ignoreDrops();
			shouldSendItem(item.getId(), item.getQuantity()).thenAccept(shouldSend -> {
				if (shouldSend) {
					sendEmbedWebhook(playerName, npcName, npcCombatLevel, itemId, quantity, geValue, haValue);
				}
			});
		}
	}

	@Subscribe
	public void onLootReceived(LootReceived lootReceived) {
		//ignore regular NPC drops; since onNpcLootReceived contains more data on the source of the drop
		if (lootReceived.getType() == LootRecordType.NPC) {
			return;
		}

		String eventName = lootReceived.getName();
		Collection<ItemStack> items = lootReceived.getItems();
		Integer minimum_value = (int) config.minimumValue();
		for (ItemStack item : items) {
			int itemId = item.getId();
			int quantity = item.getQuantity();
			List<CompletableFuture<Boolean>> futures = new ArrayList<>();
			// Get the item's value
			int geValue = itemManager.getItemPrice(itemId) * quantity;
			int haValue = itemManager.getItemComposition(itemId).getHaPrice() * quantity;
			boolean ignoreDrops = config.ignoreDrops();
			shouldSendItem(item.getId(), item.getQuantity()).thenAccept(shouldSend -> {
				if (shouldSend) {
					sendEmbedWebhook(client.getLocalPlayer().getName(), eventName, 0, itemId, quantity, geValue, haValue);
				}
			});
		}
	}

	@Provides
	DropTrackerPluginConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DropTrackerPluginConfig.class);
	}

	@Override
	protected void startUp() {
		initializeServerIdToWebhookUrlMap();
	}
	public String getIconUrl(int id)
	{
		return String.format("https://static.runelite.net/cache/item/icon/%d.png", id);
	}
	private void initializeServerIdToWebhookUrlMap() {
		OkHttpClient client = new OkHttpClient();

		Request request = new Request.Builder()
				//for now, store the server IDs and corresponding webhook URLs in a simple JSON-formatted file
				//this way we can add servers simply, without having to push updates to the plugin for each new server.
				//there is probably a much better way of approaching this, but I don't find that the server IDs/URLs are important to keep safe.
				.url("http://instinctmc.world/data/server-hooks.json")
				.build();

		try {
			Response response = client.newCall(request).execute();
			String jsonData = response.body().string();

			JSONArray jsonArray = new JSONArray(jsonData);
			serverIdToWebhookUrlMap = new HashMap<>();

			for (int i = 0; i < jsonArray.length(); i++) {
				JSONObject jsonObject = jsonArray.getJSONObject(i);
				serverIdToWebhookUrlMap.put(
						jsonObject.getString("serverId"),
						jsonObject.getString("webhookUrl")
				);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private CompletableFuture<Void> sendEmbedWebhook(String playerName, String npcName, int npcCombatLevel, int itemId, int quantity, int geValue, int haValue) {
		//System.out.println("Grabbing item name using ID.");
		ItemComposition itemComp = itemManager.getItemComposition(itemId);
		String itemName = itemComp.getName();

		CompletableFuture<String> uploadFuture = getScreenshot(playerName, itemId);
		return CompletableFuture.runAsync(() -> {
			String serverId = config.serverId();
			String webhookUrl = serverIdToWebhookUrlMap.get(serverId);
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
			if (geValue < config.minimumValue()) {
				System.out.println("Drop received (" + geValue + "gp) is below the threshold set of " + config.minimumValue());
				return;
			} else {
				//System.out.println("Sending webhook to " + webhookUrl);
				OkHttpClient client = new OkHttpClient();
				JSONObject json = new JSONObject();
				JSONObject embedJson = new JSONObject();
				// Setting up the embed.
				embedJson.put("title", "Webhook Submission");
				embedJson.put("description", "");
				//embedJson.put("description", "Details of the loot received from NPC: " + npcName + ", Combat Level: " + npcCombatLevel);
				embedJson.put("color", 15258703);

				JSONObject itemNameField = new JSONObject();
				itemNameField.put("name", "Item name");
				itemNameField.put("value", "```" + itemName + "```");
				itemNameField.put("inline", true);

				JSONObject geValueField = new JSONObject();
				geValueField.put("name", "Value");
				geValueField.put("value", "" + geValue + " GP");
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

//					System.out.println("Sending webhook to " + webhookUrl);
//					System.out.println("Payload: " + json.toString());
					Request request = new Request.Builder()
							.url(webhookUrl)
							.post(body)
							.build();

					try {
						Response response = client.newCall(request).execute();
//						System.out.println("Response code: " + response.code());
//						System.out.println("Response body: " + response.body().string());
						response.close();
					} catch (IOException e) {
						e.printStackTrace();
						System.err.println("Failed to send webhook: " + e.getMessage());
					}
				}
			});
	}

	private CompletableFuture<Boolean> shouldSendItem(int itemId, int quantity) {
		int gePrice = itemManager.getItemPrice(itemId);
		int geValue = gePrice * quantity;
		int minimum_value = (int) config.minimumValue();
		boolean ignoreDrops = config.ignoreDrops();
		if (geValue > minimum_value) {
			if (!ignoreDrops) {
				return CompletableFuture.completedFuture(true);
			} else {
				return CompletableFuture.completedFuture(false);
			}
		} else {
			return CompletableFuture.completedFuture(false);
		}
	}

	private CompletableFuture<Boolean> processEventNotification(LootRecordType type, String name, int itemId, int quantity)
	{
		return shouldSendItem(itemId, quantity).thenApply(shouldSend -> {
			if (shouldSend) {
				// Get the item's value
				int geValue = itemManager.getItemPrice(itemId) * quantity;
				int haValue = itemManager.getItemComposition(itemId).getHaPrice() * quantity;
				// Send the webhook with the player's name, event name, item id, quantity, and the item's value
				String playerName = client.getLocalPlayer().getName();
				sendEmbedWebhook(playerName, name, 0, itemId, quantity, geValue, haValue);
			}
			return shouldSend;
		});
	}

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
							String nicePlayerName = playerName.replace(" ", "_");
							OkHttpClient client = new OkHttpClient();

							RequestBody requestBody = new MultipartBody.Builder()
									.setType(MultipartBody.FORM)
									.addFormDataPart("file", nicePlayerName + "_" + itemName + ".png",
											RequestBody.create(MediaType.parse("image/png"), imageData))
									.build();

							Request request = new Request.Builder()
									.url("http://instinctmc.world/upload/upload.php") // PHP upload script for screenshots (temporary implementation)
									.post(requestBody)
									.build();

							try (Response response = client.newCall(request).execute()) {
								if (!response.isSuccessful()) {
									throw new IOException("Unexpected response code: " + response);
								}

								String responseBody = response.body().string();
								future.complete(responseBody.trim());
							} catch (IOException e) {
								future.completeExceptionally(e);
							}
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



	private CompletableFuture<Void> sendWebhook(String message) {
		return CompletableFuture.runAsync(() -> {
			String serverId = config.serverId();
			String webhookUrl = serverIdToWebhookUrlMap.get(serverId);

			if (webhookUrl == null) {
				return;
			}

			OkHttpClient client = new OkHttpClient();
			JSONObject json = new JSONObject();
			json.put("content", message);

			RequestBody body = RequestBody.create(
					MediaType.parse("application/json; charset=utf-8"),
					json.toString()
			);

			Request request = new Request.Builder()
					.url(webhookUrl)
					.post(body)
					.build();

			try {
				Response response = client.newCall(request).execute();
				//System.out.println("Response code: " + response.code());
				//System.out.println("Response message: " + response.message());
				response.close();
			} catch (IOException e) {
				e.printStackTrace();
				System.err.println("Failed to send webhook: " + e.getMessage());
			}
		});
	}
}