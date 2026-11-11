package com.pulsenet.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StressTest {

    @Test
    @DisplayName("Stress threads stop within 1 s after the duration elapses")
    void stopsAfterDuration() throws Exception {
        Stress stress = new Stress(1000L, 2);
        long start = System.currentTimeMillis();
        stress.start();
        assertTrue(stress.isRunning());
        Thread.sleep(200);
        assertTrue(stress.hasLiveWorkers(), "Workers should be busy during the run");

        assertTrue(stress.awaitCompletion(2000L), "Workers must exit within duration + 1 s");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 2000L, "Took too long: " + elapsed + " ms");
        assertFalse(stress.isRunning());
        assertFalse(stress.hasLiveWorkers());
    }

    @Test
    @DisplayName("stop() halts the threads before the duration elapses")
    void stopEarly() throws Exception {
        Stress stress = new Stress(60_000L, 1);
        stress.start();
        Thread.sleep(100);
        stress.stop();
        assertTrue(stress.awaitCompletion(1000L));
        assertFalse(stress.hasLiveWorkers());
    }
}
