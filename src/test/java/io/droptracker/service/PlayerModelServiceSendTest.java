package io.droptracker.service;

import io.droptracker.DropTrackerConfig;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.client.callback.ClientThread;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The manual "Send Player Model" path: its gating, and the outfit fingerprint
 * it shares with the automatic uploader.
 *
 * <p>The capture itself needs a live {@link net.runelite.api.Model} and is not
 * covered here; everything that decides whether a send is allowed to start is.
 */
public class PlayerModelServiceSendTest {

    /** A JDK-proxy stub answering only the listed methods; anything else returns a zero value. */
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Map<String, Object> answers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (answers.containsKey(method.getName())) {
                        return answers.get(method.getName());
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                });
    }

    private static DropTrackerConfig config(boolean useApi) {
        Map<String, Object> answers = new HashMap<>();
        answers.put("useApi", useApi);
        return stub(DropTrackerConfig.class, answers);
    }

    /** Runs the client-thread hop synchronously, like the real thing eventually does. */
    private static ClientThread immediateClientThread() {
        return new ClientThread() {
            @Override
            public void invoke(Runnable r) {
                r.run();
            }
        };
    }

    private static String await(PlayerModelService service) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean okOut = new AtomicBoolean();
        AtomicReference<String> messageOut = new AtomicReference<>();
        service.sendCurrentModel((ok, message) -> {
            okOut.set(ok);
            messageOut.set(message);
            latch.countDown();
        });
        assertTrue("callback never ran", latch.await(5, TimeUnit.SECONDS));
        assertFalse(okOut.get());
        return messageOut.get();
    }

    @Test
    public void withTheApiDisabledTheSendFailsBeforeTouchingTheClient() throws Exception {
        // Nulls everywhere the gate should never reach.
        PlayerModelService service = new PlayerModelService(null, null, config(false), null, null);

        String message = await(service);
        assertTrue(message, message.contains("API"));
    }

    @Test
    public void loggedOutFailsCleanlyAndReleasesTheGuardForTheNextTry() throws Exception {
        Map<String, Object> clientAnswers = new HashMap<>();
        clientAnswers.put("getGameState", GameState.LOGIN_SCREEN);
        Client client = stub(Client.class, clientAnswers);

        PlayerModelService service = new PlayerModelService(
                client, immediateClientThread(), config(true), null, null);

        String first = await(service);
        assertTrue(first, first.contains("Log in"));
        // A failed attempt must not leave the in-flight guard held.
        String second = await(service);
        assertEquals(first, second);
    }

    @Test
    public void whileACaptureIsInFlightASecondSendIsRefused() throws Exception {
        Map<String, Object> clientAnswers = new HashMap<>();
        clientAnswers.put("getGameState", GameState.LOGGED_IN);
        Client client = stub(Client.class, clientAnswers);

        // Holds the hop instead of running it, freezing the first send mid-capture.
        ClientThread parked = new ClientThread() {
            @Override
            public void invoke(Runnable r) {
                // never runs
            }
        };
        PlayerModelService service = new PlayerModelService(
                client, parked, config(true), null, null);

        service.sendCurrentModel((ok, message) -> {
        });
        String message = await(service);
        assertTrue(message, message.contains("Already sending"));
    }

    // ── the outfit fingerprint the pin is keyed by ──────────────────────────

    private static Player playerWith(int[] equipment, int[] colors, int gender) {
        Map<String, Object> compositionAnswers = new HashMap<>();
        compositionAnswers.put("getEquipmentIds", equipment);
        compositionAnswers.put("getColors", colors);
        compositionAnswers.put("getGender", gender);
        PlayerComposition composition = stub(PlayerComposition.class, compositionAnswers);

        Map<String, Object> playerAnswers = new HashMap<>();
        playerAnswers.put("getPlayerComposition", composition);
        return stub(Player.class, playerAnswers);
    }

    @Test
    public void fingerprintIsStableForTheSameOutfitAndChangesWithIt() {
        PlayerModelService service = new PlayerModelService(null, null, config(true), null, null);

        int[] outfit = {512, 1024, 2048};
        int[] colors = {1, 2, 3, 4, 5};
        String fingerprint = service.fingerprintOf(playerWith(outfit, colors, 0));
        assertNotNull(fingerprint);
        assertEquals(fingerprint, service.fingerprintOf(playerWith(outfit.clone(), colors.clone(), 0)));

        assertNotEquals(fingerprint, service.fingerprintOf(playerWith(new int[]{512, 1024, 4096}, colors, 0)));
        assertNotEquals(fingerprint, service.fingerprintOf(playerWith(outfit, colors, 1)));
    }

    // ── the fingerprint a personal best attaches ────────────────────────────

    @Test
    public void currentFingerprintIsTheLocalPlayersOutfitUnderTheUploadsOwnKey() {
        // A PB submission and the automatic upload must agree on the key, or
        // the leaderboard can never find the model for the time.
        Player local = playerWith(new int[]{512, 1024, 2048}, new int[]{1, 2, 3, 4, 5}, 0);
        Map<String, Object> clientAnswers = new HashMap<>();
        clientAnswers.put("getLocalPlayer", local);
        Client client = stub(Client.class, clientAnswers);
        PlayerModelService service = new PlayerModelService(client, null, config(true), null, null);

        assertEquals(service.fingerprintOf(local), service.currentFingerprint());
    }

    @Test
    public void currentFingerprintIsNullWithoutALocalPlayer() {
        Client client = stub(Client.class, new HashMap<>());
        PlayerModelService service = new PlayerModelService(client, null, config(true), null, null);

        assertNull(service.currentFingerprint());
    }
}
