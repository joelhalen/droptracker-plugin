package com.joelhalen.droptracker;

import com.google.inject.Provides;
import net.runelite.api.*;
import net.runelite.api.events.MenuOpened;
import net.runelite.client.callback.ClientThread;
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
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
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
	private ClientThread clientThread;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private DrawManager drawManager;
	private long accountHash = -1;
	private Map<String, String> serverIdToWebhookUrlMap;
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
				if (shouldSend) {
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

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		String serverId = config.serverId();
		if(serverId == "")
		{
			return;
		}
		if (event.getMenuEntries().length < 2)
		{
			return;
		}
		MenuEntry entry = event.getMenuEntries()[1];

		String entryTarget = entry.getTarget();
		if (entryTarget.equals(""))
		{
			entryTarget = entry.getOption();
		}

		if (!entryTarget.toLowerCase().endsWith(COLLECTION_LOG_STRING.toLowerCase()))
		{
			return;
		}
		System.out.println(entryTarget);

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
				.url("http://instinctmc.world/data/server-hooks.json")
				.build();

		try {
			Response response = httpClient.newCall(request).execute();
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



	public CompletableFuture<Void> sendEmbedWebhook(String playerName, String npcName, int npcCombatLevel, int itemId, int quantity, int geValue, int haValue) {
		//SwingUtilities.invokeLater(() -> {
		//System.out.println("Grabbing item name using ID.");
		AtomicReference<String> itemNameRef = new AtomicReference<>();
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
				clientThread.invokeLater(() -> {
					ItemComposition itemComp = itemManager.getItemComposition(itemId);
					String itemName = itemComp.getName();
					itemNameRef.set(itemName);
				});
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
						Response response = httpClient.newCall(request).execute();
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

	private CompletableFuture<java.awt.Image> getScreenshot()
	{
		CompletableFuture<java.awt.Image> f = new CompletableFuture<>();
		drawManager.requestNextFrameListener(screenshotImage ->
		{
			f.complete(screenshotImage);
		});
		return f;
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