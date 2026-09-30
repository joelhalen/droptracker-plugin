package io.droptracker.events;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.droptracker.DropTrackerConfig;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import io.droptracker.util.DebugLogger;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.RuneLiteConfig;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Sends the player's DropTracker settings to the server, so group leaders and
 * staff can see how a plugin is configured when they are helping debug it
 * ("why didn't my pet post?" is very often "pet notifications are off").
 *
 * <p>The snapshot is JSON in the embed's description: some 45 settings would
 * blow Discord's 25-field embed limit, and the description holds 4096
 * characters against a snapshot of about 2 KB. It rides the normal
 * submission transport (API or webhook, whichever the player uses).
 *
 * <p>Sent a little after each login, then again 60s after the settings stop
 * changing, and only when the content differs from what this client last
 * sent for the account. Internal state keys are never sent, and neither is
 * any text setting unless it is on {@link #TEXT_ALLOWED}: a future setting
 * holding something private stays private by default.
 */
@Slf4j
@Singleton
public class ConfigSnapshotHandler extends BaseEventHandler {
    static final int FORMAT_VERSION = 1;
    /** Discord's cap on an embed description. */
    static final int MAX_DESCRIPTION = 4096;
    private static final long LOGIN_DELAY_SECONDS = 20;
    private static final long CHANGE_DEBOUNCE_SECONDS = 60;

    /** Bookkeeping the plugin keeps in its config group; not settings. */
    static final Set<String> EXCLUDED = Set.of(
        "pinnedEventId", "lastVersionNotified", "lastAccountName",
        "customApiEndpoint", "lastAccountHash");
    /** Text settings that are safe to send. None today. */
    static final Set<String> TEXT_ALLOWED = Set.of();

    @Inject
    private Gson gson;

    /** Last snapshot hash sent this session, per account hash. */
    private final Map<Long, String> lastSent = new HashMap<>();
    @Nullable
    private ScheduledFuture<?> pending;

    /** Game state hook: a snapshot shortly after each login. */
    public void onGameStateChanged(GameState state) {
        if (state == GameState.LOGGED_IN) {
            schedule(LOGIN_DELAY_SECONDS);
        }
    }

    /** Config hook: a snapshot once the player stops changing settings. */
    public void onConfigChanged(String group, String key) {
        if (DropTrackerConfig.GROUP.equals(group) && !EXCLUDED.contains(key)) {
            schedule(CHANGE_DEBOUNCE_SECONDS);
        } else if (RuneLiteConfig.GROUP_NAME.equals(group) && "loottrackerplugin".equals(key)) {
            schedule(CHANGE_DEBOUNCE_SECONDS);
        }
    }

    public synchronized void reset() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        lastSent.clear();
    }

    private synchronized void schedule(long delaySeconds) {
        if (executor == null) {
            return;
        }
        if (pending != null) {
            pending.cancel(false);
        }
        pending = executor.schedule(() -> clientThread.invokeLater(this::sendIfChanged),
            delaySeconds, TimeUnit.SECONDS);
    }

    /** Client thread: reads the chatbox varbit and the local player. */
    private void sendIfChanged() {
        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) {
            return;
        }
        long account = client.getAccountHash();
        String playerName = getPlayerName();
        if (account == -1 || playerName == null || playerName.isEmpty()) {
            return;
        }
        JsonObject env = new JsonObject();
        env.addProperty("runelite_version", RuneLiteProperties.getVersion());
        env.addProperty("resizable", client.isResized());
        env.addProperty("chatbox_transparent",
            client.isResized() && client.getVarbitValue(VarbitID.CHATBOX_TRANSPARENCY) == 1);
        env.addProperty("loot_tracker_enabled",
            !"false".equals(configManager.getConfiguration(RuneLiteConfig.GROUP_NAME, "loottrackerplugin")));
        env.addProperty("custom_api_endpoint", !config.customApiEndpoint().isEmpty());

        JsonObject snapshot = build(config,
            key -> configManager.getConfiguration(DropTrackerConfig.GROUP, key) != null, env);
        String hash = sha256(gson.toJson(snapshot));
        synchronized (this) {
            if (hash.equals(lastSent.get(account))) {
                return;
            }
            lastSent.put(account, hash);
        }
        snapshot.addProperty("hash", hash.substring(0, 16));
        String json = gson.toJson(snapshot);
        if (json.length() > MAX_DESCRIPTION) {
            // Can't happen with today's ~2 KB; the test pins the headroom.
            log.warn("Config snapshot too large to send ({} chars)", json.length());
            return;
        }

        CustomWebhookBody webhook = createWebhookBody(playerName + " plugin configuration");
        CustomWebhookBody.Embed embed = createEmbed("Plugin configuration", "config_snapshot");
        embed.setDescription(json);
        webhook.getEmbeds().add(embed);
        DebugLogger.log("[ConfigSnapshot] sending snapshot hash=" + hash.substring(0, 16)
            + " size=" + json.length());
        sendData(webhook, SubmissionType.CONFIG_SNAPSHOT);
    }

    /**
     * The snapshot body: settings grouped by config section (hidden ones under
     * "Hidden"), the keys the player has changed from their defaults, and
     * the client environment. Values come through the config proxy, so a
     * setting the player never touched reports its default.
     */
    static JsonObject build(DropTrackerConfig config, Predicate<String> customized, JsonObject env) {
        Map<String, JsonObject> sections = new LinkedHashMap<>();
        for (String section : new String[]{"Tracking", "Screenshots", "Events", "Advanced", "Hidden"}) {
            sections.put(section, new JsonObject());
        }
        JsonArray changed = new JsonArray();
        List<Method> methods = Arrays.stream(DropTrackerConfig.class.getMethods())
            .filter(m -> m.getAnnotation(ConfigItem.class) != null)
            .filter(m -> m.getParameterCount() == 0 && m.getReturnType() != void.class)
            .sorted(Comparator.comparingInt((Method m) -> m.getAnnotation(ConfigItem.class).position())
                .thenComparing(Method::getName))
            .collect(Collectors.toList());
        for (Method method : methods) {
            ConfigItem item = method.getAnnotation(ConfigItem.class);
            String key = item.keyName();
            if (EXCLUDED.contains(key)
                    || (method.getReturnType() == String.class && !TEXT_ALLOWED.contains(key))) {
                continue;
            }
            Object value;
            try {
                value = method.invoke(config);
            } catch (ReflectiveOperationException | RuntimeException e) {
                continue;
            }
            String section = item.hidden() || item.section().isEmpty() ? "Hidden" : item.section();
            JsonObject target = sections.computeIfAbsent(section, s -> new JsonObject());
            if (value instanceof Boolean) {
                target.addProperty(key, (Boolean) value);
            } else if (value instanceof Number) {
                target.addProperty(key, (Number) value);
            } else if (value instanceof Enum) {
                target.addProperty(key, ((Enum<?>) value).name());
            } else if (value != null) {
                target.addProperty(key, value.toString());
            }
            if (customized.test(key)) {
                changed.add(key);
            }
        }
        JsonObject settings = new JsonObject();
        sections.forEach((name, values) -> {
            if (values.size() > 0) {
                settings.add(name, values);
            }
        });
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("v", FORMAT_VERSION);
        snapshot.add("settings", settings);
        snapshot.add("customized", changed);
        snapshot.add("env", env);
        return snapshot;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
