package io.droptracker.models;

import lombok.Getter;

import javax.annotation.Nullable;

/**
 * The structured half of an event pop-up. The chat line is one sentence; the
 * pop-ups lay the same news out in parts (a small caption, a big headline,
 * up to four "label: value" stats around the icon, a progress line and a
 * corner note). The server fills these in (the JSON keys are these field
 * names); {@link #sanitize} runs before anything is drawn.
 *
 * <p>Stats sit in the showcase's corners: left and right on the first row,
 * left2 and right2 under them.
 */
@Getter
public class EventPopupCard {
    /** What kind of news this is; drives the colour scheme. */
    public enum Kind {
        COMPLETE,
        TILE,
        PROGRESS,
        LEAD,
        LINE,
        BLACKOUT,
        STARTED,
        ENDED,
        BOARD,
        ROLL,
        DIGEST
    }

    private final Kind kind;
    /** Small line above the headline, e.g. "Tile complete". */
    private String caption;
    /** The big line: the task, the team, the event. */
    private String headline;
    /** Optional small line under the headline (the item received, the event). */
    @Nullable
    private String detail;
    @Nullable
    private String leftLabel;
    @Nullable
    private String leftValue;
    @Nullable
    private String rightLabel;
    @Nullable
    private String rightValue;
    @Nullable
    private String left2Label;
    @Nullable
    private String left2Value;
    @Nullable
    private String right2Label;
    @Nullable
    private String right2Value;
    /** Small corner note, e.g. "2d 4h left"; null when there is none. */
    @Nullable
    private String note;
    /** Remote icon path ({@code /img/}-relative) when there is no item sprite. */
    @Nullable
    private String iconPath;
    /** Progress toward the task target; both null when there is none. */
    @Nullable
    private Long have;
    @Nullable
    private Long need;

    public EventPopupCard(Kind kind, String caption, String headline) {
        this.kind = kind;
        this.caption = caption;
        this.headline = headline;
    }

    public EventPopupCard detail(@Nullable String detail) {
        this.detail = detail;
        return this;
    }

    /** Runs every text slot through {@code clean} (tag strip + length cap). */
    public void sanitize(java.util.function.UnaryOperator<String> clean) {
        caption = clean.apply(caption);
        headline = clean.apply(headline);
        detail = clean.apply(detail);
        leftLabel = clean.apply(leftLabel);
        leftValue = clean.apply(leftValue);
        rightLabel = clean.apply(rightLabel);
        rightValue = clean.apply(rightValue);
        left2Label = clean.apply(left2Label);
        left2Value = clean.apply(left2Value);
        right2Label = clean.apply(right2Label);
        right2Value = clean.apply(right2Value);
        iconPath = clean.apply(iconPath);
    }

    public EventPopupCard note(@Nullable String note) {
        if (note != null) {
            this.note = note;
        }
        return this;
    }

    public boolean hasStats() {
        return leftValue != null || rightValue != null || left2Value != null || right2Value != null;
    }

    public boolean hasSecondRow() {
        return left2Value != null || right2Value != null;
    }

    /** Progress as 0..1, or -1 when the card carries none. */
    public float fraction() {
        if (have == null || need == null || need <= 0) {
            return -1f;
        }
        return Math.max(0f, Math.min(1f, have / (float) need));
    }
}
