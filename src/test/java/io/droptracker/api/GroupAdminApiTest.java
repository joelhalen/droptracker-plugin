package io.droptracker.api;

import com.google.gson.Gson;
import org.junit.Test;

import io.droptracker.models.api.GroupAdmin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * How the group-admin client reads the server's answers. The point of these:
 * an expired session sends the leader back to the code screen (and only then),
 * a wrong code does not, and every refusal reaches the leader as a sentence.
 */
public class GroupAdminApiTest {

    private static final Gson GSON = new Gson();

    private static <T> GroupAdminApi.Result<T> read(int status, String body, Class<T> type) {
        return GroupAdminApi.interpret(GSON, status, body, type);
    }

    @Test
    public void okParsesTheValue() {
        GroupAdminApi.Result<GroupAdmin.Groups> r = read(200,
            "{\"groups\":[{\"id\":7,\"name\":\"Iron Foundry\",\"role\":\"owner\",\"member_count\":128}]}",
            GroupAdmin.Groups.class);
        assertNull(r.error);
        assertEquals(1, r.value.getGroups().size());
        assertEquals(128, r.value.getGroups().get(0).getMemberCount());
    }

    @Test
    public void rejectedSettingsStillParseSoErrorsCanBeShownInline() {
        GroupAdminApi.Result<GroupAdmin.Settings> r = read(422,
            "{\"fields\":[],\"errors\":{\"minimum_value_to_notify\":\"Must be at least 0.\"}}",
            GroupAdmin.Settings.class);
        assertNotNull(r.value);
        assertEquals("Must be at least 0.", r.value.getErrors().get("minimum_value_to_notify"));
    }

    @Test
    public void expiredSessionAsksForTheCodeAgain() {
        GroupAdminApi.Result<GroupAdmin.Overview> r = read(401, "{\"code\":\"session_expired\"}",
            GroupAdmin.Overview.class);
        assertTrue(r.needsCode);
        assertNull(r.value);
    }

    @Test
    public void wrongCodeIsNotAnExpiredSession() {
        GroupAdminApi.Result<GroupAdmin.Link> r = read(401, "{\"code\":\"wrong_code\"}", GroupAdmin.Link.class);
        assertFalse(r.needsCode);
        assertEquals(GroupAdminApi.WRONG_CODE, r.error);
    }

    @Test
    public void theServersSentenceWins() {
        GroupAdminApi.Result<GroupAdmin.Link> r = read(403,
            "{\"code\":\"not_admin\",\"error\":\"You aren't an admin of Iron Foundry.\"}", GroupAdmin.Link.class);
        assertEquals("You aren't an admin of Iron Foundry.", r.error);
    }

    @Test
    public void eachRefusalHasItsOwnFallback() {
        assertEquals(GroupAdminApi.NO_CODE, read(409, "", GroupAdmin.Link.class).error);
        assertEquals(GroupAdminApi.THROTTLED, read(429, "nope", GroupAdmin.Link.class).error);
        assertEquals(GroupAdminApi.NOT_ADMIN, read(403, "{}", GroupAdmin.Link.class).error);
        assertEquals(GroupAdminApi.UNREACHABLE, read(500, "<html>", GroupAdmin.Link.class).error);
        assertEquals(GroupAdminApi.UNREACHABLE, read(200, "{not json", GroupAdmin.Link.class).error);
    }
}
