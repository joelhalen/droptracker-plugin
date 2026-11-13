package io.droptracker.events;

import com.google.gson.*;
import io.droptracker.DropTrackerConfig;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import io.droptracker.util.DebugLogger;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.config.*;

import javax.annotation.Nullable;
import javax.inject.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

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
 * sent for the account.
 *
 * <p>What is sent is the explicit {@link #SETTINGS} list: a setting added to
 * {@link DropTrackerConfig} is not sent until it is listed there, so internal
 * state and anything private (account names, endpoints, any text setting)
 * stays out by default. The list is read through the config proxy rather than
 * discovered by reflection, which the Plugin Hub does not allow.
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

    /** One setting in the snapshot: its section, key, and how to read it. */
    /** One snapshot row: the section label, the keyName and the getter. */
    static final class Setting {
        final String section;
        final String key;
        final Method read;

        Setting(String section, String key, Method read) {
            this.section = section;
            this.key = key;
            this.read = read;
        }
    }

    /**
     * Every non-String {@code @ConfigItem} on the interface except
     * {@link #EXCLUDED}, read off the annotations so a new setting is in the
     * snapshot the day it is added. Hidden items (or ones outside a section)
     * file under "Hidden"; the rest under their {@code @ConfigSection} name.
     */
    static final List<Setting> SETTINGS = discover();

    private static List<Setting> discover() {
        Map<String, String> sectionNames = new HashMap<>();
        for (Field f : DropTrackerConfig.class.getDeclaredFields()) {
            ConfigSection section = f.getAnnotation(ConfigSection.class);
            try {
                if (section != null) {
                    // @ConfigItem.section carries the constant's VALUE, not its name.
                    sectionNames.put(String.valueOf(f.get(null)), section.name());
                }
            } catch (IllegalAccessException ignored) {
                // a non-public constant: fall through to the raw section string
            }
        }
        List<Setting> out = new ArrayList<>();
        for (Method m : DropTrackerConfig.class.getDeclaredMethods()) {
            ConfigItem item = m.getAnnotation(ConfigItem.class);
            if (item == null || m.getParameterCount() != 0 || m.getReturnType() == String.class
                || EXCLUDED.contains(item.keyName())) {
                continue;
            }
            String section = item.hidden() || item.section().isEmpty() ? "Hidden"
                : sectionNames.getOrDefault(item.section(), item.section());
            out.add(new Setting(section, item.keyName(), m));
        }
        out.sort(Comparator.comparing(s -> s.key));
        return out;
    }

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
        for (Setting setting : SETTINGS) {
            Object value;
            try {
                value = setting.read.invoke(config);
            } catch (ReflectiveOperationException | RuntimeException e) {
                continue;
            }
            JsonObject target = sections.computeIfAbsent(setting.section, s -> new JsonObject());
            if (value instanceof Boolean) {
                target.addProperty(setting.key, (Boolean) value);
            } else if (value instanceof Number) {
                target.addProperty(setting.key, (Number) value);
            } else if (value instanceof Enum) {
                target.addProperty(setting.key, ((Enum<?>) value).name());
            } else if (value != null) {
                target.addProperty(setting.key, value.toString());
            }
            if (customized.test(setting.key)) {
                changed.add(setting.key);
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
