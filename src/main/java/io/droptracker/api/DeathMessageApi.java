package io.droptracker.api;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.inject.*;
import javax.swing.SwingUtilities;

import com.google.gson.*;

import io.droptracker.DropTrackerConfig;
import io.droptracker.models.api.DeathMessages;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

/**
 * Reads and saves the logged-in account's own messages of one type: death,
 * clog, drop or pb ({@code GET/POST /player/death_messages}, {@code type}).
 *
 * <p>The same messages the website and Discord's {@code /settings} edit, keyed
 * by account: the server resolves the account hash, which is also all the
 * plugin's other endpoints go on. Every call runs on the injected executor and
 * answers on the Swing thread, because its only caller is the editor dialog.
 */
@Slf4j
@Singleton
@AllArgsConstructor(onConstructor_ = @Inject)
public class DeathMessageApi {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final Gson gson;
    private final OkHttpClient httpClient;
    private final ScheduledExecutorService executor;

    /** What a call came back with: the messages, or a sentence for the player. */
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Result {
        @Nullable
        public final DeathMessages messages;
        @Nullable
        public final String error;

        public static Result ok(DeathMessages messages) {
            return new Result(messages, null);
        }

        public static Result failed(String error) {
            return new Result(null, error);
        }
    }

    /** Loads the account's messages; {@code callback} runs on the Swing thread. */
    public void fetch(@Nullable String playerName, long accountHash, String type, Consumer<Result> callback) {
        executor.execute(() -> {
            Result result = fetchNow(playerName, accountHash, type);
            SwingUtilities.invokeLater(() -> callback.accept(result));
        });
    }

    /** Replaces the account's messages; {@code callback} runs on the Swing thread. */
    public void save(@Nullable String playerName, long accountHash, String type, List<String> messages,
                     Consumer<Result> callback) {
        executor.execute(() -> {
            Result result = saveNow(playerName, accountHash, type, messages);
            SwingUtilities.invokeLater(() -> callback.accept(result));
        });
    }

    /**
     * Reads ({@code changes} null) or saves the privacy switches of the Discord
     * account that owns this one ({@code GET/POST /player/privacy}). Answers on
     * the Swing thread with the switches, or null and a sentence for the player.
     */
    public void privacy(@Nullable String playerName, long accountHash, @Nullable Map<String, Boolean> changes,
                        BiConsumer<Map<String, Boolean>, String> callback) {
        executor.execute(() -> {
            Map<String, Boolean> settings = null;
            String error = API_OFF;
            HttpUrl url = config.useApi() ? HttpUrl.parse(api.getApiUrl() + "/player/privacy") : null;
            if (url != null) {
                Request.Builder request = new Request.Builder().cacheControl(CacheControl.FORCE_NETWORK);
                String name = playerName == null ? "" : playerName;
                if (changes == null) {
                    request.url(url.newBuilder().addQueryParameter("player_name", name)
                        .addQueryParameter("acc_hash", String.valueOf(accountHash)).build());
                } else {
                    Map<String, Object> body = new HashMap<>();
                    body.put("player_name", name);
                    body.put("acc_hash", String.valueOf(accountHash));
                    body.put("settings", changes);
                    request.url(url).post(RequestBody.create(JSON, gson.toJson(body)));
                }
                error = UNREACHABLE;
                try (Response response = httpClient.newCall(request.build()).execute()) {
                    ResponseBody raw = response.body();
                    String text = raw == null ? "" : raw.string();
                    if (response.code() == 200) {
                        JsonObject parsed = gson.fromJson(text, JsonObject.class);
                        settings = new LinkedHashMap<>();
                        for (Map.Entry<String, JsonElement> e : parsed.getAsJsonObject("settings").entrySet()) {
                            settings.put(e.getKey(), e.getValue().getAsBoolean());
                        }
                        error = null;
                    } else {
                        error = errorText(gson, response.code(), text);
                    }
                } catch (IOException | RuntimeException e) {
                    log.debug("Privacy request failed: {}", e.toString());
                    settings = null;
                    error = UNREACHABLE;
                }
            }
            Map<String, Boolean> answer = settings;
            String problem = error;
            SwingUtilities.invokeLater(() -> callback.accept(answer, problem));
        });
    }

    private Result fetchNow(@Nullable String playerName, long accountHash, String type) {
        HttpUrl base = endpoint();
        if (base == null) {
            return Result.failed(API_OFF);
        }
        HttpUrl url = base.newBuilder()
            .addQueryParameter("player_name", playerName == null ? "" : playerName)
            .addQueryParameter("acc_hash", String.valueOf(accountHash))
            .addQueryParameter("type", type)
            .build();
        // FORCE_NETWORK: the injected client has a disk cache shared with the
        // whole of RuneLite, and a stale copy here would undo a save.
        Request request = new Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build();
        return execute(request);
    }

    private Result saveNow(@Nullable String playerName, long accountHash, String type, List<String> messages) {
        HttpUrl url = endpoint();
        if (url == null) {
            return Result.failed(API_OFF);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("player_name", playerName == null ? "" : playerName);
        body.put("acc_hash", String.valueOf(accountHash));
        body.put("messages", messages);
        body.put("type", type);
        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .build();
        return execute(request);
    }

    private Result execute(Request request) {
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            return interpret(gson, response.code(), body == null ? "" : body.string());
        } catch (IOException e) {
            log.debug("Death message request failed: {}", e.toString());
            return Result.failed(UNREACHABLE);
        }
    }

    @Nullable
    private HttpUrl endpoint() {
        if (!config.useApi()) {
            return null;
        }
        return HttpUrl.parse(api.getApiUrl() + "/player/death_messages");
    }

    static final String API_OFF = "Turn on \"Use API Connections\" in the plugin settings to change this here.";
    static final String UNREACHABLE = "Couldn't reach the DropTracker. Try again in a moment.";
    static final String NOT_REGISTERED = "This account isn't registered with the DropTracker yet. Submit something first, then try again.";
    static final String TOO_MANY = "That's a lot of saves - wait a minute and try again.";

    /** Turns a response into a {@link Result}. Package-private for the tests. */
    static Result interpret(Gson gson, int code, String body) {
        if (code == 200) {
            try {
                DeathMessages parsed = gson.fromJson(body, DeathMessages.class);
                return parsed != null ? Result.ok(parsed) : Result.failed(UNREACHABLE);
            } catch (JsonSyntaxException e) {
                return Result.failed(UNREACHABLE);
            }
        }
        return Result.failed(errorText(gson, code, body));
    }

    /** The sentence to show for a refused call. */
    static String errorText(Gson gson, int code, String body) {
        if (code == 404) {
            return NOT_REGISTERED;
        }
        if (code == 429) {
            return TOO_MANY;
        }
        if (code == 422 || code == 400 || code == 409) {
            // The server's refusal is written for the player; show it as is.
            try {
                JsonObject error = gson.fromJson(body, JsonObject.class);
                if (error != null && error.has("error") && error.get("error").isJsonPrimitive()) {
                    return error.get("error").getAsString();
                }
            } catch (JsonSyntaxException | IllegalStateException e) {
                // fall through to the generic message
            }
        }
        return UNREACHABLE;
    }
}
