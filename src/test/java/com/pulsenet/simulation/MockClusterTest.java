package com.pulsenet.simulation;

import com.pulsenet.agent.TelemetryAgent;
import com.pulsenet.server.network.TelemetryServer;
import com.pulsenet.server.registry.NodeRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockClusterTest {

    @Test
    @DisplayName("Default prefix keeps the original agent-NN IDs and chaos target")
    void defaultPrefix() {
        MockCluster cluster = new MockCluster("127.0.0.1", 9000, 5, 1000L, true);
        assertEquals("agent-01", cluster.nodeIdFor(1));
        assertEquals("agent-03", cluster.getChaosTargetId());
    }

    @Test
    @DisplayName("Custom prefix is applied to node IDs and the chaos target")
    void customPrefix() {
        MockCluster cluster = new MockCluster("127.0.0.1", 9000, 5, 1000L, true, "nodeC-sim-");
        assertEquals("nodeC-sim-01", cluster.nodeIdFor(1));
        assertEquals("nodeC-sim-12", cluster.nodeIdFor(12));
        assertEquals("nodeC-sim-03", cluster.getChaosTargetId());
    }

    @Test
    @DisplayName("Prefixed cluster streams to a live server under prefixed IDs")
    void prefixedClusterRegistersOnServer() throws Exception {
        NodeRegistry registry = new NodeRegistry();
        TelemetryServer server = new TelemetryServer("127.0.0.1", 0, registry);
        server.start();
        MockCluster cluster = new MockCluster("127.0.0.1", server.getPort(), 3, 100L, false, "nodeC-sim-");
        try {
            cluster.start();
            List<TelemetryAgent> agents = cluster.getActiveAgents();
            assertEquals(3, agents.size());
            assertEquals("nodeC-sim-02", agents.get(1).getNodeId());

            long deadline = System.currentTimeMillis() + 3000;
            while (registry.size() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(3, registry.size());
            assertTrue(registry.getNode("nodeC-sim-01").isPresent());
            assertTrue(registry.getNode("nodeC-sim-03").isPresent());
            assertTrue(registry.getNode("agent-01").isEmpty(), "Default prefix must not be used");
        } finally {
            cluster.stop();
            server.stop();
        }
    }
}
