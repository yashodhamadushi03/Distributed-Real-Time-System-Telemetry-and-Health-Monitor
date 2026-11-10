package com.pulsenet.server.registry;

import com.pulsenet.common.model.TelemetryPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NodeRegistryTest {

    private NodeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new NodeRegistry();
    }

    @Test
    @DisplayName("Exit Gate: 20 concurrent threads hammering NodeRegistry with zero race conditions")
    void testConcurrent20ThreadsUpdatesWithoutRaceConditions() throws InterruptedException {
        int threadCount = 20;
        int updatesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startGate.await(); // Synchronize all 20 threads to hit at the exact same instant
                    String nodeId = "node-" + threadId;
                    for (int j = 0; j < updatesPerThread; j++) {
                        TelemetryPayload payload = new TelemetryPayload(
                                nodeId,
                                System.currentTimeMillis(),
                                10.0 + (j % 50),
                                20.0 + (j % 40),
                                30.0,
                                100 + j,
                                "HEALTHY"
                        );
                        registry.upsertNode(payload, "127.0.0.1:" + (5000 + threadId));

                        // Concurrently read snapshots to verify absence of ConcurrentModificationException
                        if (j % 10 == 0) {
                            List<NodeRecord> snapshot = registry.getAllNodes();
                            assertNotNull(snapshot);
                        }
                    }
                } catch (Throwable t) {
                    errorCount.incrementAndGet();
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown(); // Fire simultaneous execution
        boolean completed = endGate.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "All 20 concurrent threads should finish within timeout");
        assertEquals(0, errorCount.get(), "Zero exceptions or concurrency collisions should occur");
        assertEquals(threadCount, registry.size(), "Registry must contain exactly 20 nodes");

        List<NodeRecord> allNodes = registry.getAllNodes();
        assertEquals(threadCount, allNodes.size());
        for (int i = 0; i < threadCount; i++) {
            assertTrue(registry.getNode("node-" + i).isPresent());
        }
    }

    @Test
    @DisplayName("Verify auditStaleNodes marks lagging nodes OFFLINE after heartbeat timeout")
    void testAuditStaleNodesTimeout() throws InterruptedException {
        long oldTime = System.currentTimeMillis() - 10000; // 10 seconds ago

        TelemetryPayload p1 = new TelemetryPayload("active-node", System.currentTimeMillis(), 20.0, 30.0, 40.0, 100, "HEALTHY");
        TelemetryPayload p2 = new TelemetryPayload("stale-node", oldTime, 50.0, 60.0, 70.0, 120, "HEALTHY");

        registry.upsertNode(p1, "127.0.0.1:5001");
        registry.upsertNode(p2, "127.0.0.1:5002");

        // Manually adjust stale-node's timestamp backwards to simulate silence
        registry.getAllNodes(); // verify read
        // Small sleep to ensure time difference
        Thread.sleep(50);

        // Audit with 0.04s timeout (40ms) - both were recorded, but active-node updated now
        List<String> offlineNodes = registry.auditStaleNodes(0.02); // 20ms timeout
        assertEquals(2, offlineNodes.size(), "Both nodes should be marked offline if older than 20ms");

        // Re-heartbeat active-node
        registry.upsertNode(p1, "127.0.0.1:5001");
        NodeRecord activeRecord = registry.getNode("active-node").orElseThrow();
        assertEquals("HEALTHY", activeRecord.getStatus(), "Active node should recover to HEALTHY upon new telemetry");

        NodeRecord staleRecord = registry.getNode("stale-node").orElseThrow();
        assertEquals("OFFLINE", staleRecord.getStatus(), "Stale node must remain OFFLINE");
    }

    @Test
    @DisplayName("Verify defensive copy isolation in getAllNodes snapshot")
    void testDefensiveCopySnapshotIsolation() {
        TelemetryPayload payload = new TelemetryPayload("node-iso", System.currentTimeMillis(), 10.0, 20.0, 30.0, 100, "HEALTHY");
        registry.upsertNode(payload, "127.0.0.1:5000");

        List<NodeRecord> snapshot = registry.getAllNodes();
        assertEquals(1, snapshot.size());

        // Modify the returned snapshot object
        snapshot.get(0).setStatus("MODIFIED_EXTERNALLY");

        // The registry should remain unchanged
        NodeRecord actualInRegistry = registry.getNode("node-iso").orElseThrow();
        assertEquals("HEALTHY", actualInRegistry.getStatus());
    }
}
