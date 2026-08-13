package io.droptracker.service;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.droptracker.models.api.Manifest;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

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
 * <p>On logout the remaining delay is preserved rather than restarted. Without
 * that, a player who hops worlds every few minutes would restart a 60-minute
 * timer each time and never sync at all.
 */
@Slf4j
@Singleton
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

	@Inject
	public StateSyncScheduler(EventBus eventBus,
	                          ScheduledExecutorService executor,
	                          StateSyncService stateSyncService,
	                          ManifestService manifestService) {
		this.eventBus = eventBus;
		this.executor = executor;
		this.stateSyncService = stateSyncService;
		this.manifestService = manifestService;
	}

	public void startUp() {
		eventBus.register(this);
	}

	public void shutDown() {
		eventBus.unregister(this);
		synchronized (lock) {
			cancel();
			remainingOnPause = 0;
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (!stateSyncService.isEnabled()) {
			return;
		}

		if (event.getGameState() == GameState.LOGGED_IN) {
			// A login is the one moment we know the client's view is fresh and
			// possibly stale server-side, so sync promptly rather than waiting
			// out whatever was left on the timer.
			scheduleRapid("login");
			return;
		}

		// Hopping is not really "away" — but treating it the same is harmless,
		// because the resume below restores the remaining time rather than
		// restarting it.
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
