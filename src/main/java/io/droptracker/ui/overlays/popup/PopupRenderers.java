package io.droptracker.ui.overlays.popup;

import io.droptracker.models.EventNudgeStyle;
import io.droptracker.models.EventPopupStyle;
import io.droptracker.util.ItemImageCache;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.IntFunction;

/** The renderer for each pop-up style; renderers are stateless, so one each. */
@Singleton
public class PopupRenderers {
    private final Map<EventPopupStyle, PopupRenderer> popups = new EnumMap<>(EventPopupStyle.class);
    private final Map<EventNudgeStyle, PopupRenderer> nudges = new EnumMap<>(EventNudgeStyle.class);

    @Inject
    public PopupRenderers(ItemImageCache images) {
        this(id -> images.get(id, null));
    }

    /** Test seam: any sprite lookup (the cache needs a live client). */
    PopupRenderers(IntFunction<BufferedImage> images) {
        popups.put(EventPopupStyle.CLASSIC, new ClassicCardRenderer(images));
        popups.put(EventPopupStyle.SHOWCASE, new ShowcaseRenderer(images, false, 320));
        popups.put(EventPopupStyle.SHOWCASE_STONE, new ShowcaseRenderer(images, true, 320));
        popups.put(EventPopupStyle.RIBBON, new RibbonRenderer(images));
        nudges.put(EventNudgeStyle.CLASSIC, new ClassicNudgeRenderer(images));
        nudges.put(EventNudgeStyle.TAB, new TabNudgeRenderer(images));
        nudges.put(EventNudgeStyle.COMPACT, new CompactNudgeRenderer(images));
        nudges.put(EventNudgeStyle.SHOWCASE_MINI, new ShowcaseRenderer(images, false, 200));
    }

    public PopupRenderer popup(EventPopupStyle style) {
        return popups.getOrDefault(style, popups.get(EventPopupStyle.CLASSIC));
    }

    public PopupRenderer nudge(EventNudgeStyle style) {
        return nudges.getOrDefault(style, nudges.get(EventNudgeStyle.CLASSIC));
    }
}
