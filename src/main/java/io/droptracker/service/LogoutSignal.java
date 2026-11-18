package io.droptracker.service;

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.util.WorldTypes;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;

/**
 * Tells the server the moment the player logs out.
 *
 * <p>The official hiscores update when a player logs out, so that is the one
 * moment the server can read fresh skills and kill counts for this account.
 * Without the signal it finds out by polling, hours later.
 *
 * <p>The message carries the player's name and account hash and nothing else:
 * no progress, no location. It is sent only when "Use API" is on, like every
 * other request to DropTracker's server.
 *
 * <p>The identity is remembered while logged in because by the time the game
 * reports the login screen, the local player is gone. A world hop is not a
 * logout and sends nothing. Only main-game worlds count: a Leagues or Deadman
 * character has its own hiscores.
 */
@Slf4j
@Singleton
public class LogoutSignal {

	private final Client client;
	private final DropTrackerConfig config;
	private final DropTrackerApi api;
	private final ScheduledExecutorService executor;
	private final EventBus eventBus;

	/** Who is logged in on a main-game world right now; null when nobody is. */
	private volatile String playerName;
	private volatile long accountHash = -1L;

	@Inject
	public LogoutSignal(Client client, DropTrackerConfig config, DropTrackerApi api,
			ScheduledExecutorService executor, EventBus eventBus) {
		this.client = client;
		this.config = config;
		this.api = api;
		this.executor = executor;
		this.eventBus = eventBus;
	}

	public void startUp() {
		eventBus.register(this);
	}

	public void shutDown() {
		eventBus.unregister(this);
		forget();
	}

	/** The local player is not readable at LOGGED_IN itself, so look each tick until it is. */
	@Subscribe
	public void onGameTick(GameTick tick) {
		if (playerName == null) {
			remember();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN) {
			send();
		} else if (state == GameState.HOPPING) {
			// Same account, possibly a different kind of world: look again
			// once the new world has loaded.
			forget();
		}
	}

	/** Closing the client logs the player out too; hold the shutdown until the message has gone. */
	@Subscribe
	public void onClientShutdown(ClientShutdown event) {
		Future<?> sent = send();
		if (sent != null) {
			event.waitFor(sent);
		}
	}

	void remember() {
		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null) {
			return;
		}
		if (!WorldTypes.isMainWorld(client.getWorldType())) {
			return;
		}
		long hash = client.getAccountHash();
		if (hash == -1L) {
			return;
		}
		accountHash = hash;
		playerName = local.getName();
	}

	private void forget() {
		playerName = null;
		accountHash = -1L;
	}

	/** Sends the signal for whoever was logged in, once. Null when there is nothing to send. */
	Future<?> send() {
		String name = playerName;
		long hash = accountHash;
		forget();
		if (name == null || !config.useApi()) {
			return null;
		}
		return dispatch(() -> deliver(name, hash));
	}

	Future<?> dispatch(Runnable task) {
		return executor.submit(task);
	}

	void deliver(String name, long hash) {
		try {
			api.postLogout(name, hash);
		} catch (Exception e) {
			log.debug("Logout signal failed: {}", e.toString());
		}
	}
}
