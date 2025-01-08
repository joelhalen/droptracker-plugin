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
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.inject.Inject;

import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.ui.DropTrackerPanel;
import io.droptracker.util.ChatMessageEvent;
import io.droptracker.util.ContainerManager;
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
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
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
	@Inject
	private ContainerManager containerManager;
	private NavigationButton navButton;
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
	public static final Set<String> SPECIAL_NPC_NAMES = Set.of("The Whisperer", "Araxxor");

	@Inject
	private DrawManager drawManager;
	@Inject
	private ChatCommandManager chatCommandManager;

	/* REGEX FILTERS FOR CHAT MESSAGE DETECTION */
	private static final Pattern COLLECTION_LOG_ITEM_REGEX = Pattern.compile("New item added to your collection log: (.*)");
	private static final Pattern KILLCOUNT_PATTERN = Pattern.compile("Your (?<pre>completion count for |subdued |completed )?(?<boss>.+?) (?<post>(?:(?:kill|harvest|lap|completion) )?(?:count )?)is: <col=ff0000>(?<kc>\\d+)</col>");
	private static final String TEAM_SIZES = "(?<teamsize>\\d+(?:\\+|-\\d+)? players?|Solo)";
	private static final Pattern RAIDS_PB_PATTERN = Pattern.compile("<col=ef20ff>Congratulations - your raid is complete!</col><br>Team size: <col=ff0000>" + TEAM_SIZES + "</col> Duration:</col> <col=ff0000>(?<pb>[0-9:]+(?:\\.[0-9]+)?)</col> \\(new personal best\\)</col>");
	private static final Pattern RAIDS_DURATION_PATTERN = Pattern.compile("<col=ef20ff>Congratulations - your raid is complete!</col><br>Team size: <col=ff0000>" + TEAM_SIZES + "</col> Duration:</col> <col=ff0000>(?<current>[0-9:.]+)</col> Personal best: </col><col=ff0000>(?<pb>[0-9:]+(?:\\.[0-9]+)?)</col>");
	private static final Pattern KILL_DURATION_PATTERN = Pattern.compile("(?i)(?:(?:Fight |Lap |Challenge |Corrupted challenge )?duration:|Subdued in|(?<!total )completion time:) <col=[0-9a-f]{6}>(?<current>[0-9:.]+)</col>\\. Personal best: (?:<col=ff0000>)?(?<pb>[0-9:]+(?:\\.[0-9]+)?)");
	private static final Pattern NEW_PB_PATTERN = Pattern.compile("(?i)(?:(?:Fight |Lap |Challenge |Corrupted challenge )?duration:|Subdued in|(?<!total )completion time:) <col=[0-9a-f]{6}>(?<pb>[0-9:]+(?:\\.[0-9]+)?)</col> \\(new personal best\\)");
	public Integer totalLogSlots = 0;
	@Inject
	public ChatMessageManager msgManager;

	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	private String currentKillTime = "";
	private String currentPbTime = "";
	private String currentNpcName = "";
	private boolean readyToSendPb = false;
	private boolean hasUpdatedStoredItems;
	private ScheduledFuture<?> skillDataResetTask = null;
	private final Object lock = new Object();
	private boolean hasReminded = false;
	public static List<String> webhookUrls = new ArrayList<>();

	private final ExecutorService executor = Executors.newCachedThreadPool();
	private static final BufferedImage PANEL_ICON = ImageUtil.loadImageResource(DropTrackerPlugin.class, "icon.png");
	private String logItemReceived;
	private static final int MAX_RETRIES = 5;
	private int timesTried = 0;
	@Inject
	public ChatMessageEvent chatMessageEventHandler;

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

	public String pluginVersion = "3.2.2";

	public static final @Component int PRIVATE_CHAT_WIDGET = WidgetUtil.packComponentId(InterfaceID.PRIVATE_CHAT, 0);

	@Override
	protected void startUp() {
		api = new DropTrackerApi(config, msgManager, gson, httpClient, client);
		containerManager = new ContainerManager(this, client);

		if(config.showSidePanel()) {
			createSidePanel();
		}
		chatCommandManager.registerCommandAsync("!droptracker", (chatMessage, s) -> {
			BiConsumer<ChatMessage, String> linkOpener = openLink("discord");
			if (linkOpener != null && chatMessage.getSender().equalsIgnoreCase(client.getLocalPlayer().getName())) {
				linkOpener.accept(chatMessage, s);
			}
		});
		if (config.useApi()) {
			chatCommandManager.registerCommandAsync("!loot", (chatMessage, s) -> {
				BiConsumer<ChatMessage, String> linkOpener = openLink("website");
				if (linkOpener != null && chatMessage.getSender().equalsIgnoreCase(client.getLocalPlayer().getName())) {
					linkOpener.accept(chatMessage, s);
				}
			});
		}
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
	private void removeSidePanel() {
		clientToolbar.removeNavigation(navButton);
		panel = null;
	}
	/**
	 * Grabs a random webhook URL from a GitHub sites page that is cycled by the server
	 * */
	public static String getRandomWebhookUrl() throws Exception {
		if (webhookUrls.isEmpty()) {
			// Grab the current list of webhook URLs from the github-hosted file
			URL url = new URL("https://joelhalen.github.io/docs/webhooks.json");
			BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()));
			String input;
			while ((input = in.readLine()) != null) {
				// Remove double quotes and commas from the input string
				input = input.replace("\"", "").replace(",", "")
						.replace("[", "").replace("]", "");
				// Ensure the target string contains 'discord'
				if (input.contains("discord")) {
					webhookUrls.add(input);
				}
			}
			in.close();
		}
		Random randomP = new Random();
		String urls = webhookUrls.get(randomP.nextInt(webhookUrls.size()));
		return urls;
	}

	private static String itemImageUrl(int itemId) {
		return "https://static.runelite.net/cache/item/icon/" + itemId + ".png";
	}

	private BiConsumer<ChatMessage, String> openLink(String destination) {
		HttpUrl webUrl = HttpUrl.parse("https://www.discord.gg/droptracker");
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
		processDropEvent(npc.getName(), "npc", items);
		//sendChatReminder();
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived playerLootReceived) {
		Collection<ItemStack> items = playerLootReceived.getItems();
		processDropEvent(playerLootReceived.getPlayer().getName(), "pvp", items);
		//sendChatReminder();
	}

	@Subscribe
	public void onLootReceived(LootReceived lootReceived) {
		/* A select few npc loot sources will arrive here, instead of npclootreceived events */
		String npcName = chatMessageEventHandler.getStandardizedSource(lootReceived);
		if (lootReceived.getType() == LootRecordType.NPC && SPECIAL_NPC_NAMES.contains(npcName)) {
			processDropEvent(npcName, "npc", lootReceived.getItems());
			return;
		}
		if (lootReceived.getType() != LootRecordType.EVENT && lootReceived.getType() != LootRecordType.PICKPOCKET) {
			return;
		}
		processDropEvent(npcName, "other", lootReceived.getItems());
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
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		chatMessageEventHandler.onTick();
		if (client.getGameState().equals(GameState.LOGGED_IN)) {
			containerManager.onTick();
		}
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
					if (!containerManager.isRealDrop(tempItem)) {
						// If the item is determined as something that was from their inv/gear, we don't send it.
						continue;
					}
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
		else if (type.equalsIgnoreCase("2")) // clogs {
			if (config.screenshotNewClogs()) {
				requiredScreenshot = true;
			}
			else { // combat achievements
				if (config.screenshotCAs()) {
					requiredScreenshot = true;
				}
			}
		if (requiredScreenshot) {
			boolean shouldHideDm = config.hideDMs();
			if (shouldHideDm) {
				captureScreenshotWithPrivacy(webhook, true);
			} else {
				drawManager.requestNextFrameListener(image ->
				{
					BufferedImage bufferedImage = (BufferedImage) image;
					byte[] imageBytes = null;
					try {
						imageBytes = convertImageToByteArray(bufferedImage);
					} catch (IOException e) {
						log.error("Error converting image to byte array", e);
					}
					sendDropTrackerWebhook(webhook, imageBytes);
				});
			}
		} else {
			sendDropTrackerWebhook(webhook, (byte[]) null);
		}
	}


	public void sendDropTrackerWebhook(CustomWebhookBody customWebhookBody, int totalValue) {
		// Handles sending drops exclusively
		if (config.screenshotDrops() && totalValue > config.screenshotValue()) {
			boolean shouldHideDm = config.hideDMs();
			if (shouldHideDm) {
				captureScreenshotWithPrivacy(customWebhookBody, true);
			} else {
				drawManager.requestNextFrameListener(image ->
				{
					BufferedImage bufferedImage = (BufferedImage) image;

					byte[] imageBytes = null;
					try {
						imageBytes = convertImageToByteArray(bufferedImage);
					} catch (IOException e) {
						log.error("Error converting image to byte array", e);
					}
					sendDropTrackerWebhook(customWebhookBody, imageBytes);

				});
			}
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
			requestBodyBuilder.addFormDataPart("file", "image.png",
					RequestBody.create(MediaType.parse("image/png"), screenshot));
		}

		MultipartBody requestBody = requestBodyBuilder.build();
		String url;
		try {
			url = getRandomWebhookUrl();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		HttpUrl u = HttpUrl.parse(url);
		if (u == null || !isValidDiscordWebhookUrl(u)) {
			log.info("Invalid or malformed webhook URL: {}", url);
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
					return;
				} else if (response.code() == 400) {
					return;
					//sendDropTrackerWebhook(customWebhookBody, screenshot);
				} else {
					log.info("Failed to send webhook, response code: {}. Retrying...", response.code());
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
		ImageIO.write(bufferedImage, "png", byteArrayOutputStream);
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
			} catch (IOException e) {
				log.error("Error converting image to byte array", e);
			}
			sendDropTrackerWebhook(webhook, imageBytes);
		});
	}
}
