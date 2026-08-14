package io.droptracker.service;

import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

import io.droptracker.DropTrackerConfig;
import io.droptracker.api.DropTrackerApi;
import io.droptracker.modelexport.GlbExporter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.client.callback.ClientThread;

/**
 * Exports the local player's 3D model so their character — wearing whatever
 * they are wearing — can be rendered on the website and attached to
 * notifications.
 *
 * <p>Keyed by an <b>equipment fingerprint</b> rather than exported per event. A
 * GLB is a few hundred kilobytes; sending one with every personal best would be
 * absurd, and would mostly re-send an identical model. One upload per distinct
 * outfit is enough, because the render only changes when the outfit does.
 *
 * <p>Exports wait for the player to be <b>idle</b>. {@code Player#getModel()}
 * returns the current animation frame, so a model captured mid-attack is
 * captured mid-swing. Nothing here is time-critical, so waiting for a neutral
 * stance costs nothing and avoids a gallery of contorted characters.
 */
@Slf4j
@Singleton
public class PlayerModelService {

	/**
	 * Ticks the player must be idle before exporting. A couple of ticks avoids
	 * catching the tail of an animation that has technically ended.
	 */
	private static final int IDLE_TICKS_REQUIRED = 3;

	private final Client client;
	private final ClientThread clientThread;
	private final DropTrackerConfig config;
	private final DropTrackerApi api;
	private final ScheduledExecutorService executor;

	/** Fingerprint of the outfit we last uploaded, or null if none this session. */
	private volatile String uploadedFingerprint;

	/** Guards against two exports running at once — each one allocates a mesh. */
	private final AtomicBoolean exporting = new AtomicBoolean(false);

	private int idleTicks;

	/**
	 * When the next upload attempt is allowed, as a monotonic timestamp.
	 *
	 * <p>Without this a failing upload retries every tick: the fingerprint never
	 * gets recorded, the player is still idle, so the next tick exports and
	 * uploads again. A single 401 produced 258 requests in three minutes during
	 * testing, each one re-exporting the whole model.
	 */
	private long nextAttemptAtMs;

	/** How long to wait after a failed upload before trying that outfit again. */
	private static final long FAILURE_BACKOFF_MS = 5 * 60 * 1000L;

	@Inject
	public PlayerModelService(Client client,
	                          ClientThread clientThread,
	                          DropTrackerConfig config,
	                          DropTrackerApi api,
	                          ScheduledExecutorService executor) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.api = api;
		this.executor = executor;
	}

	public boolean isEnabled() {
		return config.useApi() && config.uploadCharacterModel();
	}

	public void reset() {
		uploadedFingerprint = null;
		idleTicks = 0;
		nextAttemptAtMs = 0;
		exporting.set(false);
	}

	/**
	 * Called each game tick. Exports when the outfit has changed and the player
	 * has been idle long enough.
	 *
	 * <p>Must be called on the client thread.
	 */
	public void onTick() {
		if (!isEnabled() || client.getGameState() != GameState.LOGGED_IN) {
			return;
		}

		Player local = client.getLocalPlayer();
		if (local == null) {
			return;
		}

		if (isAnimating(local)) {
			idleTicks = 0;
			return;
		}
		idleTicks++;
		if (idleTicks < IDLE_TICKS_REQUIRED) {
			return;
		}

		String fingerprint = fingerprintOf(local);
		if (fingerprint == null || fingerprint.equals(uploadedFingerprint)) {
			return;
		}
		if (System.currentTimeMillis() < nextAttemptAtMs) {
			return;
		}
		if (!exporting.compareAndSet(false, true)) {
			return;
		}

		byte[] model = null;
		byte[] petModel = null;
		try {
			model = exportModel(local);
			petModel = exportPet();
		} catch (Exception e) {
			log.debug("Could not export the player model: {}", e.toString());
		}

		if (model == null) {
			exporting.set(false);
			return;
		}

		final byte[] modelBytes = model;
		final byte[] petBytes = petModel;
		// Upload off the client thread: it is a network round-trip with a
		// payload measured in hundreds of kilobytes.
		executor.execute(() -> {
			try {
				if (api.uploadPlayerModel(fingerprint, modelBytes, petBytes)) {
					uploadedFingerprint = fingerprint;
					nextAttemptAtMs = 0;
					log.debug("Uploaded character model for outfit {}", fingerprint);
				} else {
					// Back off rather than re-exporting on the very next tick.
					nextAttemptAtMs = System.currentTimeMillis() + FAILURE_BACKOFF_MS;
					log.debug("Model upload failed; not retrying for {} minutes",
							FAILURE_BACKOFF_MS / 60000);
				}
			} finally {
				exporting.set(false);
			}
		});
	}

	/** True while the player is doing anything other than standing still. */
	private boolean isAnimating(Player local) {
		return local.getAnimation() != -1
				|| local.getPoseAnimation() != local.getIdlePoseAnimation();
	}

	@Nullable
	private byte[] exportModel(Player local) throws Exception {
		Model model = local.getModel();
		return model == null ? null : GlbExporter.toBytes(client, model, "player");
	}

	/**
	 * The player's pet, if one is following them. Optional in every sense: a
	 * failure here must not cost us the player's own model.
	 */
	@Nullable
	private byte[] exportPet() {
		try {
			NPC follower = client.getFollower();
			Model model = follower == null ? null : follower.getModel();
			return model == null ? null : GlbExporter.toBytes(client, model, "pet");
		} catch (Exception e) {
			log.debug("Could not export the pet model: {}", e.toString());
			return null;
		}
	}

	/**
	 * A stable identifier for "how this character currently looks".
	 *
	 * <p>Covers worn equipment, body kits and colours, because all three change
	 * the rendered model. Two players in identical gear produce the same
	 * fingerprint, which is fine — it keys a cache, not an identity.
	 */
	@Nullable
	String fingerprintOf(Actor actor) {
		if (!(actor instanceof Player)) {
			return null;
		}
		PlayerComposition composition = ((Player) actor).getPlayerComposition();
		if (composition == null) {
			return null;
		}

		int[] equipment = composition.getEquipmentIds();
		int[] colors = composition.getColors();
		if (equipment == null) {
			return null;
		}

		// A plain hash rather than a cryptographic digest: this is a cache key,
		// and collisions cost at most a stale render.
		int hash = 17;
		hash = hash * 31 + Arrays.hashCode(equipment);
		hash = hash * 31 + (colors == null ? 0 : Arrays.hashCode(colors));
		hash = hash * 31 + composition.getGender();
		return Integer.toHexString(hash);
	}
}
