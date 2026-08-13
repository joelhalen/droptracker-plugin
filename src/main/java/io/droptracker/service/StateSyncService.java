package io.droptracker.service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.droptracker.DropTrackerConfig;
import io.droptracker.DropTrackerPlugin;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.AchievementDiaryArea;
import io.droptracker.models.StateSnapshot;
import io.droptracker.models.api.Manifest;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.ScriptID;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;

/**
 * Builds and sends {@link StateSnapshot}s — the account's current state, which
 * the event submissions cannot describe.
 *
 * <p>Also owns the collection log items accumulated by the clog subscribers,
 * because they arrive asynchronously as the player browses and must survive
 * until the next sync sends them.
 */
@Slf4j
@Singleton
public class StateSyncService {

	private final Client client;
	private final ClientThread clientThread;
	private final DropTrackerConfig config;
	private final DropTrackerApi api;
	private final ManifestService manifestService;
	private final ScheduledExecutorService executor;
	private final DropTrackerPlugin plugin;

	/**
	 * Collection log slots seen so far this session, item id -> quantity.
	 *
	 * <p>Concurrent because the clog subscribers write from the client thread
	 * while a sync may be reading on an executor thread.
	 */
	private final Map<Integer, Integer> clogItems = new ConcurrentHashMap<>();

	/** Set once a full collection log read has completed this session. */
	private final AtomicBoolean clogComplete = new AtomicBoolean(false);

	/** Prevents overlapping syncs; a slow request must not queue up behind itself. */
	private final AtomicBoolean syncing = new AtomicBoolean(false);

	@Inject
	public StateSyncService(Client client,
	                        ClientThread clientThread,
	                        DropTrackerConfig config,
	                        DropTrackerApi api,
	                        ManifestService manifestService,
	                        ScheduledExecutorService executor,
	                        DropTrackerPlugin plugin) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.api = api;
		this.manifestService = manifestService;
		this.executor = executor;
		this.plugin = plugin;
	}

	/** True when the user has opted in and the server has not switched sync off. */
	public boolean isEnabled() {
		if (!config.useApi() || !config.syncAccountState()) {
			return false;
		}
		Manifest manifest = manifestService.getManifest();
		return manifest == null || manifest.getSync().isEnabled();
	}

	/** Records a collection log slot. Quantities of zero mean "not obtained". */
	public void storeItem(int itemId, int quantity) {
		if (quantity <= 0) {
			return;
		}
		clogItems.put(itemId, quantity);
	}

	/** Marks the accumulated items as a complete read of the collection log. */
	public void markClogComplete() {
		clogComplete.set(true);
	}

	/**
	 * Drops everything accumulated so far.
	 *
	 * <p>Called when we might have been reading somebody else's collection log
	 * (the POH adventure log) or when the logged-in account changes — in both
	 * cases keeping the items would attribute them to the wrong player.
	 */
	public void clearItems() {
		clogItems.clear();
		clogComplete.set(false);
	}

	public void reset() {
		clearItems();
		syncing.set(false);
	}

	/**
	 * Collects a snapshot on the client thread.
	 *
	 * <p>Everything here is a client read, so it must not run anywhere else;
	 * the returned future completes on the client thread.
	 */
	public CompletableFuture<StateSnapshot> collect(String source) {
		CompletableFuture<StateSnapshot> future = new CompletableFuture<>();
		clientThread.invokeLater(() -> {
			try {
				future.complete(buildSnapshot(source));
			} catch (Exception e) {
				future.completeExceptionally(e);
			}
		});
		return future;
	}

	/**
	 * Collects and sends a snapshot, unless one is already in flight.
	 *
	 * <p>Failures are logged at debug and otherwise ignored: state sync is a
	 * background enhancement and must never surface an error for something the
	 * player did not ask for.
	 */
	public void sync(String source) {
		if (!isEnabled() || !isPlayerReady()) {
			return;
		}
		if (!syncing.compareAndSet(false, true)) {
			log.debug("State sync already in progress; skipping {}", source);
			return;
		}
		collect(source)
				.thenAcceptAsync(snapshot -> {
					try {
						api.postStateSnapshot(snapshot);
					} finally {
						syncing.set(false);
					}
				}, executor)
				.exceptionally(e -> {
					log.debug("State sync ({}) failed: {}", source, e.toString());
					syncing.set(false);
					return null;
				});
	}

	private boolean isPlayerReady() {
		return client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null;
	}

	private StateSnapshot buildSnapshot(String source) {
		StateSnapshot snapshot = new StateSnapshot();
		Manifest manifest = manifestService.getManifest();

		Player local = client.getLocalPlayer();
		snapshot.setPlayerName(local != null ? local.getName() : null);
		snapshot.setAccountHash(String.valueOf(client.getAccountHash()));
		snapshot.setSource(source);
		snapshot.setPluginVersion(plugin.pluginVersion);
		snapshot.setManifestVersion(manifest != null ? manifest.getVersion() : null);
		snapshot.setAccountType(client.getVarbitValue(VarbitID.IRONMAN));
		snapshot.setCombatLevel(local != null ? local.getCombatLevel() : null);

		collectSkills(snapshot);
		collectQuests(snapshot, manifest);
		collectCombatAchievements(snapshot, manifest);
		collectDiaries(snapshot);
		collectCollectionLog(snapshot);

		return snapshot;
	}

	private void collectSkills(StateSnapshot snapshot) {
		Map<String, Integer> skills = new HashMap<>();
		for (Skill skill : Skill.values()) {
			skills.put(skill.getName(), client.getSkillExperience(skill));
		}
		snapshot.setSkills(skills);
	}

	/**
	 * Reads the state of every quest the manifest lists.
	 *
	 * <p>Iterating the manifest rather than RuneLite's {@link Quest} enum is the
	 * point: a quest released today is trackable today, instead of after a
	 * RuneLite release and a Plugin Hub build. An empty manifest list falls back
	 * to the enum so this still works before the manifest is populated.
	 */
	private void collectQuests(StateSnapshot snapshot, Manifest manifest) {
		int[] questIds = manifest != null && !manifest.getQuestIds().isEmpty()
				? manifest.getQuestIds().stream().mapToInt(Integer::intValue).toArray()
				: Arrays.stream(Quest.values()).mapToInt(Quest::getId).toArray();

		Map<Integer, Integer> quests = new HashMap<>();
		for (int questId : questIds) {
			try {
				// What Quest#getState does internally: the script leaves the
				// status on the int stack.
				client.runScript(ScriptID.QUEST_STATUS_GET, questId);
				quests.put(questId, questStateFrom(client.getIntStack()[0]));
			} catch (Exception e) {
				log.debug("Could not read quest {}: {}", questId, e.toString());
			}
		}
		snapshot.setQuests(quests);
	}

	/** Maps a QUEST_STATUS_GET result onto 0 not started / 1 in progress / 2 finished. */
	private static int questStateFrom(int status) {
		if (status == 1) {
			return 0;
		}
		if (status == 2) {
			return 2;
		}
		return 1;
	}

	/**
	 * Reads the combat achievement completion bits listed in the manifest.
	 *
	 * <p>Deliberately does nothing without a manifest: the varps are not a
	 * contiguous range, so there is no safe range to guess at, and sending a
	 * partial set would look like the player had un-completed tasks.
	 */
	private void collectCombatAchievements(StateSnapshot snapshot, Manifest manifest) {
		if (manifest == null || manifest.getCombatAchievementVarps().isEmpty()) {
			return;
		}
		Map<Integer, Integer> varps = new HashMap<>();
		for (int varpId : manifest.getCombatAchievementVarps()) {
			varps.put(varpId, client.getVarpValue(varpId));
		}
		snapshot.setCombatAchievementVarps(varps);
	}

	private void collectDiaries(StateSnapshot snapshot) {
		List<StateSnapshot.DiaryTier> tiers = new java.util.ArrayList<>();
		for (AchievementDiaryArea area : AchievementDiaryArea.values()) {
			int[] completed = area.getTiersCompletedCount(client);
			for (int tier = 0; tier < completed.length; tier++) {
				tiers.add(new StateSnapshot.DiaryTier(area.getId(), tier, completed[tier]));
			}
		}
		snapshot.setDiaryTiers(tiers);
	}

	private void collectCollectionLog(StateSnapshot snapshot) {
		snapshot.setItems(new HashMap<>(clogItems));
		snapshot.setClogComplete(clogComplete.get());

		// The game's own counters are correct even when we have read no items
		// at all, so a profile can show real progress before any scrape.
		int completed = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
		int total = client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX);
		if (total > 0 && completed >= 0) {
			snapshot.setClogSlots(completed);
			snapshot.setClogSlotsTotal(total);
		}
	}
}
