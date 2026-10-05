package io.droptracker.ui.overlays.popup;

import io.droptracker.service.EventNotificationService.Toast;

import java.awt.Graphics2D;

/**
 * One pop-up layout. Called every frame from an overlay's render on the
 * client thread; animation is driven purely by the toast's age, so a
 * renderer holds no per-toast state.
 */
public interface PopupRenderer {
    /** Width of a stand-alone card. HUD nudges ignore it and fill the HUD's width. */
    int preferredWidth();

    /** Space left above this card when it stacks under another (or under the HUD). */
    default int gap() {
        return 6;
    }

    /** How many cards of this style may show at once. */
    default int maxVisible() {
        return 3;
    }

    /**
     * Paints the toast with its top-left corner at ({@code x}, {@code top})
     * and returns the height it takes up in the stack.
     */
    int draw(Graphics2D g, Toast toast, int x, int top, int width, long now);
}
