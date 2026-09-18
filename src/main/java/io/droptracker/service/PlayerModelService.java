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
import io.droptracker.models.api.ModelStatus;
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
 * <p><b>Ask before sending.</b> Remembering only the last outfit was not enough:
 * a player alternating between two of them re-sent one on every switch, forever,
 * and the server measured four in five uploads as a model it already had (2026-09-14:
 * ~264k uploads a day, ~20% of them new). So an outfit the server may already
 * hold costs a ~200-byte question first, and the export — which runs on the
 * client thread — happens only when the answer is no. The question doubles as
 * how the server learns what the player is wearing, so switching back into known
 * gear still moves their profile.
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

	/**
	 * The outfit already settled with the server this session — either uploaded,
	 * or confirmed as one it holds. Null until the first is settled.
	 *
	 * <p>One entry rather than a set on purpose: switching back to an earlier
	 * outfit should ask again, because that question is what tells the server
	 * which outfit is being worn now.
	 */
	private volatile String settledFingerprint;

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
	// Volatile: written on the executor, read on the client thread.
	private volatile long nextAttemptAtMs;

	/** How long to wait after a failed upload before trying that outfit again. */
	private static final long FAILURE_BACKOFF_MS = 5 * 60 * 1000L;

	/**
	 * The server does not know accounts it has never had a submission from, and
	 * would refuse a model from one. Wait rather than ask on every outfit change.
	 */
	private static final long UNKNOWN_ACCOUNT_BACKOFF_MS = 30 * 60 * 1000L;

	/**
	 * Floor on the gap between two uploads. Asking first already removes the
	 * repeat sends, but a player cycling through more outfits than the server
	 * keeps would miss every time, so this bounds what that can cost: a skipped
	 * upload is not recorded as settled, and the outfit goes up the next time
	 * they stand still in it.
	 */
	private static final long MIN_UPLOAD_INTERVAL_MS = 30 * 1000L;

	/** Earliest an upload may start, as a monotonic timestamp. */
	private volatile long nextUploadAtMs;

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
		settledFingerprint = null;
		idleTicks = 0;
		nextAttemptAtMs = 0;
		nextUploadAtMs = 0;
		exporting.set(false);
	}

	/**
	 * Called each game tick. Settles the current outfit with the server when it
	 * has changed and the player has been idle long enough.
	 *
	 * <p>Nothing expensive happens here: whether to export is decided off this
	 * thread, once the server has said whether it needs the model.
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
		if (fingerprint == null || fingerprint.equals(settledFingerprint)) {
			return;
		}
		if (System.currentTimeMillis() < nextAttemptAtMs) {
			return;
		}
		if (!exporting.compareAndSet(false, true)) {
			return;
		}

		// Read here because it is a client read, and it decides whether a model
		// the server already holds is still missing the pet beside it.
		final boolean petOut = client.getFollower() != null;
		try {
			executor.execute(() -> settle(fingerprint, petOut));
		} catch (RuntimeException e) {
			// Never let a refused submission hold the guard — or reach the tick.
			exporting.set(false);
			log.debug("Could not schedule the outfit check: {}", e.toString());
		}
	}

	/** What to do about an outfit once the server has answered. */
	enum Action {
		/** Export and send it. */
		UPLOAD,
		/** The server has it; nothing more to do this session. */
		SETTLED,
		/** The server does not know this account yet. */
		UNKNOWN_ACCOUNT,
		/** Held back by the upload floor; try again later. */
		WAIT
	}

	/**
	 * Pure decision, so the interesting cases are testable without a client.
	 *
	 * @param status what the server answered, or null when it could not be asked
	 *               — which must lead to an upload, the behaviour that predates
	 *               the check endpoint.
	 */
	static Action decide(@Nullable ModelStatus status, boolean petOut, long now, long nextUploadAtMs) {
		if (status != null && !status.isAccepted()) {
			return Action.UNKNOWN_ACCOUNT;
		}
		// A stored outfit can predate the pet now following the player, and the
		// fingerprint cannot say so — it covers the character, not the follower.
		if (status != null && status.hasModel() && (!petOut || status.hasPet())) {
			return Action.SETTLED;
		}
		if (now < nextUploadAtMs) {
			return Action.WAIT;
		}
		return Action.UPLOAD;
	}

	/** Asks the server about an outfit, then exports only if it needs one. */
	private void settle(String fingerprint, boolean petOut) {
		boolean release = true;
		try {
			Action action = decide(api.checkPlayerModel(fingerprint), petOut,
					System.currentTimeMillis(), nextUploadAtMs);
			switch (action) {
				case SETTLED:
					// The question itself told the server what is being worn,
					// which is all the re-upload it replaces ever achieved.
					settledFingerprint = fingerprint;
					nextAttemptAtMs = 0;
					log.debug("Server already holds outfit {}", fingerprint);
					break;
				case UNKNOWN_ACCOUNT:
					nextAttemptAtMs = System.currentTimeMillis() + UNKNOWN_ACCOUNT_BACKOFF_MS;
					break;
				case WAIT:
					// Hold the whole cycle, not just the upload: without this
					// the question would be asked again on the very next tick.
					nextAttemptAtMs = nextUploadAtMs;
					break;
				case UPLOAD:
				default:
					captureAndUpload(fingerprint);
					// Only now has the guard changed hands: if the hop threw,
					// the finally below has to release it.
					release = false;
					break;
			}
		} catch (Exception e) {
			log.debug("Could not settle outfit {}: {}", fingerprint, e.toString());
		} finally {
			if (release) {
				exporting.set(false);
			}
		}
	}

	/** Exports on the client thread, then uploads off it. */
	private void captureAndUpload(String fingerprint) {
		clientThread.invoke(() -> {
			byte[] model = null;
			byte[] petModel = null;
			try {
				Player local = client.getGameState() == GameState.LOGGED_IN
						? client.getLocalPlayer() : null;
				// The outfit can change between the question and the answer, and
				// a model exported for a different one would be filed under the
				// wrong key. The next tick picks the new one up.
				if (local != null && fingerprint.equals(fingerprintOf(local))) {
					model = exportModel(local);
					petModel = exportPet();
				}
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
			// payload measured in tens of kilobytes. The guard is released by
			// whoever ends up owning the work, so a refused submission (the
			// executor is shutting down) has to release it here or the uploader
			// is stuck for the rest of the session.
			try {
				executor.execute(() -> {
					try {
						if (api.uploadPlayerModel(fingerprint, modelBytes, petBytes)) {
							settledFingerprint = fingerprint;
							nextAttemptAtMs = 0;
							nextUploadAtMs = System.currentTimeMillis() + MIN_UPLOAD_INTERVAL_MS;
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
			} catch (RuntimeException e) {
				exporting.set(false);
				throw e;
			}
		});
	}

	/**
	 * Manually captures the player's current model and pins it as their
	 * profile model on the website. Triggered by the panel's "Send Player
	 * Model" button, so unlike {@link #onTick()} it does not wait for the
	 * player to be idle — the player chose this exact moment — and it does not
	 * skip already-uploaded outfits, because the point is to (re)pin this one.
	 *
	 * <p>Deliberately gated only on {@link DropTrackerConfig#useApi()}, not on
	 * the automatic-upload toggle: pressing the button is explicit consent.
	 *
	 * <p>May be called from any thread; the capture hops to the client thread
	 * and the upload to the executor. The callback receives a short
	 * user-facing result message and always runs on the Swing EDT.
	 */
	public void sendCurrentModel(java.util.function.BiConsumer<Boolean, String> callback) {
		if (!config.useApi()) {
			finish(callback, false, "Enable the DropTracker API in the plugin settings first");
			return;
		}
		if (!exporting.compareAndSet(false, true)) {
			finish(callback, false, "Already sending a model — try again in a moment");
			return;
		}
		clientThread.invoke(() -> {
			Player local = client.getGameState() == GameState.LOGGED_IN
					? client.getLocalPlayer() : null;
			if (local == null) {
				exporting.set(false);
				finish(callback, false, "Log in to send your player model");
				return;
			}

			String fingerprint = fingerprintOf(local);
			byte[] model = null;
			byte[] petModel = null;
			try {
				model = exportModel(local);
				petModel = exportPet();
			} catch (Exception e) {
				log.debug("Could not export the player model: {}", e.toString());
			}
			if (fingerprint == null || model == null) {
				exporting.set(false);
				finish(callback, false, "Could not capture your model — try again");
				return;
			}

			final String fp = fingerprint;
			final byte[] modelBytes = model;
			final byte[] petBytes = petModel;
			executor.execute(() -> {
				boolean ok = false;
				try {
					ok = api.uploadPlayerModel(fp, modelBytes, petBytes, true);
				} catch (Exception e) {
					log.debug("Manual model upload failed: {}", e.toString());
				} finally {
					exporting.set(false);
				}
				if (ok) {
					// The automatic path now knows this outfit is settled.
					settledFingerprint = fp;
					nextAttemptAtMs = 0;
				}
				finish(callback, ok, ok
						? "Model sent! Your profile now shows this outfit"
						: "Upload failed — check your connection and try again");
			});
		});
	}

	private static void finish(java.util.function.BiConsumer<Boolean, String> callback,
	                           boolean ok, String message) {
		javax.swing.SwingUtilities.invokeLater(() -> callback.accept(ok, message));
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
	 * Fingerprint of the local player's current outfit, or null without one.
	 *
	 * <p>What a personal best attaches so the server can pair the time with the
	 * model of the outfit it was set in: the automatic upload files that model
	 * under exactly this key, so the two meet without any further exchange.
	 * Cheap (no export, no upload) and deliberately not gated on
	 * {@link #isEnabled()} - the caller owns the consent decision.
	 *
	 * <p>Must be called on the client thread.
	 */
	@Nullable
	public String currentFingerprint() {
		return client == null ? null : fingerprintOf(client.getLocalPlayer());
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
