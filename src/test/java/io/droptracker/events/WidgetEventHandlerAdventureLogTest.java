package io.droptracker.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.droptracker.testing.RuneLiteStubs;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;

/**
 * Reading the POH adventure log owner (ticket #472).
 *
 * <p>The adventure log opens in one of two menu interfaces, and the player's
 * interface style setting decides which. Only the original one was handled, so
 * on the newer style the owner was never read and the Counters page PB sync
 * silently did nothing.
 */
public class WidgetEventHandlerAdventureLogTest {

    private static final String PLAYER = "Rickness666";

    private final Map<Integer, Widget> widgets = new HashMap<>();
    private WidgetEventHandler handler;

    @Before
    public void setUp() {
        Client client = RuneLiteStubs.client(RuneLiteStubs.player(PLAYER, 126));
        RuneLiteStubs.state(client).put("getWidget",
            (RuneLiteStubs.Answer) args -> widgets.get((Integer) args[0]));
        handler = new WidgetEventHandler();
        handler.client = client;
    }

    @Test
    public void readsOwnerFromNewerMenuInterface() {
        // The title is a later dynamic child, among null and non-text children.
        widgets.put(InterfaceID.MenuNew.TITLE, container(null,
            null, text(null), text(""), text("The Exploits of " + PLAYER), text("The Exploits of Someone Else")));

        openAdventureLog(InterfaceID.MENU_NEW);

        assertEquals(PLAYER, pohOwner());
    }

    @Test
    public void readsOwnerFromOriginalMenuInterface() {
        widgets.put(InterfaceID.Menu.LJ_LAYER2, container(null, text(""), text("The Exploits of " + PLAYER)));

        openAdventureLog(InterfaceID.MENU);

        assertEquals(PLAYER, pohOwner());
    }

    @Test
    public void readsOwnerFromContainerTextAndStripsTags() {
        widgets.put(InterfaceID.MenuNew.TITLE, container("<col=ff981f>The Exploits of </col>" + PLAYER));

        assertEquals(PLAYER, handler.readAdventureLogOwner());
    }

    @Test
    public void unrelatedMenuDoesNotSetOwner() {
        widgets.put(InterfaceID.Menu.LJ_LAYER2, container(null, text("Select an option"), text("Teleport")));

        openAdventureLog(InterfaceID.MENU);

        assertNull(pohOwner());
    }

    @Test
    public void changingRegionOrWorldClearsOwner() {
        widgets.put(InterfaceID.MenuNew.TITLE, container(null, text("The Exploits of " + PLAYER)));
        openAdventureLog(InterfaceID.MENU_NEW);

        handler.onGameStateChanged(GameState.LOADING);
        assertNull(pohOwner());

        openAdventureLog(InterfaceID.MENU_NEW);
        handler.onGameStateChanged(GameState.HOPPING);
        assertNull(pohOwner());
    }

    @Test
    public void matchAdventureLogOwnerIgnoresOtherText() {
        assertNull(WidgetEventHandler.matchAdventureLogOwner(null));
        assertNull(WidgetEventHandler.matchAdventureLogOwner("Counters"));
        assertEquals("A Name", WidgetEventHandler.matchAdventureLogOwner("The Exploits of A Name"));
    }

    private void openAdventureLog(int groupId) {
        WidgetLoaded loaded = new WidgetLoaded();
        loaded.setGroupId(groupId);
        handler.onWidgetLoaded(loaded);
        handler.onGameTick(new GameTick());
    }

    private String pohOwner() {
        try {
            Field field = WidgetEventHandler.class.getDeclaredField("pohOwner");
            field.setAccessible(true);
            return (String) field.get(handler);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Widget text(String value) {
        Widget widget = RuneLiteStubs.stub(Widget.class, "text:" + value);
        RuneLiteStubs.state(widget).put("getText", value);
        return widget;
    }

    private static Widget container(String ownText, Widget... children) {
        Widget widget = text(ownText);
        RuneLiteStubs.state(widget).put("getChildren", children);
        return widget;
    }
}
