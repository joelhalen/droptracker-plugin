package io.droptracker.models.submissions;
import lombok.Getter;

/**
 * Represents the lifecycle status of a ValidSubmission.
 */
public enum SubmissionStatus {
    /** Submission has been created and is about to be sent */
    PENDING("Sending...", "#FFFF00"),
    /** Submission is actively being sent over the network */
    SENDING("Sending...", "#FFFF00"),
    /** Submission was successfully delivered to the server */
    SENT("Sent successfully", "#00FF00"),
    /** Submission is being retried after a failure */
    RETRYING("Retrying...", "#FFFF00"),
    /** Submission was confirmed processed by the API */
    PROCESSED("Processed by API", "#00FF00"),
    /** Submission failed after exhausting retries */
    FAILED("Failed", "#FF0000");

    @Getter
    private final String description;
    @Getter
    private final String hexColor;

    SubmissionStatus(String description, String hexColor) {
        this.description = description;
        this.hexColor = hexColor;
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
