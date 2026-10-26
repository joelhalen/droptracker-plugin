package io.droptracker.ui.components;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import com.google.gson.Gson;
import org.junit.Test;

import io.droptracker.api.DeathMessageApi;
import io.droptracker.models.api.DeathMessages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The plugin's death message editor, driven headless with a stub API: what it
 * shows, what it refuses, and what it sends.
 */
public class DeathMessageEditorTest {

    private static final Gson GSON = new Gson();

    private static final String LOADED =
        "{\"player_name\":\"Iron Ron\",\"max_messages\":5,\"max_length\":150,"
            + "\"messages\":[\"{player_name} planked\"],"
            + "\"tokens\":[{\"token\":\"{player_name}\",\"help\":\"Your name\",\"sample\":\"Zezima\"},"
            + "{\"token\":\"{killer}\",\"help\":\"What killed you\",\"sample\":\"Vorkath\"}],"
            + "\"groups\":[{\"id\":5,\"name\":\"Clan\",\"allowed\":true,\"blocked\":false}]}";

    /** Answers synchronously, as if the network were instant. */
    private static final class StubApi extends DeathMessageApi {
        int fetches;
        String lastType;
        String loaded = LOADED;
        List<String> saved;
        String saveError;

        StubApi() {
            super(null, null, null, null, null);
        }

        @Override
        public void fetch(String playerName, long accountHash, String type, Consumer<Result> callback) {
            fetches++;
            lastType = type;
            callback.accept(Result.ok(GSON.fromJson(loaded, DeathMessages.class)));
        }

        @Override
        public void save(String playerName, long accountHash, String type, List<String> messages,
                         Consumer<Result> callback) {
            lastType = type;
            saved = new ArrayList<>(messages);
            if (saveError != null) {
                callback.accept(Result.failed(saveError));
                return;
            }
            DeathMessages reply = GSON.fromJson(LOADED, DeathMessages.class);
            String json = GSON.toJson(reply).replace("[\"{player_name} planked\"]", GSON.toJson(messages));
            callback.accept(Result.ok(GSON.fromJson(json, DeathMessages.class)));
        }
    }

    private static DeathMessageEditor editor(StubApi api, long hash) {
        DeathMessageEditor editor = new DeathMessageEditor(api, () -> hash, () -> "Iron Ron", () -> { }, () -> { });
        editor.load();
        return editor;
    }

    @Test
    public void loadingFillsTheBoxesAndSaysWhereItPosts() {
        StubApi api = new StubApi();
        DeathMessageEditor editor = editor(api, 42L);
        assertEquals(1, api.fetches);
        assertEquals("{player_name} planked", editor.fields().get(0).getText());
        assertEquals("", editor.fields().get(1).getText());
        assertTrue(editor.fields().get(0).isEditable());
        assertFalse("nothing changed yet", editor.saveButton().isEnabled());
        assertTrue(editor.groupsLabelText(), editor.groupsLabelText().contains("Posted in: "));
        assertTrue(editor.previewText(), editor.previewText().contains("Example: Iron Ron planked"));
    }

    @Test
    public void aRuleBreakIsShownAndCannotBeSaved() {
        DeathMessageEditor editor = editor(new StubApi(), 42L);
        editor.fields().get(1).setText("free gp at www.scam.com");
        assertFalse(editor.saveButton().isEnabled());
        assertTrue(editor.statusText(), editor.statusText().contains("Messages can't contain links."));
    }

    @Test
    public void aValidEditSavesWhatTheServerWillStore() {
        StubApi api = new StubApi();
        DeathMessageEditor editor = editor(api, 42L);
        editor.fields().get(2).setText("  {Killer} got {player_name} again  ");
        assertTrue(editor.saveButton().isEnabled());
        editor.clickSave();
        assertEquals(Arrays.asList("{player_name} planked", "{killer} got {player_name} again"), api.saved);
        assertTrue(editor.statusText(), editor.statusText().contains("Saved."));
        assertFalse("saved, so nothing is unsaved", editor.saveButton().isEnabled());
        assertEquals("{killer} got {player_name} again", editor.fields().get(1).getText());
    }

    @Test
    public void clearingEveryBoxClearsTheMessages() {
        StubApi api = new StubApi();
        DeathMessageEditor editor = editor(api, 42L);
        editor.fields().get(0).setText("");
        editor.clickSave();
        assertEquals(Collections.emptyList(), api.saved);
        assertTrue(editor.statusText(), editor.statusText().contains("Cleared."));
    }

    @Test
    public void aRefusedSaveShowsTheServersWords() {
        StubApi api = new StubApi();
        api.saveError = "You can save at most 5 messages.";
        DeathMessageEditor editor = editor(api, 42L);
        editor.fields().get(1).setText("{player_name} again");
        editor.clickSave();
        assertTrue(editor.statusText(), editor.statusText().contains("You can save at most 5 messages."));
        assertTrue("still editable after a refusal", editor.fields().get(1).isEditable());
    }

    @Test
    public void loggedOutThereIsNothingToEdit() {
        StubApi api = new StubApi();
        DeathMessageEditor editor = editor(api, -1L);
        assertEquals(0, api.fetches);
        assertFalse(editor.fields().get(0).isEditable());
        assertFalse(editor.saveButton().isEnabled());
        assertTrue(editor.statusText(), editor.statusText().contains("Log in first"));
    }

    @Test
    public void aPlaceholderIsAddedWhereTheCursorIs() {
        DeathMessageEditor editor = editor(new StubApi(), 42L);
        editor.fields().get(0).setCaretPosition(editor.fields().get(0).getText().length());
        editor.clickToken("{killer}");
        assertEquals("{player_name} planked {killer}", editor.fields().get(0).getText());
    }

    @Test
    public void namesEachClanWithItsState() {
        String text = DeathMessageEditor.groupsText(Arrays.asList(
            group("{\"id\":1,\"name\":\"Posting Clan\",\"allowed\":true,\"blocked\":false}"),
            group("{\"id\":2,\"name\":\"Quiet Clan\",\"allowed\":false,\"blocked\":false}"),
            group("{\"id\":3,\"name\":\"Strict Clan\",\"allowed\":true,\"blocked\":true}")));
        assertTrue(text, text.contains("Posted in: "));
        assertTrue(text.contains("Posting Clan</font>"));
        assertTrue(text.contains("Quiet Clan (not switched on)"));
        assertTrue(text.contains("Strict Clan (blocked by its leaders)"));
    }

    @Test
    public void noClansSaysSo() {
        assertTrue(DeathMessageEditor.groupsText(Collections.emptyList()).contains("isn't in any clans"));
    }

    @Test
    public void clanNamesCannotInjectMarkup() {
        String text = DeathMessageEditor.groupsText(Collections.singletonList(
            group("{\"id\":1,\"name\":\"<b>Bold</b> & co\",\"allowed\":true,\"blocked\":false}")));
        assertFalse(text, text.contains("<b>"));
        assertTrue(text.contains("&lt;b&gt;Bold&lt;/b&gt; &amp; co"));
    }

    private static DeathMessages.Group group(String json) {
        return GSON.fromJson(json, DeathMessages.Group.class);
    }

    private static javax.swing.JComboBox<?> typeBox(java.awt.Container root) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.JComboBox) {
                return (javax.swing.JComboBox<?>) c;
            }
            if (c instanceof java.awt.Container) {
                javax.swing.JComboBox<?> found = typeBox((java.awt.Container) c);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    public void deathsAreTheDefaultType() {
        StubApi api = new StubApi();
        editor(api, 42L);
        assertEquals("death", api.lastType);
    }

    @Test
    public void pickingATypeLoadsItsMessagesAndPlaceholders() {
        StubApi api = new StubApi();
        DeathMessageEditor editor = editor(api, 42L);
        api.loaded = "{\"player_name\":\"Iron Ron\",\"messages\":[\"{player_name} pb {boss}\"],"
            + "\"tokens\":[{\"token\":\"{player_name}\",\"help\":\"Your name\",\"sample\":\"Zezima\"},"
            + "{\"token\":\"{boss}\",\"help\":\"The boss\",\"sample\":\"Vorkath\"}],"
            + "\"aliases\":{\"{npc_name}\":\"{boss}\"},\"groups\":[]}";
        typeBox(editor).setSelectedIndex(3);
        assertEquals(2, api.fetches);
        assertEquals("pb", api.lastType);
        assertEquals("{player_name} pb {boss}", editor.fields().get(0).getText());

        // The type's own placeholders and aliases are accepted; death's are not.
        editor.fields().get(1).setText("{player_name} {npc_name}");
        assertTrue(editor.saveButton().isEnabled());
        editor.fields().get(1).setText("{player_name} {killer}");
        assertFalse(editor.saveButton().isEnabled());
        assertTrue(editor.statusText(), editor.statusText().contains("Unknown placeholder"));

        editor.fields().get(1).setText("");
        editor.fields().get(0).setText("{player_name} got {boss}");
        editor.clickSave();
        assertEquals("pb", api.lastType);
    }
}
