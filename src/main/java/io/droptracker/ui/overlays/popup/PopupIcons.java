package io.droptracker.ui.overlays.popup;

import io.droptracker.service.EventNotificationService.Toast;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.util.function.*;

/**
 * The picture for a pop-up: the item sprite when the toast names one (the
 * drop that caused it, or the task's own item), else the server icon for the
 * task or team (an NPC, a skill, a team piece), else null and the renderer
 * draws the kind's emblem. Both lookups are cache reads that never block:
 * a remote icon still downloading simply shows up a frame or two later.
 */
@FunctionalInterface
interface PopupIcons {
    @Nullable
    BufferedImage forToast(Toast toast);

    static PopupIcons of(IntFunction<BufferedImage> items, Function<String, BufferedImage> paths) {
        return toast -> {
            Integer itemId = PopupPaint.iconOf(toast);
            BufferedImage image = itemId != null ? items.apply(itemId) : null;
            if (image == null && toast.getIconPath() != null) {
                image = paths.apply(toast.getIconPath());
            }
            return image;
        };
    }
}
