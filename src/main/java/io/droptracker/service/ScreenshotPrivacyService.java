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
 */
@Slf4j
@Singleton
public class ScreenshotPrivacyService {

    /** Extra rendered frames to wait after hiding before capturing, so the 3D scene finishes growing. */
    private static final int SETTLE_FRAMES = 1;

    /** Give up and restore if the frame listener never fires (GPU stall, minimised client, ...). */
    private static final int MAX_HIDDEN_FRAMES = 120;

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

    private final Client client;
    private final ClientThread clientThread;
    private final DrawManager drawManager;
    private final SpriteManager spriteManager;

    private State state = State.IDLE;

    /** Bumped per capture so a late frame callback from an aborted cycle can be ignored. */
    private int cycleId;
    private int framesHidden;
    private boolean captureRequested;
    private boolean widgetsMutated;
    private boolean pmChatHiddenByUs;
    private boolean bordersDrawn;

    /** Everyone waiting on the in-flight fixed-mode cycle's frame. Client-thread confined. */
    private final List<Consumer<BufferedImage>> pendingConsumers = new ArrayList<>();

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
     * Captures the next rendered frame with the given privacy mode applied and
     * hands it to {@code consumer} (invoked on the frame-listener thread, so keep
     * heavy work off it). Callable from any thread.
     */
    public void capture(PrivacyMode mode, Consumer<BufferedImage> consumer) {
        clientThread.invoke(() -> captureOnClientThread(mode, consumer));
    }

    /** Restores any in-flight mutation; called from the plugin's shutDown. */
    public void shutDown() {
        clientThread.invoke(() -> {
            cycleId++;
            state = State.IDLE;
            captureRequested = false;
            pendingConsumers.clear();
            restoreWidgets();
            clearBorderSprites();
        });
    }

    private void captureOnClientThread(PrivacyMode mode, Consumer<BufferedImage> consumer) {
        if (state != State.IDLE) {
            // A fixed-mode cycle is mid-flight; share its frame rather than
            // fighting over the layout.
            pendingConsumers.add(consumer);
            return;
        }

        if (mode.hidesChatbox()) {
            final boolean chatboxInterfaceOpen = isChatboxInterfaceOpen();

            // Resizable layouts draw the chatbox as an overlay on the scene, so a
            // plain hide is enough - no viewport surgery. With a chatbox interface
            // open (a dialog, Make-X, bank search), degrade to transcript + DMs so
            // the thing the player is interacting with stays visible.
            if (client.isResized()) {
                simpleCapture(true, chatboxInterfaceOpen, !chatboxInterfaceOpen, consumer);
                return;
            }

            if (!chatboxInterfaceOpen && client.getGameState() == GameState.LOGGED_IN) {
                pendingConsumers.add(consumer);
                cycleId++;
                framesHidden = 0;
                captureRequested = false;
                state = State.HIDE_PENDING;
                return;
            }

            // Fixed mode but the full hide is unavailable: a plain hide would
            // leave a black hole where the chatbox was, so fall back to hiding
            // the transcript + DMs.
            simpleCapture(true, true, false, consumer);
            return;
        }

        simpleCapture(mode.hidesDms(), mode.hidesMessages(), false, consumer);
    }

    // region simple captures (any layout)

    /**
     * Hides up to three overlay widgets, captures the next frame, and unhides
     * exactly the ones this call hid - a widget the game itself had hidden (a
     * dialog replacing the transcript, split chat off) is left alone.
     */
    private void simpleCapture(boolean hideDms, boolean hideMessages, boolean hideWholeChatbox,
                               Consumer<BufferedImage> consumer) {
        final List<Widget> hidden = new ArrayList<>(3);
        if (hideDms) {
            hideIfVisible(InterfaceID.PmChat.CONTAINER, hidden);
        }
        if (hideMessages) {
            hideIfVisible(InterfaceID.Chatbox.CHATDISPLAY, hidden);
        }
        if (hideWholeChatbox) {
            hideIfVisible(InterfaceID.Chatbox.UNIVERSE, hidden);
        }

        drawManager.requestNextFrameListener(image -> {
            if (!hidden.isEmpty()) {
                clientThread.invoke(() -> hidden.forEach(w -> w.setHidden(false)));
            }
            consumer.accept(toBufferedImage(image));
        });
    }

    private void hideIfVisible(@Component int componentId, List<Widget> hidden) {
        final Widget widget = client.getWidget(componentId);
        if (widget != null && !widget.isSelfHidden()) {
            widget.setHidden(true);
            hidden.add(widget);
        }
    }

    // endregion

    // region fixed-mode render loop

    public void onBeforeRender() {
        if (state == State.IDLE) {
            return;
        }

        // The player resized or logged out mid-cycle; bail out rather than fight the layout.
        if (client.isResized() || client.getGameState() != GameState.LOGGED_IN) {
            abort("client left fixed mode mid-capture");
            return;
        }

        switch (state) {
            case HIDE_PENDING:
                applyHidden(true);
                state = State.HIDDEN;
                maybeRequestFrame();
                framesHidden++;
                break;

            case HIDDEN:
                // Widget scripts re-run constantly, so the mutation has to be reasserted per frame.
                applyHidden(false);
                maybeRequestFrame();
                framesHidden++;

                if (framesHidden > MAX_HIDDEN_FRAMES) {
                    abort("timed out after " + MAX_HIDDEN_FRAMES + " frames");
                }
                break;

            case RESTORE_PENDING:
                restoreWidgets();
                state = State.IDLE;
                break;

            default:
                break;
        }
    }

    private void maybeRequestFrame() {
        if (captureRequested || framesHidden < SETTLE_FRAMES) {
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

        state = State.RESTORE_PENDING;
        deliver(toBufferedImage(image));
    }

    private void deliver(BufferedImage image) {
        final List<Consumer<BufferedImage>> consumers = new ArrayList<>(pendingConsumers);
        pendingConsumers.clear();
        for (Consumer<BufferedImage> consumer : consumers) {
            try {
                consumer.accept(image);
            } catch (Exception e) {
                log.warn("screenshot consumer failed", e);
            }
        }
    }

    private void abort(String reason) {
        log.debug("fixed-mode chatbox capture aborted: {}", reason);
        cycleId++;
        state = State.IDLE;
        captureRequested = false;
        restoreWidgets();

        // The submissions attached to this cycle still need an image; fall back
        // to a plain capture with the transcript + DMs hidden.
        final List<Consumer<BufferedImage>> consumers = new ArrayList<>(pendingConsumers);
        pendingConsumers.clear();
        if (!consumers.isEmpty()) {
            simpleCapture(true, true, false, image -> consumers.forEach(consumer -> {
                try {
                    consumer.accept(image);
                } catch (Exception e) {
                    log.warn("screenshot consumer failed", e);
                }
            }));
        }
    }

    // endregion

    // region fixed-mode widget mutation (derived from Fixed Mode Hide Chat)

    private void applyHidden(boolean transition) {
        widgetsMutated = true;

        final Widget pmChat = client.getWidget(InterfaceID.PmChat.CONTAINER);
        if (pmChat != null && !pmChat.isSelfHidden()) {
            pmChat.setHidden(true);
            pmChatHiddenByUs = true;
        }

        setViewSizeTo(DEFAULT_VIEW_HEIGHT, EXPANDED_VIEW_HEIGHT);
        setWidgetsSizeTo(DEFAULT_VIEW_WIDGET_HEIGHT, EXPANDED_VIEW_WIDGET_HEIGHT);
        keepContainersOnScreen(transition);

        final Widget chatboxFrame = client.getWidget(ComponentID.CHATBOX_FRAME);
        if (chatboxFrame != null) {
            chatboxFrame.setHidden(true);
        }

        drawChatboxBorders();
    }

    private void restoreWidgets() {
        if (pmChatHiddenByUs) {
            pmChatHiddenByUs = false;
            final Widget pmChat = client.getWidget(InterfaceID.PmChat.CONTAINER);
            if (pmChat != null) {
                pmChat.setHidden(false);
            }
        }

        if (!widgetsMutated || client.isResized()) {
            widgetsMutated = false;
            return;
        }

        widgetsMutated = false;

        setViewSizeTo(EXPANDED_VIEW_HEIGHT, DEFAULT_VIEW_HEIGHT);
        setWidgetsSizeTo(EXPANDED_VIEW_WIDGET_HEIGHT, DEFAULT_VIEW_WIDGET_HEIGHT);
        keepContainersOnScreen(true);
        removeChatboxBorders();

        final Widget chatboxFrame = client.getWidget(ComponentID.CHATBOX_FRAME);
        if (chatboxFrame != null) {
            chatboxFrame.setHidden(false);
        }
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
