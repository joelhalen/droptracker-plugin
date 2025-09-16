package io.droptracker.service;

import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages a queue of failed submissions for retry processing
 */
@Slf4j
@Singleton
public class SubmissionQueue {
    
    private final BlockingQueue<QueuedSubmission> submissionQueue;
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final AtomicLong totalQueued = new AtomicLong(0);
    private final AtomicLong totalProcessed = new AtomicLong(0);
    
    // Configuration
    private static final int MAX_QUEUE_SIZE = 500;
    private static final int MAX_RETRY_ATTEMPTS = 5;
    
    @Inject
    public SubmissionQueue() {
        this.submissionQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    }
    
    /**
     * Add a failed submission to the retry queue
     */
    public boolean enqueue(CustomWebhookBody webhook, byte[] screenshot, SubmissionType type, String failureReason) {
        if (queueSize.get() >= MAX_QUEUE_SIZE) {
            log.warn("Submission queue is full ({}), dropping submission", MAX_QUEUE_SIZE);
            return false;
        }
        
        QueuedSubmission submission = new QueuedSubmission(
            webhook, 
            screenshot, 
            type, 
            failureReason,
            Instant.now()
        );
        
        boolean added = submissionQueue.offer(submission);
        if (added) {
            queueSize.incrementAndGet();
            totalQueued.incrementAndGet();
            
            log.debug("Queued {} submission for retry. Queue size: {}", 
                    type, queueSize.get());
        } else {
            log.warn("Failed to add submission to queue");
        }
        
        return added;
    }
    
    /**
     * Get the next submission to retry
     */
    public QueuedSubmission dequeue() {
        QueuedSubmission submission = submissionQueue.poll();
        if (submission != null) {
            queueSize.decrementAndGet();
            totalProcessed.incrementAndGet();
        }
        return submission;
    }
    
    /**
     * Peek at the next submission without removing it
     */
    public QueuedSubmission peek() {
        return submissionQueue.peek();
    }
    
    /**
     * Re-queue a submission that failed retry (with incremented attempt count)
     */
    public boolean requeue(QueuedSubmission submission) {
        if (submission.getRetryAttempts() >= MAX_RETRY_ATTEMPTS) {
            log.warn("Dropping submission after {} retry attempts: {}", 
                    MAX_RETRY_ATTEMPTS, submission.getType());
            return false;
        }
        
        if (queueSize.get() >= MAX_QUEUE_SIZE) {
            log.warn("Cannot requeue submission, queue is full");
            return false;
        }
        
        submission.incrementRetryAttempts();
        submission.setLastRetryTime(Instant.now());
        
        boolean added = submissionQueue.offer(submission);
        if (added) {
            queueSize.incrementAndGet();
            log.debug("Requeued {} submission (attempt {}). Queue size: {}", 
                    submission.getType(), submission.getRetryAttempts(), queueSize.get());
        }
        
        return added;
    }
    
    /**
     * Clear all queued submissions
     */
    public void clear() {
        int cleared = submissionQueue.size();
        submissionQueue.clear();
        queueSize.set(0);
        
        if (cleared > 0) {
            log.info("Cleared {} queued submissions", cleared);
        }
    }
    
    /**
     * Get current queue statistics
     */
    public QueueStats getStats() {
        return new QueueStats(
            queueSize.get(),
            totalQueued.get(),
            totalProcessed.get(),
            MAX_QUEUE_SIZE
        );
    }
    
    /**
     * Check if the queue is empty
     */
    public boolean isEmpty() {
        return submissionQueue.isEmpty();
    }
    
    /**
     * Check if the queue is full
     */
    public boolean isFull() {
        return queueSize.get() >= MAX_QUEUE_SIZE;
    }
    
    /**
     * Get the current size of the queue
     */
    public int size() {
        return queueSize.get();
    }
    
    /**
     * Data class representing a queued submission
     */
    @Data
    public static class QueuedSubmission {
        private final CustomWebhookBody webhook;
        private final byte[] screenshot;
        private final SubmissionType type;
        private final String originalFailureReason;
        private final Instant queuedTime;
        
        private int retryAttempts = 0;
        private Instant lastRetryTime;
        private String lastRetryFailureReason;
        
        public QueuedSubmission(CustomWebhookBody webhook, byte[] screenshot, 
                               SubmissionType type, String failureReason, Instant queuedTime) {
            this.webhook = webhook;
            this.screenshot = screenshot;
            this.type = type;
            this.originalFailureReason = failureReason;
            this.queuedTime = queuedTime;
        }
        
        public void incrementRetryAttempts() {
            this.retryAttempts++;
        }
        
        public boolean hasScreenshot() {
            return screenshot != null && screenshot.length > 0;
        }
        
        public long getAgeMs() {
            return Instant.now().toEpochMilli() - queuedTime.toEpochMilli();
        }
        
        public boolean isExpired(long maxAgeMs) {
            return getAgeMs() > maxAgeMs;
        }
    }
    
    /**
     * Data class for queue statistics
     */
    @Data
    public static class QueueStats {
        private final int currentSize;
        private final long totalQueued;
        private final long totalProcessed;
        private final int maxSize;
        
        public double getSuccessRate() {
            if (totalQueued == 0) return 1.0;
            return (double) totalProcessed / totalQueued;
        }
        
        public boolean isFull() {
            return currentSize >= maxSize;
        }
        
        public double getUtilization() {
            return (double) currentSize / maxSize;
        }
    }
}
