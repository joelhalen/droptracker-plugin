package io.droptracker.models.submissions;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the {@link SubmissionStatus} lifecycle predicates that drive retry
 * eligibility and panel polling. isActive/isTerminal partition the retry
 * poller's view of a submission, so a misclassified state either spins
 * forever or drops a submission silently.
 */
public class SubmissionStatusTest {

    @Test
    public void terminalStatesAreProcessedAndFailed() {
        assertTrue(SubmissionStatus.PROCESSED.isTerminal());
        assertTrue(SubmissionStatus.FAILED.isTerminal());
        assertFalse(SubmissionStatus.PENDING.isTerminal());
        assertFalse(SubmissionStatus.SENDING.isTerminal());
        assertFalse(SubmissionStatus.SENT.isTerminal());
        assertFalse(SubmissionStatus.RETRYING.isTerminal());
    }

    @Test
    public void activeStatesAreTheInProgressSet() {
        assertTrue(SubmissionStatus.PENDING.isActive());
        assertTrue(SubmissionStatus.SENDING.isActive());
        assertTrue(SubmissionStatus.SENT.isActive());
        assertTrue(SubmissionStatus.RETRYING.isActive());
        assertFalse(SubmissionStatus.PROCESSED.isActive());
        assertFalse(SubmissionStatus.FAILED.isActive());
    }

    @Test
    public void retryableStatesAreFailedSentAndPending() {
        assertTrue(SubmissionStatus.FAILED.canRetry());
        assertTrue(SubmissionStatus.SENT.canRetry());
        assertTrue(SubmissionStatus.PENDING.canRetry());
        assertFalse(SubmissionStatus.SENDING.canRetry());
        assertFalse(SubmissionStatus.RETRYING.canRetry());
        assertFalse(SubmissionStatus.PROCESSED.canRetry());
    }
}
