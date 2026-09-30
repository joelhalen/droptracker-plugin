package io.droptracker.ui.overlays.popup;

import io.droptracker.models.EventPopupStyle;
import io.droptracker.util.ItemImageCache;
import io.droptracker.util.RemoteImageCache;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;

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
