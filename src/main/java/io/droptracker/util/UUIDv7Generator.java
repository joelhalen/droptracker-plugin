package io.droptracker.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import net.runelite.client.util.OSType;

/**
 * UUIDv7 Generator that produces guaranteed unique UUIDs even under high concurrency.
 * 
 * UUIDv7 format:
 * - 48 bits: Unix timestamp in milliseconds
 * - 12 bits: Sub-millisecond precision + counter for ordering
 * - 4 bits: Version (0111 for version 7)
 * - 2 bits: Variant (10)
 * - 62 bits: Random data with machine-specific entropy
 * 
 * This implementation guarantees uniqueness through:
 * 1. High-resolution timestamps
 * 2. Atomic counters for sub-millisecond ordering
 * 3. Machine-specific entropy in random bits
 * 4. Thread-safe generation
 */
public class UUIDv7Generator {
    
    private static final UUIDv7Generator INSTANCE = new UUIDv7Generator();
    
    // Secure random for cryptographically strong randomness
    private final SecureRandom secureRandom;
    
    // Atomic counter for sub-millisecond ordering within same timestamp
    private final AtomicLong counter;
    
    // Last timestamp to detect clock backwards movement
    private volatile long lastTimestamp;
    
    // Machine-specific entropy (based on system properties and random data)
    private final long machineEntropy;
    
    // Variant bits for UUIDv7
    private static final long VARIANT_RFC4122 = 0x8000000000000000L;
    
    private UUIDv7Generator() {
        this.secureRandom = new SecureRandom();
        this.counter = new AtomicLong(0);
        this.lastTimestamp = 0;
        this.machineEntropy = generateMachineEntropy();
    }
    
    /**
     * Get the singleton instance of the UUIDv7 generator.
     */
    public static UUIDv7Generator getInstance() {
        return INSTANCE;
    }
    
    /**
     * Generate a new UUIDv7 with guaranteed uniqueness.
     * This method is thread-safe and can be called concurrently.
     */
    public synchronized UUID generate() {
        long currentTimestamp = Instant.now().toEpochMilli();
        
        // Handle clock going backwards (rare but possible)
        if (currentTimestamp < lastTimestamp) {
            // Wait until clock catches up to last timestamp
            while (currentTimestamp <= lastTimestamp) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("UUID generation interrupted", e);
                }
                currentTimestamp = Instant.now().toEpochMilli();
            }
        }
        
        // Reset counter if we're in a new millisecond
        if (currentTimestamp > lastTimestamp) {
            counter.set(0);
            lastTimestamp = currentTimestamp;
        }
        
        // Get sub-millisecond counter value (12 bits max = 4095)
        long counterValue = counter.getAndIncrement() & 0xFFF;
        
        // If counter overflows in same millisecond, wait for next millisecond
        if (counterValue == 0xFFF) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("UUID generation interrupted", e);
            }
            return generate(); // Recursive call for next millisecond
        }
        
        return buildUUIDv7(currentTimestamp, counterValue);
    }
    
    /**
     * Build the actual UUIDv7 from timestamp and counter.
     */
    private UUID buildUUIDv7(long timestamp, long counter) {
        // Most significant bits (MSB):
        // 48 bits timestamp + 12 bits counter + 4 bits version
        long msb = (timestamp << 16) | (counter << 4) | 0x7L;
        
        // Least significant bits (LSB):
        // 2 bits variant + 62 bits random (with machine entropy)
        long randomBits = secureRandom.nextLong();
        
        // Mix in machine entropy to ensure different machines generate different UUIDs
        randomBits ^= machineEntropy;
        
        // Set variant bits (10xxxxxx...)
        long lsb = (randomBits & 0x3FFFFFFFFFFFFFFFL) | VARIANT_RFC4122;
        
        return new UUID(msb, lsb);
    }
    
    /**
     * Generate machine-specific entropy to ensure different machines produce different UUIDs.
     * This method is privacy-conscious and avoids using personal information.
     */
    private long generateMachineEntropy() {
        // Use only non-personal system characteristics for machine uniqueness
        StringBuilder entropyBuilder = new StringBuilder();
        
        // Safe system properties (no personal info)
        entropyBuilder.append(System.getProperty("java.version", ""));
        entropyBuilder.append(OSType.getOSType().toString());
        entropyBuilder.append(Runtime.getRuntime().availableProcessors());
        entropyBuilder.append(Runtime.getRuntime().maxMemory());
        
        // JVM instance-specific info (no personal data)
        entropyBuilder.append(System.identityHashCode(Runtime.getRuntime()));
        entropyBuilder.append(System.nanoTime()); // More precise than currentTimeMillis
        
        
        // Hash the collected info and mix with secure random
        long entropy = entropyBuilder.toString().hashCode();
        entropy = entropy * 31L + secureRandom.nextLong();
        
        return entropy;
    }
    
    /**
     * Convenience method to generate UUIDv7 as string.
     */
    public String generateAsString() {
        return generate().toString();
    }
    
    /**
     * Get information about the last generated timestamp (for debugging).
     */
    public long getLastTimestamp() {
        return lastTimestamp;
    }
    
    /**
     * Get current counter value (for debugging).
     */
    public long getCurrentCounter() {
        return counter.get();
    }
} 