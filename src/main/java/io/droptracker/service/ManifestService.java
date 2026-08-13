package io.droptracker.service;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

import io.droptracker.api.DropTrackerApi;
import io.droptracker.models.api.Manifest;
import lombok.extern.slf4j.Slf4j;

/**
 * Holds the {@link Manifest} for the life of the client session.
 *
 * <p>Fetched once, off the client thread, and never re-fetched: a manifest that
 * changed underneath a running session would mean the varps we are reading stop
 * matching the varps we started with, and callers have no way to reconcile a
 * half-old snapshot. New settings therefore reach a client on its next start,
 * which is the same propagation model as the rest of the plugin's config.
 *
 * <p>Every consumer must tolerate {@link #getManifest()} returning null — that
 * is the state before the first fetch completes, and after a failed one.
 */
@Slf4j
@Singleton
public class ManifestService {

	private final DropTrackerApi api;
	private final ScheduledExecutorService executor;

	/** Guards against a second fetch while the first is still in flight. */
	private final AtomicBoolean fetching = new AtomicBoolean(false);

	private volatile Manifest manifest;

	@Inject
	public ManifestService(DropTrackerApi api, ScheduledExecutorService executor) {
		this.api = api;
		this.executor = executor;
	}

	/**
	 * The manifest, or null if it has not arrived (or could not be fetched).
	 *
	 * <p>Null is a normal state, not an error: callers fall back to their
	 * built-in behaviour, which is what the plugin did before the manifest
	 * existed.
	 */
	@Nullable
	public Manifest getManifest() {
		return manifest;
	}

	/** Kicks off the one fetch for this session. Safe to call more than once. */
	public void startUp() {
		refresh();
	}

	public void shutDown() {
		manifest = null;
		fetching.set(false);
	}

	/**
	 * Fetches the manifest in the background.
	 *
	 * <p>Failure is deliberately quiet: the manifest is an enhancement, and a
	 * user whose network blocked one request should not see an error for a
	 * feature they did not ask about.
	 */
	public void refresh() {
		if (manifest != null || !fetching.compareAndSet(false, true)) {
			return;
		}
		executor.execute(() -> {
			try {
				Manifest fetched = api.getManifest();
				if (fetched != null) {
					manifest = fetched;
					log.debug("Loaded plugin manifest version {}", fetched.getVersion());
				}
			} catch (Exception e) {
				log.debug("Could not load plugin manifest; using built-in defaults", e);
			} finally {
				fetching.set(false);
			}
		});
	}
}
