package io.droptracker.models;

/** How in-game event notifications manifest (config: eventDisplayMode). */
public enum EventDisplayMode {
    CHAT("Chat messages only"),
    POPUP("Chat + text pop-ups"),
    ENHANCED("Enhanced display (HUD)");

    private final String label;

    EventDisplayMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }

    public boolean popupsEnabled() {
        return this == POPUP || this == ENHANCED;
    }

    public boolean hudEnabled() {
        return this == ENHANCED;
    }
}
