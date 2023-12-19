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

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;

import com.joelhalen.droptracker.ui.DropEntryOverlay;
import net.runelite.api.*;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetLoaded;
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
import net.runelite.client.chat.ChatClient;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.loottracker.LootRecordType;
import okhttp3.*;

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
import javax.inject.Named;
import javax.swing.*;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Array;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.joelhalen.droptracker.DropTrackerPanel.formatNumber;


@PluginDescriptor(
		name = "DropTracker",
		description = "Automatically uploads your drops to the DropTracker discord bot!",
		tags = {"droptracker", "drop", "leaderboard", "tracking"}
)

public class DropTrackerPlugin extends Plugin {
	private OverlayManager overlayManager;
	private DropEntryOverlay overlay;
	public DropTrackerPlugin() {
	}
	//* For sending collection log entry slots to the server *//
	private static final Pattern COLLECTION_LOG_ITEM_REGEX = Pattern.compile("New item added to your collection log:.*");
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
	public List<String> itemsOfInterest;
	List<DropEntryStream> storedDrops = new CopyOnWriteArrayList<>();
	@Inject
	private ClientThread clientThread;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private DrawManager drawManager;
	private long accountHash = -1;
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

	public void removeOverlay() {
		setOverlayManager(overlayManager);
		setDropEntryOverlay(overlay);
		if (this.overlay != null) {
			this.overlayManager.remove(overlay);
		}
	}
	public void addOverlay() {
		setOverlayManager(overlayManager);
		setDropEntryOverlay(overlay);
		if (this.overlay != null) {
			this.overlayManager.add(overlay);
		}
	}
	@Inject
	public void setOverlayManager(OverlayManager overlayManager) {
		this.overlayManager = overlayManager;
	}

	@Inject
	public void setDropEntryOverlay(DropEntryOverlay overlay) {
		this.overlay = overlay;
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived npcLootReceived) {
		NPC npc = npcLootReceived.getNpc();
		String npcName = npc.getName();
		int npcCombatLevel = npc.getCombatLevel();
		String playerName = config.permPlayerName().isEmpty() ? client.getLocalPlayer().getName() : config.permPlayerName();
		Collection<ItemStack> items = npcLootReceived.getItems();

		for (ItemStack item : items) {
			int itemId = item.getId();
			int quantity = item.getQuantity();

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
							if (serverIdToConfirmedOnlyMap.get(serverId) != true) {
								sendDropData(playerName, npcName, itemId, itemName, "", quantity, geValue, 0, config.authKey(), "");
							}
						} else {
							/* Create the screenshot object so that we can upload it, saving the url to the drop object */
							CompletableFuture<String> uploadFuture = getScreenshot(playerName, itemId, npcName);
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
								getScreenshot(playerName, itemId, npcName).thenAccept(imageUrl -> {
									SwingUtilities.invokeLater(() -> {
										entry.setImageLink(imageUrl);
									});
								});
							} else {
								entry.setImageLink("none");
							}
							panel.addDrop(entry);
							if (config.showOverlay() && panel.getEntries() != null) {
								addOverlay();
							}
						}
			});
		}
	}
	public void loadGroupMembersAsync() {
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
							CompletableFuture<String> uploadFuture = getScreenshot(submissionPlayer, itemId, lootReceived.getName());
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
			if (panel.getEntries() == null && overlay != null) {
				removeOverlay();
			}
			if (config.showOverlay() && panel.getEntries() != null) {
				addOverlay();
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
		panel.refreshPanel();
		if (config.showEventPanel() && clanEventActiveMap.get(config.serverId()) && this.eventPanel != null) {
			eventPanel.refreshPanel();
		}
		if (overlay != null) {
			if (!config.showOverlay()) {
				removeOverlay();
			} else if (config.showOverlay() && panel.getEntries() != null) {
				addOverlay();
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

		Widget colLogTitleWig = client.getWidget(621, 1);

		if (colLogTitleWig != null) {
			colLogTitleWig = colLogTitleWig.getChild(1);
			// Added closing parenthesis and semicolon here
			Integer new_slots = Integer.parseInt(colLogTitleWig.getText().split("- ")[1].split("/")[0]);
			if(new_slots != totalLogSlots){
				/* Player has more slots stored than we previously knew? */
				log.debug("[DropTracker] Updating log slots to" + new_slots + " from " + totalLogSlots);
				System.out.println("[DropTracker] Updating log slots to" + new_slots + " from " + totalLogSlots);
				totalLogSlots = new_slots;
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		if (isFakeWorld()) {
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
	}

	public CompletableFuture<Void> sendBankItemsToServer(Map<String, Boolean> itemData) {

		if (itemData.equals(lastSentItemData)) {
			return CompletableFuture.completedFuture(null);
		}
		lastSentItemData = new HashMap<>(itemData);

		HttpUrl url = HttpUrl.parse("http://api.droptracker.io/api/players/update");
		String serverId = config.serverId();

		Gson gson = new Gson();
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
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
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
		clientToolbar.removeNavigation(eventNavButton);
		removeOverlay();
		panel = null;
		eventPanel = null;
		navButton = null;
		overlayManager = null;
		overlay = null;
		accountHash = -1;
	}
	public String getPlayerName() {
		if (config.permPlayerName().equals("")) {
			return client.getLocalPlayer().getName();
		} else {
			return config.permPlayerName();
		}
	}
	@Override
	protected void startUp() {
		initializeEventPanel();
		loadGroupMembersAsync();

		accountHash = client.getAccountHash();
		panel = new DropTrackerPanel(this, config, itemManager, chatMessageManager);

		navButton = NavigationButton.builder()
				.tooltip("Drop Tracker")
				.icon(ICON)
				.priority(2)
				.panel(panel)
				.build();
		clientToolbar.addNavigation(navButton);

		if (!prepared) {
			clientThread.invoke(() ->
			{
				switch (client.getGameState()) {
					case LOGGED_IN:
						if (config.serverId().equals("")) {
							//TODO: Send a chat message letting the user know the plugin is not yet set up

						} else if (config.authKey().equals("")) {
							//TODO: Message the user that their auth key has been left empty
						} else {
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
	private void initializeEventPanel() {
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

			MediaType mediaType = MediaType.parse("application/x-www-form-urlencoded");
			RequestBody body = RequestBody.create(mediaType,
					"auth_token=" + config.authKey() + "&player_name=" + playerName);

			Request request = new Request.Builder()
					.url("http://api.droptracker.io/api/server_settings")
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

	public CompletableFuture<Void> sendDropData(String playerName, String npcName, int itemId, String itemName, String memberList, int quantity, int geValue, int nonMembers, String authKey, String imageUrl) {
		HttpUrl url = HttpUrl.parse("http://api.droptracker.io/api/drops/submit");
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
				.url(url)
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
				System.out.println("Exception occurred: " + e.getMessage());
				e.printStackTrace();
			}
		});
	}

	private CompletableFuture<String> getScreenshot(String playerName, int itemId, String npcName) {
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
					String nicePlayerName = playerName.replace(" ", "_");

					RequestBody requestBody = new MultipartBody.Builder()
							.setType(MultipartBody.FORM)
							.addFormDataPart("file", itemName + ".png",
									RequestBody.create(MediaType.parse("image/png"), imageData))
							.addFormDataPart("server_name", serverIdToClanNameMap.get(config.serverId()))
							.addFormDataPart("player_name", playerName)
							.addFormDataPart("npc", npcName)
							.build();
						executor.submit(() -> {
							Request request = new Request.Builder()
									.url("http://api.droptracker.io/api/upload_image")
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
}