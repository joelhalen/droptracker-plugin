package io.droptracker;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.*;

import io.droptracker.api.DropTrackerApi;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
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
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageCapture;

import static net.runelite.http.api.RuneLiteAPI.GSON;
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
	private DropTrackerApi api;

	public DropTrackerPlugin() {
	}

	@Inject
	private ItemManager itemManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ImageCapture imageCapture;

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private DrawManager drawManager;
	/* REGEX FILTERS FOR CHAT MESSAGE DETECTION */
	private static final Pattern COLLECTION_LOG_ITEM_REGEX = Pattern.compile("New item added to your collection log:.*");
	private static final Pattern KILLCOUNT_PATTERN = Pattern.compile("Your (?<pre>completion count for |subdued |completed )?(?<boss>.+?) (?<post>(?:(?:kill|harvest|lap|completion) )?(?:count )?)is: <col=ff0000>(?<kc>\\d+)</col>");
	private static final String TEAM_SIZES = "(?<teamsize>\\d+(?:\\+|-\\d+)? players?|Solo)";
	private static final Pattern RAIDS_PB_PATTERN = Pattern.compile("<col=ef20ff>Congratulations - your raid is complete!</col><br>Team size: <col=ff0000>" + TEAM_SIZES + "</col> Duration:</col> <col=ff0000>(?<pb>[0-9:]+(?:\\.[0-9]+)?)</col> \\(new personal best\\)</col>");
	private static final Pattern RAIDS_DURATION_PATTERN = Pattern.compile("<col=ef20ff>Congratulations - your raid is complete!</col><br>Team size: <col=ff0000>" + TEAM_SIZES + "</col> Duration:</col> <col=ff0000>(?<current>[0-9:.]+)</col> Personal best: </col><col=ff0000>(?<pb>[0-9:]+(?:\\.[0-9]+)?)</col>");
	private static final Pattern KILL_DURATION_PATTERN = Pattern.compile("(?i)(?:(?:Fight |Lap |Challenge |Corrupted challenge )?duration:|Subdued in|(?<!total )completion time:) <col=[0-9a-f]{6}>(?<current>[0-9:.]+)</col>\\. Personal best: (?:<col=ff0000>)?(?<pb>[0-9:]+(?:\\.[0-9]+)?)");
	private static final Pattern NEW_PB_PATTERN = Pattern.compile("(?i)(?:(?:Fight |Lap |Challenge |Corrupted challenge )?duration:|Subdued in|(?<!total )completion time:) <col=[0-9a-f]{6}>(?<pb>[0-9:]+(?:\\.[0-9]+)?)</col> \\(new personal best\\)");
	public Integer totalLogSlots = 0;
	@Inject
	public ChatMessageManager msgManager;
	private int MINIMUM_FOR_SCREENSHOTS = 2500000;
	//^overwrites the user's input value if it's below 2.5M for API submissions to prevent overfilling our webserver

	/* Memory storage for details about the current npc being killed and it's kill time */
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	private String currentKillTime = "";
	private String currentPbTime = "";
	private String currentNpcName = "";
	// Flag that indicates when all kill-time related data is ready to send
	private boolean readyToSendPb = false;
	private boolean hasUpdatedStoredItems; // prevent spamming the API with gear updates
	private ScheduledFuture<?> skillDataResetTask = null;
	private final Object lock = new Object();

	/* Get a random webhook from the GitHub endpoint (cycled in/out the Discord Bot regularly)
	 */
	public static List<String> webhookUrls = new ArrayList<>();

	private final ExecutorService executor = Executors.newCachedThreadPool();

	public static String getRandomWebhookUrl() throws Exception {
		if (webhookUrls.isEmpty()) {
			URL url = new URL("https://joelhalen.github.io/docs/webhooks.json");
			BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()));
			String input;
			while ((input = in.readLine()) != null) {
				// Remove double quotes and commas from the input string
				input = input.replace("\"", "").replace(",", "")
						.replace("[", "").replace("]", "");
				webhookUrls.add(input);
			}
			in.close();
		}
		Random randomP = new Random();
		return webhookUrls.get(randomP.nextInt(webhookUrls.size()));
	}

	private static String itemImageUrl(int itemId) {
		return "https://static.runelite.net/cache/item/icon/" + itemId + ".png";
	}

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;

	@Override
	protected void startUp() {
		api = new DropTrackerApi(config, msgManager, new Gson());
	}

	@Override
	protected void shutDown() {
	}

	@Provides
	DropTrackerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DropTrackerConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged) {
		if (configChanged.getGroup().equalsIgnoreCase(DropTrackerConfig.GROUP)) {

		}
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived npcLootReceived) {
		NPC npc = npcLootReceived.getNpc();
		Collection<ItemStack> items = npcLootReceived.getItems();
		processDropEvent(npc.getName(), "npc", items);
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived playerLootReceived) {
		Collection<ItemStack> items = playerLootReceived.getItems();
		processDropEvent(playerLootReceived.getPlayer().getName(), "player", items);
	}

	@Subscribe
	public void onLootReceived(LootReceived lootReceived) {
		if (lootReceived.getType() != LootRecordType.EVENT && lootReceived.getType() != LootRecordType.PICKPOCKET) {
			return;
		}
		processDropEvent(lootReceived.getName(), "other", lootReceived.getItems());
	}

	@Subscribe
	public void onChatMessage(ChatMessage chatMessage) {
		if (!config.useApi()) {
			return;
		}
		if (chatMessage.getType() != ChatMessageType.GAMEMESSAGE && chatMessage.getType() != ChatMessageType.SPAM) {
			return;
		}
		if (isFakeWorld()) {
			return;
		}

		synchronized (lock) {
			String message = chatMessage.getMessage();
			Matcher matcher;

			if (COLLECTION_LOG_ITEM_REGEX.matcher(message).matches()) {
				++totalLogSlots;
				hasUpdatedStoredItems = false;
			}

			Matcher npcMatcher = KILLCOUNT_PATTERN.matcher(message);
			if (npcMatcher.find()) {
				currentNpcName = npcMatcher.group("boss");
				scheduleKillTimeReset();
				hasUpdatedStoredItems = false;
			}

			if ((matcher = RAIDS_PB_PATTERN.matcher(message)).find()) {
				currentKillTime = matcher.group("pb");
				currentPbTime = currentKillTime;
				readyToSendPb = true;
				scheduleKillTimeReset();
				hasUpdatedStoredItems = false;
			} else if ((matcher = RAIDS_DURATION_PATTERN.matcher(message)).find() || (matcher = KILL_DURATION_PATTERN.matcher(message)).find()) {
				currentKillTime = matcher.group("current");
				currentPbTime = matcher.group("pb");
				readyToSendPb = !currentNpcName.isEmpty();
				scheduleKillTimeReset();
				hasUpdatedStoredItems = false;
			}

			if (readyToSendPb) {
				String playerName = client.getLocalPlayer().getName();
				api.sendKillTimeData(playerName, currentNpcName, currentKillTime, currentPbTime);
				resetState();
			}
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

	private String getPlayerName() {
		return client.getLocalPlayer().getName();
	}

	private void processDropEvent(String npcName, String sourceType, Collection<ItemStack> items) {
		if (!config.useApi()) {
			AtomicReference<Integer> finalValue = new AtomicReference<>(0);
			clientThread.invokeLater(() -> {
				WebhookBody webhookBody = new WebhookBody();
				for (ItemStack item : stack(items)) {
					int itemId = item.getId();
					int qty = item.getQuantity();
					int price = itemManager.getItemPrice(itemId);
					ItemComposition itemComposition = itemManager.getItemComposition(itemId);
					finalValue.set(qty * price);
					// Create a new embed for each item
					WebhookBody.Embed itemEmbed = new WebhookBody.Embed(new WebhookBody.UrlEmbed(itemImageUrl(itemId)));

					// Add fields to the embed
					itemEmbed.addField("item", itemComposition.getName(), true);
					itemEmbed.addField("player", client.getLocalPlayer().getName(), true);
					if (config.authKey() != "") {
						itemEmbed.addField("auth_token", config.authKey(), true);
					}
					itemEmbed.addField("id", String.valueOf(itemComposition.getId()), true);
					itemEmbed.addField("quantity", String.valueOf(qty), true);
					itemEmbed.addField("value", String.valueOf(price), true);
					itemEmbed.addField("source", npcName, true);
					itemEmbed.addField("type", sourceType, true);
					if (config.sheetID() != "") {
						itemEmbed.addField("sheet", String.valueOf(config.sheetID()), true);
					}
					if (config.webhook() != "") {
						itemEmbed.addField("webhook", String.valueOf(config.webhook()), true);
					}
					webhookBody.getEmbeds().add(itemEmbed);
				}

				webhookBody.setContent(getPlayerName() + " received some drops:");
				sendDropTrackerWebhook(webhookBody, finalValue.get());
			});
		} else {
			for (ItemStack item : items) {
				int itemId = item.getId();
				int quantity = item.getQuantity();

				int geValue = itemManager.getItemPrice(itemId);
				int haValue = itemManager.getItemComposition(itemId).getHaPrice();
				if (geValue == 0 && haValue == 0) {
					continue;
				}
				ItemComposition itemComp = itemManager.getItemComposition(itemId);
				String itemName = itemComp.getName();
				int finalValue = geValue * quantity;
				SwingUtilities.invokeLater(() -> {
					if (config.useApi()) {
						String serverId = config.serverID();
						if (config.sendScreenshot() && finalValue > config.screenshotValue()) {
							AtomicReference<String> this_imageUrl = new AtomicReference<>("null");
							drawManager.requestNextFrameListener(image -> {
								getApiScreenshot(client.getLocalPlayer().getName(), itemId, npcName).thenAccept(imageUrl -> {
									SwingUtilities.invokeLater(() -> {
										this_imageUrl.set(imageUrl);
										api.sendDropData(getLocalPlayerName(), npcName, itemId, itemName, quantity, geValue, config.authKey(), this_imageUrl.get());
									});
								});

							});

						}
					} else {
						api.sendDropData(getLocalPlayerName(), npcName, itemId, itemName, quantity, geValue, config.authKey(), "");
					}
				});

			}
		}
	}

	private String getLocalPlayerName() {
		if (config.registeredName() != "") {
			return config.registeredName();
		}
		return client.getLocalPlayer().getName();
	}

	private void sendDropTrackerWebhook(WebhookBody webhookBody, int totalValue) {
		if (config.sendScreenshot() && totalValue > config.screenshotValue()) {
			drawManager.requestNextFrameListener(image ->
			{
				BufferedImage bufferedImage = (BufferedImage) image;
				byte[] imageBytes = null;
				try {
					imageBytes = convertImageToByteArray(bufferedImage);
				} catch (IOException e) {
					log.error("Error converting image to byte array", e);
				}
				sendDropTrackerWebhook(webhookBody, imageBytes);
			});
		} else {
			sendDropTrackerWebhook(webhookBody, null);
		}
	}
	private void sendDropTrackerWebhook(WebhookBody webhookBody, byte[] screenshot) {
		MultipartBody.Builder requestBodyBuilder = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("payload_json", GSON.toJson(webhookBody));

		if (screenshot != null) {
			requestBodyBuilder.addFormDataPart("file", "image.png",
					RequestBody.create(MediaType.parse("image/png"), screenshot));
		}

		MultipartBody requestBody = requestBodyBuilder.build();
		String url = "";
		try {
			url = getRandomWebhookUrl();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		HttpUrl u = HttpUrl.parse(url);
		if (u == null) {
			log.info("Malformed webhook url {}", url);
			return;
		}

		Request request = new Request.Builder()
				.url(url)
				.post(requestBody)
				.build();
		okHttpClient.newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(Call call, IOException e) {
				log.debug("Error submitting webhook", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException {
				response.close();
			}
		});

	}
	private static byte[] convertImageToByteArray(BufferedImage bufferedImage) throws IOException {
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		ImageIO.write(bufferedImage, "png", byteArrayOutputStream);
		return byteArrayOutputStream.toByteArray();
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
	public boolean isFakeWorld() {
		if (client.getGameState().equals(GameState.LOGGED_IN)) {
			boolean isFake = !client.getWorldType().contains(WorldType.SEASONAL) || client.getWorldType().contains(WorldType.DEADMAN);
			return isFake;
		} else {
			return true;
		}
	}
	public CompletableFuture<String> getApiScreenshot(String playerName, int itemId, String npcName) {
		log.debug("getApiScreenshot called");
		CompletableFuture<String> future = new CompletableFuture<>();
		if (config.useApi() || config.sendScreenshot()) {
			String serverId = config.serverID();
			drawManager.requestNextFrameListener(image -> {
				try {
					ItemComposition itemComp = itemManager.getItemComposition(itemId);
					String itemName = itemComp.getName();
					ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
					ImageIO.write((RenderedImage) image, "png", byteArrayOutputStream);
					byte[] imageData = byteArrayOutputStream.toByteArray();
					String apiBase;
					if (!config.sendScreenshot()) {
						apiBase = null;
					} else {
						apiBase = api.getApiUrl();
						String serverName;
						RequestBody requestBody = new MultipartBody.Builder()
								.setType(MultipartBody.FORM)
								.addFormDataPart("file", itemName + ".png",
										RequestBody.create(MediaType.parse("image/png"), imageData))
								.addFormDataPart("server_id", serverId)
								.addFormDataPart("player_name", playerName)
								.addFormDataPart("npc", npcName)
								.build();
						executor.submit(() -> {

							Request request = new Request.Builder()
									.url(apiBase + "/api/upload_image")
									.post(requestBody)
									.build();
							try (Response response = httpClient.newCall(request).execute()) {
								if (!response.isSuccessful()) {
									throw new IOException("Unexpected response code: " + response);
								}

								String responseBody = response.body().string();
								future.complete(responseBody.trim());
							} catch (IOException e) {
								future.completeExceptionally(e);
							}
						});
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}

			});
			return future;
		}
		return future;
	}
}
