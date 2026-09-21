package io.droptracker.models;

/** What gets hidden from the frame a screenshot captures (config: privacyMode). */
public enum PrivacyMode {
    NONE("None"),
    HIDE_DMS("Hide DMs"),
    HIDE_MESSAGES_AND_DMS("Hide messages + DMs"),
    HIDE_CHATBOX("Hide entire chatbox");

    private final String label;

    PrivacyMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }

    /** Every mode above NONE hides split private messages. */
    public boolean hidesDms() {
        return this != NONE;
    }

    /** Whether the chat transcript and typed input line are hidden. */
    public boolean hidesMessages() {
        return this == HIDE_MESSAGES_AND_DMS || this == HIDE_CHATBOX;
    }

    /** Whether the whole chatbox disappears from the shot. */
    public boolean hidesChatbox() {
        return this == HIDE_CHATBOX;
    }
}
