package io.droptracker.api;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.inject.*;
import javax.swing.SwingUtilities;

import com.google.gson.*;

import io.droptracker.DropTrackerConfig;
import io.droptracker.models.api.GroupAdmin;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import okhttp3.*;

/**
 * Group administration from the plugin ({@code /plugin/admin/...}).
 *
 * <p>Two factors stand behind every call. The account (name + hash, like every
 * other endpoint) must belong to a site user who owns or administers the group,
 * and the request must carry a device session for that group, which the server
 * only issues in exchange for the group's verification code. The code is sent
 * once and never stored; the session is stored per RuneScape profile and is
 * revoked server-side whenever the owner resets the code.
 *
 * <p>Every call runs on the injected executor and answers on the Swing thread.
 */
@Slf4j
@Singleton
@AllArgsConstructor(onConstructor_ = @Inject)
public class GroupAdminApi {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String TOKEN_KEY = "groupAdminSession.";

    private final DropTrackerConfig config;
    private final DropTrackerApi api;
    private final Gson gson;
    private final OkHttpClient httpClient;
    private final ScheduledExecutorService executor;
    private final ConfigManager configManager;

    /** What a call came back with: a value, or a sentence for the leader. */
    @AllArgsConstructor
    public static final class Result<T> {
        @Nullable
        public final T value;
        @Nullable
        public final String error;
        /** The group needs its verification code (again) before this works. */
        public final boolean needsCode;

        static <T> Result<T> ok(T value) {
            return new Result<>(value, null, false);
        }

        static <T> Result<T> failed(String error) {
            return new Result<>(null, error, false);
        }
    }

    public void groups(String playerName, long accountHash, Consumer<Result<GroupAdmin.Groups>> callback) {
        run(callback, () -> call("GET", "/plugin/admin/groups", playerName, accountHash, 0, null,
            GroupAdmin.Groups.class));
    }

    /** Exchanges the group's verification code for a device session, and stores it. */
    public void link(String playerName, long accountHash, int groupId, String code,
                     Consumer<Result<GroupAdmin.Link>> callback) {
        Map<String, Object> body = new HashMap<>();
        body.put("group_id", groupId);
        body.put("code", code);
        run(callback, () -> {
            Result<GroupAdmin.Link> result = call("POST", "/plugin/admin/link", playerName, accountHash, 0, body,
                GroupAdmin.Link.class);
            if (result.value != null && result.value.getToken() != null) {
                configManager.setRSProfileConfiguration(DropTrackerConfig.GROUP, TOKEN_KEY + groupId,
                    result.value.getToken());
            }
            return result;
        });
    }

    /** Ends this device's session for the group, here and on the server. */
    public void unlink(String playerName, long accountHash, int groupId, Consumer<Result<JsonObject>> callback) {
        run(callback, () -> {
            Result<JsonObject> result = call("POST", "/plugin/admin/groups/" + groupId + "/unlink", playerName,
                accountHash, groupId, new HashMap<>(), JsonObject.class);
            forget(groupId);
            return result;
        });
    }

    public void overview(String playerName, long accountHash, int groupId,
                         Consumer<Result<GroupAdmin.Overview>> callback) {
        run(callback, () -> call("GET", "/plugin/admin/groups/" + groupId + "/overview", playerName, accountHash,
            groupId, null, GroupAdmin.Overview.class));
    }

    public void settings(String playerName, long accountHash, int groupId,
                         Consumer<Result<GroupAdmin.Settings>> callback) {
        run(callback, () -> call("GET", "/plugin/admin/groups/" + groupId + "/settings", playerName, accountHash,
            groupId, null, GroupAdmin.Settings.class));
    }

    /** Saves changed settings. A refusal comes back as {@link GroupAdmin.Settings#getErrors()}. */
    public void save(String playerName, long accountHash, int groupId, Map<String, Object> changes,
                     Consumer<Result<GroupAdmin.Settings>> callback) {
        Map<String, Object> body = new HashMap<>();
        body.put("changes", changes);
        run(callback, () -> call("PATCH", "/plugin/admin/groups/" + groupId + "/settings", playerName,
            accountHash, groupId, body, GroupAdmin.Settings.class));
    }

    public void members(String playerName, long accountHash, int groupId, String query, int page,
                        Consumer<Result<GroupAdmin.Members>> callback) {
        String path = "/plugin/admin/groups/" + groupId + "/members?page=" + page + "&q="
            + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
        run(callback, () -> call("GET", path, playerName, accountHash, groupId, null, GroupAdmin.Members.class));
    }

    /** Shows or hides a member on the group's boards. */
    public void setHidden(String playerName, long accountHash, int groupId, int playerId, boolean hidden,
                          Consumer<Result<JsonObject>> callback) {
        Map<String, Object> body = new HashMap<>();
        body.put("hidden", hidden);
        run(callback, () -> call("POST", "/plugin/admin/groups/" + groupId + "/members/" + playerId + "/hidden",
            playerName, accountHash, groupId, body, JsonObject.class));
    }

    public void womSync(String playerName, long accountHash, int groupId, Consumer<Result<GroupAdmin.Notice>> callback) {
        run(callback, () -> call("POST", "/plugin/admin/groups/" + groupId + "/wom-sync", playerName, accountHash,
            groupId, new HashMap<>(), GroupAdmin.Notice.class));
    }

    /** Starts registering a group: a WiseOldMan id, or (null) the clan roster to make one from. */
    public void register(String playerName, long accountHash, String name, @Nullable Integer womId,
                         List<Map<String, String>> roster, Consumer<Result<GroupAdmin.Registration>> callback) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("wom_id", womId);
        body.put("roster", roster);
        run(callback, () -> call("POST", "/plugin/register", playerName, accountHash, 0, body,
            GroupAdmin.Registration.class));
    }

    /** Polls a registration; once done, keeps the new group's session like {@link #link}. */
    public void registration(String playerName, long accountHash, String id,
                             Consumer<Result<GroupAdmin.RegistrationStatus>> callback) {
        run(callback, () -> {
            Result<GroupAdmin.RegistrationStatus> result = call("GET", "/plugin/register/status?id=" + id, playerName,
                accountHash, 0, null, GroupAdmin.RegistrationStatus.class);
            if (result.value != null && result.value.getToken() != null) {
                configManager.setRSProfileConfiguration(DropTrackerConfig.GROUP, TOKEN_KEY + result.value.getGroupId(),
                    result.value.getToken());
            }
            return result;
        });
    }

    public boolean hasSession(int groupId) {
        return token(groupId) != null;
    }

    @Nullable
    private String token(int groupId) {
        String token = configManager.getRSProfileConfiguration(DropTrackerConfig.GROUP, TOKEN_KEY + groupId);
        return token == null || token.isEmpty() ? null : token;
    }

    private void forget(int groupId) {
        configManager.unsetRSProfileConfiguration(DropTrackerConfig.GROUP, TOKEN_KEY + groupId);
    }

    private <T> void run(Consumer<Result<T>> callback, java.util.function.Supplier<Result<T>> work) {
        executor.execute(() -> {
            Result<T> result = work.get();
            SwingUtilities.invokeLater(() -> callback.accept(result));
        });
    }

    private <T> Result<T> call(String method, String path, String playerName, long accountHash, int groupId,
                               @Nullable Map<String, Object> body, Type type) {
        if (!config.useApi()) {
            return Result.failed(API_OFF);
        }
        HttpUrl base = HttpUrl.parse(api.getApiUrl() + path);
        if (base == null) {
            return Result.failed(UNREACHABLE);
        }
        Request.Builder request = new Request.Builder().cacheControl(CacheControl.FORCE_NETWORK);
        if (body == null) {
            request.url(base.newBuilder()
                .addQueryParameter("player_name", playerName)
                .addQueryParameter("acc_hash", String.valueOf(accountHash))
                .build());
            request.method(method, null);
        } else {
            body.put("player_name", playerName);
            body.put("acc_hash", String.valueOf(accountHash));
            request.url(base).method(method, RequestBody.create(JSON, gson.toJson(body)));
        }
        String token = groupId > 0 ? token(groupId) : null;
        if (groupId > 0 && token == null) {
            return new Result<>(null, null, true);
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try (Response response = httpClient.newCall(request.build()).execute()) {
            ResponseBody responseBody = response.body();
            Result<T> result = interpret(gson, response.code(), responseBody == null ? "" : responseBody.string(), type);
            if (result.needsCode && groupId > 0) {
                forget(groupId);
            }
            return result;
        } catch (IOException e) {
            log.debug("Group admin request failed: {}", e.toString());
            return Result.failed(UNREACHABLE);
        }
    }

    static final String API_OFF = "Turn on \"Use API Connections\" in the plugin settings to manage your group here.";
    static final String UNREACHABLE = "Couldn't reach the DropTracker. Try again in a moment.";
    static final String WRONG_CODE = "That code isn't right. Check it with your group's owner.";
    static final String NO_CODE = "This group has no verification code yet. Its owner can create one under "
        + "Roles & access on droptracker.io.";
    static final String THROTTLED = "Too many tries. Wait a few minutes and try again.";
    static final String NOT_ADMIN = "This account isn't an owner or admin of that group. Claim your RSN on "
        + "droptracker.io with the Discord account that manages it.";

    /** Turns a response into a {@link Result}. Package-private for the tests. */
    static <T> Result<T> interpret(Gson gson, int status, String body, Type type) {
        JsonObject error = null;
        if (status != 200) {
            try {
                JsonElement parsed = gson.fromJson(body, JsonElement.class);
                error = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            } catch (JsonSyntaxException e) {
                // not JSON: the generic sentence below
            }
        }
        String code = text(error, "code");
        if (status == 200 || (status == 422 && type == GroupAdmin.Settings.class)) {
            try {
                T value = gson.fromJson(body, type);
                return value != null ? Result.ok(value) : Result.failed(UNREACHABLE);
            } catch (JsonSyntaxException e) {
                return Result.failed(UNREACHABLE);
            }
        }
        if (status == 401 && !"wrong_code".equals(code)) {
            // The session expired or the owner reset the code.
            return new Result<>(null, "Your session for this group has ended. Enter the code again.", true);
        }
        String fallback;
        if ("wrong_code".equals(code)) {
            fallback = WRONG_CODE;
        } else if ("no_code".equals(code) || status == 409) {
            fallback = NO_CODE;
        } else if (status == 429) {
            fallback = THROTTLED;
        } else if (status == 403) {
            fallback = NOT_ADMIN;
        } else if (status == 422) {
            fallback = UNREACHABLE;
        } else {
            return Result.failed(UNREACHABLE);
        }
        // The server's sentence is written for the leader; prefer it.
        String message = text(error, "error");
        return Result.failed(message != null ? message : fallback);
    }

    @Nullable
    private static String text(@Nullable JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        return object.get(key).getAsString();
    }
}
