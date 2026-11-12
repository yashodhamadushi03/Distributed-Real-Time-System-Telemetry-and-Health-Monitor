package com.pulsenet.server.registry;

import com.pulsenet.common.model.NetworkMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetworkHistoryRegistryTest {

    @Test
    @DisplayName("Sample recording and FIFO eviction respect retention limits")
    void testSampleRecordingAndEviction() {
        int maxRetention = 5;
        NetworkHistoryRegistry registry = new NetworkHistoryRegistry(maxRetention);
        assertEquals(maxRetention, registry.getMaxRetentionSamples());

        String nodeId = "node-01";
        for (int i = 1; i <= 8; i++) {
            NetworkMetrics m = new NetworkMetrics();
            m.setCurrentRttMs(i * 10.0);
            m.setAvgRttMs(i * 10.0);
            m.setPacketLossPercent(i * 0.5);
            m.setConnectionStatus("CONNECTED");
            m.setReconnectionCount(0);
            registry.recordSample(nodeId, m, 1000L + i);
        }

        List<NetworkHistorySample> history = registry.getHistory(nodeId);
        assertEquals(5, history.size(), "Should be capped at maxRetention");

        // The oldest 3 samples (i=1, 2, 3) must be evicted; newest 5 (i=4, 5, 6, 7, 8) retained
        assertEquals(40.0, history.get(0).currentRttMs());
        assertEquals(80.0, history.get(4).currentRttMs());

        // Test limit subset query
        List<NetworkHistorySample> lastTwo = registry.getHistory(nodeId, 2);
        assertEquals(2, lastTwo.size());
        assertEquals(70.0, lastTwo.get(0).currentRttMs());
        assertEquals(80.0, lastTwo.get(1).currentRttMs());
    }

    @Test
    @DisplayName("Multiple nodes maintain isolated history streams and clear works")
    void testMultiNodeIsolationAndClear() {
        NetworkHistoryRegistry registry = new NetworkHistoryRegistry(10);

        NetworkMetrics m1 = new NetworkMetrics();
        m1.setCurrentRttMs(12.0);
        registry.recordSample("node-A", m1, 1000L);

        NetworkMetrics m2 = new NetworkMetrics();
        m2.setCurrentRttMs(25.0);
        registry.recordSample("node-B", m2, 1001L);

        assertEquals(1, registry.getHistory("node-A").size());
        assertEquals(12.0, registry.getHistory("node-A").get(0).currentRttMs());

        assertEquals(1, registry.getHistory("node-B").size());
        assertEquals(25.0, registry.getHistory("node-B").get(0).currentRttMs());

        assertTrue(registry.getHistory("unknown-node").isEmpty());

        registry.clear();
        assertTrue(registry.getHistory("node-A").isEmpty());
        assertTrue(registry.getHistory("node-B").isEmpty());
    }
}

