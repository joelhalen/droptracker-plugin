package io.droptracker.service;

import io.droptracker.DropTrackerConfig;
import io.droptracker.models.CustomWebhookBody;
import io.droptracker.models.submissions.SubmissionType;
import io.droptracker.models.submissions.ValidSubmission;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Service that manages retry logic for failed submissions
 */
@Slf4j
@Singleton
public class RetryService {
    
    private final DropTrackerConfig config;
    private final FailureTracker failureTracker;
    private final SubmissionQueue submissionQueue;
    private final ScheduledExecutorService executor;
    
    private Consumer<SubmissionQueue.QueuedSubmission> retryCallback;
    private volatile boolean processingEnabled = true;
    
    @Inject
    public RetryService(DropTrackerConfig config, FailureTracker failureTracker, 
                       SubmissionQueue submissionQueue, ScheduledExecutorService executor) {
        this.config = config;
        this.failureTracker = failureTracker;
        this.submissionQueue = submissionQueue;
        this.executor = executor;
        
        // Start the retry processor
        startRetryProcessor();
    }
    
    /**
     * Set the callback function to handle retry attempts
     */
    public void setRetryCallback(Consumer<SubmissionQueue.QueuedSubmission> callback) {
        this.retryCallback = callback;
    }
    
    /**
     * Handle a failed submission attempt
     */
    public void handleFailure(CustomWebhookBody webhook, byte[] screenshot, SubmissionType type, 
                             Throwable error, ValidSubmission validSubmission) {
        
        FailureTracker.FailureType failureType = categorizeFailure(error);
        String failureReason = getFailureReason(error);
        
        // Record the failure in our tracker
        recordFailure(failureType, error);
        
        // Update the ValidSubmission status
        if (validSubmission != null) {
            validSubmission.markAsFailed(failureReason);
        }
        
        // Decide whether to retry
        if (!config.enableRetryQueue() || !failureTracker.shouldRetry(failureType)) {
            log.debug("Not retrying {} submission: retry disabled or max attempts reached", type);
            return;
        }
        
        // Queue for retry
        boolean queued = submissionQueue.enqueue(webhook, screenshot, type, failureReason);
        if (queued && validSubmission != null) {
            validSubmission.markAsQueued();
            log.debug("Queued {} submission for retry due to: {}", type, failureReason);
        }
    }
    
    /**
     * Handle a successful submission
     */
    public void handleSuccess(ValidSubmission validSubmission) {
        failureTracker.recordSuccess();
        
        if (validSubmission != null) {
            validSubmission.markAsSuccess();
        }
        
        log.debug("Submission successful, failure tracker reset");
    }
    
    /**
     * Manually retry a specific submission
     */
    public void retrySubmission(ValidSubmission validSubmission) {
        if (validSubmission == null || validSubmission.getOriginalWebhook() == null) {
            log.warn("Cannot retry submission: missing webhook data");
            return;
        }
        
        if (!validSubmission.canRetry()) {
            log.warn("Cannot retry submission: max attempts reached or already sent");
            return;
        }
        
        // Create a queued submission and process immediately
        SubmissionQueue.QueuedSubmission queuedSubmission = new SubmissionQueue.QueuedSubmission(
            validSubmission.getOriginalWebhook(),
            null, // No screenshot for manual retries for now
            validSubmission.getType(),
            "Manual retry",
            java.time.Instant.now()
        );
        
        validSubmission.markAsRetrying();
        
        if (retryCallback != null) {
            executor.submit(() -> retryCallback.accept(queuedSubmission));
        }
    }
    
    /**
     * Get current retry service statistics
     */
    public RetryStats getStats() {
        return new RetryStats(
            failureTracker.getFailureStats(),
            submissionQueue.getStats(),
            failureTracker.isApiHealthy(),
            processingEnabled
        );
    }
    
    /**
     * Enable or disable retry processing
     */
    public void setProcessingEnabled(boolean enabled) {
        this.processingEnabled = enabled;
        log.info("Retry processing {}", enabled ? "enabled" : "disabled");
    }
    
    /**
     * Clear all queued submissions
     */
    public void clearQueue() {
        submissionQueue.clear();
        log.info("Retry queue cleared");
    }
    
    private void startRetryProcessor() {
        // Process retry queue every 10 seconds
        executor.scheduleWithFixedDelay(this::processRetryQueue, 10, 10, TimeUnit.SECONDS);
        
        // Perform health checks every 30 seconds
        executor.scheduleWithFixedDelay(this::performHealthCheck, 30, 30, TimeUnit.SECONDS);
    }
    
    private void processRetryQueue() {
        if (!processingEnabled || !config.enableRetryQueue()) {
            return;
        }
        
        // Don't process queue if API is unhealthy and not enough time has passed
        if (!failureTracker.isApiHealthy() && !failureTracker.shouldPerformHealthCheck()) {
            return;
        }
        
        SubmissionQueue.QueuedSubmission submission = submissionQueue.dequeue();
        if (submission == null) {
            return; // Queue is empty
        }
        
        // Check if submission is too old
        if (submission.isExpired(TimeUnit.HOURS.toMillis(24))) {
            log.debug("Dropping expired {} submission", submission.getType());
            return;
        }
        
        // Calculate retry delay
        long retryDelay = failureTracker.getRetryDelayMs();
        if (retryDelay > 0) {
            // Re-queue with delay
            executor.schedule(() -> {
                if (retryCallback != null) {
                    retryCallback.accept(submission);
                }
            }, retryDelay, TimeUnit.MILLISECONDS);
            
            log.debug("Scheduled retry for {} submission in {}ms", 
                    submission.getType(), retryDelay);
        } else {
            // Retry immediately
            if (retryCallback != null) {
                retryCallback.accept(submission);
            }
        }
    }
    
    private void performHealthCheck() {
        if (!failureTracker.shouldPerformHealthCheck()) {
            return;
        }
        
        log.debug("Performing API health check");
        
        // The health check will be performed by attempting to process the next queued item
        // If it succeeds, the failure tracker will be reset
        // This is a passive health check approach
    }
    
    private FailureTracker.FailureType categorizeFailure(Throwable error) {
        if (error instanceof SocketTimeoutException) {
            return FailureTracker.FailureType.TIMEOUT;
        }
        
        if (error instanceof IOException) {
            return FailureTracker.FailureType.NETWORK_ERROR;
        }
        
        // Check for HTTP status codes in error message
        String message = error.getMessage();
        if (message != null) {
            if (message.contains("429")) {
                return FailureTracker.FailureType.CLIENT_ERROR_RATE_LIMIT;
            }
            if (message.contains("400") || message.contains("401") || 
                message.contains("403") || message.contains("404")) {
                return FailureTracker.FailureType.CLIENT_ERROR_OTHER;
            }
            if (message.contains("500") || message.contains("502") || 
                message.contains("503") || message.contains("504")) {
                return FailureTracker.FailureType.SERVER_ERROR;
            }
        }
        
        return FailureTracker.FailureType.UNKNOWN;
    }
    
    private void recordFailure(FailureTracker.FailureType failureType, Throwable error) {
        switch (failureType) {
            case TIMEOUT:
                failureTracker.recordTimeoutFailure();
                break;
            case SERVER_ERROR:
                failureTracker.recordServerError(extractStatusCode(error, 500));
                break;
            case CLIENT_ERROR_RATE_LIMIT:
                failureTracker.recordClientError(429);
                break;
            case CLIENT_ERROR_OTHER:
                failureTracker.recordClientError(extractStatusCode(error, 400));
                break;
            case NETWORK_ERROR:
                failureTracker.recordTimeoutFailure(); // Treat as timeout
                break;
            default:
                failureTracker.recordTimeoutFailure(); // Default fallback
                break;
        }
    }
    
    private int extractStatusCode(Throwable error, int defaultCode) {
        String message = error.getMessage();
        if (message == null) return defaultCode;
        
        // Try to extract HTTP status code from error message
        try {
            if (message.contains("status:")) {
                String[] parts = message.split("status:");
                if (parts.length > 1) {
                    String statusStr = parts[1].trim().split("\\s")[0];
                    return Integer.parseInt(statusStr);
                }
            }
        } catch (NumberFormatException e) {
            // Ignore parsing errors
        }
        
        return defaultCode;
    }
    
    private String getFailureReason(Throwable error) {
        if (error instanceof SocketTimeoutException) {
            return "Connection timeout";
        }
        
        if (error instanceof IOException) {
            return "Network error: " + error.getMessage();
        }
        
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
    
    /**
     * Statistics for the retry service
     */
    public static class RetryStats {
        public final String failureStats;
        public final SubmissionQueue.QueueStats queueStats;
        public final boolean apiHealthy;
        public final boolean processingEnabled;
        
        public RetryStats(String failureStats, SubmissionQueue.QueueStats queueStats, 
                         boolean apiHealthy, boolean processingEnabled) {
            this.failureStats = failureStats;
            this.queueStats = queueStats;
            this.apiHealthy = apiHealthy;
            this.processingEnabled = processingEnabled;
        }
        
        @Override
        public String toString() {
            return String.format(
                "RetryStats{failures=%s, queue=%d/%d, healthy=%s, processing=%s}",
                failureStats, queueStats.getCurrentSize(), queueStats.getMaxSize(), 
                apiHealthy, processingEnabled
            );
        }
    }
}
