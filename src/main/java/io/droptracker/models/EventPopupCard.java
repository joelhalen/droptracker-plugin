package io.droptracker.models;

import lombok.Getter;

import javax.annotation.Nullable;

/**
 * The structured half of an event pop-up. The chat line is one sentence; the
 * pop-ups lay the same news out in parts (a small caption, a big headline,
 * up to four "label: value" stats around the icon, a progress line and a
 * corner note), so the renderer that composes the sentence fills these in
 * too. Every string is already sanitized by the time it lands here.
 *
 * <p>Stats sit in the showcase's corners: left and right on the first row,
 * left2 and right2 under them. Setters skip null values, so a card can be
 * built in one expression from optional server fields.
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
    private final String caption;
    /** The big line: the task, the team, the event. */
    private final String headline;
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

    public EventPopupCard left(String label, @Nullable String value) {
        if (value != null) {
            this.leftLabel = label;
            this.leftValue = value;
        }
        return this;
    }

    public EventPopupCard right(String label, @Nullable String value) {
        if (value != null) {
            this.rightLabel = label;
            this.rightValue = value;
        }
        return this;
    }

    public EventPopupCard left2(String label, @Nullable String value) {
        if (value != null) {
            this.left2Label = label;
            this.left2Value = value;
        }
        return this;
    }

    public EventPopupCard right2(String label, @Nullable String value) {
        if (value != null) {
            this.right2Label = label;
            this.right2Value = value;
        }
        return this;
    }

    /** Fills the first empty stat slot (left, right, left2, right2). */
    public EventPopupCard extra(String label, @Nullable String value) {
        if (value == null) {
            return this;
        }
        if (leftValue == null) {
            return left(label, value);
        }
        if (rightValue == null) {
            return right(label, value);
        }
        if (left2Value == null) {
            return left2(label, value);
        }
        if (right2Value == null) {
            return right2(label, value);
        }
        return this;
    }

    public EventPopupCard note(@Nullable String note) {
        if (note != null) {
            this.note = note;
        }
        return this;
    }

    public EventPopupCard iconPath(@Nullable String iconPath) {
        this.iconPath = iconPath;
        return this;
    }

    public boolean hasStats() {
        return leftValue != null || rightValue != null || left2Value != null || right2Value != null;
    }

    public boolean hasSecondRow() {
        return left2Value != null || right2Value != null;
    }

    public EventPopupCard progress(@Nullable Long have, @Nullable Long need) {
        if (have != null && need != null && need > 0) {
            this.have = have;
            this.need = need;
        }
        return this;
    }

    /** Progress as 0..1, or -1 when the card carries none. */
    public float fraction() {
        if (have == null || need == null || need <= 0) {
            return -1f;
        }
        return Math.max(0f, Math.min(1f, have / (float) need));
    }
}
