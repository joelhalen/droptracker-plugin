package io.droptracker.service;

import java.util.concurrent.Future;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.droptracker.DropTrackerPlugin;
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
 * no progress, no location. It goes out the way every other submission does,
 * to the API when "Use API" is on and through our Discord webhooks when it
 * is not, so statistics stay current for every player whichever they use.
 *
 * <p>The identity is remembered while logged in because by the time the game
 * reports the login screen, the local player is gone. A world hop updates
 * the hiscores as well, so it is reported too, but only when the player has
 * gained experience since the last report: hopping back and forth must not
 * send a message each time. Only main-game worlds count: a Leagues or
 * Deadman character has its own hiscores.
 */
@Slf4j
@Singleton
public class LogoutSignal {

	private final Client client;
	private final DropTrackerPlugin plugin;
	private final SubmissionManager submissionManager;
	private final EventBus eventBus;

	/** Who is logged in on a main-game world right now; null when nobody is. */
	private volatile String playerName;
	private volatile long accountHash = -1L;
	/** Total experience when this player was last reported (or first seen). */
	private volatile long reportedXp = -1L;

	@Inject
	public LogoutSignal(Client client, DropTrackerPlugin plugin, SubmissionManager submissionManager,
			EventBus eventBus) {
		this.client = client;
		this.plugin = plugin;
		this.submissionManager = submissionManager;
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
			// The hiscores update on a hop too. Report it if anything was
			// gained, then look again once the new world has loaded: it may be
			// a different kind of world.
			long xp = client.getOverallExperience();
			if (playerName != null && reportedXp >= 0 && xp != reportedXp) {
				send();
			} else {
				forget();
			}
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
		reportedXp = client.getOverallExperience();
		playerName = local.getName();
	}

	private void forget() {
		playerName = null;
		accountHash = -1L;
		reportedXp = -1L;
	}

	/** Sends the signal for whoever was logged in, once. Null when there is nothing to send. */
	Future<?> send() {
		String name = playerName;
		long hash = accountHash;
		forget();
		if (name == null || !trackingEnabled()) {
			return null;
		}
		return deliver(name, hash);
	}

	boolean trackingEnabled() {
		return plugin.isTracking;
	}

	Future<?> deliver(String name, long hash) {
		return submissionManager.sendLogout(name, hash, plugin.pluginVersion);
	}
}
