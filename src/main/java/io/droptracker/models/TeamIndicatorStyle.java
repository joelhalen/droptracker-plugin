package io.droptracker.models;

/**
 * How a teammate's clan-chat line is badged during an event.
 *
 * ORB is the default because it is the same colored circle the team's Discord
 * channel carries, so the two surfaces read as one thing. It degrades to the
 * tag on its own when no sprite slot could be claimed, which is what lets the
 * badge work on a client where another plugin has taken the mod-icon budget.
 */
public enum TeamIndicatorStyle {
    OFF("Off"),
    TAG("Tag only"),
    ORB("Team color orb"),
    ORB_AND_TAG("Orb and tag");

    private final String label;

    TeamIndicatorStyle(String label) {
        this.label = label;
    }

    public boolean showsTag() {
        return this == TAG || this == ORB_AND_TAG;
    }

    public boolean showsOrb() {
        return this == ORB || this == ORB_AND_TAG;
    }

    @Override
    public String toString() {
        return label;
    }
}
