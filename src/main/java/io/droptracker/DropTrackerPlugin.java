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

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.inject.Inject;

import io.droptracker.api.DropTrackerApi;
import io.droptracker.api.FernetDecrypt;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.ui.DropTrackerPanel;
import io.droptracker.util.ChatMessageEvent;
import io.droptracker.util.KCService;
import io.droptracker.util.WidgetEvent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.annotations.Component;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageCapture;

import static net.runelite.http.api.RuneLiteAPI.GSON;

import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;
import okhttp3.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import okio.Buffer;

/* Re-written using the Discord Loot Logger base code */
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

	private NavigationButton newNavButton;
	@Inject
	private Gson gson;
	@Inject
	private ItemManager itemManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ImageCapture imageCapture;

	@Inject
	private OkHttpClient httpClient;
	public static final Set<String> SPECIAL_NPC_NAMES = Set.of("The Whisperer", "Araxxor","Branda the Fire Queen","Eldric the Ice King");

	@Inject
	private DrawManager drawManager;
	@Inject
	private ChatCommandManager chatCommandManager;

	@Inject
	private KCService kcService;

	/* REGEX FILTERS FOR CHAT MESSAGE DETECTION */
	@Inject
	public ChatMessageManager msgManager;
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	private String currentKillTime = "";
	private String currentPbTime = "";
	private String currentNpcName = "";
	private boolean readyToSendPb = false;
	private ScheduledFuture<?> skillDataResetTask = null;
	private final Object lock = new Object();
	public static List<String> webhookUrls = new ArrayList<>();

	private final ExecutorService executor = Executors.newCachedThreadPool();
	private static final BufferedImage PANEL_ICON = ImageUtil.loadImageResource(DropTrackerPlugin.class, "icon.png");
	private int timesTried = 0;
	@Inject
	public ChatMessageEvent chatMessageEventHandler;

	@Inject
	public WidgetEvent widgetEventHandler;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;

	// In the event of a 429 response from discord,
	// we'll force the user to wait 10 minutes before sending more webhooks
	// hopefully preventing them from being ip banned by discord
	private int timeToRetry = 0;

	public String pluginVersion = "3.81";

	public static final @Component int PRIVATE_CHAT_WIDGET = WidgetUtil.packComponentId(InterfaceID.PRIVATE_CHAT, 0);

	// Add a future to track loading state
	private CompletableFuture<Void> webhookUrlsLoaded = new CompletableFuture<>();

	@Override
	protected void startUp() {
		api = new DropTrackerApi(config, msgManager, gson, httpClient, client);
		if(config.showSidePanel()) {
			createSidePanel();
		}
		// Preload webhook URLs asynchronously
		executor.submit(this::loadWebhookUrls);

		chatCommandManager.registerCommandAsync("!droptracker", (chatMessage, s) -> {
			BiConsumer<ChatMessage, String> linkOpener = openLink("discord");
			if (linkOpener != null && chatMessage.getSender().equalsIgnoreCase(client.getLocalPlayer().getName())) {
				linkOpener.accept(chatMessage, s);
			}
		});
		chatCommandManager.registerCommandAsync("!loot", (chatMessage, s) -> {
			BiConsumer<ChatMessage, String> linkOpener = openLink("website");
			if (linkOpener != null && chatMessage.getSender().equalsIgnoreCase(client.getLocalPlayer().getName())) {
				linkOpener.accept(chatMessage, s);
			}
		});
	}
	private void createSidePanel() {
		panel = injector.getInstance(DropTrackerPanel.class);
		panel.init();


		navButton = NavigationButton.builder()
				.tooltip("DropTracker")
				.icon(PANEL_ICON)
				.priority(1)
				.panel(panel)
				.build();


		clientToolbar.addNavigation(navButton);
	}
	// New method to load webhook URLs in the background
	private void loadWebhookUrls() {
		try {
			if (webhookUrls.isEmpty()) {
				URL url = new URL("https://joelhalen.github.io/docs/crypt.json");
				BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()));
				StringBuilder response = new StringBuilder();
				String inputLine;
				while ((inputLine = in.readLine()) != null) {
					response.append(inputLine);
				}
				in.close();

				JsonArray jsonArray = new JsonParser().parse(response.toString()).getAsJsonArray();

				for (JsonElement element : jsonArray) {
					try {
						String encrypted = element.getAsString();
						try {
							String decryptedUrl = FernetDecrypt.decryptWebhook(encrypted);
							if (decryptedUrl.contains("discord")) {
								webhookUrls.add(decryptedUrl);
							} else {
								log.error("[DropTracker] Decrypted URL is not based on discord; skipping");
							}
						} catch (Exception e) {
							log.error("Decryption failed with error: " + e.getMessage());
						}
					} catch (Exception e) {
						log.error("Error processing element: " + e.getMessage());
					}
				}
			}
			webhookUrlsLoaded.complete(null);
		} catch (Exception e) {
			webhookUrlsLoaded.completeExceptionally(e);
		} finally {
			log.debug("Webhook URLs have been loaded successfully.");
		}
	}

	/**
	 * Grabs a random webhook URL from a preloaded list.
	 * If not loaded yet, throws or returns null.
	 */
	public String getRandomWebhookUrl() throws Exception {
		// Wait for URLs to be loaded, but don't block the main thread
		if (!webhookUrlsLoaded.isDone()) {
			throw new IllegalStateException("Webhook URLs are still loading, try again later.");
		}
		if (webhookUrls.isEmpty()) {
			throw new IllegalStateException("No valid webhook URLs were loaded");
		}
		Random randomP = new Random();
		return webhookUrls.get(randomP.nextInt(webhookUrls.size()));
	}

	private static String itemImageUrl(int itemId) {
		return "https://static.runelite.net/cache/item/icon/" + itemId + ".png";
	}

	private BiConsumer<ChatMessage, String> openLink(String destination) {
		HttpUrl webUrl = HttpUrl.parse("https://discord.gg/dvb7yP7JJH");
		if (destination == "website" && config.useApi()) {
			webUrl = HttpUrl.parse(api.getApiUrl());
		}
		HttpUrl.Builder urlBuilder = webUrl.newBuilder();
		HttpUrl url = urlBuilder.build();
		LinkBrowser.browse(url.toString());
		return null;
	}

	@Override
	protected void shutDown() {
		if(navButton != null) {
			clientToolbar.removeNavigation(navButton);
		}
		chatCommandManager.unregisterCommand("!droptracker");
		chatCommandManager.unregisterCommand("!loot");
		if (panel != null) {
			panel.deinit();
			panel = null;
		}
		executor.shutdown();
	}

	@Provides
	DropTrackerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DropTrackerConfig.class);
	}

	public boolean isFakeWorld() {
		var worldType = client.getWorldType();
		return worldType.contains(WorldType.BETA_WORLD)
				|| worldType.contains(WorldType.DEADMAN)
				|| worldType.contains(WorldType.FRESH_START_WORLD)
				|| worldType.contains(WorldType.LAST_MAN_STANDING)
				|| worldType.contains(WorldType.NOSAVE_MODE)
				|| worldType.contains(WorldType.PVP_ARENA)
				|| worldType.contains(WorldType.QUEST_SPEEDRUNNING)
				|| worldType.contains(WorldType.SEASONAL)
				|| worldType.contains(WorldType.TOURNAMENT_WORLD);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged) {
		if (configChanged.getGroup().equalsIgnoreCase(DropTrackerConfig.GROUP)) {
			if (configChanged.getKey().equals("useApi")) {
				panel.deinit();
				
				if(navButton != null) {
					clientToolbar.removeNavigation(navButton);
				}
				createSidePanel();
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
					if(navButton != null) {
						clientToolbar.removeNavigation(navButton);
					}
					panel.deinit();
					panel = null;
				} else {
					if(panel == null) {
						createSidePanel();
					}
				}
			}


			//sendChatReminder();
		}
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived npcLootReceived) {
		NPC npc = npcLootReceived.getNpc();
		Collection<ItemStack> items = npcLootReceived.getItems();
		kcService.onNpcKill(npcLootReceived);
		processDropEvent(npc.getName(), "npc", items);
		//sendChatReminder();
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived playerLootReceived) {
		Collection<ItemStack> items = playerLootReceived.getItems();
		processDropEvent(playerLootReceived.getPlayer().getName(), "pvp", items);
		kcService.onPlayerKill(playerLootReceived);
		//sendChatReminder();
	}

	@Subscribe
	public void onLootReceived(LootReceived lootReceived) {
		/* A select few npc loot sources will arrive here, instead of npclootreceived events */
		String npcName = chatMessageEventHandler.getStandardizedSource(lootReceived);

		if (lootReceived.getType() == LootRecordType.NPC && SPECIAL_NPC_NAMES.contains(npcName)) {

			if(npcName.equals("Branda the Fire Queen")|| npcName.equals("Eldric the Ice King")) {
				npcName = "Royal Titans";
			}
			processDropEvent(npcName, "npc", lootReceived.getItems());
			return;
		}
		if (lootReceived.getType() != LootRecordType.EVENT && lootReceived.getType() != LootRecordType.PICKPOCKET) {
			return;
		}
		processDropEvent(npcName, "other", lootReceived.getItems());
		kcService.onLoot(lootReceived);
		//sendChatReminder();
	}

	public String sanitize(String str) {
		if (str == null || str.isEmpty()) return "";
		return Text.removeTags(str.replace("<br>", "\n")).replace('\u00A0', ' ').trim();
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event) {
		chatMessageEventHandler.onScript(event.getScriptId());
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded widget) {
		widgetEventHandler.onWidgetLoaded(widget);
	}

	@Subscribe(priority = 1)
	public void onChatMessage(ChatMessage message) {
		String chatMessage = sanitize(message.getMessage());
		switch (message.getType()) {
			case GAMEMESSAGE:
				chatMessageEventHandler.onGameMessage(chatMessage);
				chatMessageEventHandler.onChatMessage(chatMessage);
			case FRIENDSCHATNOTIFICATION:
				chatMessageEventHandler.onFriendsChatNotification(chatMessage);
		}
		kcService.onGameMessage(chatMessage);
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		chatMessageEventHandler.onTick();
		widgetEventHandler.onGameTick(event);
	}


	private void scheduleKillTimeReset() {
		if (skillDataResetTask != null) {
			skillDataResetTask.cancel(false);
		}
		skillDataResetTask = scheduler.schedule(this::resetState, 500, TimeUnit.MILLISECONDS);
	}

	private void resetState() {
		synchronized (lock) {
			currentKillTime = "";
			currentPbTime = "";
			currentNpcName = "";
			readyToSendPb = false;
		}
	}

	private void processDropEvent(String npcName, String sourceType, Collection<ItemStack> items) {
		AtomicReference<Integer> finalValue = new AtomicReference<>(0);
		CustomWebhookBody customWebhookBody = new CustomWebhookBody();
		AtomicReference<StringBuilder> itemListBuilder = new AtomicReference<>(new StringBuilder());
		clientThread.invokeLater(() -> {
			if (sourceType != "pvp") {
				for (ItemStack item : stack(items)) {
					Item tempItem = new Item(item.getId(), item.getQuantity());
					int itemId = item.getId();
					int qty = item.getQuantity();
					int price = itemManager.getItemPrice(itemId);
					ItemComposition itemComposition = itemManager.getItemComposition(itemId);
					finalValue.set(qty * price);
					CustomWebhookBody.Embed itemEmbed = new CustomWebhookBody.Embed();
					itemEmbed.setImage(itemImageUrl(itemId));
					String accountHash = String.valueOf(client.getAccountHash());
					itemEmbed.addField("type", "drop", true);
					itemEmbed.addField("source_type", sourceType, true);
					itemEmbed.addField("acc_hash", accountHash, true);
					itemEmbed.addField("item", itemComposition.getName(), true);
					itemEmbed.addField("player", getLocalPlayerName(), true);
					itemEmbed.addField("id", String.valueOf(itemComposition.getId()), true);
					itemEmbed.addField("quantity", String.valueOf(qty), true);
					itemEmbed.addField("value", String.valueOf(price), true);
					itemEmbed.addField("source", npcName, true);
					itemEmbed.addField("type", sourceType, true);
					itemEmbed.addField("p_v",pluginVersion,true);
					itemEmbed.title = getLocalPlayerName() + " received some drops:";
					customWebhookBody.getEmbeds().add(itemEmbed);
				}
				customWebhookBody.setContent(getLocalPlayerName() + " received some drops:");
				if (!customWebhookBody.getEmbeds().isEmpty()) {
					sendDropTrackerWebhook(customWebhookBody, finalValue.get());
				}
			} else {
				/* PVP kills are basically completely ignored on the server side at the moment... */
				// Tries to send one message for the entire kill, since theoretically a PvP kill could be 70+ items at once

				itemListBuilder.get().append(getLocalPlayerName()).append(" received a PvP kill:\n");
				Integer totalValue = 0;
				boolean isFirstPart = true;

				for (ItemStack item : stack(items)) {
					int itemId = item.getId();
					int qty = item.getQuantity();
					int price = itemManager.getItemPrice(itemId);
					ItemComposition itemComposition = itemManager.getItemComposition(itemId);
					totalValue = totalValue + (qty * price);

					String itemDetails = "Item: " + itemComposition.getName()
							+ ", Quantity: " + qty
							+ ", Value: " + price
							+ ", Item ID: " + itemId + "\n";

					if (itemListBuilder.get().length() + itemDetails.length() >= 1800) {
						if (isFirstPart) {
							itemListBuilder.get().append("\np1");
							isFirstPart = false;
						} else {
							itemListBuilder.get().append("\np2");
						}
						itemListBuilder.get().append("\nFrom: ").append(npcName); // refers to the player name in this context
						customWebhookBody.setContent(itemListBuilder.toString());
						sendDropTrackerWebhook(customWebhookBody, finalValue.get());

						itemListBuilder.set(new StringBuilder());
						itemListBuilder.get().append(isFirstPart ? "\np1\n\n" : "\np2\n\n").append(getLocalPlayerName()).append(" received a PvP kill:\n");
					}

					itemListBuilder.get().append(itemDetails);
				}

				finalValue.set(totalValue);
				itemListBuilder.get().append("\nFrom: ").append(npcName); // refers to the player name in this context
				customWebhookBody.setContent(itemListBuilder.toString());
				sendDropTrackerWebhook(customWebhookBody, finalValue.get());
			}
		});

	}

	public String getLocalPlayerName() {
		if (client.getLocalPlayer() != null) {
			return client.getLocalPlayer().getName();
		} else {
			return "";
		}
	}
	public void sendDropTrackerWebhook(CustomWebhookBody webhook, String type) {
		/* Requires a type ID to be passed
		 * "1" = a "Kill Time" or "KC" submission
		 * "2" = a "Collection Log" submission
		 * "3" = a "Combat Achievement" submission
		 *  */
		Boolean requiredScreenshot = false;
		if (type.equalsIgnoreCase("1")) {
			// Kc / kill time
			List<CustomWebhookBody.Embed> embeds = webhook.getEmbeds();
			if(!config.pbEmbeds()){
				return;
			}
			if (config.screenshotPBs()) {
				for (CustomWebhookBody.Embed embed : embeds) {
					for (CustomWebhookBody.Field field : embed.getFields()) {
						if (field.getName().equalsIgnoreCase("is_pb")) {
							if (field.getValue().equalsIgnoreCase("true")) {
								requiredScreenshot = true;
							}
						}
					}
				}
			}

		}
		else if (type.equalsIgnoreCase("2")) {
			if(!config.clogEmbeds()){
				return;
			}
			if (config.screenshotNewClogs()) {
				requiredScreenshot = true;
			}
		} else if(type.equalsIgnoreCase("3")){ // combat achievements
			if(!config.caEmbeds()){
				return;
			}
			if (config.screenshotCAs()) {
				requiredScreenshot = true;
			}
		}

		if (requiredScreenshot) {
			boolean shouldHideDm = config.hideDMs();
			captureScreenshotWithPrivacy(webhook, shouldHideDm);
		} else {
			sendDropTrackerWebhook(webhook, (byte[]) null);
		}
	}


	public void sendDropTrackerWebhook(CustomWebhookBody customWebhookBody, int totalValue) {
		// Handles sending drops exclusively
		if(!config.lootEmbeds()){
			return;
		}
		if (config.screenshotDrops() && totalValue > config.screenshotValue()) {
			boolean shouldHideDm = config.hideDMs();
			captureScreenshotWithPrivacy(customWebhookBody, shouldHideDm);
		} else {
			sendDropTrackerWebhook(customWebhookBody, (byte[]) null);
		}
	}
	private void sendDropTrackerWebhook(CustomWebhookBody customWebhookBody, byte[] screenshot) {
		if (timeToRetry != 0 && timeToRetry > (int) (System.currentTimeMillis() / 1000)) {
			return;
		} else if (timeToRetry < (int) (System.currentTimeMillis() / 1000)) {
			timeToRetry = 0;
		}
		if (isFakeWorld()) {
			return;
		}
		this.timesTried++;
		MultipartBody.Builder requestBodyBuilder = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("payload_json", GSON.toJson(customWebhookBody));

		if (screenshot != null) {
			requestBodyBuilder.addFormDataPart("file", "image.jpeg",
					RequestBody.create(MediaType.parse("image/jpeg"), screenshot));
		}

		MultipartBody requestBody = requestBodyBuilder.build();
		for (MultipartBody.Part part : requestBody.parts()) {
			Headers headers = part.headers();
			if (headers != null) {
				for (int i = 0; i < headers.size(); i++) {
				}
			}

			// Try to read the body content
			RequestBody body = part.body();
			if (body != null) {
				// Safely check content type
				MediaType contentType = body.contentType();
				if (contentType != null && 
						(contentType.toString().contains("text") || 
						 contentType.toString().contains("json"))) {
					Buffer buffer = new Buffer();
					try {
						body.writeTo(buffer);
					} catch (IOException e) {
					}
				} else {
				}
			}
		}
		String url;
		try {
			url = getRandomWebhookUrl();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		HttpUrl u = HttpUrl.parse(url);
		if (u == null || !isValidDiscordWebhookUrl(u)) {
			log.debug("Invalid or malformed webhook URL: {}", url);
			return;
		}
		Request request = new Request.Builder()
				.url(url)
				.post(requestBody)
				.build();
		okHttpClient.newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(Call call, IOException e) {
				log.error("Error submitting: ", e);
				timesTried = 0;
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException {
				if (response.isSuccessful()) {
					timesTried = 0;
				} else if (response.code() == 429) {
					timeToRetry = (int) (System.currentTimeMillis() / 1000) + 600;
					sendDropTrackerWebhook(customWebhookBody, screenshot);

				} else if (response.code() == 400) {
					response.close();
					return;

				} else if(response.code() == 404){
					timeToRetry = (int) (System.currentTimeMillis() / 1000) + 600;
					sendDropTrackerWebhook(customWebhookBody, screenshot);

				} else {
					sendDropTrackerWebhook(customWebhookBody, screenshot);
				}
				response.close();
			}
		});

	}
	
	private boolean isValidDiscordWebhookUrl(HttpUrl url) {
		// Ensure that any webhook URLs returned from the GitHub page are actual Discord webhooks
		// And not external connections of some sort
		if (!"discord.com".equals(url.host()) && !url.host().endsWith(".discord.com")) {
			return false;
		}
		List<String> segments = url.pathSegments();
		if (segments.size() >= 4 && "api".equals(segments.get(0)) && "webhooks".equals(segments.get(1))) {
			return true;
		}

		return false;
	}
	private static byte[] convertImageToByteArray(BufferedImage bufferedImage) throws IOException {
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		ImageIO.write(bufferedImage, "jpeg", byteArrayOutputStream);
		return byteArrayOutputStream.toByteArray();
	}
	public static void modWidget(boolean shouldHide, Client client, ClientThread clientThread, @Component int info) {
		clientThread.invoke(() -> {
			Widget widget = client.getWidget(info);
			if (widget != null) {
				widget.setHidden(shouldHide);
			}
		});
	}
	private static Collection<ItemStack> stack(Collection<ItemStack> items) {
		final List<ItemStack> list = new ArrayList<>();

		for (final ItemStack item : items) {
			int quantity = 0;
			for (final ItemStack i : list) {
				if (i.getId() == item.getId()) {
					quantity = i.getQuantity();
					list.remove(i);
					break;
				}
			}
			if (quantity > 0) {
				list.add(new ItemStack(item.getId(), item.getQuantity() + quantity, item.getLocation()));
			} else {
				list.add(item);
			}
		}

		return list;
	}
	private void captureScreenshotWithPrivacy(CustomWebhookBody webhook, boolean hideDMs) {
		// First hide DMs if configured
		modWidget(hideDMs, client, clientThread, PRIVATE_CHAT_WIDGET);

		drawManager.requestNextFrameListener(image -> {
			BufferedImage bufferedImage = (BufferedImage) image;

			// Restore DM visibility immediately after capturing
			modWidget(false, client, clientThread, PRIVATE_CHAT_WIDGET);

			byte[] imageBytes = null;
			try {
				imageBytes = convertImageToByteArray(bufferedImage);
				if (imageBytes.length > 5 * 1024 * 1024) {
					// perform compression here
				}
			} catch (IOException e) {
				log.error("Error converting image to byte array", e);
			}
			sendDropTrackerWebhook(webhook, imageBytes);
		});
	}
}
