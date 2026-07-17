package io.droptracker.models;

/** How much the event HUD shows (config: eventHudDetail). */
public enum EventHudDetail {
    COMPACT("Compact"),
    DETAILED("Detailed");

    private final String label;

    EventHudDetail(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
