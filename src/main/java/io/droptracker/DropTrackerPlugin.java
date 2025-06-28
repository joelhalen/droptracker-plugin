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
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.inject.Inject;

import io.droptracker.api.DropTrackerApi;
import io.droptracker.api.FernetDecrypt;
import io.droptracker.events.CaHandler;
import io.droptracker.events.ClogHandler;
import io.droptracker.events.DropHandler;
import io.droptracker.events.PbHandler;
import io.droptracker.events.WidgetEvent;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.Drop;
import io.droptracker.ui.DropTrackerPanel;
import io.droptracker.util.ChatMessageUtil;
import io.droptracker.util.*;
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
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;

import static net.runelite.http.api.RuneLiteAPI.GSON;

import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;
import okhttp3.*;
import okio.Buffer;

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
	private ItemManager itemManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private DrawManager drawManager;
	@Inject
	private ChatCommandManager chatCommandManager;

	@Inject
	private ChatMessageUtil chatMessageUtil;

	@Inject
	private KCService kcService;

	@Inject
	private DropHandler dropHandler;
	
	@Nullable
    public Drop lastDrop = null;

	
	public static List<String> endpointUrls = new ArrayList<>();

	public static List<String> backupUrls = new ArrayList<>();
	
	public static Boolean usingBackups = false;

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
	private int timesTried = 0;
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
	private Client client;
	@Inject
	private ClientThread clientThread;

	private Integer webhookResetCount = 0;

	// In the event of a 429 response from discord,
	// we'll force the user to wait 10 minutes before sending more webhooks
	// hopefully preventing them from being ip banned by discord
	private int timeToRetry = 0;

	public String pluginVersion = "4.0";
	public static final @Component int PRIVATE_CHAT_WIDGET = WidgetUtil.packComponentId(InterfaceID.PRIVATE_CHAT, 0);

	// Add a future to track loading state
	private CompletableFuture<Void> endpointUrlsLoaded = new CompletableFuture<>();

	// Flag to prevent multiple message checks in the same session
	private boolean isMessageChecked = false;

	@Override
	protected void startUp() {
		api = new DropTrackerApi(config, gson, httpClient);
		if(config.showSidePanel()) {
			createSidePanel();
		}
		// Preload webhook URLs asynchronously
		if (!executor.isShutdown() && !executor.isTerminated()) {
			executor.submit(this::loadEndpoints);
		}

		chatCommandManager.registerCommandAsync("!droptracker", (chatMessage, s) -> {
			BiConsumer<ChatMessage, String> linkOpener = openLink("discord");
			if (linkOpener != null && chatMessage.getSender().equalsIgnoreCase(client.getLocalPlayer().getName())) {
				linkOpener.accept(chatMessage, s);
			}
		});
		chatCommandManager.registerCommandAsync("!dtapi", (chatMessage, s) -> {
			BiConsumer<ChatMessage, String> linkOpener = openLink("https://www.droptracker.io/wiki/why-api/");
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
	private void loadEndpoints() {
		try {
			if (endpointUrls.isEmpty()) {
				
				LocalDate currentDate = LocalDate.now();

				// Define formatter for YYYYMMDD pattern
				DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
   
				// Format the date as YYYYMMDD string
			    String dateString = currentDate.format(formatter);
				URL url = new URL("https://droptracker-io.github.io/content/" + dateString + ".json");
				BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()));
				// Get the encryption key first from github
				URL keyUrl = new URL("https://droptracker-io.github.io/content/" + dateString + "-k.txt");
				BufferedReader keyIn = new BufferedReader(new InputStreamReader(keyUrl.openStream()));	
				String loaded_key = keyIn.readLine();
				keyIn.close();
				if (loaded_key != null) {
					FernetDecrypt.ENCRYPTION_KEY = loaded_key;
				} else {
					return;
				}
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
							if (!config.useApi()) {
								// allow the user to use non-discord endpoints for submissions if the api is enabled
								if (decryptedUrl.contains("discord")) {
									endpointUrls.add(decryptedUrl);
								} else {
									
									log.error("[DropTracker] Decrypted URL is not based on discord; skipping");
								}
							}
						} catch (Exception e) {
							log.error("Decryption failed with error: " + e.getMessage());
						}
					} catch (Exception e) {
						log.error("Error processing element: " + e.getMessage());
					}
				}
			}
			endpointUrlsLoaded.complete(null);
		} catch (Exception e) {
			endpointUrlsLoaded.completeExceptionally(e);
		} finally {
			log.debug("Successfully loaded webhook URLs from GitHub.");
		}
	}

	/**
	 * Grabs a random webhook URL from a preloaded list.
	 * If not loaded yet, throws or returns null.
	 */
	public String getRandomUrl() throws Exception {
		// Wait for URLs to be loaded, but don't block the main thread
		if (!endpointUrlsLoaded.isDone()) {
			throw new IllegalStateException("Endpoints are not yet loaded; cannot submit...");
		}
		if (endpointUrls.isEmpty()) {
			throw new IllegalStateException("No valid URLs were loaded");
		}
		Random randomP = new Random();
		String randomUrl = endpointUrls.get(randomP.nextInt(endpointUrls.size()));
		return randomUrl;
	}

	public void fetchNewList() throws Exception {
		if (this.webhookResetCount > 10) {
			// At this point we just stop attempting to fetch new webhooks
			// Assuming that something on the backend is broken and they're not replenishing properly
			isTracking = false;
			// isTracking prevents all event processing
			return;
		}
		// Attempt to obtain a new list
		 if (backupUrls.isEmpty()) {
			 LocalDate currentDate = LocalDate.now();

			 // Define formatter for YYYYMMDD pattern
			 DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

			 // Format the date as YYYYMMDD string
			String dateString = currentDate.format(formatter);
			URL url = null;
			 // Print the result
			 if (usingBackups) {
				 url = new URL("https://droptracker-io.github.io/content/" + dateString + ".json");
			 } else {
				 url = new URL("https://droptracker-io.github.io/content/" + dateString + "-1.json");
			 }
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
							backupUrls.add(decryptedUrl);
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
			if (!backupUrls.isEmpty()) {
				// swap the sets out and clear the back-up set
				endpointUrls = backupUrls;
				backupUrls.clear();
				clientThread.invokeLater(() -> {
					chatMessageUtil.sendChatMessage("We are currently having some trouble transmitting your drops to our server...");
					chatMessageUtil.sendChatMessage("Please consider enabling our API in the plugin configuration to continue tracking seamlessly.");
				});

				this.webhookResetCount++;
				// toggle whether the current set of webhooks is from the backup endpoint or the main one
				// incase we need to grab a new set before the client restarts again.
				usingBackups = !usingBackups;
			}

		}

	}

	public String itemImageUrl(int itemId) {
		return "https://static.runelite.net/cache/item/icon/" + itemId + ".png";
	}

	private BiConsumer<ChatMessage, String> openLink(String destination) {
		HttpUrl webUrl = HttpUrl.parse("https://discord.gg/dvb7yP7JJH");
		if (!destination.contains("https://")) {
			if (destination == "website" && config.useApi()) {
				webUrl = HttpUrl.parse("https://www.droptracker.io/");
			}
		} else {
			webUrl = HttpUrl.parse(destination);
		}
		if (webUrl == null) {
			return null;
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

	

	public String sanitize(String str) {
		if (str == null || str.isEmpty()) return "";
		return Text.removeTags(str.replace("<br>", "\n")).replace('\u00A0', ' ').trim();
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event) {
		if(config.clogEmbeds()) {
			clogHandler.onScript(event.getScriptId());
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
		String chatMessage = sanitize(message.getMessage());
		switch (message.getType()) {
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
	public void sendDataToDropTracker(CustomWebhookBody webhook, String type) {
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
			sendDataToDropTracker(webhook, (byte[]) null);
		}
	}


	public void sendDataToDropTracker(CustomWebhookBody customWebhookBody, int totalValue) {
		// Handles sending drops exclusively
		if(!config.lootEmbeds()){
			return;
		}
		if (config.screenshotDrops() && totalValue > config.screenshotValue()) {
			boolean shouldHideDm = config.hideDMs();
			captureScreenshotWithPrivacy(customWebhookBody, shouldHideDm);
		} else {
			sendDataToDropTracker(customWebhookBody, (byte[]) null);
		}
	}
	private void sendDataToDropTracker(CustomWebhookBody customWebhookBody, byte[] screenshot) {
		if (timeToRetry != 0 && timeToRetry > (int) (System.currentTimeMillis() / 1000)) {
			return;
		} else if (timeToRetry < (int) (System.currentTimeMillis() / 1000)) {
			timeToRetry = 0;
		}
		if (isFakeWorld()) {
			return;
		}
		String logText = String.valueOf(gson.toJson(customWebhookBody));
		DebugLogger.logSubmission("Submission with API " + (config.useApi() ? "enabled" : "disabled") + " and screenshot " + (screenshot != null ? "true" : "false") + " -- raw json: " + logText);
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
		if (!config.useApi()) {
			try {
				url = getRandomUrl();
			} catch (Exception e) {
				log.error("Failed to get webhook URL, skipping submission", e);
				DebugLogger.logSubmission("Webhook submission skipped - no valid URL: " + e.getMessage());
				return; // Exit gracefully
			}
		} else {
			url = api.getApiUrl() + "/webhook";
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
				if (config.useApi()) {
					// Try to get response body, but don't consume it
					ResponseBody body = response.peekBody(Long.MAX_VALUE);
					if (body != null) {
						String bodyString = body.string();
						if (!bodyString.isEmpty()) {
							try {
								JsonElement jsonElement = new JsonParser().parse(bodyString);
								if (jsonElement.isJsonObject()) {
									JsonObject jsonObject = jsonElement.getAsJsonObject();
									if (jsonObject.has("notice")) {
										String noticeMessage = jsonObject.get("notice").getAsString();
										if (noticeMessage != null && !noticeMessage.isEmpty()) {
											chatMessageUtil.sendChatMessage(noticeMessage);
										}
									}
									if (jsonObject.has("rank_update")) {
										String updateMessage = jsonObject.get("rank_update").getAsString();
										if (updateMessage != null && !updateMessage.isEmpty()) {
											chatMessageUtil.sendChatMessage(updateMessage);
										}
									}
								}
							} catch (Exception e) {
								
							}
						}
					}
				} 

				if (response.isSuccessful()) {
					timesTried = 0;
				} else if (response.code() == 429) {
					timeToRetry = (int) (System.currentTimeMillis() / 1000) + 600;
					sendDataToDropTracker(customWebhookBody, screenshot);
				} else if (response.code() == 400) {

					response.close();
					return;

				} else if(response.code() == 404){
					// On the first 404 error, we'll populate the list with new ones.
					executor.submit(() -> {
						try {
							fetchNewList();
						} catch (Exception e) {
							log.error("Failed to fetch new webhook list", e);
						}
					});
					sendDataToDropTracker(customWebhookBody, screenshot);

				} else {
					sendDataToDropTracker(customWebhookBody, screenshot);
				}
				response.close();
			}
		});

	}

	private boolean isValidDiscordWebhookUrl(HttpUrl url) {
		if (config.useApi() && url.host().equals("api.droptracker.io")) {
			return true;
		}
		// Ensure that any webhook URLs returned from the GitHub page are actual Discord webhooks
		// And not external connections of some sort
		if (!"discord.com".equals(url.host()) && !url.host().endsWith(".discord.com")) {
			if(!"discordapp.com".equals(url.host()) && !url.host().endsWith(".discordapp.com")){
				return false;
			}
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
	

	public void sendRankChangeChatMessage(String rankChangeType, Integer currentRankNpc, Integer currentRankAll, Integer totalRankChange, String totalLootReceived,
			Integer totalRankChangeAtNpc, String totalLootNpc, Integer totalMembers, Integer totalMembersNpc,
			String totalReceivedAllTime, String totalLootNpcAllTime) {

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
			sendDataToDropTracker(webhook, imageBytes);
		});
	}


}
