package com.pulsenet.agent.connection;

import java.util.Random;

/**
 * Computes exponential backoff intervals with randomized jitter for reconnection retries.
 *
 * <p>Formula:
 * <pre>
 *   D(n) = min(D_max, D_0 * 2^(n - 1))
 *   D_jittered = round(D(n) * (0.8 + 0.2 * random))
 * </pre>
 */
public class BackoffCalculator {

    public static final long DEFAULT_INITIAL_DELAY_MS = 1000L;
    public static final long DEFAULT_MAX_DELAY_MS = 10000L;
    public static final double JITTER_MIN_FACTOR = 0.8;
    public static final double JITTER_MAX_FACTOR = 1.0;

    private final long initialDelayMs;
    private final long maxDelayMs;
    private final Random random;

    public BackoffCalculator() {
        this(DEFAULT_INITIAL_DELAY_MS, DEFAULT_MAX_DELAY_MS, new Random());
    }

    public BackoffCalculator(long initialDelayMs, long maxDelayMs) {
        this(initialDelayMs, maxDelayMs, new Random());
    }

    public BackoffCalculator(long initialDelayMs, long maxDelayMs, Random random) {
        if (initialDelayMs <= 0) {
            throw new IllegalArgumentException("initialDelayMs must be > 0: " + initialDelayMs);
        }
        if (maxDelayMs < initialDelayMs) {
            throw new IllegalArgumentException("maxDelayMs cannot be less than initialDelayMs");
        }
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.random = random != null ? random : new Random();
    }

    /**
     * Calculates the deterministic exponential backoff delay without jitter for attempt {@code n}.
     *
     * @param attempt 1-based attempt counter
     * @return delay in milliseconds clamped to {@link #maxDelayMs}
     */
    public long calculateDeterministicDelay(int attempt) {
        if (attempt <= 1) {
            return initialDelayMs;
        }
        // Guard against overflow on large attempt numbers (e.g. 2^63)
        if (attempt >= 62) {
            return maxDelayMs;
        }
        long multiplier = 1L << (attempt - 1);
        long delay;
        try {
            delay = Math.multiplyExact(initialDelayMs, multiplier);
        } catch (ArithmeticException e) {
            return maxDelayMs;
        }
        return Math.min(delay, maxDelayMs);
    }

    /**
     * Calculates the exponential backoff delay with 80%-100% randomized jitter.
     *
     * @param attempt 1-based attempt counter
     * @return jittered delay in milliseconds
     */
    public long calculateJitteredDelay(int attempt) {
        long baseDelay = calculateDeterministicDelay(attempt);
        double factor = JITTER_MIN_FACTOR + (JITTER_MAX_FACTOR - JITTER_MIN_FACTOR) * random.nextDouble();
        return Math.round(baseDelay * factor);
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public long getMaxDelayMs() {
        return maxDelayMs;
    }
}

