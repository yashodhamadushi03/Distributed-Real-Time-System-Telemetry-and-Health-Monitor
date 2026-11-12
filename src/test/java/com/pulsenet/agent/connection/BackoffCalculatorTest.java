package com.pulsenet.agent.connection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackoffCalculatorTest {

    @Test
    @DisplayName("Deterministic exponential backoff matches formula D(n) = min(Dmax, D0 * 2^(n-1))")
    void testDeterministicSequence() {
        BackoffCalculator calc = new BackoffCalculator(1000L, 10000L);

        assertEquals(1000L, calc.calculateDeterministicDelay(1), "Attempt 1: 1s");
        assertEquals(2000L, calc.calculateDeterministicDelay(2), "Attempt 2: 2s");
        assertEquals(4000L, calc.calculateDeterministicDelay(3), "Attempt 3: 4s");
        assertEquals(8000L, calc.calculateDeterministicDelay(4), "Attempt 4: 8s");
        assertEquals(10000L, calc.calculateDeterministicDelay(5), "Attempt 5: 10s (capped)");
        assertEquals(10000L, calc.calculateDeterministicDelay(6), "Attempt 6: 10s (capped)");
        assertEquals(10000L, calc.calculateDeterministicDelay(20), "Attempt 20: 10s (capped)");
    }

    @Test
    @DisplayName("Jitter bounds stay strictly between 80% and 100% of calculated delay")
    void testJitterBounds() {
        BackoffCalculator calc = new BackoffCalculator(1000L, 10000L);

        for (int attempt = 1; attempt <= 10; attempt++) {
            long deterministic = calc.calculateDeterministicDelay(attempt);
            long minAllowed = Math.round(deterministic * 0.8);
            long maxAllowed = deterministic;

            for (int sample = 0; sample < 50; sample++) {
                long jittered = calc.calculateJitteredDelay(attempt);
                assertTrue(jittered >= minAllowed && jittered <= maxAllowed,
                        String.format("Attempt %d sample %d: delay %d must be in [%d, %d]",
                                attempt, sample, jittered, minAllowed, maxAllowed));
            }
        }
    }

    @Test
    @DisplayName("Invalid constructor parameters throw IllegalArgumentException")
    void testInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> new BackoffCalculator(0, 1000));
        assertThrows(IllegalArgumentException.class, () -> new BackoffCalculator(-10, 1000));
        assertThrows(IllegalArgumentException.class, () -> new BackoffCalculator(5000, 2000));
    }
}

