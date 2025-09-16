package io.droptracker.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks API connection failures and determines appropriate retry strategies
 */
@Slf4j
@Singleton
public class FailureTracker {
    
    // Failure counters
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger timeoutFailures = new AtomicInteger(0);
    private final AtomicInteger serverErrorFailures = new AtomicInteger(0);
    private final AtomicInteger clientErrorFailures = new AtomicInteger(0);
    
    // Timestamps
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong lastSuccessTime = new AtomicLong(System.currentTimeMillis());
    
    // Thresholds
    private static final int MAX_CONSECUTIVE_FAILURES = 10;
    private static final int MAX_TIMEOUT_FAILURES = 20;
    private static final long HEALTH_CHECK_INTERVAL_MS = 30_000; // 30 seconds
    
    @Getter
    private volatile boolean apiHealthy = true;
    
    /**
     * Record a successful API call
     */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        lastSuccessTime.set(System.currentTimeMillis());
        
        if (!apiHealthy) {
            log.info("API connection restored after {} consecutive failures", 
                    consecutiveFailures.get());
            apiHealthy = true;
        }
    }
    
    /**
     * Record a timeout failure
     */
    public void recordTimeoutFailure() {
        int consecutive = consecutiveFailures.incrementAndGet();
        int timeouts = timeoutFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        
        log.debug("Timeout failure recorded. Consecutive: {}, Total timeouts: {}", 
                consecutive, timeouts);
        
        updateHealthStatus();
    }
    
    /**
     * Record a server error (5xx response)
     */
    public void recordServerError(int statusCode) {
        int consecutive = consecutiveFailures.incrementAndGet();
        int serverErrors = serverErrorFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        
        log.debug("Server error {} recorded. Consecutive: {}, Total server errors: {}", 
                statusCode, consecutive, serverErrors);
        
        updateHealthStatus();
    }
    
    /**
     * Record a client error (4xx response)
     */
    public void recordClientError(int statusCode) {
        int consecutive = consecutiveFailures.incrementAndGet();
        int clientErrors = clientErrorFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        
        log.debug("Client error {} recorded. Consecutive: {}, Total client errors: {}", 
                statusCode, consecutive, clientErrors);
        
        // Client errors (except 429) usually don't indicate API health issues
        if (statusCode != 429) {
            // Don't mark API as unhealthy for most client errors
            return;
        }
        
        updateHealthStatus();
    }
    
    /**
     * Calculate the next retry delay using exponential backoff
     */
    public long getRetryDelayMs() {
        int failures = consecutiveFailures.get();
        
        if (failures == 0) {
            return 0;
        }
        
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s, max 60s
        long baseDelay = 1000L;
        long delay = Math.min(baseDelay * (1L << Math.min(failures - 1, 6)), 60_000L);
        
        // Add some jitter to prevent thundering herd
        double jitter = 0.1 * Math.random();
        return (long) (delay * (1 + jitter));
    }
    
    /**
     * Determine if we should retry based on the error type and failure count
     */
    public boolean shouldRetry(FailureType failureType) {
        int consecutive = consecutiveFailures.get();
        
        switch (failureType) {
            case TIMEOUT:
                // Retry timeouts more aggressively as they're often temporary
                return consecutive < MAX_TIMEOUT_FAILURES;
                
            case SERVER_ERROR:
                // Retry server errors but with lower threshold
                return consecutive < MAX_CONSECUTIVE_FAILURES;
                
            case CLIENT_ERROR_RATE_LIMIT:
                // Always retry rate limits with backoff
                return true;
                
            case CLIENT_ERROR_OTHER:
                // Don't retry most client errors (400, 401, 403, etc.)
                return false;
                
            case NETWORK_ERROR:
                // Retry network errors similar to timeouts
                return consecutive < MAX_TIMEOUT_FAILURES;
                
            default:
                return consecutive < MAX_CONSECUTIVE_FAILURES;
        }
    }
    
    /**
     * Check if enough time has passed to attempt a health check
     */
    public boolean shouldPerformHealthCheck() {
        if (apiHealthy) {
            return false;
        }
        
        long timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime.get();
        return timeSinceLastFailure >= HEALTH_CHECK_INTERVAL_MS;
    }
    
    /**
     * Get a summary of current failure statistics
     */
    public String getFailureStats() {
        return String.format(
            "Consecutive: %d, Timeouts: %d, Server: %d, Client: %d, Healthy: %s",
            consecutiveFailures.get(),
            timeoutFailures.get(),
            serverErrorFailures.get(),
            clientErrorFailures.get(),
            apiHealthy
        );
    }
    
    /**
     * Reset all failure counters (useful for testing or manual reset)
     */
    public void reset() {
        consecutiveFailures.set(0);
        timeoutFailures.set(0);
        serverErrorFailures.set(0);
        clientErrorFailures.set(0);
        lastFailureTime.set(0);
        lastSuccessTime.set(System.currentTimeMillis());
        apiHealthy = true;
        
        log.info("Failure tracker reset");
    }
    
    private void updateHealthStatus() {
        int consecutive = consecutiveFailures.get();
        boolean wasHealthy = apiHealthy;
        
        apiHealthy = consecutive < MAX_CONSECUTIVE_FAILURES;
        
        if (wasHealthy && !apiHealthy) {
            log.warn("API marked as unhealthy after {} consecutive failures", consecutive);
        }
    }
    
    /**
     * Get time since last successful API call
     */
    public long getTimeSinceLastSuccessMs() {
        return System.currentTimeMillis() - lastSuccessTime.get();
    }
    
    /**
     * Get time since last failure
     */
    public long getTimeSinceLastFailureMs() {
        long lastFailure = lastFailureTime.get();
        return lastFailure == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastFailure;
    }
    
    public enum FailureType {
        TIMEOUT,
        SERVER_ERROR,
        CLIENT_ERROR_RATE_LIMIT,
        CLIENT_ERROR_OTHER,
        NETWORK_ERROR,
        UNKNOWN
    }
}
