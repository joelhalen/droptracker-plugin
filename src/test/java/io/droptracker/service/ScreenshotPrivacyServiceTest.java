package io.droptracker.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import io.droptracker.models.PrivacyMode;
import io.droptracker.service.ScreenshotPrivacyService.CapturePlan;
import io.droptracker.testing.RuneLiteStubs;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the capture coordination that keeps a player's privacy mode honest.
 *
 * <p>The bug these exist for: a Broken dragon hook is both a screenshot-worthy
 * drop and a collection log unlock, so two submissions asked for proof of the
 * same kill. Each hid the private chat, captured, then unhid it — and the first
 * one's unhide landed while the second was still waiting for its frame, so the
 * second screenshot went out with the DMs in it.
 */
public class ScreenshotPrivacyServiceTest {

    /** Split private chat on: the PM overlay interface is loaded. */
    private static final boolean SPLIT_CHAT_ON = true;

    private Client client;
    private CountingDrawManager drawManager;
    private ScreenshotPrivacyService service;

    private final Map<Integer, Widget> widgets = new HashMap<>();
    private int tick;

    /** What the chat looked like in the frame the client last handed over. */
    private boolean pmChatHiddenInFrame;
    private boolean transcriptHiddenInFrame;

    @Before
    public void setUp() {
        widgets.clear();
        tick = 0;

        client = RuneLiteStubs.stub(Client.class, "client");
        Map<String, Object> state = RuneLiteStubs.state(client);
        state.put("isResized", true);
        state.put("getGameState", GameState.LOGGED_IN);
        state.put("getTickCount", (Function<Object[], Object>) args -> tick);
        state.put("getWidget", (Function<Object[], Object>) args -> args.length == 1
            ? widgets.get((Integer) args[0])
            : null);

        drawManager = new CountingDrawManager();
        service = new ScreenshotPrivacyService(client, new InlineClientThread(), drawManager, null);
    }

    // --- the reported leak -------------------------------------------------

    @Test
    public void twoSubmissionsFromOneKillShareOneCapture() {
        givenChat(SPLIT_CHAT_ON);

        Capture drop = capture(PrivacyMode.HIDE_DMS);
        Capture clog = capture(PrivacyMode.HIDE_DMS);

        service.onBeforeRender();
        BufferedImage frame = renderFrame();

        assertEquals("one capture request for both submissions", 1, drawManager.requests);
        assertSame(frame, drop.image);
        assertSame(frame, clog.image);
        assertTrue("private chat was hidden in the captured frame", pmChatHiddenInFrame);
    }

    @Test
    public void aSubmissionArrivingJustAfterACaptureReusesIt() {
        givenChat(SPLIT_CHAT_ON);

        Capture drop = capture(PrivacyMode.HIDE_DMS);
        service.onBeforeRender();
        BufferedImage frame = renderFrame();

        Capture clog = capture(PrivacyMode.HIDE_DMS);

        assertEquals("no second capture requested", 1, drawManager.requests);
        assertSame(frame, drop.image);
        assertSame("collection log rode the drop's frame", frame, clog.image);
    }

    @Test
    public void theWholeCycleLeavesTheChatAsItFoundIt() {
        givenChat(SPLIT_CHAT_ON);
        Widget pmChat = widgets.get(InterfaceID.PmChat.CONTAINER);

        capture(PrivacyMode.HIDE_DMS);
        service.onBeforeRender();
        renderFrame();

        assertFalse("private chat put back after the capture", pmChat.isSelfHidden());
    }

    @Test
    public void aWidgetTheGameHadHiddenIsNotRevealed() {
        givenChat(SPLIT_CHAT_ON);
        Widget transcript = widgets.get(InterfaceID.Chatbox.CHATDISPLAY);
        transcript.setHidden(true);

        capture(PrivacyMode.HIDE_MESSAGES_AND_DMS);
        service.onBeforeRender();
        renderFrame();

        assertTrue("a transcript the game hid stays hidden", transcript.isSelfHidden());
    }

    // --- privacy correctness -----------------------------------------------

    @Test
    public void withSplitChatOffHidingDmsHidesTheTranscriptTheyLiveIn() {
        givenChat(!SPLIT_CHAT_ON);
        Widget transcript = widgets.get(InterfaceID.Chatbox.CHATDISPLAY);

        Capture shot = capture(PrivacyMode.HIDE_DMS);
        service.onBeforeRender();
        renderFrame();

        assertTrue("DMs are transcript lines with split chat off", transcriptHiddenInFrame);
        assertFalse("and the transcript comes back", transcript.isSelfHidden());
    }

    @Test
    public void aStricterRequestDoesNotRideALooserFrame() {
        givenChat(SPLIT_CHAT_ON);

        Capture openShot = capture(PrivacyMode.NONE);
        Capture privateShot = capture(PrivacyMode.HIDE_DMS);

        service.onBeforeRender();
        BufferedImage first = renderFrame();

        assertFalse("nothing hidden for the None-mode shot", pmChatHiddenInFrame);
        assertSame(first, openShot.image);
        assertNull("the DM-hiding request did not take the unhidden frame", privateShot.image);

        service.onBeforeRender();
        BufferedImage second = renderFrame();

        assertEquals(2, drawManager.requests);
        assertSame(second, privateShot.image);
        assertTrue("its own frame hid the private chat", pmChatHiddenInFrame);
    }

    @Test
    public void hidingTheWholeChatboxInResizableModeIsAPlainHide() {
        givenChat(SPLIT_CHAT_ON);
        Widget universe = widgets.get(InterfaceID.Chatbox.UNIVERSE);

        capture(PrivacyMode.HIDE_CHATBOX);
        service.onBeforeRender();

        assertTrue("chatbox hidden for the shot", universe.isSelfHidden());

        renderFrame();

        assertFalse("and restored afterwards", universe.isSelfHidden());
    }

    // --- never leave a submission hanging ----------------------------------

    @Test
    public void aCaptureThatNeverRendersGivesUpRatherThanBlockingForever() {
        givenChat(SPLIT_CHAT_ON);
        Widget pmChat = widgets.get(InterfaceID.PmChat.CONTAINER);

        Capture shot = capture(PrivacyMode.HIDE_DMS);
        service.onBeforeRender();

        // Frames stop arriving (minimised client, GPU stall); ticks keep coming.
        // One retry, then the submission is answered with no image at all.
        tick += 50;
        service.onGameTick();
        assertNull(shot.image);

        tick += 50;
        service.onGameTick();

        assertTrue("consumer was called", shot.delivered);
        assertNull("with no image rather than an unhidden one", shot.image);
        assertFalse("and the chat was put back", pmChat.isSelfHidden());
    }

    // --- plan coverage ------------------------------------------------------

    @Test
    public void aPlanCoversAnythingItHidesAtLeastAsMuchOf() {
        CapturePlan dmsOnly = new CapturePlan(true, false, false, false);
        CapturePlan dmsAndTranscript = new CapturePlan(true, true, false, false);
        CapturePlan wholeChatbox = new CapturePlan(true, false, true, false);

        assertTrue(dmsOnly.covers(CapturePlan.NOTHING));
        assertTrue(dmsAndTranscript.covers(dmsOnly));
        assertFalse(dmsOnly.covers(dmsAndTranscript));

        assertTrue("hiding the chatbox hides the transcript in it", wholeChatbox.transcript);
        assertTrue(wholeChatbox.covers(dmsAndTranscript));
        assertFalse(dmsAndTranscript.covers(wholeChatbox));
    }

    // --- harness ------------------------------------------------------------

    /**
     * @param splitChat whether the split private chat overlay exists; with the
     *                  option off the client never loads that interface
     */
    private void givenChat(boolean splitChat) {
        if (splitChat) {
            widgets.put(InterfaceID.PmChat.CONTAINER, widget());
        }
        widgets.put(InterfaceID.Chatbox.CHATDISPLAY, widget());
        widgets.put(InterfaceID.Chatbox.UNIVERSE, widget());
    }

    private Capture capture(PrivacyMode mode) {
        Capture capture = new Capture();
        service.capture(mode, capture);
        return capture;
    }

    /**
     * Draws a frame and hands it to whoever asked for one, as the client does.
     * The chat's state is read as the frame is produced, since that is what the
     * pixels would show - the service unhides again as soon as it has the image.
     */
    private BufferedImage renderFrame() {
        BufferedImage frame = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        drawManager.processDrawComplete(() -> {
            pmChatHiddenInFrame = isHidden(InterfaceID.PmChat.CONTAINER);
            transcriptHiddenInFrame = isHidden(InterfaceID.Chatbox.CHATDISPLAY);
            return frame;
        });
        return frame;
    }

    private boolean isHidden(int componentId) {
        Widget widget = widgets.get(componentId);
        return widget != null && widget.isSelfHidden();
    }

    /** A widget stub whose hidden flag actually changes when it is set. */
    private static Widget widget() {
        Widget widget = RuneLiteStubs.stub(Widget.class, "widget");
        Map<String, Object> state = RuneLiteStubs.state(widget);
        state.put("isSelfHidden", false);
        state.put("isHidden", false);
        state.put("setHidden", (Function<Object[], Object>) args -> {
            state.put("isSelfHidden", args[0]);
            state.put("isHidden", args[0]);
            return null;
        });
        return widget;
    }

    /** Records what the consumer was given. */
    private static final class Capture implements Consumer<BufferedImage> {

        private BufferedImage image;
        private boolean delivered;

        @Override
        public void accept(BufferedImage image) {
            this.image = image;
            this.delivered = true;
        }
    }

    /** The real queue-and-drain behaviour, plus a count of how often it was asked. */
    private static final class CountingDrawManager extends DrawManager {

        private int requests;

        @Override
        public void requestNextFrameListener(Consumer<Image> consumer) {
            requests++;
            super.requestNextFrameListener(consumer);
        }
    }

    /** Runs client-thread work inline, as it would when already on that thread. */
    private static final class InlineClientThread extends ClientThread {

        @Override
        public void invoke(Runnable runnable) {
            runnable.run();
        }

        @Override
        public void invokeLater(Runnable runnable) {
            runnable.run();
        }
    }
}
