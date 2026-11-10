package com.pulsenet.integration;

import com.pulsenet.agent.TelemetryAgent;
import com.pulsenet.server.network.TelemetryServer;
import com.pulsenet.server.registry.NodeRecord;
import com.pulsenet.server.registry.NodeRegistry;
import com.pulsenet.server.watchdog.WatchdogSupervisor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationTest {

    private NodeRegistry registry;
    private TelemetryServer server;
    private WatchdogSupervisor watchdog;
    private final List<TelemetryAgent> agents = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        registry = new NodeRegistry();
        // Bind to dynamic ephemeral port (port 0)
        server = new TelemetryServer("127.0.0.1", 0, registry);
        server.start();

        // Fast watchdog for integration test: 1.0s timeout, 200ms sweep interval
        watchdog = new WatchdogSupervisor(registry, 1.0, 200L);
        watchdog.start();
    }

    @AfterEach
    void tearDown() {
        for (TelemetryAgent agent : agents) {
            agent.stop();
        }
        agents.clear();

        if (watchdog != null) {
            watchdog.stop();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Exit Gate: Launch server on dynamic port, stream from two clients, verify registry and watchdog detection")
    void testFullLoopbackClusterTelemetry() throws Exception {
        int serverPort = server.getPort();
        assertTrue(serverPort > 0, "Server should be bound to valid ephemeral port");

        TelemetryAgent agent1 = new TelemetryAgent("127.0.0.1", serverPort, "cluster-node-01", 50);
        TelemetryAgent agent2 = new TelemetryAgent("127.0.0.1", serverPort, "cluster-node-02", 50);
        agents.add(agent1);
        agents.add(agent2);

        agent1.start();
        agent2.start();

        // 1. Verify server registry contains both nodes within 2 seconds
        boolean bothRegistered = false;
        long deadline = System.currentTimeMillis() + 2500;
        while (System.currentTimeMillis() < deadline) {
            if (registry.size() >= 2) {
                bothRegistered = true;
                break;
            }
            Thread.sleep(50);
        }

        assertTrue(bothRegistered, "Both client agents must be registered within 2.5 seconds");
        assertEquals(2, registry.size());

        Optional<NodeRecord> record1 = registry.getNode("cluster-node-01");
        Optional<NodeRecord> record2 = registry.getNode("cluster-node-02");

        assertTrue(record1.isPresent());
        assertTrue(record2.isPresent());

        assertNotNull(record1.get().getLatestPayload());
        assertNotNull(record2.get().getLatestPayload());
        assertNotEquals("OFFLINE", record1.get().getStatus());
        assertNotEquals("OFFLINE", record2.get().getStatus());

        // 2. Test Chaos / Node Failure: Abruptly stop agent2
        agent2.stop();

        // Wait for watchdog to detect absence (1.0s timeout + sweep time)
        boolean node2MarkedOffline = false;
        deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            Optional<NodeRecord> r2 = registry.getNode("cluster-node-02");
            if (r2.isPresent() && "OFFLINE".equals(r2.get().getStatus())) {
                node2MarkedOffline = true;
                break;
            }
            Thread.sleep(100);
        }

        assertTrue(node2MarkedOffline, "Watchdog must mark silent cluster-node-02 as OFFLINE");

        // Verify agent1 is still active and healthy
        Optional<NodeRecord> r1 = registry.getNode("cluster-node-01");
        assertTrue(r1.isPresent());
        assertNotEquals("OFFLINE", r1.get().getStatus(), "cluster-node-01 must remain active and healthy");
    }

    @Test
    @DisplayName("Verify concurrent connection of 5 agents and clean partial client disconnects")
    void testFiveAgentsConnectAndPartialDisconnect() throws Exception {
        int serverPort = server.getPort();

        for (int i = 1; i <= 5; i++) {
            TelemetryAgent agent = new TelemetryAgent("127.0.0.1", serverPort, "agent-" + i, 50);
            agents.add(agent);
            agent.start();
        }

        // Wait for all 5 to register
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && registry.size() < 5) {
            Thread.sleep(50);
        }
        assertEquals(5, registry.size(), "All 5 agents must register");

        // Stop 3 agents
        agents.get(0).stop();
        agents.get(1).stop();
        agents.get(2).stop();

        // Wait for watchdog to mark stopped nodes offline
        Thread.sleep(1500);

        List<NodeRecord> snapshot = registry.getAllNodes();
        int offlineCount = 0;
        int activeCount = 0;
        for (NodeRecord r : snapshot) {
            if ("OFFLINE".equals(r.getStatus())) {
                offlineCount++;
            } else {
                activeCount++;
            }
        }

        assertEquals(3, offlineCount, "Exactly 3 stopped nodes should transition to OFFLINE");
        assertEquals(2, activeCount, "Remaining 2 nodes must stay active");
    }
}
