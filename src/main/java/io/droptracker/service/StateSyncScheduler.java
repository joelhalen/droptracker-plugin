package io.droptracker.service;

import lombok.RequiredArgsConstructor;
import java.util.concurrent.*;

import javax.inject.*;

import io.droptracker.DropTrackerConfig;
import io.droptracker.models.api.Manifest;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.*;
import net.runelite.client.events.ConfigChanged;

/**
 * Decides <em>when</em> a state snapshot is sent.
 *
 * <p>Three triggers, in increasing urgency:
 * <ul>
 *   <li>a periodic sync, so a player who never does anything notable still has
 *       current state;</li>
 *   <li>login, so a player who played on another client is caught up;</li>
 *   <li>a short-debounced "rapid" sync after something notable (a new collection
 *       log slot), so the website reflects it while the player still cares.</li>
 * </ul>
 *
 * <p>The interval and the debounce both come from the manifest, so sync load is
 * tunable — and switchable off — from the server without a Plugin Hub release.
 *
 * <p>On logout or a hop the remaining delay is preserved rather than
 * restarted. Without that, a player who hops worlds every few minutes would
 * restart the timer each time and never sync at all. Loading screens are not
 * logins and change nothing (see {@link #react}).
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class StateSyncScheduler {

	private final EventBus eventBus;
	private final ScheduledExecutorService executor;
	private final StateSyncService stateSyncService;
	private final ManifestService manifestService;

	private ScheduledFuture<?> pendingSync;

	/** Milliseconds left on the timer when the player logged out; 0 when not paused. */
	private long remainingOnPause;

	/** Guards the future and the pause state, both touched from several threads. */
	private final Object lock = new Object();

	private static final int DEFAULT_INTERVAL_MINUTES = 60;
	private static final int DEFAULT_RAPID_SECONDS = 3;

	public void startUp() {
		eventBus.register(this);
		// Schedule immediately, not just on the next login. The plugin usually
		// starts while the player is already logged in, so waiting for a
		// GameStateChanged means the first sync never happens at all.
		if (stateSyncService.isEnabled()) {
			scheduleRapid("startup");
		}
	}

	public void shutDown() {
		eventBus.unregister(this);
		synchronized (lock) {
			cancel();
			remainingOnPause = 0;
		}
	}

	/**
	 * Reacts to the sync toggle being flipped while the client is running.
	 *
	 * <p>Without this, enabling "Sync account progress" does nothing until the
	 * player next logs in — which reads as the feature being broken, because
	 * nothing happens and there is no error to see.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (!DropTrackerConfig.GROUP.equals(event.getGroup())) {
			return;
		}
		if (stateSyncService.isEnabled()) {
			// The manifest may never have loaded (it is skipped entirely while
			// the API is disabled), and without it no combat achievement varps
			// are read at all.
			manifestService.refresh();
			scheduleRapid("config-changed");
		} else {
			synchronized (lock) {
				cancel();
				remainingOnPause = 0;
			}
		}
	}

	/** What a game state change means for syncing. */
	enum Reaction { LOGIN, RESUME, PAUSE, IGNORE }

	/**
	 * Decides what a change of game state means.
	 *
	 * <p>The client passes through LOADING and back to LOGGED_IN on every
	 * loading screen: a teleport, a new region, entering a house. Until
	 * 6.1.30 each of those counted as a login and sent a full sync three
	 * seconds later, about seventeen an hour per player, almost all of them
	 * with nothing new in them. Only arriving from the login screen is a
	 * login. Coming back from a hop or a dropped connection resumes the
	 * timer where it stopped.
	 *
	 * @param previous the last state seen that was not LOADING; null before any
	 */
	static Reaction react(GameState previous, GameState now) {
		if (now == GameState.LOADING) {
			return Reaction.IGNORE;
		}
		if (now == GameState.LOGGED_IN) {
			if (previous == GameState.LOGGED_IN) {
				return Reaction.IGNORE;
			}
			boolean wasAway = previous == GameState.HOPPING || previous == GameState.CONNECTION_LOST;
			return wasAway ? Reaction.RESUME : Reaction.LOGIN;
		}
		return Reaction.PAUSE;
	}

	/** The last game state seen that was not LOADING. */
	private GameState lastState;

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState now = event.getGameState();
		Reaction reaction = react(lastState, now);
		if (now != GameState.LOADING) {
			lastState = now;
		}
		if (!stateSyncService.isEnabled() || reaction == Reaction.IGNORE) {
			return;
		}

		if (reaction == Reaction.LOGIN) {
			// A login is the one moment we know the client's view is fresh and
			// possibly stale server-side, so sync promptly rather than waiting
			// out whatever was left on the timer.
			scheduleRapid("login");
			return;
		}
		if (reaction == Reaction.RESUME) {
			scheduleNext();
			return;
		}

		// Away (login screen, hopping, connection lost): keep what was left on
		// the timer so the resume above restores it rather than restarting it.
		synchronized (lock) {
			if (pendingSync != null) {
				remainingOnPause = Math.max(0, pendingSync.getDelay(TimeUnit.MILLISECONDS));
				cancel();
				log.debug("Paused state sync with {}ms remaining", remainingOnPause);
			}
		}
	}

	/** Schedules a sync after the short debounce, replacing anything pending. */
	public void scheduleRapid(String source) {
		if (!stateSyncService.isEnabled()) {
			return;
		}
		synchronized (lock) {
			cancel();
			pendingSync = executor.schedule(() -> run(source), rapidSeconds(), TimeUnit.SECONDS);
		}
	}

	/** Schedules the next periodic sync, resuming a paused timer if there is one. */
	public void scheduleNext() {
		if (!stateSyncService.isEnabled()) {
			return;
		}
		synchronized (lock) {
			cancel();
			long delayMs;
			if (remainingOnPause > 0) {
				delayMs = remainingOnPause;
				remainingOnPause = 0;
				log.debug("Resuming state sync in {}ms", delayMs);
			} else {
				delayMs = TimeUnit.MINUTES.toMillis(intervalMinutes());
			}
			pendingSync = executor.schedule(() -> run("interval"), delayMs, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * Runs one sync and queues the next.
	 *
	 * <p>The next sync is scheduled unconditionally, including after a failure —
	 * otherwise one network blip would end syncing for the rest of the session.
	 */
	private void run(String source) {
		try {
			stateSyncService.sync(source);
		} catch (Exception e) {
			log.debug("State sync ({}) threw: {}", source, e.toString());
		} finally {
			scheduleNext();
		}
	}

	private void cancel() {
		if (pendingSync != null) {
			pendingSync.cancel(false);
			pendingSync = null;
		}
	}

	private int intervalMinutes() {
		Manifest manifest = manifestService.getManifest();
		return manifest == null ? DEFAULT_INTERVAL_MINUTES : manifest.getSync().getIntervalMinutes();
	}

	private int rapidSeconds() {
		Manifest manifest = manifestService.getManifest();
		return manifest == null ? DEFAULT_RAPID_SECONDS : manifest.getSync().getRapidSeconds();
	}
}
