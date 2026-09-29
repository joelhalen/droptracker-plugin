package io.droptracker.models;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Look of the pop-ups that hang beneath the Enhanced display HUD. Hidden
 * config (eventNudgeStyle) while the candidates are play-tested with
 * {@code ::dtpopup}; CLASSIC is the pre-6.0.13 nudge.
 */
public enum EventNudgeStyle {
    CLASSIC("Classic", "the original framed nudge"),
    TAB("Tab", "a drawer that unrolls from the HUD, part of the same frame"),
    COMPACT("Compact", "one slim line per update"),
    SHOWCASE_MINI("Mini showcase", "a HUD-width showcase panel with the framed icon");

    private final String label;
    private final String blurb;

    EventNudgeStyle(String label, String blurb) {
        this.label = label;
        this.blurb = blurb;
    }

    public String blurb() {
        return blurb;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public EventNudgeStyle next() {
        EventNudgeStyle[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    @Nullable
    public static EventNudgeStyle parse(String raw) {
        return StyleNames.parse(values(), raw);
    }

    @Override
    public String toString() {
        return label;
    }
}
