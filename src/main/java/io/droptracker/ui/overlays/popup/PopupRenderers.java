package io.droptracker.ui.overlays.popup;

import io.droptracker.models.EventPopupStyle;
import io.droptracker.util.*;

import javax.inject.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.*;

/** The renderer for each pop-up style; renderers are stateless, so one each. */
@Singleton
public class PopupRenderers {
    private final Map<EventPopupStyle, PopupRenderer> popups = new EnumMap<>(EventPopupStyle.class);

    @Inject
    public PopupRenderers(ItemImageCache images, RemoteImageCache remoteImages) {
        this(id -> images.get(id, null), path -> remoteImages.get(path, null));
    }

    /** Test seam: any sprite and icon lookups (the caches need a live client). */
    PopupRenderers(IntFunction<BufferedImage> items, Function<String, BufferedImage> paths) {
        PopupIcons icons = PopupIcons.of(items, paths);
        popups.put(EventPopupStyle.SHOWCASE, new ShowcaseRenderer(icons));
        popups.put(EventPopupStyle.BANNER, new BannerRenderer(icons));
    }

    public PopupRenderer popup(EventPopupStyle style) {
        return popups.getOrDefault(style, popups.get(EventPopupStyle.BANNER));
    }
}
