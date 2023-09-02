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
		Or via the DropTracker website: https://www.droptracker.io/

		~~~SHARES THE USER'S IP ADDRESS WITH THE AUTHENTICATION/DROP SUBMISSION SERVER~~~

 */
package com.joelhalen.droptracker;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.inject.Provides;
import com.joelhalen.droptracker.api.DropTrackerApi;
import com.joelhalen.droptracker.ui.DropTrackerOverlay;
import lombok.var;
import net.runelite.api.*;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.game.ItemStack;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.loottracker.LootRecordType;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.joelhalen.droptracker.api.exp;

@PluginDescriptor(
		name = "DropTracker",
		description = "Automatically uploads your drops to the DropTracker discord bot!",
		tags = {"droptracker", "drop", "webhook"}
)

public class DropTrackerPlugin extends Plugin {
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
	List<DropEntryStream> storedDrops = new ArrayList<>();
	@Inject
	private ClientThread clientThread;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private DropTrackerOverlay dropTrackerOverlay;
	@Inject
	private DrawManager drawManager;
	private DropTrackerApi dropTrackerApi;
	private long accountHash = -1;
	private Map<String, String> serverIdToWebhookUrlMap;
	private Map<String, Integer> serverMinimumLootVarMap;
	private Map<String, Long> clanServerDiscordIDMap;
	private Map<String, String> serverIdToClanNameMap;
	private Map<String, Boolean> serverIdToConfirmedOnlyMap;
	public Map<String, Integer> clanWiseOldManGroupIDMap;
	public Map<String, Boolean> clanEventActiveMap;
	private boolean prepared = false;
	private static final Logger log = LoggerFactory.getLogger(DropTrackerPlugin.class);
	private static final BufferedImage ICON = ImageUtil.loadImageResource(DropTrackerPlugin.class, "icon.png");
	private NavigationButton navButton;
	private final Map<Skill, Integer> previousSkillExpTable = new EnumMap<>(Skill.class);
	private static final Pattern WOOD_CUT_PATTERN = Pattern.compile("You get (?:some|an)[\\w ]+(?:logs?|mushrooms)\\.");
	private static final Pattern MINING_PATTERN = Pattern.compile(
			"You " +
					"(?:manage to|just)" +
					" (?:mined?|quarry) " +
					"(?:some|an?) " +
					"(?:copper|tin|clay|iron|silver|coal|gold|mithril|adamantite|runeite|amethyst|sandstone|granite|barronite shards|barronite deposit|Opal|piece of Jade|Red Topaz|Emerald|Sapphire|Ruby|Diamond)" +
					"(?:\\.|!)");

	private Multiset<Integer> previousInventorySnapshot;
	private Integer containerChangedCount = 0;
	private Integer pendingInventoryUpdates = 0;
	private WorldPoint currentLocation = null;

	private Multiset<Integer> getInventorySnapshot()
	{
		final ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		Multiset<Integer> inventorySnapshot = HashMultiset.create();

		if (inventory != null)
		{
			Arrays.stream(inventory.getItems())
					.forEach(item -> inventorySnapshot.add(item.getId(), item.getQuantity()));
		}

		return inventorySnapshot;
	}
	private String[] groupMembers = new String[0];
	private final Object groupMembersLock = new Object();
	@Inject
	private WiseOldManClient wiseOldManClient;
	@Subscribe
	public void onNpcLootReceived(NpcLootReceived npcLootReceived) {
		// handles drops from NPCs that are obtained on the floor (mostly)
		NPC npc = npcLootReceived.getNpc();
		String npcName = npc.getName();
		int npcCombatLevel = npc.getCombatLevel();
		String playerName;
		if(config.permPlayerName().equals("")) {
			playerName = client.getLocalPlayer().getName();
		} else {
			playerName = config.permPlayerName();
		}
		Collection<ItemStack> items = npcLootReceived.getItems();
		for (ItemStack item : items) {
			int itemId = item.getId();
			int quantity = item.getQuantity();
			List<CompletableFuture<Boolean>> futures = new ArrayList<>();
			// Make sure the quantity is 1, so that we aren't submitting item stacks that are above the specified value

			int geValue = itemManager.getItemPrice(itemId);
			int haValue = itemManager.getItemComposition(itemId).getHaPrice();
			if(geValue == 0 && haValue == 0) {
				return;
			}
			ItemComposition itemComp = itemManager.getItemComposition(itemId);
			String itemName = itemComp.getName();
			boolean ignoreDrops = config.ignoreDrops();
			SwingUtilities.invokeLater(() -> {
				canBeSent(geValue).thenAccept(shouldSend -> {
					String serverId = config.serverId();
					Integer clanMinimumLoot = serverMinimumLootVarMap.get(serverId);
					if (shouldSend) {
						if (geValue < clanMinimumLoot) {
							if (serverIdToConfirmedOnlyMap.get(serverId) != true) {
								String memberList = "";
								String imageUrl = null;
								Integer nonMembers = 0;
								String authKey = config.authKey();
								try {
									dropTrackerApi.sendDropToApi(playerName, npcName, npcCombatLevel, itemId, itemName, memberList, quantity, geValue, nonMembers, authKey, imageUrl);
								} catch (IOException e) {
									throw new RuntimeException(e);
								}
							}
						} else {
							/* Drops entering this else are > clan's defined min. value */
							if (quantity > 1) {
								return;
							}
							/* Create the screenshot object so that we can upload it, saving the url to the drop object */
							CompletableFuture<String> uploadFuture = getScreenshot(playerName, itemId);
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

	public void loadGroupMembersAsync() {
		executor.execute(() -> {
			while (true) {
				try {
					String[] newGroupMembers = wiseOldManClient.getGroupMembers(getServerWiseOldManGroupID(config.serverId()));
					synchronized (groupMembersLock) {
						groupMembers = newGroupMembers;
					}
					Thread.sleep(180 * 60 * 1000); // update every 180 minutes
				} catch (IOException | InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
	}
	/* Handles excluding temporary worlds like deadman/etc from exp tracking events */
	private boolean isTracking() {
			return !isFakeWorld() && !config.authKey().isEmpty();
	}
	private boolean isFakeWorld() {
		EnumSet<WorldType> worldType = client.getWorldType();
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
	/* Experience tracking method which sends data >every< time experience changes, and all exp on login. */
	@Subscribe
	public void onStatChanged(StatChanged statChanged) {
		if (isTracking()) {
			final Skill skill = statChanged.getSkill();
			final int xp = statChanged.getXp();
			Long accountHash = this.client.getAccountHash();
			String apiKey = config.authKey();

			Integer previous = previousSkillExpTable.put(skill, xp);
			// Since we get all the skills upon login/load/whenever, we don't have to worry about seeding the table.
			if (previous != null) {
				int delta = xp - previous;
				if (delta > 0) {

					// Use the new ApiClient to send the XP data
					dropTrackerApi.sendXP(new exp(skill.name(), delta, xp, apiKey, accountHash, config));

					if (statChanged.getSkill() == Skill.RUNECRAFT) {
						pendingInventoryUpdates++;
						log.debug("Runecrafting increasing pending inv");
					}
				}
			} else {
				// Use the new ApiClient to send the XP data
				dropTrackerApi.sendXP(new exp(skill.name(), 0, xp, apiKey, accountHash, config));
			}
		}
	}

	@Subscribe
	public void onLootReceived(LootReceived lootReceived) {
		//ignore regular NPC drops; since onNpcLootReceived contains more data on the source of the drop
		if (lootReceived.getType() == LootRecordType.NPC) {
			//if the drop was an NPC, it's already been handled in onNpcLootReceived
			return;
		}
		String playerName;
		if(config.permPlayerName().equals("")) {
			playerName = client.getLocalPlayer().getName();
		} else {
			playerName = config.permPlayerName();
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
				canBeSent(geValue).thenAccept(shouldSend -> {
					String serverId = config.serverId();
					Integer clanMinimumLoot = serverMinimumLootVarMap.get(serverId);
					String submissionPlayer = "";
					if (shouldSend) {
						if(!config.permPlayerName().equals("")) {
							submissionPlayer = config.permPlayerName();
						} else {
							submissionPlayer = client.getLocalPlayer().getName();
						}
						if (geValue < clanMinimumLoot) {
							if (serverIdToConfirmedOnlyMap.get(serverId) != true) {

								String memberList = "";
								String imageUrl = null;
								Integer nonMembers = 0;
								String npcName = lootReceived.getName();
								Integer combatLevel = lootReceived.getCombatLevel();
								Integer npcCombatLevel = 0;
								String authKey = config.authKey();
								try {
									dropTrackerApi.sendDropToApi(playerName, npcName, combatLevel, itemId, itemName, memberList, quantity, geValue, nonMembers, authKey, imageUrl);
								} catch (IOException e) {
									throw new RuntimeException(e);
								}
							}
						} else {
							if (quantity > 1) {
								return;
							}
							CompletableFuture<String> uploadFuture = getScreenshot(submissionPlayer, itemId);
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
					}
				});
			});
		}
	}


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
		} else {
			panelRefreshed = false;
		}
	}

	public void onConfigChanged(ConfigChanged event) {
		panel.refreshPanel();

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
		panel = null;
		navButton = null;
		overlayManager.remove(dropTrackerOverlay);
		accountHash = -1;
	}

	@Override
	protected void startUp() {
		initializeServerIdToWebhookUrlMap().thenRun(this::loadGroupMembersAsync);

		accountHash = client.getAccountHash();
		panel = new DropTrackerPanel(this, config, itemManager, chatMessageManager, dropTrackerApi);
		navButton = NavigationButton.builder()
				.tooltip("Drop Tracker")
				.icon(ICON)
				.priority(6)
				.panel(panel)
				.build();
		clientToolbar.addNavigation(navButton);
		dropTrackerApi = new DropTrackerApi(httpClient, this, config);
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
							overlayManager.add(dropTrackerOverlay);
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

	public int getServerWiseOldManGroupID(String serverId) {
		/* If empty serverId or the mapping doesn't contain the server ID, then return 0 */
		if (serverId == "" | !clanWiseOldManGroupIDMap.containsKey(serverId)) {
			return 0;
		}
		return clanWiseOldManGroupIDMap.get(serverId);
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
		// Must ensure this is being called on the client thread
		return itemManager.getItemComposition(itemId);
	}

	private CompletableFuture<Void> initializeServerIdToWebhookUrlMap() {
		return CompletableFuture.runAsync(() -> {

			Request request = new Request.Builder()
					.url("http://data.droptracker.io/data/server_settings3.json")
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
				clanWiseOldManGroupIDMap = new HashMap<>();
				clanEventActiveMap = new HashMap<>();

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
					clanWiseOldManGroupIDMap.put(
							jsonObject.getString("serverId"),
							jsonObject.getInt("womGroup")
					);
					clanEventActiveMap.put(
							jsonObject.getString("serverId"),
							jsonObject.getBoolean("eventActive")
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
				footer.put("text", serverName + " (ID #" + serverId + ") Support: http://www.droptracker.io");

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
	private JSONObject createDropJsonForApi(DropEntryStream drop) {
		JSONObject json = new JSONObject();

		// Fields common to both low-value and high-value drops
		json.put("currentname", drop.getPlayerName());
		json.put("itemName", drop.getItemName());
		json.put("quantity", drop.getQuantity());
		json.put("npcName", drop.getNpcOrEventName());
		json.put("value", drop.getGeValue()); // Assuming 'getGeValue' returns the GE value
		json.put("apiKey", config.authKey());
		json.put("serverid", getClanDiscordServerID(config.serverId())); // Replace with the correct method if different
		json.put("npcLevel", 0);

		return json;
	}

	public CompletableFuture<Void> sendEmbedWebhook(List<DropEntryStream> storedDrops) {
		return CompletableFuture.runAsync(() -> {
			String serverId = config.serverId();
			String webhookUrl = serverIdToWebhookUrlMap.get(serverId);
			if (webhookUrl == null || config.ignoreDrops()) {
				return;
			}
			JSONObject json = new JSONObject();
			for (DropEntryStream drop : storedDrops) {
				sendDropToApi(drop); // Assuming all storedDrops are low-value
			}
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
				storedDrops.clear();  // clear the list after sending
			} catch (IOException e) {
				e.printStackTrace();
			}

		});
	}
	private void sendDropToApi(DropEntryStream drop) {
		System.out.println("SendDropToApi");
		JSONObject jsonForApi = createDropJsonForApi(drop);
		// Create a RequestBody containing your JSON data
		RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), jsonForApi.toString());

		// Create a new Request
		Request request = new Request.Builder()
				.url("http://api.droptracker.io/api/drops") // Replace with your API URL
				.post(body)
				.addHeader("Authorization", "Bearer " + config.authKey()) // Replace with your Auth method
				.build();

		// Make the request
		try (Response response = httpClient.newCall(request).execute()) {
			if (response.isSuccessful()) {
				log.debug("Sent low-value drop to the api endpoint");
				log.debug("Response: " + response.body() + response.message());
			} else {
				log.error("Error occurred sending low value drops..");
				log.error("HTTP Error Code: " + response.code());
				log.error("HTTP Error Message: " + response.message());
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	private CompletableFuture<Boolean> canBeSent(int geValue) {
		String serverId = config.serverId();
		int minimumClanValue = serverMinimumLootVarMap.get(serverId);
		boolean ignoreDrops = config.ignoreDrops();
		if (!ignoreDrops) {
			if (geValue > minimumClanValue) {
				return CompletableFuture.completedFuture(true);
			} else if(serverIdToConfirmedOnlyMap.get(serverId) != true) {
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
									.url("http://data.droptracker.io/upload/upload.php") // PHP upload script for screenshots (temporary implementation)
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