package com.pulsenet.integration;

import com.pulsenet.agent.TelemetryAgent;
import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.common.protocol.WireProtocol;
import com.pulsenet.server.network.TelemetryServer;
import com.pulsenet.server.registry.NodeRecord;
import com.pulsenet.server.registry.NodeRegistry;
import com.pulsenet.server.watchdog.WatchdogSupervisor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
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

    // ---------------------------------------------------------------- Phase 8: connection hygiene

    private static void sendFrame(Socket socket, String nodeId, double cpu) throws IOException {
        TelemetryPayload payload = new TelemetryPayload(nodeId, System.currentTimeMillis(),
                cpu, 40.0, 30.0, 120, cpu > NodeRegistry.CPU_WARN_THRESHOLD ? "WARNING" : "HEALTHY");
        WireProtocol.sendFramedMessage(socket.getOutputStream(), payload);
    }

    private long countEvents(String marker) {
        return registry.getRecentEvents(1000).stream().filter(e -> e.contains(marker)).count();
    }

    private boolean awaitCondition(long timeoutMillis, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    @Test
    @DisplayName("Phase 8: silent half-open client is dropped within readTimeout + 1s")
    void testSilentClientTimesOut() throws Exception {
        long readTimeout = 1000L;
        TelemetryServer timedServer = new TelemetryServer("127.0.0.1", 0, registry, readTimeout);
        timedServer.start();
        try (Socket silent = new Socket("127.0.0.1", timedServer.getPort())) {
            sendFrame(silent, "silent-node", 10.0);
            assertTrue(awaitCondition(1000, () -> registry.getNode("silent-node").isPresent()));
            assertEquals(1, timedServer.getActiveConnectionCount());
            long sentAt = System.currentTimeMillis();

            // Go silent without closing. The handler thread must exit on SO_TIMEOUT.
            assertTrue(awaitCondition(readTimeout + 1000, () -> timedServer.getActiveConnectionCount() == 0),
                    "Handler thread must terminate within readTimeout + 1s");
            assertTrue(System.currentTimeMillis() - sentAt <= readTimeout + 1000);
            assertTrue(awaitCondition(500, () -> countEvents("Connection timed out: silent-node") == 1));
            assertEquals(0, countEvents("Client disconnected: silent-node"));

            // Server side closed the socket: the client now sees EOF.
            silent.setSoTimeout(2000);
            assertEquals(-1, silent.getInputStream().read());
        } finally {
            timedServer.stop();
        }
    }

    @Test
    @DisplayName("Phase 8: a second connection with the same nodeId supersedes the first")
    void testDuplicateNodeIdSupersedesStaleConnection() throws Exception {
        TelemetryServer timedServer = new TelemetryServer("127.0.0.1", 0, registry, 1500L);
        timedServer.start();
        try (Socket first = new Socket("127.0.0.1", timedServer.getPort());
             Socket second = new Socket("127.0.0.1", timedServer.getPort())) {
            sendFrame(first, "dup-node", 10.0);
            assertTrue(awaitCondition(1000, () -> registry.getNode("dup-node").isPresent()));

            // The first connection is never closed by the client and goes silent (a Wi-Fi blip).
            // The second keeps streaming until the stale one times out on the server.
            long deadline = System.currentTimeMillis() + 3500;
            while (System.currentTimeMillis() < deadline && countEvents("Superseded stale connection for dup-node") == 0) {
                sendFrame(second, "dup-node", 12.0);
                Thread.sleep(150);
            }
            sendFrame(second, "dup-node", 12.0);

            assertEquals(1, countEvents("Superseded stale connection for dup-node"));
            assertEquals(0, countEvents("Client disconnected: dup-node"),
                    "Superseded connection must not report a disconnect");
            assertEquals(0, countEvents("Connection timed out: dup-node"));
            assertEquals(1, registry.getAllNodes().stream().filter(r -> r.getNodeId().equals("dup-node")).count());
            assertNotEquals("OFFLINE", registry.getNode("dup-node").orElseThrow().getStatus());
            assertTrue(awaitCondition(500, () -> timedServer.getActiveConnectionCount() == 1),
                    "Only the live connection's handler remains");
        } finally {
            timedServer.stop();
        }
    }

    @Test
    @DisplayName("Phase 8: 10 consecutive frames at 95% CPU produce exactly one WARN event")
    void testSustainedHighCpuEmitsSingleWarn() throws Exception {
        try (Socket client = new Socket("127.0.0.1", server.getPort())) {
            for (int i = 0; i < 10; i++) {
                sendFrame(client, "hot-node", 95.0);
            }
        }
        assertTrue(awaitCondition(2000, () -> countEvents("Client disconnected: hot-node") == 1),
                "All frames processed before disconnect");
        assertEquals(1, countEvents("[WARN] hot-node crossed CPU threshold"));
    }
}
