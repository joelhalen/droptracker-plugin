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
package com.joelhalen.droptracker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;

import com.joelhalen.droptracker.discord.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.*;
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
import net.runelite.client.util.LinkBrowser;
import net.runelite.http.api.loottracker.LootRecordType;
import okhttp3.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.MediaType;
import okhttp3.RequestBody;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.io.ByteArrayOutputStream;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.joelhalen.droptracker.DropTrackerPanel.formatNumber;


@PluginDescriptor(
		name = "DropTracker",
		description = "Automatically uploads your drops to the DropTracker discord bot!",
		tags = {"droptracker", "drop", "leaderboard", "tracking"}
)

public class DropTrackerPlugin extends Plugin {
	public DropTrackerPlugin() {
	}
	//* Regexes for detecting kills on particular bosses and PBs *//
	//* Source: ChatCommandsPlugin.java | net.runelite.client.plugins.chatcommands *//
	private static final Pattern COLLECTION_LOG_ITEM_REGEX = Pattern.compile("New item added to your collection log:.*");
	private static final Pattern KILLCOUNT_PATTERN = Pattern.compile("Your (?<pre>completion count for |subdued |completed )?(?<boss>.+?) (?<post>(?:(?:kill|harvest|lap|completion) )?(?:count )?)is: <col=ff0000>(?<kc>\\d+)</col>");
	private static final String TEAM_SIZES = "(?<teamsize>\\d+(?:\\+|-\\d+)? players?|Solo)";
	private static final Pattern RAIDS_PB_PATTERN = Pattern.compile("<col=ef20ff>Congratulations - your raid is complete!</col><br>Team size: <col=ff0000>" + TEAM_SIZES + "</col> Duration:</col> <col=ff0000>(?<pb>[0-9:]+(?:\\.[0-9]+)?)</col> \\(new personal best\\)</col>");
	private static final Pattern RAIDS_DURATION_PATTERN = Pattern.compile("<col=ef20ff>Congratulations - your raid is complete!</col><br>Team size: <col=ff0000>" + TEAM_SIZES + "</col> Duration:</col> <col=ff0000>(?<current>[0-9:.]+)</col> Personal best: </col><col=ff0000>(?<pb>[0-9:]+(?:\\.[0-9]+)?)</col>");
	private static final Pattern KILL_DURATION_PATTERN = Pattern.compile("(?i)(?:(?:Fight |Lap |Challenge |Corrupted challenge )?duration:|Subdued in|(?<!total )completion time:) <col=[0-9a-f]{6}>(?<current>[0-9:.]+)</col>\\. Personal best: (?:<col=ff0000>)?(?<pb>[0-9:]+(?:\\.[0-9]+)?)");
	private static final Pattern NEW_PB_PATTERN = Pattern.compile("(?i)(?:(?:Fight |Lap |Challenge |Corrupted challenge )?duration:|Subdued in|(?<!total )completion time:) <col=[0-9a-f]{6}>(?<pb>[0-9:]+(?:\\.[0-9]+)?)</col> \\(new personal best\\)");
	private String currentKillTime = "";
	private String currentPbTime = "";
	private String currentNpcName = "";
	private boolean readyToSendPb = false; // Flag to indicate when all data is ready

	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	private ScheduledFuture<?> skillDataResetTask = null;
	private final Object lock = new Object();
	public static final String CONFIG_GROUP = "droptracker";
	private final ExecutorService executor = Executors.newCachedThreadPool();
	@Inject
	private DropTrackerPluginConfig config;
	@Inject
	private OkHttpClient httpClient;
	private boolean panelRefreshed = false;
	private boolean eventPanelRefreshed = false;
	private String currentPlayerName = "";
	@Inject
	private ItemManager itemManager;
	@Inject
	public Client client;
	private DropTrackerPanel panel;
	private DropTrackerEventPanel eventPanel;
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
	@Inject
	private Gson gson;
	private int MINIMUM_FOR_SCREENSHOTS = 1000000;
	public Integer totalLogSlots = 0;
	private Map<String, Integer> serverMinimumLootVarMap;
	private Map<String, Long> clanServerDiscordIDMap;
	private Map<String, String> serverIdToClanNameMap;
	private Map<String, Boolean> serverIdToConfirmedOnlyMap;
	public Map<String, Boolean> clanEventActiveMap;
	private Set<DropEntryStream> sentDrops = new HashSet<>();
	private boolean prepared = false;
	private static final Logger log = LoggerFactory.getLogger(DropTrackerPlugin.class);
	private static final BufferedImage ICON = ImageUtil.loadImageResource(DropTrackerPlugin.class, "icon.png");
	private static final BufferedImage EVENT_ICON = ImageUtil.loadImageResource(DropTrackerPlugin.class, "event_icon.png");
	private Map<String, Boolean> lastSentItemData = new HashMap<>();
	private NavigationButton navButton;
	private NavigationButton eventNavButton;
	private String[] groupMembers = new String[0];
	private final Object groupMembersLock = new Object();
	@Inject
	private GroupMemberClient groupMemberClient;
	private boolean hasSentDiscordMsg; // only send a message one time to the user per client session
	private boolean hasUpdatedStoredItems; // prevent spamming the API with gear updates
	@Inject
	private ChatCommandManager chatCommandManager;

	public String getApiUrl() {
		/* if, somehow, a player still reaches a call to the API with the API disabled, this
		should prevent any cases where their IP would still potentially be shared...
		 */
		if (config.useApi()) {
			return "https://www.droptracker.io/";
		}
		else {

			return "null.com";
		}
	}
	public void loadGroupMembersAsync() {
		if (!config.useApi()) {
			return;
		}
		executor.execute(() -> {
			while (true) {
				try {
					String[] newGroupMembers = groupMemberClient.getGroupMembers(Long.valueOf(config.serverId()), getLocalPlayerName());
					synchronized (groupMembersLock) {
						groupMembers = newGroupMembers;
					}
					Thread.sleep(15 * 60 * 1000);
				} catch (IOException | InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
	}
	@Subscribe
	public void onChatMessage(ChatMessage chatMessage) {
		if(!config.useApi()) {
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
			}

			Matcher npcMatcher = KILLCOUNT_PATTERN.matcher(message);
			if (npcMatcher.find()) {
				currentNpcName = npcMatcher.group("boss");
				scheduleKillTimeReset();
			}

			if ((matcher = RAIDS_PB_PATTERN.matcher(message)).find()) {
				currentKillTime = matcher.group("pb");
				currentPbTime = currentKillTime;
				readyToSendPb = true;
				scheduleKillTimeReset();
			} else if ((matcher = RAIDS_DURATION_PATTERN.matcher(message)).find() || (matcher = KILL_DURATION_PATTERN.matcher(message)).find()) {
				currentKillTime = matcher.group("current");
				currentPbTime = matcher.group("pb");
				readyToSendPb = !currentNpcName.isEmpty();
				scheduleKillTimeReset();
			}

			if (readyToSendPb) {
				String playerName = client.getLocalPlayer().getName();
				sendKillTimeData(playerName, currentNpcName, currentKillTime, currentPbTime);
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




	@Subscribe
	public void onLootReceived(LootReceived lootReceived) {
		String playerName = config.permPlayerName().isEmpty() ? client.getLocalPlayer().getName() : config.permPlayerName();
		if (lootReceived.getType() == LootRecordType.NPC) {
			return;
		}
		Collection<ItemStack> items = lootReceived.getItems();
		String receivedFrom = lootReceived.getName();
		if (!config.useApi()) {
			/* No API enabled */
			if (config.sendScreenshots()) {
				SwingUtilities.invokeLater(() -> {
					boolean shouldTakeScreenshot = false;
					for (ItemStack item : items) {
						int geValue = itemManager.getItemPrice(item.getId());
						/* Prevent screenshots from being taken for ALL drops if the player
						is not using the API, but is using the screenshot feature */
						if (geValue >= MINIMUM_FOR_SCREENSHOTS) {
							shouldTakeScreenshot = true;
						}
					}
					if (shouldTakeScreenshot) {
						getScreenshot(playerName, 0, receivedFrom).thenAccept(imageUrl -> {
							try {
								sendWebhookDropData(playerName, receivedFrom, imageUrl, items);
							} catch (IOException e) {
								log.error("Unable to send webhook:" + e);
								throw new RuntimeException(e);
							}
						});
					} else {
						try {
							sendWebhookDropData(playerName, receivedFrom, "none", items);
						} catch (IOException e) {
							log.error("Unable to send webhook:" + e);
							throw new RuntimeException(e);
						}
					}
				});
			} else {
				try {
					sendWebhookDropData(playerName, receivedFrom, "none", items);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
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
				SwingUtilities.invokeLater(() -> {
					if (config.useApi()) {
						String serverId = config.serverId();
						if (geValue < serverMinimumLootVarMap.get(config.serverId())) {
							sendDropData(playerName, receivedFrom, itemId, itemName, "", quantity, geValue, 0, config.authKey(), "");
						} else {
							AtomicReference<String> this_imageUrl = new AtomicReference<>("null");
							getScreenshot(playerName, itemId, receivedFrom).thenAccept(imageUrl -> {
								SwingUtilities.invokeLater(() -> {
									this_imageUrl.set(imageUrl);
									sendDropData(playerName, receivedFrom, itemId, itemName, "", quantity, geValue, 0, config.authKey(), this_imageUrl.get());
								});
							});

						}
					}
				});

			}
		}
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived npcLootReceived) {
		NPC npc = npcLootReceived.getNpc();
		String receivedFrom = npc.getName();
		String playerName = config.permPlayerName().isEmpty() ? client.getLocalPlayer().getName() : config.permPlayerName();
		Collection<ItemStack> items = npcLootReceived.getItems();

		if (!config.useApi()) {
			/* No API enabled */
			if (config.sendScreenshots()) {
				SwingUtilities.invokeLater(() -> {
					boolean shouldTakeScreenshot = false;
					for (ItemStack item : items) {
						int geValue = itemManager.getItemPrice(item.getId());
						/* Prevent screenshots from being taken for ALL drops if the player
						is not using the API, but is using the screenshot feature */
						if (geValue >= MINIMUM_FOR_SCREENSHOTS) {
							shouldTakeScreenshot = true;
						}
					}
					if (shouldTakeScreenshot) {
						getScreenshot(playerName, 0, receivedFrom).thenAccept(imageUrl -> {
							try {
								sendWebhookDropData(playerName, receivedFrom, imageUrl, items);
							} catch (IOException e) {
								log.error("Unable to send webhook:" + e);
								throw new RuntimeException(e);
							}
						});
					} else {
						try {
							sendWebhookDropData(playerName, receivedFrom, "none", items);
						} catch (IOException e) {
							log.error("Unable to send webhook:" + e);
							throw new RuntimeException(e);
						}
					}
				});
			} else {
				try {
					sendWebhookDropData(playerName, receivedFrom, "none", items);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
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
				SwingUtilities.invokeLater(() -> {
					if (config.useApi()) {
						String serverId = config.serverId();
						if (geValue < serverMinimumLootVarMap.get(config.serverId())) {
							sendDropData(playerName, receivedFrom, itemId, itemName, "", quantity, geValue, 0, config.authKey(), "");
						} else {
							AtomicReference<String> this_imageUrl = new AtomicReference<>("null");
							getScreenshot(playerName, itemId, receivedFrom).thenAccept(imageUrl -> {
								SwingUtilities.invokeLater(() -> {
									this_imageUrl.set(imageUrl);
									sendDropData(playerName, receivedFrom, itemId, itemName, "", quantity, geValue, 0, config.authKey(), this_imageUrl.get());
								});
							});

						}
					}
				});

			}
		}
	}
	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (event.getGameState().equals(GameState.LOGGED_IN)) {
			if (config.sendReminders() && !hasSentDiscordMsg) {
				final String firstMessage = new ChatMessageBuilder()
						.append(ChatColorType.HIGHLIGHT)
						.append("[DropTracker]")
						.append(ChatColorType.NORMAL)
						.append(" Did you know you are automatically competing on the DropTracker's Loot Leaderboards by using the plugin?")
						.build();
				final String secondMessage = new ChatMessageBuilder()
						.append(ChatColorType.NORMAL)
						.append("- Join the discord or visit our site to learn more: !droptracker")
						.build();
				chatMessageManager.queue(
						QueuedMessage.builder()
								.type(ChatMessageType.GAMEMESSAGE)
								.runeLiteFormattedMessage(firstMessage)
								.build());
				chatMessageManager.queue(
						QueuedMessage.builder()
								.type(ChatMessageType.GAMEMESSAGE)
								.runeLiteFormattedMessage(secondMessage)
								.build());

				hasSentDiscordMsg = true;
			}
		}
	}
	@Subscribe
	public void onGameTick(GameTick event) {
		/* Refresh the panel every time the game state changes, or the player's local name changes */
		if ((client.getGameState() == GameState.LOGGED_IN) && (client.getLocalPlayer().getName() != null) && (!client.getLocalPlayer().getName().equals(currentPlayerName))) {
			currentPlayerName = client.getLocalPlayer().getName();
			if (!panelRefreshed && config.useApi()) {
				log.debug("[DropTracker] Updating panel due to a new player state");
				panel.refreshPanel();
				panelRefreshed = true;
			}
			if (client.getGameState() == GameState.LOGGED_IN && !eventPanelRefreshed) {
				initializeEventPanel();
				eventPanelRefreshed = true;
			} else if (client.getGameState() != GameState.LOGGED_IN) {
				eventPanelRefreshed = false;
			}

		} else {

			panelRefreshed = false;
			eventPanelRefreshed = false;
		}
	}
	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (config.useApi()) {
			if (this.panel != null) {
				panel.refreshPanel();
				if (config.showEventPanel() && clanEventActiveMap.get(config.serverId()) && this.eventPanel != null) {
					eventPanel.refreshPanel();
				}
			} else {
				grabServerConfiguration();
				createSidePanel();
			}
		}
	}
	public boolean isFakeWorld() {
		if (client.getGameState().equals(GameState.LOGGED_IN)) {
			boolean isFake = client.getWorldType().contains(WorldType.SEASONAL) || client.getWorldType().contains(WorldType.BETA_WORLD) || client.getWorldType().contains(WorldType.FRESH_START_WORLD) || client.getWorldType().contains(WorldType.DEADMAN);
			return isFake;
		} else {
			return true;
		}
	}
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (!config.useApi() || !config.sendAccountData()) {
			/* Don't do anything! */
			return;
		}
		try {
			Widget colLogTitleWig = client.getWidget(621, 1);
			if (colLogTitleWig != null && colLogTitleWig.getChild(1) != null) {
				colLogTitleWig = colLogTitleWig.getChild(1);
				String text = colLogTitleWig.getText();
				if (text != null && text.contains("- ")) {
					Integer new_slots = Integer.parseInt(text.split("- ")[1].split("/")[0]);
					if (new_slots > totalLogSlots) {
						//log.debug("[DropTracker] Updating log slots to " + new_slots + " from " + totalLogSlots);
						totalLogSlots = new_slots;
					}
				}
			}
		} catch (NumberFormatException e) {
			log.error("Error parsing the number of slots", e);
		} catch (Exception e) {
			log.error("Unexpected error occurred", e);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		if ((isFakeWorld()) || (!config.useApi()) || (!config.sendAccountData())) {
			/* If the world is not a 'real world' (leagues, etc.), or
			the API is disabled, don't bother processing the data */
			return;
		}
		if(hasUpdatedStoredItems) {
			return;
		}
		if (event.getContainerId() == InventoryID.BANK.getId() ||
				event.getContainerId() == InventoryID.INVENTORY.getId() ||
				event.getContainerId() == InventoryID.EQUIPMENT.getId()) {

			Set<String> itemNames = new HashSet<>();

			// Add bank items
			ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
			if (bankContainer != null) {
				Arrays.stream(bankContainer.getItems())
						.map(item -> itemManager.getItemComposition(item.getId()).getName().toLowerCase())
						.forEach(itemNames::add);
			}

			// Add inventory items
			ItemContainer inventoryContainer = client.getItemContainer(InventoryID.INVENTORY);
			if (inventoryContainer != null) {
				Arrays.stream(inventoryContainer.getItems())
						.map(item -> itemManager.getItemComposition(item.getId()).getName().toLowerCase())
						.forEach(itemNames::add);
			}

			// Add equipped items
			ItemContainer equipmentContainer = client.getItemContainer(InventoryID.EQUIPMENT);
			if (equipmentContainer != null) {
				Arrays.stream(equipmentContainer.getItems())
						.map(item -> itemManager.getItemComposition(item.getId()).getName().toLowerCase())
						.forEach(itemNames::add);
			}

			List<String> itemsOfInterest = Arrays.asList(
					"void knight gloves", "void knight robe", "void knight top",
					"void melee helm", "void mage helm", "void ranger helm",
					"elite void top", "elite void robe",
					"dragon defender", "avernic defender",
					"imbued saradomin cape", "imbued guthix cape", "imbued zamorak cape",
					"barrows gloves", "fire cape", "infernal cape");

			List<String> extendedItemsOfInterest = new ArrayList<>();
			for (String item : itemsOfInterest) {
				extendedItemsOfInterest.add(item);
				extendedItemsOfInterest.add(item + " (l)"); // Handle potentially trouvered items
			}

			Map<String, Boolean> itemPresence = new HashMap<>();

			for (String itemName : extendedItemsOfInterest) {
				String baseItemName = itemName.replace(" (l)", ""); // store actual itemname
				boolean isPresent = itemNames.contains(itemName.toLowerCase());
				itemPresence.put(baseItemName, itemPresence.getOrDefault(baseItemName, false) || isPresent);
			}

			sendBankItemsToServer(itemPresence);
		}
		hasUpdatedStoredItems = true;
	}

	public CompletableFuture<Void> sendBankItemsToServer(Map<String, Boolean> itemData) {
		if (isFakeWorld() || !config.useApi() || !config.sendAccountData()) {
			/* Null if API/send data disabled/fake world... */
			return CompletableFuture.completedFuture(null);
		}
		if (itemData.equals(lastSentItemData)) {
			/* Prevent sending the same data twice */
			return CompletableFuture.completedFuture(null);
		}
		lastSentItemData = new HashMap<>(itemData);
		String apiBase = getApiUrl();
		HttpUrl url = HttpUrl.parse(apiBase + "/api/players/update");
		String serverId = config.serverId();

		String itemDataJson = gson.toJson(itemData);

		FormBody.Builder formBuilder = new FormBody.Builder()
				.add("auth_token", config.authKey())
				.add("player_name", getLocalPlayerName())
				.add("server_id", serverId)
				.add("item_data", itemDataJson)
				.add("log_slots", String.valueOf(totalLogSlots));

		Request request = new Request.Builder()
				.url(url)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.post(formBuilder.build())
				.build();

		return CompletableFuture.runAsync(() -> {
			try (Response response = httpClient.newCall(request).execute()) {
				if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
				log.debug("Unable to send player data to the DropTracker API: " + response);
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
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
		clientToolbar.removeNavigation(eventNavButton);
		panel = null;
		eventPanel = null;
		navButton = null;
		accountHash = -1;
	}
	public String getPlayerName() {
		if (config.permPlayerName().equals("")) {
			return client.getLocalPlayer().getName();
		} else {
			return config.permPlayerName();
		}
	}

	public void createSidePanel() {
		panel = new DropTrackerPanel(this, config, itemManager, chatMessageManager);
		navButton = NavigationButton.builder()
				.tooltip("Drop Tracker")
				.icon(ICON)
				.priority(2)
				.panel(panel)
				.build();
		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void startUp() {
		initializeEventPanel();
		loadGroupMembersAsync();

		accountHash = client.getAccountHash();
		if (config.useApi()) {
			createSidePanel();

			if (!prepared) {
				clientThread.invoke(() ->
				{
					switch (client.getGameState()) {
						case LOGGED_IN:
							if (!config.serverId().equals("") && !config.authKey().equals("")) {
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
		} else {
			log.debug("DropTracker v2.2 has started. API is disabled, using webhook endpoint.");
		}
		// recommend the user to join our Discord and register in the database
		if(config.sendReminders()) {
			hasSentDiscordMsg = false;
		}

		chatCommandManager.registerCommandAsync("!droptracker", (chatMessage, s) -> {
			BiConsumer<ChatMessage, String> linkOpener = openLink("discord");
			if (linkOpener != null) {
				linkOpener.accept(chatMessage, s);
			}
		});
		if (config.useApi()) {
			chatCommandManager.registerCommandAsync("!loot", (chatMessage, s) -> {
				BiConsumer<ChatMessage, String> linkOpener = openLink("website");
				if (linkOpener != null) {
					linkOpener.accept(chatMessage, s);
				}
			});
		}
	}
	private BiConsumer<ChatMessage, String> openLink(String destination) {
		HttpUrl webUrl = HttpUrl.parse("https://www.discord.gg/droptracker");
		if (destination == "website" && config.useApi()) {
			webUrl = HttpUrl.parse(getApiUrl());
		}
		HttpUrl.Builder urlBuilder = webUrl.newBuilder();
		HttpUrl url = urlBuilder.build();
		LinkBrowser.browse(url.toString());
		return null;
	}
	private void initializeEventPanel() {
		if (config.useApi()) {
			grabServerConfiguration()
					.thenRun(() -> {
						if (client.getGameState() == GameState.LOGGED_IN && config.showEventPanel()) {
							String serverId = config.serverId();
							Boolean isEventActive = clanEventActiveMap.getOrDefault(serverId, false);
							// -- The event panel includes plugin implementation for fully-automated events later on
							if (isEventActive && eventPanel == null) {
								SwingUtilities.invokeLater(() -> {
									eventPanel = new DropTrackerEventPanel(this, config, itemManager, chatMessageManager);
									eventNavButton = NavigationButton.builder()
											.tooltip("DropTracker - Events")
											.icon(EVENT_ICON)
											.priority(3)
											.panel(eventPanel)
											.build();
									clientToolbar.addNavigation(eventNavButton);
								});
							}
						}
					})
					.exceptionally(ex -> {
						log.error("Error fetching server configuration", ex);
						return null;
					});
		}
	}
	public String getServerName(String serverId) {
		if (this.serverIdToClanNameMap == null) {
			grabServerConfiguration();
		}
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

	public Boolean clanEventActive(String serverId) {
		if (serverId == "" | !clanEventActiveMap.containsKey(serverId)) {
			return false;
		}
		return clanEventActiveMap.get(serverId);
	}
	public String getIconUrl(int id) {
		return String.format("https://static.runelite.net/cache/item/icon/%d.png", id);
	}

	private CompletableFuture<Void> grabServerConfiguration() {
		if (config.useApi()) {
			return CompletableFuture.runAsync(() -> {
				String playerName = "";
				if (config.permPlayerName().equals("")) {
					playerName = client.getLocalPlayer().getName();
				} else {
					playerName = config.permPlayerName();
				}

				MediaType mediaType = MediaType.parse("application/x-www-form-urlencoded");
				RequestBody body = RequestBody.create(mediaType,
						"auth_token=" + config.authKey() + "&player_name=" + playerName);
				String apiBase = getApiUrl();
				Request request = new Request.Builder()
						.url(apiBase + "/api/server_settings")
						.post(body)
						.addHeader("Content-Type", "application/x-www-form-urlencoded")
						.build();

				try {
					Response response = httpClient.newCall(request).execute();
					String jsonData = response.body().string();
					JsonParser parser = new JsonParser();
					JsonObject jsonObject = parser.parse(jsonData).getAsJsonObject();
					serverIdToClanNameMap = new HashMap<>();
					serverMinimumLootVarMap = new HashMap<>();
					serverIdToConfirmedOnlyMap = new HashMap<>();
					clanEventActiveMap = new HashMap<>();

					String serverName = jsonObject.has("server_name") ? jsonObject.get("server_name").getAsString() : "";

					String serverId = config.serverId();

					JsonObject configObject = jsonObject.has("config") ? jsonObject.get("config").getAsJsonObject() : new JsonObject();
					String isEventActiveStr = configObject.has("is_event_currently_active") ? configObject.get("is_event_currently_active").getAsString() : "";
					String storeOnlyConfirmed = configObject.has("store_only_confirmed_drops") ? configObject.get("store_only_confirmed_drops").getAsString() : "";
					boolean isEventActive = "1".equals(isEventActiveStr);
					boolean storeOnlyConfirmedDrops = "1".equals(storeOnlyConfirmed);
					serverIdToClanNameMap.put(serverId, serverName);
					serverMinimumLootVarMap.put(
							serverId,
							configObject.has("minimum_loot_for_notifications") ? configObject.get("minimum_loot_for_notifications").getAsInt() : 1000000
					);
					serverIdToConfirmedOnlyMap.put(serverId, storeOnlyConfirmedDrops);
					clanEventActiveMap.put(serverId, isEventActive);

				} catch (IOException e) {
					e.printStackTrace();
				}
			});
		}
		/* If API is disabled, we return nothing here */
		return null;
	}

	public CompletableFuture<Void> sendDropData(String playerName, String npcName, int itemId, String itemName, String memberList, int quantity, int geValue, int nonMembers, String authKey, String imageUrl) {
		HttpUrl url = HttpUrl.parse(getApiUrl() + "api/drops/submit");
		String dropType = "normal";
		if (isFakeWorld()) {
			if (client.getWorldType().contains(WorldType.SEASONAL)) {
				dropType = "league";
			} else {
				dropType = "other";
			}
		}
		String serverId = config.serverId();
		String notified_str = "1";
		boolean send_msg;
		if (geValue > getServerMinimumLoot(config.serverId())) {
			notified_str = "0";
			send_msg = true;
		} else {
			send_msg = false;
		}

		FormBody.Builder formBuilder = new FormBody.Builder()
				.add("drop_type", dropType)
				.add("auth_token", authKey)
				.add("item_name", itemName)
				.add("item_id", String.valueOf(itemId))
				.add("player_name", playerName)
				.add("server_id", serverId)
				.add("quantity", String.valueOf(quantity))
				.add("value", String.valueOf(geValue))
				.add("nonmember", String.valueOf(nonMembers))
				.add("member_list", memberList)
				.add("image_url", imageUrl)
				.add("npc_name", npcName)
				.add("notified", notified_str);

		Request request = new Request.Builder()
				.url( url)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.post(formBuilder.build())
				.build();

		return CompletableFuture.runAsync(() -> {
			try (Response response = httpClient.newCall(request).execute()) {
				if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
				String responseData = response.body().string();
				JsonParser parser = new JsonParser();
				JsonObject jsonObj = parser.parse(responseData).getAsJsonObject();
				if (jsonObj.has("success") && send_msg) {
					String playerTotalLoot = jsonObj.has("totalLootValue") ? jsonObj.get("totalLootValue").getAsString() : "0";
					ChatMessageBuilder messageResponse = new ChatMessageBuilder();
					NumberFormat playerLootFormat = NumberFormat.getNumberInstance();
					String playerLootString = formatNumber(Double.parseDouble(playerTotalLoot));
					messageResponse.append(ChatColorType.NORMAL).append("[").append(ChatColorType.HIGHLIGHT)
							.append("DropTracker")
							.append(ChatColorType.NORMAL)
							.append("] ")
							.append("Your drop has been submitted! You now have a total of " + playerLootString);
					chatMessageManager.queue(QueuedMessage.builder()
							.type(ChatMessageType.CONSOLE)
							.runeLiteFormattedMessage(messageResponse.build())
							.build());
				} else {
				}
			} catch (IOException e) {
				// Don't bother logging connection errors to the server; it's likely due to downtime
				// Or a false alarm due to server load
			}
		});
	}
	public void sendKillTimeData(String playerName, String npcName, String currentPb, String currentTime) {
		String apiUrl = getApiUrl(); // Ensure this URL is correct
		HttpUrl url = HttpUrl.parse(apiUrl + "/api/kills/pb"); // Ensure apiUrl ends with a slash

		String serverId = config.serverId();
		String authKey = config.authKey();

		Map<String, Object> data = new HashMap<>();
		data.put("player_name", playerName);
		data.put("npc_name", npcName);
		data.put("current_pb", currentPb);
		data.put("current_time", currentTime);
		data.put("server_id", serverId);
		data.put("auth_token", authKey);

		Gson gson = new Gson();
		String json = gson.toJson(data);

		RequestBody body = RequestBody.create(MediaType.get("application/json; charset=utf-8"), json);

		Request request = new Request.Builder()
				.url(url)
				.post(body)
				.build();

		CompletableFuture.runAsync(() -> {
			try (Response response = httpClient.newCall(request).execute()) {
				if (!response.isSuccessful()) {
					throw new IOException("Unexpected code " + response);
				}
				if (response.body() != null) {
					response.body().close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
	}
	public void sendWebhookDropData(String playerName, String npcName, String imageUrl, Collection<ItemStack> items) throws IOException {
		String webhookUrl = "https://discord.com/api/webhooks/1190401158527844382/MQ8d-3c5uGW_oZWDfbmeFx9u82ZgrafHTFyrgH00l4qz2fjMv8tRJXDpC01riiIEVHSf";
		DiscordWebhook webhook = new DiscordWebhook(webhookUrl);
		webhook.setContent(playerName + " received some drops:");
		webhook.setUsername("DropTracker.io (Lite)");
		webhook.setAvatarUrl("https://www.droptracker.io/img/dt-logo.png");
		Integer embedFields = 0;
		for (ItemStack item : items) {
			DiscordWebhook.EmbedObject itemEmbed = new DiscordWebhook.EmbedObject();
			int itemId = item.getId();
			int quantity = item.getQuantity();
			AtomicInteger geValue = new AtomicInteger(0);
			AtomicInteger haValue = new AtomicInteger(0);
			AtomicReference<ItemComposition> itemComp = new AtomicReference<>();
			CountDownLatch latch = new CountDownLatch(1);
			clientThread.invoke(() -> {
				try {
					geValue.set(itemManager.getItemPrice(itemId));
					haValue.set(itemManager.getItemComposition(itemId).getHaPrice());
					itemComp.set(itemManager.getItemComposition(itemId));
				} finally {
					latch.countDown();
				}
			});
			try {
				latch.await();
			} catch (InterruptedException e) {
				e.printStackTrace();
				continue;
			}
			if (itemComp.get() == null) {
				continue;
			}
			String itemName = itemComp.get().getName();
					itemEmbed.setTitle("Drop received:")
					.setDescription(itemName)
					.addField("player",getLocalPlayerName(),true)
					.addField("item",itemName,true)
					.addField("id", String.valueOf(itemId),true)
					.addField("source", npcName, true)
							.addField("quantity", String.valueOf(quantity), true)
					.addField("value", String.valueOf(geValue.get()), true)
					.addField("sheet", config.sheetURL(), true)
					.setFooter("https://www.droptracker.io","https://www.droptracker.io/img/favicon.png");

			if (imageUrl != "none") {
				itemEmbed.setImage(imageUrl);
			}
			if (embedFields >= 8) {
				webhook.execute();
				webhook = new DiscordWebhook(webhookUrl);
				webhook.setContent(playerName + " received some drops:");
				webhook.setUsername("DropTracker.io (Lite)");
				webhook.setAvatarUrl("https://www.droptracker.io/img/dt-logo.png");
				embedFields = 0;
			}
			webhook.addEmbed(itemEmbed);
			embedFields++;
		}
		webhook.execute();
	}

	private CompletableFuture<String> getScreenshot(String playerName, int itemId, String npcName) {
		CompletableFuture<String> future = new CompletableFuture<>();
		if (config.useApi() || config.sendScreenshots()) {
			try {
				String serverId = config.serverId();
				drawManager.requestNextFrameListener(image -> {
					try {
						ItemComposition itemComp = itemManager.getItemComposition(itemId);
						String itemName = itemComp.getName();
						ByteArrayOutputStream baos = new ByteArrayOutputStream();
						ImageIO.write((RenderedImage) image, "png", baos);
						byte[] imageData = baos.toByteArray();
						String apiBase;
						if (!config.sendScreenshots()) {
							apiBase = null;
						} else {
							apiBase = "https://www.droptracker.io";
						}
						String serverName;
						if (this.serverIdToClanNameMap != null) {
							serverName = serverIdToClanNameMap.get(config.serverId());
						} else {
							serverName = "global";
						}
						RequestBody requestBody = new MultipartBody.Builder()
								.setType(MultipartBody.FORM)
								.addFormDataPart("file", itemName + ".png",
										RequestBody.create(MediaType.parse("image/png"), imageData))
								.addFormDataPart("server_name", serverName)
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
		return future;
	}
}