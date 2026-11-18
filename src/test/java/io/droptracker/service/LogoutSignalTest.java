package io.droptracker.service;

import io.droptracker.DropTrackerConfig;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.events.ClientShutdown;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** When a logout is reported to the server, and when it must not be. */
public class LogoutSignalTest {

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

    /** A signal whose sends are recorded instead of going to the network. */
    private static final class Recording extends LogoutSignal {
        final List<String> sent = new ArrayList<>();

        Recording(Client client, DropTrackerConfig config) {
            super(client, config, null, null, null);
        }

        @Override
        Future<?> dispatch(Runnable task) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        void deliver(String name, long hash) {
            sent.add(name + ":" + hash);
        }
    }

    private final Map<String, Object> clientAnswers = new HashMap<>();
    private final Map<String, Object> configAnswers = new HashMap<>();

    private Recording signal(String name, long hash, EnumSet<WorldType> world, boolean useApi) {
        if (name != null) {
            Map<String, Object> player = new HashMap<>();
            player.put("getName", name);
            clientAnswers.put("getLocalPlayer", stub(Player.class, player));
        }
        clientAnswers.put("getAccountHash", hash);
        clientAnswers.put("getWorldType", world);
        configAnswers.put("useApi", useApi);
        return new Recording(stub(Client.class, clientAnswers), stub(DropTrackerConfig.class, configAnswers));
    }

    private static GameStateChanged state(GameState gameState) {
        GameStateChanged event = new GameStateChanged();
        event.setGameState(gameState);
        return event;
    }

    private static final EnumSet<WorldType> MAIN = EnumSet.of(WorldType.MEMBERS);

    @Test
    public void logoutIsReportedOnceWithWhoWasLoggedIn() {
        Recording signal = signal("Some Player", 42L, MAIN, true);
        signal.onGameTick(new GameTick());
        signal.onGameStateChanged(state(GameState.LOGIN_SCREEN));
        signal.onGameStateChanged(state(GameState.LOGIN_SCREEN));
        assertEquals(1, signal.sent.size());
        assertEquals("Some Player:42", signal.sent.get(0));
    }

    @Test
    public void theLoginScreenAtStartupReportsNothing() {
        Recording signal = signal(null, -1L, MAIN, true);
        signal.onGameStateChanged(state(GameState.LOGIN_SCREEN));
        assertTrue(signal.sent.isEmpty());
    }

    @Test
    public void aWorldHopIsNotALogout() {
        Recording signal = signal("Some Player", 42L, MAIN, true);
        signal.onGameTick(new GameTick());
        signal.onGameStateChanged(state(GameState.HOPPING));
        signal.onGameStateChanged(state(GameState.LOGGED_IN));
        assertTrue(signal.sent.isEmpty());
        // And the player is picked up again in the new world.
        signal.onGameTick(new GameTick());
        signal.onGameStateChanged(state(GameState.LOGIN_SCREEN));
        assertEquals(1, signal.sent.size());
    }

    @Test
    public void nothingIsSentWithTheApiOff() {
        Recording signal = signal("Some Player", 42L, MAIN, false);
        signal.onGameTick(new GameTick());
        signal.onGameStateChanged(state(GameState.LOGIN_SCREEN));
        assertTrue(signal.sent.isEmpty());
    }

    @Test
    public void seasonalWorldsAreNotReported() {
        Recording signal = signal("Some Player", 42L, EnumSet.of(WorldType.MEMBERS, WorldType.SEASONAL), true);
        signal.onGameTick(new GameTick());
        signal.onGameStateChanged(state(GameState.LOGIN_SCREEN));
        assertTrue(signal.sent.isEmpty());
    }

    @Test
    public void anAccountWithoutAHashIsNotReported() {
        Recording signal = signal("Some Player", -1L, MAIN, true);
        signal.onGameTick(new GameTick());
        signal.onGameStateChanged(state(GameState.LOGIN_SCREEN));
        assertTrue(signal.sent.isEmpty());
    }

    @Test
    public void closingTheClientWhileLoggedInReportsAndWaits() {
        Recording signal = signal("Some Player", 42L, MAIN, true);
        signal.onGameTick(new GameTick());
        ClientShutdown shutdown = new ClientShutdown();
        signal.onClientShutdown(shutdown);
        assertEquals(1, signal.sent.size());
        // Already reported: logging out as the client closes does not repeat it.
        signal.onGameStateChanged(state(GameState.LOGIN_SCREEN));
        assertEquals(1, signal.sent.size());
    }

    @Test
    public void closingTheClientAtTheLoginScreenReportsNothing() {
        Recording signal = signal(null, -1L, MAIN, true);
        signal.onClientShutdown(new ClientShutdown());
        assertTrue(signal.sent.isEmpty());
    }
}
