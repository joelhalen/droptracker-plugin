package io.droptracker.api;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import io.droptracker.DropTrackerConfig;
import io.droptracker.models.api.DeathMessages;
import lombok.extern.slf4j.Slf4j;
import okhttp3.CacheControl;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Reads and saves the logged-in account's own death messages
 * ({@code GET/POST /player/death_messages}).
 *
 * <p>The same messages the website and Discord's {@code /settings} edit, keyed
 * by account: the server resolves the account hash, which is also all the
 * plugin's other endpoints go on. Every call runs on the injected executor and
 * answers on the Swing thread, because its only caller is the editor dialog.
 */
@Slf4j
@Singleton
public class DeathMessageApi {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final Gson gson;
    private final OkHttpClient httpClient;
    private final ScheduledExecutorService executor;

    @Inject
    public DeathMessageApi(DropTrackerConfig config, DropTrackerApi api, Gson gson,
                           OkHttpClient httpClient, ScheduledExecutorService executor) {
        this.config = config;
        this.api = api;
        this.gson = gson;
        this.httpClient = httpClient;
        this.executor = executor;
    }

    /** What a call came back with: the messages, or a sentence for the player. */
    public static final class Result {
        @Nullable
        public final DeathMessages messages;
        @Nullable
        public final String error;

        private Result(@Nullable DeathMessages messages, @Nullable String error) {
            this.messages = messages;
            this.error = error;
        }

        public static Result ok(DeathMessages messages) {
            return new Result(messages, null);
        }

        public static Result failed(String error) {
            return new Result(null, error);
        }
    }

    /** Loads the account's messages; {@code callback} runs on the Swing thread. */
    public void fetch(@Nullable String playerName, long accountHash, Consumer<Result> callback) {
        executor.execute(() -> {
            Result result = fetchNow(playerName, accountHash);
            SwingUtilities.invokeLater(() -> callback.accept(result));
        });
    }

    /** Replaces the account's messages; {@code callback} runs on the Swing thread. */
    public void save(@Nullable String playerName, long accountHash, List<String> messages,
                     Consumer<Result> callback) {
        executor.execute(() -> {
            Result result = saveNow(playerName, accountHash, messages);
            SwingUtilities.invokeLater(() -> callback.accept(result));
        });
    }

    private Result fetchNow(@Nullable String playerName, long accountHash) {
        HttpUrl base = endpoint();
        if (base == null) {
            return Result.failed(API_OFF);
        }
        HttpUrl url = base.newBuilder()
            .addQueryParameter("player_name", playerName == null ? "" : playerName)
            .addQueryParameter("acc_hash", String.valueOf(accountHash))
            .build();
        // FORCE_NETWORK: the injected client has a disk cache shared with the
        // whole of RuneLite, and a stale copy here would undo a save.
        Request request = new Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build();
        return execute(request);
    }

    private Result saveNow(@Nullable String playerName, long accountHash, List<String> messages) {
        HttpUrl url = endpoint();
        if (url == null) {
            return Result.failed(API_OFF);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("player_name", playerName == null ? "" : playerName);
        body.put("acc_hash", String.valueOf(accountHash));
        body.put("messages", messages);
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

    static final String API_OFF = "Turn on \"Use API Connections\" in the plugin settings to edit your death message here.";
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
        if (code == 404) {
            return Result.failed(NOT_REGISTERED);
        }
        if (code == 429) {
            return Result.failed(TOO_MANY);
        }
        if (code == 422 || code == 400) {
            // The server's refusal is written for the player; show it as is.
            try {
                JsonObject error = gson.fromJson(body, JsonObject.class);
                if (error != null && error.has("error") && error.get("error").isJsonPrimitive()) {
                    return Result.failed(error.get("error").getAsString());
                }
            } catch (JsonSyntaxException | IllegalStateException e) {
                // fall through to the generic message
            }
        }
        return Result.failed(UNREACHABLE);
    }
}
