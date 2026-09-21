/*
 * Copyright (c) 2020 Tomas Slusny
 * Copyright (c) 2026 joelhalen
 *
 * The fixed-mode chatbox hiding / viewport expansion logic is derived from the
 * "Fixed Mode Hide Chat" plugin (https://github.com/deathbeam/example-plugin,
 * branch fixed-mode-hide-chat), licensed under the MIT license.
 */
package io.droptracker.service;

import com.google.common.collect.ImmutableSet;
import io.droptracker.models.PrivacyMode;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.SpritePixels;
import net.runelite.api.annotations.Component;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Captures a single frame with the widgets the user's {@link PrivacyMode} covers
 * hidden, then puts everything back.
 *
 * <p>DMs and chat messages are plain per-frame widget hides in any layout. Hiding
 * the entire chatbox is layout-dependent: in resizable modes the chatbox is an
 * overlay on the scene, so it too is a plain hide; in fixed mode the chatbox owns
 * the bottom 142px of the client, so hiding it means expanding the viewport into
 * the freed space, patching the window-frame border strips it used to cover, and
 * reasserting all of that every frame until the capture lands (widget scripts
 * re-run constantly and would revert the mutation). That machinery lives in the
 * {@link #onBeforeRender()} state machine, driven off the plugin's event bus
 * subscription.
 *
 * <p>When an interface is open inside the chatbox (a dialog, Make-X, bank
 * search), hiding the whole box would hide something the player is interacting
 * with, so the capture degrades to hiding the transcript + DMs for that shot.
 *
 * <p>Every capture in the plugin comes through here, and only one runs at a
 * time. That is a correctness requirement, not an optimisation: hiding is a flag
 * on a shared widget, so two captures overlapping meant the first one's restore
 * unhid the chat while the second was still waiting for its frame. See
 * {@link #capture(PrivacyMode, Consumer)}.
 */
@Slf4j
@Singleton
public class ScreenshotPrivacyService {

    /** Extra rendered frames to wait after hiding before capturing, so the 3D scene finishes growing. */
    private static final int SETTLE_FRAMES = 1;

    /** Give up and restore if the frame listener never fires (GPU stall, minimised client, ...). */
    private static final int MAX_HIDDEN_FRAMES = 120;

    /** Give up if the whole cycle stalls - ticks keep coming when frames do not. */
    private static final int MAX_CYCLE_TICKS = 20;

    /** Attempts one request gets before it is answered without an image. */
    private static final int MAX_ATTEMPTS = 2;

    /**
     * How long a frame already taken can stand in for a fresh request. One kill
     * commonly produces a drop submission and a collection log submission a beat
     * apart, and they are proof of the same moment - two captures would only mean
     * two rounds of hiding the chat, with the second racing the first's restore.
     * Two game ticks is long enough to pair them up and short enough that the
     * next unrelated event still gets its own frame.
     */
    private static final long REUSE_WINDOW_MS = 1200L;

    // Border sprites we synthesise to fill the window frame edges the chatbox used to cover.
    private static final int LEFT_BORDER_SPRITE_ID = -20611;
    private static final int RIGHT_BORDER_SPRITE_ID = -20612;
    private static final int CHATBOX_PARENT_WIDTH = 519;
    private static final int LEFT_BORDER_WIDTH = 4;
    private static final int RIGHT_BORDER_WIDTH = 3;
    private static final int BORDER_HEIGHT = 142;

    /*
     * The fixed viewport is 765x503. Of that, the 3D scene container is 334px tall
     * with the chatbox visible; hiding the chatbox frees the 142px it occupied and
     * lets the scene grow to 476px.
     */
    private static final int DEFAULT_VIEW_HEIGHT = 334;
    private static final int EXPANDED_VIEW_HEIGHT = 476;
    private static final int BANK_X = 12;
    private static final int BANK_Y = 2;
    private static final int SEED_VAULT_X = 6;
    private static final int SEED_VAULT_COMPONENT_ID = 41353217;

    // The view height minus BANK_Y minus 1, since there is a 1px gap at the bottom without the plugin.
    private static final int DEFAULT_VIEW_WIDGET_HEIGHT = DEFAULT_VIEW_HEIGHT - BANK_Y - 1;
    private static final int EXPANDED_VIEW_WIDGET_HEIGHT = EXPANDED_VIEW_HEIGHT - BANK_Y - 1;

    /** The fixed viewport's main container, whose static children make up the game scene area. */
    private static final Map.Entry<Integer, Integer> FIXED_MAIN = entry(net.runelite.api.widgets.InterfaceID.FIXED_VIEWPORT, 9);

    /**
     * Interfaces that live inside the chatbox. If any of these is open the chatbox
     * must stay visible, because hiding it would hide an interface the player is
     * interacting with.
     */
    private static final Set<Map.Entry<Integer, Integer>> CHATBOX_INTERFACE_WIDGETS = ImmutableSet
        .<Map.Entry<Integer, Integer>>builder()
        // 'Rub' option (S219.0) Duel rings, Slayer Rings, Burning Amulets, etc.
        .add(entry(net.runelite.api.widgets.InterfaceID.DIALOG_OPTION, 0))
        // Player dialog (S217.0) - when your player character speaks in a dialog
        .add(entry(net.runelite.api.widgets.InterfaceID.DIALOG_PLAYER, 0))
        // Sprite dialog (S193.0) - "Are you sure you want to drop..." valuable item warnings, etc.
        .add(entry(net.runelite.api.widgets.InterfaceID.DIALOG_SPRITE, 0))
        // Message box dialog (S229.0) - NPC popups with only a "Click here to continue" option.
        .add(entry(InterfaceID.MESSAGEBOX, 0))
        // Membership benefits prompt (S284.0) - "Become a Member" chatbox popup.
        .add(entry(InterfaceID.MEMBERSHIP_BENEFITS_PROMPT, 0))
        // Skill multi-choice dialog (S270.0) - "How many would you like to..." (cutting gems, etc.)
        .add(entry(InterfaceID.SKILLMULTI, 0))
        // Bank search container
        .add(entry(net.runelite.api.widgets.InterfaceID.CHATBOX, 42))
        // Catch-all layer we recurse through: wrong PIN popup, NPC dialog, Make-X, etc.
        .add(entry(net.runelite.api.widgets.InterfaceID.CHATBOX, 566))
        .add(entry(net.runelite.api.widgets.InterfaceID.CHATBOX, 43)) // GE search
        .add(entry(InterfaceID.CHAT_LEFT, 0))
        .add(entry(InterfaceID.CHATBOX, 48)) // mes layer scrollarea
        .build();

    /** Containers that overlap the chatbox area and must be resized along with the viewport. */
    private static final Set<Map.Entry<Integer, Integer>> TO_CONTRACT_WIDGETS = ImmutableSet
        .<Map.Entry<Integer, Integer>>builder()
        .add(entry(ComponentID.BANK_CONTAINER, 0))
        .add(entry(net.runelite.api.widgets.InterfaceID.SEED_VAULT, 1))
        .build();

    private enum State {
        IDLE,
        HIDE_PENDING,
        HIDDEN,
        RESTORE_PENDING
    }

    /**
     * What a capture has to keep out of the frame, independent of how it gets
     * there. {@code transcript} implies nothing about {@code chatbox} but the
     * reverse holds, so the constructor canonicalises it: coverage comparisons
     * between two plans are then a plain componentwise implication.
     */
    @VisibleForTesting
    static final class CapturePlan {

        static final CapturePlan NOTHING = new CapturePlan(false, false, false, false);

        final boolean dms;
        final boolean transcript;
        final boolean chatbox;
        /** Fixed mode only: hide the chatbox by growing the game view into its space. */
        final boolean expandViewport;

        CapturePlan(boolean dms, boolean transcript, boolean chatbox, boolean expandViewport) {
            this.dms = dms;
            this.transcript = transcript || chatbox;
            this.chatbox = chatbox;
            this.expandViewport = expandViewport;
        }

        /** Whether a frame captured under this plan is also acceptable for {@code other}. */
        boolean covers(CapturePlan other) {
            return (dms || !other.dms)
                && (transcript || !other.transcript)
                && (chatbox || !other.chatbox);
        }
    }

    /** One caller waiting for a frame. Client-thread confined. */
    private static final class Request {

        private final PrivacyMode mode;
        private final Consumer<BufferedImage> consumer;
        private int attempts;

        private Request(PrivacyMode mode, Consumer<BufferedImage> consumer) {
            this.mode = mode;
            this.consumer = consumer;
        }
    }

    private final Client client;
    private final ClientThread clientThread;
    private final DrawManager drawManager;
    private final SpriteManager spriteManager;

    /*
     * Everything below is client-thread confined: capture() marshals onto it, and
     * onBeforeRender / onGameTick / the frame callback all run there. No locking.
     */
    private State state = State.IDLE;

    /** Bumped per capture so a late frame callback from an aborted cycle can be ignored. */
    private int cycleId;
    private int framesHidden;
    private int cycleStartTick;
    private boolean captureRequested;
    private boolean widgetsMutated;
    private boolean bordersDrawn;

    /** What the in-flight cycle hides; null while {@link State#IDLE}. */
    private CapturePlan activePlan;

    /** Everyone riding the in-flight cycle's frame. Client-thread confined. */
    private final List<Request> pending = new ArrayList<>();

    /** Requests that need more hidden than the in-flight cycle hides. */
    private final List<Request> queued = new ArrayList<>();

    /** Widgets this service hid, so a restore puts back exactly those. */
    private final List<Widget> hiddenByUs = new ArrayList<>();

    private BufferedImage lastImage;
    private CapturePlan lastImagePlan;
    private long lastImageAt;

    private boolean borderSpritesLoaded;
    private SpritePixels lastWindowFrameLeftSprite;
    private SpritePixels lastSidePanelLeftUpperSprite;

    @Inject
    public ScreenshotPrivacyService(
        Client client,
        ClientThread clientThread,
        DrawManager drawManager,
        SpriteManager spriteManager
    ) {
        this.client = client;
        this.clientThread = clientThread;
        this.drawManager = drawManager;
        this.spriteManager = spriteManager;
    }

    private static Map.Entry<Integer, Integer> entry(int group, int child) {
        return new AbstractMap.SimpleEntry<>(group, child);
    }

    /**
     * Captures a frame with the given privacy mode applied and hands it to
     * {@code consumer}. Callable from any thread; the consumer runs on the client
     * thread, so keep heavy work off it.
     *
     * <p>Captures are single-flight. One kill can produce several submissions at
     * once - a drop over the screenshot threshold, the collection log slot it
     * filled, a personal best - and each of those asks for proof. They share one
     * frame: requests arriving while a capture is being set up ride it, and a
     * request arriving just after one completes is answered from
     * {@link #REUSE_WINDOW_MS the frame just taken} rather than making the client
     * draw, hide and restore all over again. Two overlapping captures used to
     * unhide each other's widgets mid-flight, which is how a screenshot with the
     * private chat still in it got submitted.
     *
     * <p>The consumer is passed {@code null} when no frame could be captured (the
     * client stopped rendering, or the layout kept moving under the capture). It
     * is never passed a frame with the privacy mode unapplied.
     */
    public void capture(PrivacyMode mode, Consumer<BufferedImage> consumer) {
        clientThread.invoke(() -> accept(new Request(mode, consumer)));
    }

    /** Restores any in-flight mutation; called from the plugin's shutDown. */
    public void shutDown() {
        clientThread.invoke(() -> {
            cycleId++;
            state = State.IDLE;
            activePlan = null;
            captureRequested = false;
            forgetLastImage();
            restoreWidgets();
            clearBorderSprites();

            // Answer anything still waiting rather than dropping it: the plugin
            // is going away, but the submission it belongs to can still be sent
            // without proof.
            final List<Request> waiting = new ArrayList<>(pending);
            waiting.addAll(queued);
            pending.clear();
            queued.clear();
            for (final Request request : waiting) {
                deliver(request, null);
            }
        });
    }

    /**
     * Watchdog, driven off the game tick because it keeps running when rendering
     * does not. A cycle that never sees another frame would otherwise hold every
     * later capture behind it forever.
     */
    public void onGameTick() {
        if (state != State.IDLE) {
            if (client.getTickCount() - cycleStartTick > MAX_CYCLE_TICKS) {
                abort("no rendered frame within " + MAX_CYCLE_TICKS + " ticks");
            }
            return;
        }

        // Nothing is holding a full-size frame open once it is too old to reuse.
        if (lastImage != null && elapsedSinceLastImage() > REUSE_WINDOW_MS) {
            forgetLastImage();
        }
        drainQueued();
    }

    private void accept(Request request) {
        final CapturePlan plan = planFor(request.mode);

        // The frame taken moments ago is the same moment this request is asking
        // about, and it already hides at least as much: reuse it.
        if (lastImage != null && lastImagePlan != null && lastImagePlan.covers(plan)
            && elapsedSinceLastImage() <= REUSE_WINDOW_MS) {
            deliver(request, lastImage);
            return;
        }

        if (state != State.IDLE) {
            if (activePlan.covers(plan)) {
                pending.add(request);
            } else {
                // Wants more hidden than the frame being set up; wait for the next cycle.
                queued.add(request);
            }
            return;
        }

        startCycle(plan, request);
    }

    private void startCycle(CapturePlan plan, Request first) {
        cycleId++;
        activePlan = plan;
        framesHidden = 0;
        captureRequested = false;
        cycleStartTick = client.getTickCount();
        hiddenByUs.clear();
        pending.add(first);
        // Nothing is hidden yet: the hide is applied from onBeforeRender so it
        // lands in the very frame that gets captured, rather than racing a draw
        // that may already be underway.
        state = State.HIDE_PENDING;
    }

    /**
     * Turns the player's configured mode into the set of things this capture must
     * keep out of the frame, given the layout in front of us right now.
     */
    private CapturePlan planFor(PrivacyMode mode) {
        if (mode == null || !mode.hidesDms()) {
            return CapturePlan.NOTHING;
        }

        // "Hide DMs" has to mean the DMs, wherever the client draws them. With
        // split private chat off there is no PM overlay at all and private
        // messages are ordinary transcript lines, so the transcript goes too -
        // otherwise the setting silently does nothing for those players.
        final boolean hideTranscript = mode.hidesMessages() || dmsAreInTranscript();

        if (!mode.hidesChatbox()) {
            return new CapturePlan(true, hideTranscript, false, false);
        }

        // Hiding the whole chatbox. With an interface open inside it (a dialog,
        // Make-X, bank search) that would hide something the player is using, so
        // the capture degrades to the transcript + DMs.
        if (isChatboxInterfaceOpen()) {
            return new CapturePlan(true, true, false, false);
        }

        // Resizable layouts draw the chatbox as an overlay on the scene, so a
        // plain hide is enough. Fixed mode needs the viewport surgery, and only
        // while logged in - a plain hide there would leave a black hole.
        if (client.isResized()) {
            return new CapturePlan(true, true, true, false);
        }
        if (client.getGameState() != GameState.LOGGED_IN) {
            return new CapturePlan(true, true, false, false);
        }
        return new CapturePlan(true, true, true, true);
    }

    /**
     * Whether private messages are rendered as ordinary chat transcript lines.
     * The split private chat overlay is its own interface: when the option is off
     * the client never loads it, so an absent widget is the signal that DMs are
     * inline. (With split chat on, the transcript still lists DMs under its
     * Private tab - "Hide messages + DMs" is the mode that covers that.)
     */
    private boolean dmsAreInTranscript() {
        return client.getWidget(InterfaceID.PmChat.CONTAINER) == null;
    }

    private long elapsedSinceLastImage() {
        return System.currentTimeMillis() - lastImageAt;
    }

    private void forgetLastImage() {
        lastImage = null;
        lastImagePlan = null;
        lastImageAt = 0L;
    }

    // region capture cycle

    public void onBeforeRender() {
        if (state == State.IDLE) {
            drainQueued();
            return;
        }

        // The player resized or logged out mid-cycle; bail out rather than fight
        // the layout. Only the viewport surgery cares - a plain widget hide works
        // in any layout.
        if (activePlan.expandViewport
            && (client.isResized() || client.getGameState() != GameState.LOGGED_IN)) {
            abort("client left fixed mode mid-capture");
            return;
        }

        switch (state) {
            case HIDE_PENDING:
                applyPlan(true);
                state = State.HIDDEN;
                maybeRequestFrame();
                framesHidden++;
                break;

            case HIDDEN:
                // Widget scripts re-run constantly, so the mutation has to be reasserted per frame.
                applyPlan(false);
                maybeRequestFrame();
                framesHidden++;

                if (framesHidden > MAX_HIDDEN_FRAMES) {
                    abort("timed out after " + MAX_HIDDEN_FRAMES + " frames");
                }
                break;

            case RESTORE_PENDING:
                restoreWidgets();
                finishCycle();
                break;

            default:
                break;
        }
    }

    /** Hides everything the active plan covers; safe to call every frame. */
    private void applyPlan(boolean transition) {
        if (activePlan.dms) {
            hideIfVisible(InterfaceID.PmChat.CONTAINER);
        }

        if (activePlan.chatbox) {
            if (activePlan.expandViewport) {
                expandOverChatbox(transition);
            } else {
                hideIfVisible(InterfaceID.Chatbox.UNIVERSE);
            }
        } else if (activePlan.transcript) {
            hideIfVisible(InterfaceID.Chatbox.CHATDISPLAY);
        }
    }

    /**
     * Hides one widget and remembers that we were the ones who did it, so the
     * restore puts back exactly what it took away - a widget the game itself had
     * hidden (a dialog replacing the transcript, split chat off) is left alone.
     * Already hidden by us means nothing to do; hidden again by a script that
     * re-ran means it comes back visible and gets re-hidden here.
     */
    private void hideIfVisible(@Component int componentId) {
        final Widget widget = client.getWidget(componentId);
        if (widget == null || widget.isSelfHidden()) {
            return;
        }

        widget.setHidden(true);
        for (final Widget alreadyTracked : hiddenByUs) {
            if (alreadyTracked == widget) {
                return;
            }
        }
        hiddenByUs.add(widget);
    }

    private void maybeRequestFrame() {
        // Only the viewport expansion needs a settling frame: the 3D scene has to
        // finish growing into the freed space before the shot is worth taking.
        final int settleFrames = activePlan.expandViewport ? SETTLE_FRAMES : 0;
        if (captureRequested || framesHidden < settleFrames) {
            return;
        }

        captureRequested = true;
        final int requestCycle = cycleId;
        drawManager.requestNextFrameListener(image -> onFrameReady(requestCycle, image));
    }

    /** Called on the client thread once the hidden frame has finished drawing. */
    private void onFrameReady(int requestCycle, Image image) {
        if (requestCycle != cycleId || state != State.HIDDEN) {
            // Stale callback from a cycle we already gave up on.
            return;
        }

        final BufferedImage captured = toBufferedImage(image);
        lastImage = captured;
        lastImagePlan = activePlan;
        lastImageAt = System.currentTimeMillis();

        // Detach this cycle's waiters before anything below can start the next
        // one, or a queued request that wanted more hidden than this frame hides
        // would be handed this frame anyway.
        final List<Request> waiting = new ArrayList<>(pending);
        pending.clear();

        state = State.RESTORE_PENDING;
        if (!activePlan.expandViewport) {
            // A plain widget hide can go back immediately now the frame is drawn;
            // only the viewport surgery has to unwind on a render pass.
            restoreWidgets();
            finishCycle();
        }

        for (final Request request : waiting) {
            deliver(request, captured);
        }
    }

    private void finishCycle() {
        state = State.IDLE;
        activePlan = null;
        captureRequested = false;
        drainQueued();
    }

    private void deliver(Request request, BufferedImage image) {
        try {
            request.consumer.accept(image);
        } catch (Exception e) {
            log.warn("screenshot consumer failed", e);
        }
    }

    /** Starts a cycle for anything that could not ride the last one. */
    private void drainQueued() {
        if (queued.isEmpty() || state != State.IDLE) {
            return;
        }

        final List<Request> batch = new ArrayList<>(queued);
        queued.clear();
        for (final Request request : batch) {
            accept(request);
        }
    }

    private void abort(String reason) {
        log.debug("screenshot privacy capture aborted: {}", reason);
        cycleId++;
        state = State.IDLE;
        activePlan = null;
        captureRequested = false;
        restoreWidgets();

        // Give each waiting request one more go with a freshly computed plan; the
        // client may simply have left fixed mode. Anything already retried is
        // answered with no image rather than left hanging - a submission without
        // proof beats a submission that never arrives, and it is never the
        // unhidden frame.
        final List<Request> waiting = new ArrayList<>(pending);
        pending.clear();
        for (final Request request : waiting) {
            if (++request.attempts >= MAX_ATTEMPTS) {
                deliver(request, null);
            } else {
                queued.add(request);
            }
        }

        drainQueued();
    }

    // endregion

    // region fixed-mode widget mutation (derived from Fixed Mode Hide Chat)

    /**
     * Fixed mode only: grow the game view into the 142px the chatbox occupies and
     * hide the frame around it. The DM/transcript hiding this rides on top of is
     * applied by {@link #applyPlan(boolean)}.
     */
    private void expandOverChatbox(boolean transition) {
        widgetsMutated = true;

        setViewSizeTo(DEFAULT_VIEW_HEIGHT, EXPANDED_VIEW_HEIGHT);
        setWidgetsSizeTo(DEFAULT_VIEW_WIDGET_HEIGHT, EXPANDED_VIEW_WIDGET_HEIGHT);
        keepContainersOnScreen(transition);

        hideIfVisible(ComponentID.CHATBOX_FRAME);

        drawChatboxBorders();
    }

    private void restoreWidgets() {
        for (final Widget widget : hiddenByUs) {
            widget.setHidden(false);
        }
        hiddenByUs.clear();

        if (!widgetsMutated || client.isResized()) {
            widgetsMutated = false;
            return;
        }

        widgetsMutated = false;

        setViewSizeTo(EXPANDED_VIEW_HEIGHT, DEFAULT_VIEW_HEIGHT);
        setWidgetsSizeTo(EXPANDED_VIEW_WIDGET_HEIGHT, DEFAULT_VIEW_WIDGET_HEIGHT);
        keepContainersOnScreen(true);
        removeChatboxBorders();
    }

    /**
     * The bank and seed vault containers drift off screen when the viewport
     * resizes under them, so their position is pinned explicitly on every frame
     * we touch the layout.
     */
    private void keepContainersOnScreen(boolean transition) {
        final Widget bankWidget = client.getWidget(ComponentID.BANK_CONTAINER);
        if (bankWidget != null && !bankWidget.isSelfHidden()) {
            // Re-run [clientscript,bankmain_init] so the tag tabs re-extend to the
            // new height. Only on the frames where the layout actually changes -
            // running it every frame tanks performance.
            if (transition) {
                client.createScriptEventBuilder(bankWidget.getOnLoadListener())
                    .setSource(bankWidget)
                    .build()
                    .run();
            }

            pinWidget(bankWidget, BANK_X);
        }

        final Widget seedVaultWidget = client.getWidget(SEED_VAULT_COMPONENT_ID);
        if (seedVaultWidget != null && !seedVaultWidget.isSelfHidden()) {
            pinWidget(seedVaultWidget, SEED_VAULT_X);
        }
    }

    private static void pinWidget(Widget widget, int x) {
        widget.setOriginalX(x);
        widget.setOriginalY(BANK_Y);
        widget.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
        widget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
        widget.revalidateScroll();
    }

    private static void setWidgetHeight(Widget widget, int height) {
        widget.setOriginalHeight(height);
        widget.setHeightMode(WidgetSizeMode.ABSOLUTE);
        widget.revalidateScroll();
    }

    private static void changeWidgetHeight(int originalHeight, int newHeight, Widget widget) {
        if (widget.getHeight() != originalHeight) {
            return;
        }

        setWidgetHeight(widget, newHeight);

        final Widget[] nestedChildren = widget.getNestedChildren();
        if (nestedChildren != null) {
            for (final Widget nestedChild : nestedChildren) {
                if (nestedChild != null && nestedChild.getHeight() == originalHeight) {
                    setWidgetHeight(nestedChild, newHeight);
                }
            }
        }

        final Widget[] dynamicChildren = widget.getDynamicChildren();
        if (dynamicChildren != null) {
            for (final Widget child : dynamicChildren) {
                if (child != null && child.getHeight() == originalHeight) {
                    setWidgetHeight(child, newHeight);
                }
            }
        }
    }

    private void setWidgetsSizeTo(int originalHeight, int newHeight) {
        for (final Map.Entry<Integer, Integer> entry : TO_CONTRACT_WIDGETS) {
            final Widget widget = entry.getValue() == 0
                ? client.getWidget(entry.getKey())
                : client.getWidget(entry.getKey(), entry.getValue());

            if (widget != null && !widget.isSelfHidden()) {
                changeWidgetHeight(originalHeight, newHeight, widget);
            }
        }
    }

    private void setViewSizeTo(int originalHeight, int newHeight) {
        final Widget viewport = client.getWidget(InterfaceID.Toplevel.MAIN);
        if (viewport != null) {
            setWidgetHeight(viewport, newHeight);
        }

        final Widget fixedMain = client.getWidget(FIXED_MAIN.getKey(), FIXED_MAIN.getValue());
        if (fixedMain == null || fixedMain.getHeight() != originalHeight) {
            return;
        }

        setWidgetHeight(fixedMain, newHeight);

        final Widget[] staticChildren = fixedMain.getStaticChildren();
        if (staticChildren == null) {
            return;
        }

        for (final Widget child : staticChildren) {
            if (child != null) {
                changeWidgetHeight(originalHeight, newHeight, child);
            }
        }
    }

    // endregion

    // region chatbox interface detection

    /**
     * True when something the player is interacting with lives inside the
     * chatbox - a dialog, the bank search, Make-X, a fairy ring code entry.
     * Hiding the chatbox would hide it too.
     */
    private boolean isChatboxInterfaceOpen() {
        final Widget fairyRingSearch = client.getWidget(net.runelite.api.widgets.InterfaceID.CHATBOX, 38);
        if (fairyRingSearch != null) {
            final Widget[] children = fairyRingSearch.getDynamicChildren();
            if (children != null && children.length > 0 && children[0] != null
                && children[0].getText() != null && children[0].getText().contains("fairy")) {
                return true;
            }
        }

        for (final Map.Entry<Integer, Integer> entry : CHATBOX_INTERFACE_WIDGETS) {
            final Widget widget = client.getWidget(entry.getKey(), entry.getValue());
            if (widget == null || widget.isSelfHidden()) {
                continue;
            }

            final Widget[] staticChildren = widget.getStaticChildren();
            final Widget[] nestedChildren = widget.getNestedChildren();

            final boolean found;
            if (staticChildren != null && staticChildren.length > 0) {
                found = anyVisible(staticChildren);
            } else if (nestedChildren != null && nestedChildren.length > 0) {
                found = anyVisible(nestedChildren);
            } else {
                found = isVisible(widget);
            }

            if (found) {
                return true;
            }
        }

        return false;
    }

    private static boolean anyVisible(Widget[] widgets) {
        return Arrays.stream(widgets).anyMatch(ScreenshotPrivacyService::isVisible);
    }

    private static boolean isVisible(Widget widget) {
        return widget != null && !widget.isSelfHidden() && !widget.isHidden();
    }

    // endregion

    // region chatbox border sprites

    /**
     * The chatbox covers 4px of window frame on the left and 3px on the right.
     * Once it is hidden those strips are empty, so the equivalent slices are
     * cropped out of the client's own frame sprites (respecting resource packs)
     * and drawn as children of the chatbox parent.
     */
    private void drawChatboxBorders() {
        final Widget chatbox = client.getWidget(ComponentID.CHATBOX_PARENT);
        if (chatbox == null || !updateBorderSprites() || chatbox.getChild(1) != null) {
            return;
        }

        final Widget leftBorder = chatbox.createChild(-1, WidgetType.GRAPHIC);
        leftBorder.setSpriteId(LEFT_BORDER_SPRITE_ID);
        leftBorder.setOriginalWidth(LEFT_BORDER_WIDTH);
        leftBorder.setOriginalHeight(BORDER_HEIGHT);
        leftBorder.setOriginalX(0);
        leftBorder.setOriginalY(0);
        leftBorder.setHidden(false);
        leftBorder.revalidate();

        final Widget rightBorder = chatbox.createChild(-1, WidgetType.GRAPHIC);
        rightBorder.setSpriteId(RIGHT_BORDER_SPRITE_ID);
        rightBorder.setOriginalWidth(RIGHT_BORDER_WIDTH);
        rightBorder.setOriginalHeight(BORDER_HEIGHT);
        rightBorder.setOriginalX(CHATBOX_PARENT_WIDTH - RIGHT_BORDER_WIDTH);
        rightBorder.setOriginalY(0);
        rightBorder.setHidden(false);
        rightBorder.revalidate();

        bordersDrawn = true;
    }

    private void removeChatboxBorders() {
        if (!bordersDrawn) {
            return;
        }

        bordersDrawn = false;

        final Widget chatbox = client.getWidget(ComponentID.CHATBOX_PARENT);
        if (chatbox != null && chatbox.getChild(1) != null) {
            chatbox.deleteAllChildren();
        }
    }

    private boolean updateBorderSprites() {
        final SpritePixels windowFrameLeftSprite = client.getSpriteOverrides().get(SpriteID.BACKLEFT1);
        final SpritePixels sidePanelLeftUpperSprite = client.getSpriteOverrides().get(SpriteID.SIDE_BACKGROUND_LEFT1);

        if (borderSpritesLoaded
            && windowFrameLeftSprite == lastWindowFrameLeftSprite
            && sidePanelLeftUpperSprite == lastSidePanelLeftUpperSprite) {
            return true;
        }

        final BufferedImage windowFrameLeft = windowFrameLeftSprite != null
            ? windowFrameLeftSprite.toBufferedImage()
            : spriteManager.getSprite(SpriteID.BACKLEFT1, 0);
        final BufferedImage sidePanelLeftUpper = sidePanelLeftUpperSprite != null
            ? sidePanelLeftUpperSprite.toBufferedImage()
            : spriteManager.getSprite(SpriteID.SIDE_BACKGROUND_LEFT1, 0);

        if (windowFrameLeft == null
            || windowFrameLeft.getWidth() < LEFT_BORDER_WIDTH
            || windowFrameLeft.getHeight() <= 0
            || sidePanelLeftUpper == null
            || sidePanelLeftUpper.getWidth() < RIGHT_BORDER_WIDTH
            || sidePanelLeftUpper.getHeight() <= 0) {
            // SpriteManager loads asynchronously; try again next frame.
            return false;
        }

        final int leftSourceY = Math.max(0, windowFrameLeft.getHeight() - BORDER_HEIGHT);
        final BufferedImage leftBorder = flipVertically(cropSprite(windowFrameLeft, leftSourceY, LEFT_BORDER_WIDTH));
        final BufferedImage rightBorder = cropSprite(sidePanelLeftUpper, 0, RIGHT_BORDER_WIDTH);

        client.getSpriteOverrides().put(LEFT_BORDER_SPRITE_ID, ImageUtil.getImageSpritePixels(leftBorder, client));
        client.getSpriteOverrides().put(RIGHT_BORDER_SPRITE_ID, ImageUtil.getImageSpritePixels(rightBorder, client));

        lastWindowFrameLeftSprite = windowFrameLeftSprite;
        lastSidePanelLeftUpperSprite = sidePanelLeftUpperSprite;
        borderSpritesLoaded = true;
        client.getWidgetSpriteCache().reset();
        return true;
    }

    private void clearBorderSprites() {
        client.getSpriteOverrides().remove(LEFT_BORDER_SPRITE_ID);
        client.getSpriteOverrides().remove(RIGHT_BORDER_SPRITE_ID);
        borderSpritesLoaded = false;
        lastWindowFrameLeftSprite = null;
        lastSidePanelLeftUpperSprite = null;
        client.getWidgetSpriteCache().reset();
    }

    private static BufferedImage cropSprite(BufferedImage source, int sourceY, int width) {
        final int sourceHeight = Math.min(BORDER_HEIGHT, source.getHeight() - sourceY);
        final BufferedImage cropped = new BufferedImage(width, BORDER_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = cropped.createGraphics();
        graphics.drawImage(source, 0, 0, width, BORDER_HEIGHT, 0, sourceY, width, sourceY + sourceHeight, null);
        graphics.dispose();
        return cropped;
    }

    private static BufferedImage flipVertically(BufferedImage source) {
        final BufferedImage flipped = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = flipped.createGraphics();
        graphics.drawImage(source, 0, source.getHeight(), source.getWidth(), -source.getHeight(), null);
        graphics.dispose();
        return flipped;
    }

    // endregion

    private static BufferedImage toBufferedImage(Image image) {
        if (image instanceof BufferedImage) {
            return (BufferedImage) image;
        }

        final BufferedImage copy = new BufferedImage(
            image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = copy.createGraphics();
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return copy;
    }
}
