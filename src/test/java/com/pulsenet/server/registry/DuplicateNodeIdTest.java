package com.pulsenet.server.registry;

import com.pulsenet.common.model.TelemetryPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DuplicateNodeIdTest {

    private final NodeRegistry registry = new NodeRegistry();
    private final Object first = new Object();
    private final Object second = new Object();

    private void send(Object owner, double cpu) {
        registry.upsertNode(new TelemetryPayload("laptop", System.currentTimeMillis(), cpu, 20, 30, 40, "HEALTHY"),
                owner == first ? "10.0.0.1:1" : "10.0.0.2:2", owner);
    }

    private long events(String text) {
        return registry.getRecentEvents(500).stream().filter(e -> e.contains(text)).count();
    }

    @Test
    @DisplayName("Two live agents with one node ID get separate rows and a single warning")
    void liveDuplicatesAreSeparated() throws Exception {
        for (int i = 0; i < 30; i++) {
            send(first, 10);
            send(second, 50);
            Thread.sleep(100);
        }
        assertEquals(2, registry.size());
        assertEquals(50, registry.getNode("laptop").orElseThrow().getLatestPayload().getCpuPercent());
        assertEquals(10, registry.getNode("laptop (2)").orElseThrow().getLatestPayload().getCpuPercent());
        assertEquals(1, events("Two agents use node ID 'laptop'"));
        assertEquals(1, events("reconnected on new connection"), "Ownership must not ping-pong");
        assertTrue(registry.releaseConnection("laptop", first), "The separated agent owns its own row");
        assertTrue(registry.releaseConnection("laptop", second));
    }

    @Test
    @DisplayName("A few late frames from a replaced connection change nothing")
    void lateFramesAreIgnored() {
        send(first, 10);
        send(second, 50);
        send(first, 10);
        send(first, 10);
        assertEquals(1, registry.size());
        assertEquals(50, registry.getNode("laptop").orElseThrow().getLatestPayload().getCpuPercent());
        assertFalse(registry.releaseConnection("laptop", first));
    }

    @Test
    @DisplayName("A replaced connection takes the ID back once the newer one is gone")
    void replacedConnectionMayReclaim() {
        send(first, 10);
        send(second, 50);
        registry.releaseConnection("laptop", second);
        send(first, 11);
        assertEquals(11, registry.getNode("laptop").orElseThrow().getLatestPayload().getCpuPercent());
        assertTrue(registry.isCurrentOwner("laptop", first));
    }

    @Test
    @DisplayName("Nodes are listed in a stable order")
    void nodesAreSorted() {
        for (String id : new String[]{"c", "A", "b"}) {
            registry.upsertNode(new TelemetryPayload(id, 1, 1, 1, 1, 1, "HEALTHY"), "x");
        }
        assertEquals(java.util.List.of("A", "b", "c"),
                registry.getAllNodes().stream().map(NodeRecord::getNodeId).toList());
    }
}
