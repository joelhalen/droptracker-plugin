package io.droptracker.models;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Look of the on-screen event pop-ups. Not a setting of its own: the
 * "Display type" picks it ({@link EventDisplayMode#popupStyle()}), so
 * "Chat + text pop-ups" gets the slim banner and "Enhanced display" the
 * showcase panel. {@code ::dtpopup demo} pins one per preview.
 */
public enum EventPopupStyle {
    SHOWCASE("Showcase"),
    BANNER("Banner");

    private final String label;

    EventPopupStyle(String label) {
        this.label = label;
    }

    /** "showcase" / "banner", or null. */
    @Nullable
    public static EventPopupStyle parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String wanted = raw.trim().toLowerCase(Locale.ROOT);
        for (EventPopupStyle style : values()) {
            if (style.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                return style;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return label;
    }
}
