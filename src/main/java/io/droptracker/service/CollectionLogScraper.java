package io.droptracker.service;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.droptracker.DropTrackerConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Reads the player's <em>entire</em> collection log when they open it.
 *
 * <p>The client API does not expose collection log contents; the game only sends
 * them when the interface asks. The technique here — open the log, invoke its
 * "Search" operation so the server transmits every slot, then re-run the init
 * script to put the interface back — comes from WikiSync
 * (github.com/weirdgloop/WikiSync), Copyright (c) 2021, andmcadams,
 * BSD 2-Clause License (see LICENSE), by way of RuneProfile.
 *
 * <p>Why it matters: without a full read we can only ever know about items that
 * dropped while the plugin was running, so a player's collection log page would
 * start empty on the day they installed and slowly fill in. One open of the log
 * backfills everything they have ever obtained.
 *
 * <p><b>The adventure log guard is not optional.</b> A player viewing someone
 * else's collection log through a POH adventure log receives that player's slots
 * through the very same script. Storing those would silently overwrite the
 * viewer's collection log with a stranger's, so any hint of adventure-log
 * viewing discards everything accumulated.
 */
@Slf4j
@Singleton
public class CollectionLogScraper {

	/** Fired once per collection log slot, carrying (itemId, quantity). */
	private static final int COLLECTION_DELAYED_TRANSMIT = 4100;
	/** Fired when the collection log interface finishes building. */
	private static final int COLLECTION_LOG_SETUP = 7797;
	/** Rebuilds the interface, which closes the search we opened. */
	private static final int COLLECTION_INIT = 2240;

	/**
	 * Ticks of silence after the last slot before the read is considered done.
	 * The slots arrive over several ticks, so "no more for a couple of ticks" is
	 * the only completion signal available.
	 */
	private static final int QUIET_TICKS_UNTIL_COMPLETE = 2;

	private final EventBus eventBus;
	private final Client client;
	private final DropTrackerConfig config;
	private final StateSyncService stateSyncService;
	private final StateSyncScheduler stateSyncScheduler;

	/** Tick the last slot arrived on, or -1 when no read is in progress. */
	private int lastTransmitTick = -1;

	/** True while we are driving the interface, so our own init does not re-trigger us. */
	private boolean selfTriggered = false;

	@Inject
	public CollectionLogScraper(EventBus eventBus,
	                            Client client,
	                            DropTrackerConfig config,
	                            StateSyncService stateSyncService,
	                            StateSyncScheduler stateSyncScheduler) {
		this.eventBus = eventBus;
		this.client = client;
		this.config = config;
		this.stateSyncService = stateSyncService;
		this.stateSyncScheduler = stateSyncScheduler;
	}

	public void startUp() {
		eventBus.register(this);
	}

	public void shutDown() {
		eventBus.unregister(this);
		reset();
	}

	private void reset() {
		lastTransmitTick = -1;
		selfTriggered = false;
	}

	private boolean isEnabled() {
		return config.useApi() && config.syncAccountState() && stateSyncService.isEnabled();
	}

	/** True when the open log belongs to another player (POH adventure log). */
	private boolean isViewingSomeoneElse() {
		return client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState state = event.getGameState();
		if (state != GameState.LOGGED_IN && state != GameState.HOPPING) {
			reset();
		}
	}

	/**
	 * Asks the game to transmit every slot once the log has been opened.
	 *
	 * <p>The "Search" operation is what makes the server send the full contents;
	 * re-running the init script immediately afterwards restores the normal view
	 * so the player does not find their search box mysteriously open.
	 */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event) {
		if (event.getScriptId() != COLLECTION_LOG_SETUP || !isEnabled()) {
			return;
		}

		if (isViewingSomeoneElse()) {
			stateSyncService.clearItems();
			return;
		}

		// Our own init call re-fires setup; without this we would loop.
		if (selfTriggered) {
			return;
		}
		selfTriggered = true;

		client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);
		client.runScript(COLLECTION_INIT);
	}

	/** Collects one slot per firing. */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event) {
		if (event.getScriptId() != COLLECTION_DELAYED_TRANSMIT || !isEnabled()) {
			return;
		}

		if (isViewingSomeoneElse()) {
			return;
		}

		Object[] args = event.getScriptEvent() == null ? null : event.getScriptEvent().getArguments();
		if (args == null || args.length < 3) {
			return;
		}

		try {
			int itemId = (int) args[1];
			int quantity = (int) args[2];
			stateSyncService.storeItem(itemId, quantity);
			lastTransmitTick = client.getTickCount();
		} catch (ClassCastException e) {
			log.debug("Unexpected collection log script arguments: {}", e.toString());
		}
	}

	/**
	 * Detects the end of a read and schedules a sync.
	 *
	 * <p>Only a read that ran to completion marks the item set as complete —
	 * that flag is what tells the server the absence of an item means "not
	 * obtained" rather than "not seen yet".
	 */
	@Subscribe
	public void onGameTick(GameTick event) {
		if (lastTransmitTick == -1) {
			return;
		}
		if (client.getTickCount() <= lastTransmitTick + QUIET_TICKS_UNTIL_COMPLETE) {
			return;
		}

		lastTransmitTick = -1;
		selfTriggered = false;

		if (isViewingSomeoneElse()) {
			stateSyncService.clearItems();
			return;
		}

		stateSyncService.markClogComplete();
		stateSyncScheduler.scheduleRapid("clog");
	}

	/**
	 * Fail-safe: if the adventure log opens at any point, drop everything.
	 *
	 * <p>The checks above cover the ordinary paths, but the cost of being wrong
	 * here is attributing another player's collection log to this account, so it
	 * is worth catching the varbit directly as well.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		if (event.getVarbitId() == VarbitID.COLLECTION_POH_HOST_BOOK_OPEN && isViewingSomeoneElse()) {
			log.debug("Adventure log opened; discarding accumulated collection log items");
			stateSyncService.clearItems();
			reset();
		}
	}
}
