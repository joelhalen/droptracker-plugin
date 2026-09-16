package io.droptracker.testing;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.widgets.Widget;

/**
 * JDK dynamic-proxy stand-ins for the RuneLite API interfaces.
 *
 * <p>The plugin has no mocking framework on the test classpath (JUnit 4 only),
 * so handler tests build their client, actors and world view out of proxies that
 * return type-appropriate defaults, with per-method overrides supplied through a
 * mutable map. Mutability matters here: killer attribution turns on an actor's
 * interaction changing between one tick and the next, which is exactly what a
 * fixed stub cannot express.
 */
public final class RuneLiteStubs {

    private RuneLiteStubs() {
    }

    /**
     * An override whose result depends on the call's arguments: a varp read
     * keyed by id, a cache table lookup keyed by row. Put one in a stub's
     * {@link #state} map under the method name.
     */
    @FunctionalInterface
    public interface Answer {
        Object answer(Object[] args);
    }

    /**
     * @return the mutable override map backing a stub, keyed by method name
     */
    public static Map<String, Object> state(Object stub) {
        return ((Handler) Proxy.getInvocationHandler(stub)).values;
    }

    public static <T> T stub(Class<T> type, String label) {
        Object proxy = Proxy.newProxyInstance(
                RuneLiteStubs.class.getClassLoader(), new Class<?>[] { type }, new Handler(label));
        return type.cast(proxy);
    }

    // --- actors ---------------------------------------------------------

    public static NPCComposition composition(int id, String name, int combatLevel, boolean attackable,
            boolean follower) {
        NPCComposition composition = stub(NPCComposition.class, "composition:" + name);
        Map<String, Object> state = state(composition);
        state.put("getId", id);
        state.put("getName", name);
        state.put("getCombatLevel", combatLevel);
        state.put("getActions", attackable ? new String[] { "Attack" } : new String[] { "Talk-to" });
        state.put("isFollower", follower);
        state.put("isInteractible", true);
        state.put("isMinimapVisible", true);
        state.put("getSize", 1);
        return composition;
    }

    /** An attackable NPC of the given combat level. */
    public static NPC npc(int index, int id, String name, int combatLevel) {
        return npc(index, id, name, combatLevel, true, false);
    }

    public static NPC npc(int index, int id, String name, int combatLevel, boolean attackable, boolean follower) {
        NPC npc = stub(NPC.class, "npc:" + name);
        Map<String, Object> state = state(npc);
        state.put("getIndex", index);
        state.put("getId", id);
        state.put("getName", name);
        state.put("getCombatLevel", combatLevel);
        NPCComposition composition = composition(id, name, combatLevel, attackable, follower);
        state.put("getComposition", composition);
        state.put("getTransformedComposition", composition);
        return npc;
    }

    public static Player player(String name, int combatLevel) {
        Player player = stub(Player.class, "player:" + name);
        Map<String, Object> state = state(player);
        state.put("getName", name);
        state.put("getCombatLevel", combatLevel);
        return player;
    }

    /** A player the local account is friends with (a bystander, not a killer). */
    public static Player friendlyPlayer(String name, int combatLevel) {
        Player player = player(name, combatLevel);
        state(player).put("isFriend", true);
        return player;
    }

    // --- world ----------------------------------------------------------

    public static WorldView worldView(List<? extends Player> players, List<? extends NPC> npcs) {
        WorldView worldView = stub(WorldView.class, "worldView");
        Map<String, Object> state = state(worldView);
        state.put("players", objectSet(players));
        state.put("npcs", objectSet(npcs));
        state.put("isInstance", false);
        return worldView;
    }

    public static <T> IndexedObjectSet<T> objectSet(List<T> items) {
        List<T> copy = new ArrayList<>(items);
        return new IndexedObjectSet<T>() {
            @Override
            public T byIndex(int index) {
                return copy.get(index);
            }

            @Override
            public Iterator<T> iterator() {
                return copy.iterator();
            }
        };
    }

    /** A widget that is present and visible — e.g. the PvP safe-zone indicator. */
    public static Widget visibleWidget() {
        Widget widget = stub(Widget.class, "widget");
        state(widget).put("isHidden", false);
        return widget;
    }

    /**
     * A client whose non-primitive returns are safe to call: an empty world type
     * set and an empty world view, both overridable per test.
     */
    public static Client client(Player localPlayer) {
        Client client = stub(Client.class, "client");
        Map<String, Object> state = state(client);
        state.put("getLocalPlayer", localPlayer);
        state.put("getWorldType", EnumSet.noneOf(WorldType.class));
        state.put("getTickCount", 0);
        WorldView worldView = worldView(new ArrayList<>(), new ArrayList<>());
        state.put("getTopLevelWorldView", worldView);
        if (localPlayer != null) {
            state(localPlayer).put("getWorldView", worldView);
        }
        return client;
    }

    /** Replaces the world view seen by both the client and the local player. */
    public static void setScene(Client client, Player localPlayer, List<? extends Player> players,
            List<? extends NPC> npcs) {
        WorldView worldView = worldView(players, npcs);
        state(client).put("getTopLevelWorldView", worldView);
        if (localPlayer != null) {
            state(localPlayer).put("getWorldView", worldView);
        }
    }

    private static final class Handler implements InvocationHandler {

        private final Map<String, Object> values = new HashMap<>();
        private final String label;

        private Handler(String label) {
            this.label = label;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            switch (name) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return label;
                default:
                    break;
            }
            if (values.containsKey(name)) {
                Object value = values.get(name);
                if (value instanceof Answer) {
                    return ((Answer) value).answer(args == null ? new Object[0] : args);
                }
                return value;
            }
            return defaultValue(method.getReturnType());
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive() || type == void.class) {
                return null;
            }
            if (type == boolean.class) {
                return Boolean.FALSE;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == double.class) {
                return 0d;
            }
            if (type == float.class) {
                return 0f;
            }
            if (type == char.class) {
                return (char) 0;
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == short.class) {
                return (short) 0;
            }
            return 0;
        }
    }
}
