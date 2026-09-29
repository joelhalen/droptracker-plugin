package io.droptracker.models;

import lombok.Getter;

import javax.annotation.Nullable;

/**
 * The structured half of an event pop-up. The classic card only ever needed
 * a title and a sentence; the showcase-style layouts lay the same news out in
 * parts (a small caption, a big headline, two "label: value" stat corners and
 * a progress line), so the renderer that composes the sentence fills these in
 * too. Every string is already sanitized by the time it lands here.
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
