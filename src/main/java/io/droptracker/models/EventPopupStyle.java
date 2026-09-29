package io.droptracker.models;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Look of the stand-alone event pop-ups ("Chat + text pop-ups" display type).
 * Hidden config (eventPopupStyle) while the candidates are play-tested with
 * {@code ::dtpopup}; CLASSIC is the pre-6.0.13 card.
 */
public enum EventPopupStyle {
    CLASSIC("Classic", "the original small card"),
    SHOWCASE("Showcase", "big colour-coded panel, framed icon hanging off the bottom"),
    SHOWCASE_STONE("Showcase (stone)", "the showcase layout on a game-interface stone panel"),
    RIBBON("Ribbon", "slim banner that slides in, with a countdown line");

    private final String label;
    private final String blurb;

    EventPopupStyle(String label, String blurb) {
        this.label = label;
        this.blurb = blurb;
    }

    public String blurb() {
        return blurb;
    }

    /** The command-line name, e.g. "showcase_stone". */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public EventPopupStyle next() {
        EventPopupStyle[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** Matches a key ("ribbon"), a label or a 1-based number; null if none. */
    @Nullable
    public static EventPopupStyle parse(String raw) {
        return StyleNames.parse(values(), raw);
    }

    @Override
    public String toString() {
        return label;
    }
}
