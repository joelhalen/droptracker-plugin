package io.droptracker.models.submissions;

import java.awt.Color;

/**
 * Represents the lifecycle status of a ValidSubmission.
 */
public enum SubmissionStatus {
    /** Submission has been created and is about to be sent */
    PENDING("Sending...", "#FFFF00", new Color(255, 255, 0)),
    /** Submission is actively being sent over the network */
    SENDING("Sending...", "#FFFF00", new Color(255, 255, 0)),
    /** Submission was successfully delivered to the server */
    SENT("Sent successfully", "#00FF00", new Color(0, 255, 0)),
    /** Submission is being retried after a failure */
    RETRYING("Retrying...", "#FFFF00", new Color(255, 255, 0)),
    /** Submission was confirmed processed by the API */
    PROCESSED("Processed by API", "#00FF00", new Color(0, 255, 0)),
    /** Submission failed after exhausting retries */
    FAILED("Failed", "#FF0000", new Color(255, 0, 0));

    private final String description;
    private final String hexColor;
    private final Color awtColor;

    SubmissionStatus(String description, String hexColor, Color awtColor) {
        this.description = description;
        this.hexColor = hexColor;
        this.awtColor = awtColor;
    }

    public String getDescription() {
        return description;
    }

    public String getHexColor() {
        return hexColor;
    }

    public Color getAwtColor() {
        return awtColor;
    }

    /**
     * Whether this status represents a terminal state that should not be automatically retried
     */
    public boolean isTerminal() {
        return this == PROCESSED || this == FAILED;
    }

    /**
     * Whether this status represents an active/in-progress state that should be polled
     */
    public boolean isActive() {
        return this == PENDING || this == SENDING || this == SENT || this == RETRYING;
    }

    /**
     * Whether this submission can be manually retried by the user
     */
    public boolean canRetry() {
        return this == FAILED || this == SENT || this == PENDING;
    }
}
