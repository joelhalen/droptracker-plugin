package io.droptracker.models.submissions;

public enum SubmissionType {
    DROP("Drop", "DROP"),
    KILL_TIME("Personal Best", "PB"),
    COLLECTION_LOG("Collection Log", "CLOG"),
    COMBAT_ACHIEVEMENT("Combat Achievement", "CA"),
    LEVEL_UP("Level Up", "LVL"),
    QUEST_COMPLETION("Quest Completion", "QST"),
    EXPERIENCE("Experience", "XP"),
    EXPERIENCE_MILESTONE("Experience Milestone", "XP"),
    EXPERIENCE_UPDATE("Experience Sync", "XP"),
    ADVENTURE_LOG("Adventure Log", "LOG"),
    PET("Pet", "PET"),
    DEATH,
    DIARY,
    SLAYER_TASK("Slayer Task", "SLAY"),
    CLAN_BROADCAST,
    CLAN_CHAT,
    CONFIG_SNAPSHOT;

    /** Display name and the short UI tag ("Submission" / "SUB" when unnamed). */
    public final String label;
    public final String shortLabel;

    SubmissionType() {
        this("Submission", "SUB");
    }

    SubmissionType(String label, String shortLabel) {
        this.label = label;
        this.shortLabel = shortLabel;
    }
}
