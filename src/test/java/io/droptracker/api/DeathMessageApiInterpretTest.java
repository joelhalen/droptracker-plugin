package io.droptracker.api;

import com.google.gson.Gson;
import org.junit.Test;

import io.droptracker.models.api.DeathMessages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * How {@code /player/death_messages} answers become what the editor shows,
 * against bodies shaped like the ones the server sends.
 */
public class DeathMessageApiInterpretTest {

    private final Gson gson = new Gson();

    private static final String LIVE_SHAPE =
        "{\"groups\":[{\"allowed\":false,\"blocked\":false,\"id\":2,\"name\":\"DropTracker.io\"},"
            + "{\"allowed\":true,\"blocked\":true,\"id\":270,\"name\":\"Evangelist\"}],"
            + "\"max_length\":150,\"max_messages\":5,"
            + "\"messages\":[\"{player_name} forgot to pray against {killer}\"],"
            + "\"player_name\":\"Iron Ron\","
            + "\"tokens\":[{\"help\":\"Your name\",\"sample\":\"Zezima\",\"token\":\"{player_name}\"},"
            + "{\"help\":\"What killed you\",\"sample\":\"Vorkath\",\"token\":\"{killer}\"}]}";

    @Test
    public void aSuccessfulAnswerCarriesEverythingTheEditorNeeds() {
        DeathMessageApi.Result result = DeathMessageApi.interpret(gson, 200, LIVE_SHAPE);
        assertNull(result.error);
        DeathMessages messages = result.messages;
        assertNotNull(messages);
        assertEquals("Iron Ron", messages.getPlayerName());
        assertEquals(5, messages.getMaxMessages());
        assertEquals(1, messages.getMessages().size());
        assertEquals("{killer}", messages.getTokens().get(1).getToken());
        assertEquals("Vorkath", messages.getTokens().get(1).getSample());
        assertFalse(messages.getGroups().get(0).isAllowed());
        assertTrue(messages.getGroups().get(1).isBlocked());
    }

    @Test
    public void anOlderServerLeavingFieldsOutKeepsTheDefaults() {
        DeathMessageApi.Result result = DeathMessageApi.interpret(gson, 200, "{\"messages\":[]}");
        assertNotNull(result.messages);
        assertEquals(5, result.messages.getMaxMessages());
        assertEquals(150, result.messages.getMaxLength());
        assertTrue(result.messages.getGroups().isEmpty());
        assertTrue(result.messages.getTokens().isEmpty());
    }

    @Test
    public void aRefusalIsShownInTheServersOwnWords() {
        DeathMessageApi.Result result = DeathMessageApi.interpret(gson, 422,
            "{\"error\":\"Messages can't contain links.\"}");
        assertNull(result.messages);
        assertEquals("Messages can't contain links.", result.error);
    }

    @Test
    public void anUnknownAccountSaysSo() {
        assertEquals(DeathMessageApi.NOT_REGISTERED,
            DeathMessageApi.interpret(gson, 404, "{\"error\":\"Player not found\"}").error);
    }

    @Test
    public void rateLimitedSaysToWait() {
        assertEquals(DeathMessageApi.TOO_MANY, DeathMessageApi.interpret(gson, 429, "").error);
    }

    @Test
    public void anythingElseIsTheGenericMessage() {
        assertEquals(DeathMessageApi.UNREACHABLE, DeathMessageApi.interpret(gson, 500, "<html>oops</html>").error);
        assertEquals(DeathMessageApi.UNREACHABLE, DeathMessageApi.interpret(gson, 200, "<html>proxy page</html>").error);
        assertEquals(DeathMessageApi.UNREACHABLE, DeathMessageApi.interpret(gson, 422, "not json").error);
        assertEquals(DeathMessageApi.UNREACHABLE, DeathMessageApi.interpret(gson, 200, "").error);
    }
}
