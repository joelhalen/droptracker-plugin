package io.droptracker.events;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.droptracker.DropTrackerConfig;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConfigSnapshotHandlerTest {
    private static DropTrackerConfig defaults() {
        return new DropTrackerConfig() {
            @Override
            public void setPinnedEventId(int eventId) {
            }

            @Override
            public void setLastVersionNotified(String versionNotified) {
            }

            @Override
            public void setLastAccountName(String accountName) {
            }

            @Override
            public void setCustomApiEndpoint(String customApiEndpoint) {
            }

            @Override
            public void setLastAccountHash(String accountHash) {
            }

            @Override
            public String customApiEndpoint() {
                return "https://private.example/api";
            }
        };
    }

    private static JsonObject env() {
        JsonObject env = new JsonObject();
        env.addProperty("runelite_version", "1.13.1");
        env.addProperty("resizable", true);
        env.addProperty("chatbox_transparent", false);
        env.addProperty("loot_tracker_enabled", true);
        env.addProperty("custom_api_endpoint", true);
        return env;
    }

    @Test
    public void settingsAreGroupedBySectionWithTheirValues() {
        JsonObject snapshot = ConfigSnapshotHandler.build(defaults(), "petEmbeds"::equals, env());
        JsonObject settings = snapshot.getAsJsonObject("settings");
        assertEquals(ConfigSnapshotHandler.FORMAT_VERSION, snapshot.get("v").getAsInt());
        assertTrue(settings.getAsJsonObject("Tracking").get("lootEmbeds").getAsBoolean());
        assertEquals("POPUP", settings.getAsJsonObject("Events").get("eventDisplayMode").getAsString());
        assertTrue(settings.getAsJsonObject("Hidden").has("trackExperience"));
        assertEquals(1, snapshot.getAsJsonArray("customized").size());
        assertEquals("petEmbeds", snapshot.getAsJsonArray("customized").get(0).getAsString());
    }

    @Test
    public void internalAndTextSettingsNeverLeave() {
        String json = new Gson().toJson(ConfigSnapshotHandler.build(defaults(), key -> true, env()));
        for (String key : ConfigSnapshotHandler.EXCLUDED) {
            assertFalse(key + " was sent", json.contains("\"" + key + "\""));
        }
        assertFalse(json.contains("private.example"));
        assertFalse(json.contains("customized\":[\"customApiEndpoint"));
    }

    @Test
    public void worstCaseFitsAnEmbedDescription() {
        // Every setting customized, plus the hash the sender appends.
        JsonObject snapshot = ConfigSnapshotHandler.build(defaults(), key -> true, env());
        snapshot.addProperty("hash", "0123456789abcdef");
        String json = new Gson().toJson(snapshot);
        assertTrue("snapshot is " + json.length() + " chars",
            json.length() < ConfigSnapshotHandler.MAX_DESCRIPTION - 1000);
    }

    /**
     * Every boolean, number and enum setting in DropTrackerConfig is either in
     * SETTINGS or deliberately EXCLUDED, and SETTINGS names no key that does
     * not exist. Reads the config interface as source text: the snapshot list
     * is explicit because the Plugin Hub does not allow reflection.
     */
    @Test
    public void settingsListMatchesTheConfigInterface() throws IOException {
        String source = new String(Files.readAllBytes(
            Paths.get("src/main/java/io/droptracker/DropTrackerConfig.java")), StandardCharsets.UTF_8);
        Pattern item = Pattern.compile(
            "@ConfigItem\\((.*?)\\)\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*default\\s+([\\w.<>]+)\\s+\\w+\\(\\)",
            Pattern.DOTALL);
        Pattern keyName = Pattern.compile("keyName\\s*=\\s*\"([^\"]+)\"");
        Set<String> expected = new HashSet<>();
        Matcher m = item.matcher(source);
        while (m.find()) {
            Matcher key = keyName.matcher(m.group(1));
            if (!key.find()) {
                continue;
            }
            if (!"String".equals(m.group(2)) && !ConfigSnapshotHandler.EXCLUDED.contains(key.group(1))) {
                expected.add(key.group(1));
            }
        }
        Set<String> listed = ConfigSnapshotHandler.SETTINGS.stream()
            .map(s -> s.key).collect(Collectors.toSet());
        assertTrue("found only " + expected.size() + " settings in the source", expected.size() > 30);
        assertEquals(expected, listed);
        assertEquals("duplicate keys in SETTINGS", listed.size(), ConfigSnapshotHandler.SETTINGS.size());
    }
}
