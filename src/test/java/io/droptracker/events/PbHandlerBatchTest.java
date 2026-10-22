package io.droptracker.events;

import com.google.gson.Gson;
import io.droptracker.DropTrackerConfig;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Which chat lines PbHandler forwards, and when. The server parses them
 * (data/submissions/pb_lines.py, whose tests hold the parsing cases); this
 * side only has to send a kill's lines together, on the tick they complete it.
 */
public class PbHandlerBatchTest {

    private final List<Map<String, String>> sent = new ArrayList<>();
    private int tick = 100;
    private PbHandler handler;

    @Before
    public void setUp() {
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (p, m, a) -> {
                switch (m.getName()) {
                    case "getTickCount": return tick;
                    case "getPlayers": return Collections.nCopies(2, (Player) null);
                    case "getVarbitValue": return 0;
                }
                Class<?> r = m.getReturnType();
                return r == boolean.class ? false : r == int.class ? 0 : r == long.class ? 0L : null;
            });
        DropTrackerConfig config = (DropTrackerConfig) Proxy.newProxyInstance(DropTrackerConfig.class.getClassLoader(),
            new Class<?>[]{DropTrackerConfig.class}, (p, m, a) -> m.getReturnType() == boolean.class ? Boolean.TRUE
                : m.getReturnType() == String.class ? "" : m.getReturnType() == int.class ? 0 : null);
        handler = new PbHandler() {
            @Override
            protected String getPlayerName() {
                return "P";
            }

            @Override
            protected void sendData(CustomWebhookBody webhook, SubmissionType type) {
                Map<String, String> fields = new LinkedHashMap<>();
                for (CustomWebhookBody.Field f : webhook.getEmbeds().get(0).getFields()) {
                    fields.put(f.getName(), f.getValue());
                }
                sent.add(fields);
            }
        };
        handler.client = client;
        handler.config = config;
        handler.gson = new Gson();
    }

    private void at(int tick, String... messages) {
        this.tick = tick;
        for (String message : messages) {
            handler.onGameMessage(message);
        }
        handler.onTick();
    }

    @Test
    public void aCountAndATimeAreSentTogetherOnTheTickTheyComplete() {
        at(100, "Fight duration: 1:23.40 (new personal best)");
        assertTrue(sent.isEmpty());
        at(101, "Your Vorkath kill count is: 12.");
        assertEquals(1, sent.size());
        Map<String, String> kill = sent.get(0);
        assertEquals("npc_kill", kill.get("type"));
        assertEquals("true", kill.get("is_pb"));
        assertEquals("Your Vorkath kill count is: 12.", kill.get("source"));
        assertTrue(kill.get("chat_line_1"), kill.get("chat_line_1").contains("\"t\":100"));
        assertTrue(kill.get("chat_line_2"), kill.get("chat_line_2").contains("\"p\":[0,0,0,2]"));
        assertNull(kill.get("chat_line_3"));
        at(102);
        assertEquals("the batch is cleared once sent", 1, sent.size());
    }

    @Test
    public void wallClockTotalsAndIgnoredLinesDoNotCompleteAKill() {
        at(100, "Theatre of Blood total completion time: 24:40.20. Personal best: 20:45.00",
            "Your completed Theatre of Blood count is: 5.", "Preparation time: 1:00");
        assertTrue(sent.isEmpty());
        at(101, "Theatre of Blood completion time: 17:00.20 (new personal best)");
        assertEquals(1, sent.size());
        assertNotNull(sent.get(0).get("chat_line_3"));
        assertFalse("the preparation line is never forwarded", sent.get(0).toString().contains("Preparation"));
    }

    @Test
    public void aRoomPersonalBestOnATotalLineIsNotAPb() {
        at(100, "Tombs of Amascut total completion time: 0:01 (new personal best)",
            "Tombs of Amascut challenge completion time: 8:31. Personal best: 8:00",
            "Your completed Tombs of Amascut count is: 3.");
        assertEquals("false", sent.get(0).get("is_pb"));
    }

    @Test
    public void aTimedDelveLevelIsAKillOnItsOwn() {
        at(100, "Delve level: 8+ (9)");
        assertTrue("the level-start announcement has no time", sent.isEmpty());
        at(101, "Delve level: 8+ (9) duration: 1:27 (new personal best)");
        assertEquals(1, sent.size());
    }

    @Test
    public void unrelatedMessagesAreNotForwarded() {
        at(100, "You have a funny feeling...", "Your heriblore level is now 78.");
        at(101, "Your Vorkath kill count is: 12.", "Fight duration: 1:00.");
        assertEquals(1, sent.size());
        assertNull(sent.get(0).get("chat_line_3"));
    }
}
