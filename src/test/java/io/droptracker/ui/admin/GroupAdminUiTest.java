package io.droptracker.ui.admin;

import java.awt.*;
import java.util.*;
import java.util.List;

import javax.swing.*;

import com.google.gson.*;
import org.junit.Test;

import io.droptracker.models.api.GroupAdmin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The manager's form logic: which rows count as changed, what Save sends, and
 * the code boxes. Rendering is checked by eye; these pin the behaviour.
 */
public class GroupAdminUiTest {

    private static final Gson GSON = new Gson();

    private static final String SETTINGS = "{\"categories\":[{\"key\":\"drops\",\"label\":\"Drops\"}],"
        + "\"channels\":[{\"value\":\"1001\",\"label\":\"drops\"}],"
        + "\"fields\":["
        + "{\"key\":\"min\",\"label\":\"Minimum\",\"category\":\"drops\",\"type\":\"int\",\"value\":\"2500000\",\"min\":0},"
        + "{\"key\":\"img\",\"label\":\"Screenshots\",\"category\":\"drops\",\"type\":\"boolean\",\"value\":\"0\"},"
        + "{\"key\":\"chan\",\"label\":\"Channel\",\"category\":\"drops\",\"type\":\"channel\",\"value\":\"999\"},"
        + "{\"key\":\"list\",\"label\":\"Bosses\",\"category\":\"drops\",\"type\":\"bosslist\",\"value\":\"[1,2]\"},"
        + "{\"key\":\"other\",\"label\":\"Elsewhere\",\"category\":\"pvp\",\"type\":\"boolean\",\"value\":\"1\"}]}";

    private static SettingsForm form(int[] dirtyCalls) {
        GroupAdmin.Settings settings = GSON.fromJson(SETTINGS, GroupAdmin.Settings.class);
        return new SettingsForm(settings, settings.getCategories().get(0), () -> dirtyCalls[0]++, () -> {
        });
    }

    @Test
    public void aFreshFormHasNoChanges() {
        SettingsForm form = form(new int[1]);
        assertEquals(0, form.dirtyCount());
        assertTrue(form.changes().isEmpty());
        assertTrue(form.valid());
    }

    @Test
    public void onlyThisCategorysFieldsAreDrawn() {
        SettingsForm form = form(new int[1]);
        assertEquals(1, all(form, ToggleSwitch.class).size());
    }

    @Test
    public void groupedNumbersAreNotAChange() {
        // "2500000" shows as "2,500,000"; that alone must not light up Save.
        SettingsForm form = form(new int[1]);
        assertEquals("2,500,000", all(form, JTextField.class).get(0).getText());
        assertEquals(0, form.dirtyCount());
    }

    @Test
    public void changesCarryOnlyEditedKeysWithTypedValues() {
        int[] calls = new int[1];
        SettingsForm form = form(calls);
        all(form, JTextField.class).get(0).setText("10,000,000");
        flip(all(form, ToggleSwitch.class).get(0));

        Map<String, Object> changes = form.changes();
        assertEquals(new LinkedHashSet<>(Arrays.asList("min", "img")), changes.keySet());
        assertEquals(new JsonPrimitive(10_000_000L), changes.get("min"));
        assertEquals(new JsonPrimitive(true), changes.get("img"));
        assertEquals(2, form.dirtyCount());
        assertTrue(calls[0] > 0);
    }

    @Test
    public void undoingAnEditClearsIt() {
        SettingsForm form = form(new int[1]);
        JTextField min = all(form, JTextField.class).get(0);
        min.setText("1");
        min.setText("2,500,000");
        assertEquals(0, form.dirtyCount());
    }

    @Test
    public void anOutOfRangeNumberBlocksSave() {
        SettingsForm form = form(new int[1]);
        all(form, JTextField.class).get(0).setText("-5");
        assertFalse(form.valid());
        all(form, JTextField.class).get(0).setText("");
        assertTrue(form.valid());
        assertEquals(JsonNull.INSTANCE, form.changes().get("min") == null ? JsonNull.INSTANCE : form.changes().get("min"));
    }

    @Test
    public void aChannelTheBotCantSeeIsKeptNotCleared() {
        SettingsForm form = form(new int[1]);
        JComboBox<?> box = all(form, JComboBox.class).get(0);
        GroupAdmin.Choice selected = (GroupAdmin.Choice) box.getSelectedItem();
        assertEquals("999", selected.getValue());
        assertEquals(0, form.dirtyCount());
    }

    @Test
    public void unknownTypesAreReadOnly() {
        SettingsForm form = form(new int[1]);
        boolean link = false;
        for (JButton b : all(form, JButton.class)) {
            link |= "Edit on website".equals(b.getText());
        }
        assertTrue(link);
        assertFalse(form.changes().containsKey("list"));
    }

    @Test
    public void serverErrorsLandOnTheirRow() {
        SettingsForm form = form(new int[1]);
        form.showErrors(Collections.singletonMap("img", "Needs a drops channel first."));
        boolean shown = false;
        for (JLabel l : all(form, JLabel.class)) {
            shown |= "Needs a drops channel first.".equals(l.getText()) && l.isVisible();
        }
        assertTrue(shown);
    }

    @Test
    public void numberRules() {
        GroupAdmin.Field field = GSON.fromJson("{\"min\":0,\"max\":100}", GroupAdmin.Field.class);
        assertNull(SettingsForm.intIssue(new JsonPrimitive(50L), field));
        assertEquals("Must be at least 0.", SettingsForm.intIssue(new JsonPrimitive(-1L), field));
        assertEquals("Must be at most 100.", SettingsForm.intIssue(new JsonPrimitive(101L), field));
        assertEquals("Numbers only.", SettingsForm.intIssue(new JsonPrimitive("abc"), field));
        assertNull(SettingsForm.intIssue(JsonNull.INSTANCE, field));
    }

    @Test
    public void legacyBooleansRead() {
        assertTrue(SettingsForm.asBoolean(new JsonPrimitive("1")));
        assertTrue(SettingsForm.asBoolean(new JsonPrimitive(true)));
        assertFalse(SettingsForm.asBoolean(new JsonPrimitive("0")));
        assertFalse(SettingsForm.asBoolean(null));
    }

    @Test
    public void codeBoxesAssembleTheCode() throws Exception {
        CodeEntryPanel panel = new CodeEntryPanel(() -> {
        }, () -> {
        });
        assertNull(panel.code());
        panel.fill("123456789", 0);
        assertEquals("123-456-789", panel.code());
    }

    @Test
    public void aPastedCodeFillsEveryBox() throws Exception {
        CodeEntryPanel panel = new CodeEntryPanel(() -> {
        }, () -> {
        });
        all(panel, JTextField.class).get(0).setText("123-456-789");
        SwingUtilities.invokeAndWait(() -> {
        });
        assertEquals("123-456-789", panel.code());
    }

    @Test
    public void lettersNeverGetIn() {
        CodeEntryPanel panel = new CodeEntryPanel(() -> {
        }, () -> {
        });
        JTextField first = all(panel, JTextField.class).get(0);
        first.setText("1a2");
        assertEquals("12", first.getText());
    }

    @Test
    public void headerLine() {
        GroupAdmin.Group g = GSON.fromJson("{\"role\":\"owner\",\"member_count\":1,\"tier\":\"Supporter\"}",
            GroupAdmin.Group.class);
        assertEquals("Owner  ·  1 member  ·  Supporter", GroupAdminWindow.meta(g));
        assertEquals("1,204 members", GroupAdminWindow.plural(1204, "member"));
    }

    private static void flip(ToggleSwitch toggle) {
        // A real click (key events would be re-routed to the focus owner).
        toggle.dispatchEvent(new java.awt.event.MouseEvent(toggle, java.awt.event.MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(), 0, 5, 5, 1, false));
    }

    private static <T> List<T> all(Component c, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (type.isInstance(c)) {
            out.add(type.cast(c));
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                out.addAll(all(k, type));
            }
        }
        return out;
    }
}
