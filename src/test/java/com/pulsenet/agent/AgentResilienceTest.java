package com.pulsenet.agent;

import com.pulsenet.agent.connection.ConnectionManager;
import com.pulsenet.agent.connection.ConnectionState;
import com.pulsenet.agent.discovery.DiscoveryClient;
import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.server.discovery.DiscoveryServer;
import com.pulsenet.server.network.TelemetryServer;
import com.pulsenet.server.registry.NodeRegistry;
import com.pulsenet.simulation.MockCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentResilienceTest {

    private final NodeRegistry registry = new NodeRegistry();
    private TelemetryServer server;
    private TelemetryAgent agent;

    @BeforeEach
    void setUp() throws IOException {
        server = new TelemetryServer("127.0.0.1", 0, registry);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (agent != null) {
            agent.stop();
        }
        server.stop();
    }

    private static boolean await(Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.call()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static MetricCollector synthetic(String id) {
        return new MockCluster.SyntheticMetricCollector(id, 1);
    }

    private TelemetryAgent newAgent(MetricCollector collector) {
        TelemetryAgent created = new TelemetryAgent("127.0.0.1", server.getPort(), "n", 50, collector);
        created.setInternetMonitoringEnabled(false);
        return created;
    }

    @Test
    @DisplayName("One failed sample does not end the telemetry stream")
    void collectorFailureDoesNotKillAgent() throws Exception {
        AtomicInteger samples = new AtomicInteger();
        agent = newAgent(new MockCluster.SyntheticMetricCollector("n", 1) {
            @Override
            public TelemetryPayload collect(String id) {
                if (samples.incrementAndGet() == 2) {
                    throw new IllegalStateException("sensor failure");
                }
                return super.collect(id);
            }
        });
        agent.start();
        assertTrue(await(() -> agent.getMessagesSent() >= 4));
        assertTrue(samples.get() >= 5);
    }

    @Test
    @DisplayName("The TCP source port is reported, not the probe socket's port")
    void reportsTcpSourcePort() throws Exception {
        agent = newAgent(synthetic("n"));
        agent.start();
        assertTrue(await(() -> registry.getNode("n").isPresent()));
        String remote = registry.getNode("n").orElseThrow().getRemoteAddress();
        int reported = registry.getNode("n").orElseThrow().getNetworkMetrics().getAgentSourcePort();
        assertTrue(remote.endsWith(":" + reported), remote + " vs " + reported);
    }

    @Test
    @DisplayName("Invalid agent settings are rejected up front")
    void constructorValidation() {
        assertThrows(IllegalArgumentException.class, () -> new TelemetryAgent("h", 70_000, "n", 100, synthetic("n")));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryAgent(" ", 9000, "n", 100, synthetic("n")));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryAgent("h", 9000, "", 100, synthetic("n")));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryAgent("h", 9000, "n", 0, synthetic("n")));
    }

    @Test
    @DisplayName("A stopped agent stays stopped instead of pretending to run")
    void stoppedAgentIsNotRestarted() {
        agent = newAgent(synthetic("n"));
        agent.start();
        agent.stop();
        agent.start();
        assertFalse(agent.isRunning());
    }

    @Test
    @DisplayName("stop() returns at once while discovery is in progress")
    void stopDuringDiscoveryIsPrompt() throws Exception {
        int freeUdpPort;
        try (DatagramSocket s = new DatagramSocket(0)) {
            freeUdpPort = s.getLocalPort();
        }
        DiscoveryClient discovery = new DiscoveryClient("n", freeUdpPort, 5000, 1, 0, null);
        agent = new TelemetryAgent(discovery, "n", 100, synthetic("n"));
        agent.setInternetMonitoringEnabled(false);
        agent.start();
        Thread.sleep(300);
        long start = System.nanoTime();
        agent.stop();
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 1500, "stop() took " + millis + " ms");
        assertEquals(ConnectionState.STOPPED, agent.getConnectionState());
    }

    @Test
    @DisplayName("A late failure on an old socket does not tear down the current connection")
    void staleSocketFailureIsIgnored() throws Exception {
        ConnectionManager manager = new ConnectionManager("n", "127.0.0.1", server.getPort());
        try (Socket old = new Socket()) {
            assertTrue(manager.connect());
            manager.notifyConnectionLost(old, new IOException("late failure"));
            assertTrue(manager.isConnected());
        } finally {
            manager.stop();
        }
    }

    @Test
    @DisplayName("A server that cannot be addressed keeps the agent retrying instead of failing")
    void unexpectedConnectErrorKeepsRetrying() throws Exception {
        ConnectionManager manager = new ConnectionManager("n", "127.0.0.1", 70_000);
        manager.updateBackoff(20, 40);
        try {
            assertFalse(manager.connect());
            assertTrue(await(() -> manager.getRetryCount() >= 3));
        } finally {
            manager.stop();
        }
    }

    @Test
    @DisplayName("The probe port announced by a discovered server is used")
    void usesDiscoveredProbePort() throws Exception {
        int udpPort;
        try (DatagramSocket s = new DatagramSocket(0)) {
            udpPort = s.getLocalPort();
        }
        DiscoveryServer discoveryServer = new DiscoveryServer("0.0.0.0", udpPort, "127.0.0.1", server.getPort(),
                45_678, "srv");
        discoveryServer.start();
        try {
            agent = new TelemetryAgent(new DiscoveryClient("n", udpPort, 800, 1, 0, null), "n", 100, synthetic("n"));
            agent.setInternetMonitoringEnabled(false);
            agent.start();
            assertTrue(await(() -> agent.getMessagesSent() > 0));
            assertEquals(45_678, agent.getProbePort());
        } finally {
            discoveryServer.stop();
        }
    }

    @Test
    @DisplayName("A stopped cluster can be started again, with chaos enabled")
    void clusterCanBeRestarted() throws Exception {
        MockCluster cluster = new MockCluster("127.0.0.1", server.getPort(), 3, 100L, true, "re-");
        try {
            cluster.start();
            cluster.start();
            assertEquals(3, cluster.getActiveAgents().size(), "A second start() must not add agents");
            cluster.stop();
            cluster.start();
            assertTrue(await(() -> registry.size() == 3));
            assertNull(registry.getNode("re-01").orElseThrow().getInternetMetrics());
        } finally {
            cluster.stop();
        }
    }
}
