package com.pulsenet.integration;

import com.pulsenet.agent.TelemetryAgent;
import com.pulsenet.agent.connection.ConnectionState;
import com.pulsenet.agent.discovery.DiscoveryClient;
import com.pulsenet.common.discovery.DiscoveredServer;
import com.pulsenet.server.discovery.DiscoveryServer;
import com.pulsenet.server.network.TelemetryServer;
import com.pulsenet.server.registry.NodeRecord;
import com.pulsenet.server.registry.NodeRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryAndReconnectionIntegrationTest {

    private final List<TelemetryServer> servers = new ArrayList<>();
    private final List<DiscoveryServer> discoveryServers = new ArrayList<>();
    private final List<TelemetryAgent> agents = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (TelemetryAgent agent : agents) {
            agent.stop();
        }
        agents.clear();

        for (DiscoveryServer ds : discoveryServers) {
            ds.stop();
        }
        discoveryServers.clear();

        for (TelemetryServer ts : servers) {
            ts.stop();
        }
        servers.clear();
    }

    private static int findFreeUdpPort() throws IOException {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int findFreeTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    @DisplayName("Test 1: Successful automatic UDP discovery of running server")
    void testAutomaticDiscoverySuccessful() throws Exception {
        int udpPort = findFreeUdpPort();
        int tcpPort = findFreeTcpPort();

        DiscoveryServer server = new DiscoveryServer("127.0.0.1", udpPort, "127.0.0.1", tcpPort, "test-server-alpha");
        discoveryServers.add(server);
        server.start();

        DiscoveryClient client = new DiscoveryClient("agent-tester", udpPort, 1500, 2, 500L, null);
        Optional<DiscoveredServer> discovered = client.discover();

        assertTrue(discovered.isPresent(), "Server must be discovered via UDP broadcast");
        DiscoveredServer info = discovered.get();
        assertEquals("test-server-alpha", info.serverId());
        assertEquals(tcpPort, info.tcpPort());
        assertNotNull(info.resolveConnectHost());
    }

    @Test
    @DisplayName("Test 3: Discovery gracefully reports empty when no server is running")
    void testDiscoveryFailureNoServer() throws Exception {
        int unusedUdpPort = findFreeUdpPort();

        DiscoveryClient client = new DiscoveryClient("agent-lonely", unusedUdpPort, 500, 1, 100L, null);
        Optional<DiscoveredServer> discovered = client.discover();

        assertTrue(discovered.isEmpty(), "Discovery should safely return empty when no server responds");
    }

    @Test
    @DisplayName("Test 2: Agent discovers server via UDP, establishes TCP, and streams telemetry")
    void testAgentAutoDiscoveryAndTelemetryStreaming() throws Exception {
        int udpPort = findFreeUdpPort();
        NodeRegistry registry = new NodeRegistry();

        TelemetryServer tcpServer = new TelemetryServer("127.0.0.1", 0, registry);
        servers.add(tcpServer);
        tcpServer.start();
        int tcpPort = tcpServer.getPort();

        DiscoveryServer udpServer = new DiscoveryServer("127.0.0.1", udpPort, "127.0.0.1", tcpPort, "pulsenet-central-server");
        discoveryServers.add(udpServer);
        udpServer.start();

        DiscoveryClient discoveryClient = new DiscoveryClient("auto-node-01", udpPort, 1500, 2, 500L, null);
        TelemetryAgent agent = new TelemetryAgent(discoveryClient, "auto-node-01", 50);
        agents.add(agent);
        agent.start();

        // Verify that agent successfully registered and sent metrics
        boolean registered = awaitCondition(4000, () -> registry.getNode("auto-node-01").isPresent());
        assertTrue(registered, "Agent must discover host, connect over TCP, and register in NodeRegistry");

        Optional<NodeRecord> record = registry.getNode("auto-node-01");
        assertTrue(record.isPresent());
        assertEquals("auto-node-01", record.get().getNodeId());
        assertNotNull(record.get().getLatestPayload());
        assertEquals(ConnectionState.CONNECTED, agent.getConnectionState());
    }

    @Test
    @DisplayName("Test 4 & 5: TCP connection loss detection and automatic recovery with node ID preservation")
    void testAutomaticTcpReconnection() throws Exception {
        int tcpPort = findFreeTcpPort();
        NodeRegistry registry = new NodeRegistry();

        // 1. Start server
        TelemetryServer server1 = new TelemetryServer("127.0.0.1", tcpPort, registry);
        servers.add(server1);
        server1.start();

        // 2. Start agent targeting server port
        TelemetryAgent agent = new TelemetryAgent("127.0.0.1", tcpPort, "reconnect-node", 50);
        agent.setInitialBackoffMillis(200);
        agent.setMaxBackoffMillis(1000);
        agents.add(agent);
        agent.start();

        assertTrue(awaitCondition(3000, () -> registry.getNode("reconnect-node").isPresent()));
        assertEquals(ConnectionState.CONNECTED, agent.getConnectionState());

        // 3. Stop server abruptly (simulates crash / network severed)
        server1.stop();
        servers.remove(server1);

        // Verify agent detects connection loss and enters RECONNECTING
        assertTrue(awaitCondition(3000, () -> agent.getConnectionState() == ConnectionState.RECONNECTING
                || agent.getConnectionState() == ConnectionState.CONNECTION_LOST),
                "Agent must detect connection loss and enter RECONNECTING");

        // 4. Restart server on the same port
        TelemetryServer server2 = new TelemetryServer("127.0.0.1", tcpPort, registry);
        servers.add(server2);
        server2.start();

        // 5. Verify agent reconnects automatically and node status remains healthy / recovered
        assertTrue(awaitCondition(4000, () -> agent.getConnectionState() == ConnectionState.CONNECTED),
                "Agent must automatically reconnect once server restarts");

        Optional<NodeRecord> recoveredRecord = registry.getNode("reconnect-node");
        assertTrue(recoveredRecord.isPresent(), "Original node identity must be retained in registry");
        assertEquals("reconnect-node", recoveredRecord.get().getNodeId());
        assertEquals(1, registry.size(), "Node count must remain 1 (no duplicate records created)");
    }

    @Test
    @DisplayName("Test 7: Multiple agents discover the server concurrently and maintain unique node IDs")
    void testMultipleAgentsDiscoveryAndConcurrentConnection() throws Exception {
        int udpPort = findFreeUdpPort();
        NodeRegistry registry = new NodeRegistry();

        TelemetryServer tcpServer = new TelemetryServer("127.0.0.1", 0, registry);
        servers.add(tcpServer);
        tcpServer.start();
        int tcpPort = tcpServer.getPort();

        DiscoveryServer udpServer = new DiscoveryServer("127.0.0.1", udpPort, "127.0.0.1", tcpPort, "cluster-host");
        discoveryServers.add(udpServer);
        udpServer.start();

        int agentCount = 3;
        for (int i = 1; i <= agentCount; i++) {
            String nodeId = "disc-node-" + i;
            DiscoveryClient dc = new DiscoveryClient(nodeId, udpPort, 1500, 2, 500L, null);
            TelemetryAgent agent = new TelemetryAgent(dc, nodeId, 50);
            agents.add(agent);
            agent.start();
        }

        assertTrue(awaitCondition(5000, () -> registry.size() == agentCount),
                "All agents must discover the host and connect concurrently");
        assertEquals(agentCount, registry.getAllNodes().size());
    }

    @Test
    @DisplayName("Test 8: Graceful shutdown cancels reconnection and enters terminal STOPPED state")
    void testGracefulShutdownCancelsReconnection() {
        TelemetryAgent agent = new TelemetryAgent("127.0.0.1", 9999, "shutdown-node", 50);
        agent.start();

        agent.stop();
        assertEquals(ConnectionState.STOPPED, agent.getConnectionState());
        assertFalse(agent.isRunning());
    }

    @Test
    @DisplayName("Test 9: Agent uses UDP discovery to reconnect when server IP/port changes and resumes telemetry")
    void testServerAddressChangeRediscoveryAndResume() throws Exception {
        int udpPort = findFreeUdpPort();
        int tcpPort1 = findFreeTcpPort();
        int candidatePort2 = findFreeTcpPort();
        while (candidatePort2 == tcpPort1) {
            candidatePort2 = findFreeTcpPort();
        }
        final int tcpPort2 = candidatePort2;

        NodeRegistry registry1 = new NodeRegistry();
        TelemetryServer server1 = new TelemetryServer("127.0.0.1", tcpPort1, registry1);
        servers.add(server1);
        server1.start();

        DiscoveryServer udpServer1 = new DiscoveryServer("127.0.0.1", udpPort, "127.0.0.1", tcpPort1, "server-v1");
        discoveryServers.add(udpServer1);
        udpServer1.start();

        DiscoveryClient discoveryClient = new DiscoveryClient("roaming-node", udpPort, 1000, 2, 200L, null);
        TelemetryAgent agent = new TelemetryAgent(discoveryClient, "roaming-node", 50);
        agent.setInitialBackoffMillis(100);
        agent.setMaxBackoffMillis(500);
        agent.setConnectTimeoutMillis(1000);
        agents.add(agent);
        agent.start();

        // 1. Initial discovery and connection to server 1
        assertTrue(awaitCondition(4000, () -> registry1.getNode("roaming-node").isPresent()),
                "Agent must discover and connect to server 1");
        assertEquals(ConnectionState.CONNECTED, agent.getConnectionState());
        assertEquals(tcpPort1, agent.getServerPort());

        // 2. Server 1 shuts down (simulating server IP/port change or server migration)
        udpServer1.stop();
        discoveryServers.remove(udpServer1);
        server1.stop();
        servers.remove(server1);

        assertTrue(awaitCondition(3000, () -> agent.getConnectionState() == ConnectionState.RECONNECTING
                || agent.getConnectionState() == ConnectionState.CONNECTION_LOST),
                "Agent must detect connection loss");

        // 3. Start Server 2 on a DIFFERENT TCP port, with new DiscoveryServer announcing tcpPort2
        NodeRegistry registry2 = new NodeRegistry();
        TelemetryServer server2 = new TelemetryServer("127.0.0.1", tcpPort2, registry2);
        servers.add(server2);
        server2.start();

        DiscoveryServer udpServer2 = new DiscoveryServer("127.0.0.1", udpPort, "127.0.0.1", tcpPort2, "server-v1");
        discoveryServers.add(udpServer2);
        udpServer2.start();

        // 4. Verify agent discovers new address via UDP discovery, reconnects, and updates target port
        assertTrue(awaitCondition(6000, () -> agent.getConnectionState() == ConnectionState.CONNECTED
                && agent.getServerPort() == tcpPort2),
                "Agent must discover new server coordinates via UDP and reconnect to new port");

        // 5. Verify telemetry streaming resumes automatically on server 2
        assertTrue(awaitCondition(4000, () -> registry2.getNode("roaming-node").isPresent()
                && registry2.getNode("roaming-node").get().getLatestPayload() != null),
                "Telemetry transmission must resume automatically on new server");
    }

    @Test
    @DisplayName("Test 10: Agent started before server starts up discovers and connects automatically")
    void testAgentStartsBeforeServerAndDiscoversWhenServerStarts() throws Exception {
        int udpPort = findFreeUdpPort();
        int tcpPort = findFreeTcpPort();

        DiscoveryClient discoveryClient = new DiscoveryClient("early-bird", udpPort, 500, 1, 100L, null);
        TelemetryAgent agent = new TelemetryAgent(discoveryClient, "early-bird", 50);
        agent.setInitialBackoffMillis(100);
        agent.setMaxBackoffMillis(500);
        agents.add(agent);
        agent.start();

        // Initially no server is running, agent must be in RECONNECTING
        assertTrue(awaitCondition(2000, () -> agent.getConnectionState() == ConnectionState.RECONNECTING));

        // Now start the server
        NodeRegistry registry = new NodeRegistry();
        TelemetryServer tcpServer = new TelemetryServer("127.0.0.1", tcpPort, registry);
        servers.add(tcpServer);
        tcpServer.start();

        DiscoveryServer udpServer = new DiscoveryServer("127.0.0.1", udpPort, "127.0.0.1", tcpPort, "late-server");
        discoveryServers.add(udpServer);
        udpServer.start();

        // Verify agent discovers server on subsequent retry and connects
        assertTrue(awaitCondition(4000, () -> agent.getConnectionState() == ConnectionState.CONNECTED));
        assertTrue(awaitCondition(3000, () -> registry.getNode("early-bird").isPresent()));
    }

    @Test
    @DisplayName("Test 11: Preferred server selection when multiple servers respond")
    void testPreferredServerSelectionWhenMultipleServersRespond() throws Exception {
        int udpPort1 = findFreeUdpPort();
        int udpPort2 = findFreeUdpPort();
        while (udpPort2 == udpPort1) udpPort2 = findFreeUdpPort();

        int tcpPort1 = findFreeTcpPort();
        int candidatePort2 = findFreeTcpPort();
        while (candidatePort2 == tcpPort1) candidatePort2 = findFreeTcpPort();
        final int tcpPort2 = candidatePort2;

        NodeRegistry registry1 = new NodeRegistry();
        TelemetryServer server1 = new TelemetryServer("127.0.0.1", tcpPort1, registry1);
        servers.add(server1);
        server1.start();
        DiscoveryServer udp1 = new DiscoveryServer("127.0.0.1", udpPort1, "127.0.0.1", tcpPort1, "server-one");
        discoveryServers.add(udp1);
        udp1.start();

        NodeRegistry registry2 = new NodeRegistry();
        TelemetryServer server2 = new TelemetryServer("127.0.0.1", tcpPort2, registry2);
        servers.add(server2);
        server2.start();
        DiscoveryServer udp2 = new DiscoveryServer("127.0.0.1", udpPort2, "127.0.0.1", tcpPort2, "server-two");
        discoveryServers.add(udp2);
        udp2.start();

        // Agent probes both UDP ports, configured with preferredServerId = "server-two"
        DiscoveryClient dc = new DiscoveryClient("pref-agent", List.of(udpPort1, udpPort2), 1000, 2, 200L, "server-two", null);
        TelemetryAgent agent = new TelemetryAgent(dc, "pref-agent", 50);
        agents.add(agent);
        agent.start();

        // Must connect to server-two (tcpPort2), not server-one
        assertTrue(awaitCondition(5000, () -> registry2.getNode("pref-agent").isPresent()));
        assertFalse(registry1.getNode("pref-agent").isPresent(), "Must not connect to non-preferred server");
        assertEquals("server-two", agent.getSelectedServerId());
        assertEquals(tcpPort2, agent.getServerPort());
    }

    @Test
    @DisplayName("Test 12: Manual server selection via ServerSelector when multiple servers respond")
    void testManualServerSelectionWhenMultipleServersRespond() throws Exception {
        int udpPort1 = findFreeUdpPort();
        int udpPort2 = findFreeUdpPort();
        while (udpPort2 == udpPort1) udpPort2 = findFreeUdpPort();

        int tcpPort1 = findFreeTcpPort();
        int candidatePort2 = findFreeTcpPort();
        while (candidatePort2 == tcpPort1) candidatePort2 = findFreeTcpPort();
        final int tcpPort2 = candidatePort2;

        NodeRegistry registry1 = new NodeRegistry();
        TelemetryServer server1 = new TelemetryServer("127.0.0.1", tcpPort1, registry1);
        servers.add(server1);
        server1.start();
        DiscoveryServer udp1 = new DiscoveryServer("127.0.0.1", udpPort1, "127.0.0.1", tcpPort1, "srv-alpha");
        discoveryServers.add(udp1);
        udp1.start();

        NodeRegistry registry2 = new NodeRegistry();
        TelemetryServer server2 = new TelemetryServer("127.0.0.1", tcpPort2, registry2);
        servers.add(server2);
        server2.start();
        DiscoveryServer udp2 = new DiscoveryServer("127.0.0.1", udpPort2, "127.0.0.1", tcpPort2, "srv-beta");
        discoveryServers.add(udp2);
        udp2.start();

        // Agent uses selector that chooses "srv-beta"
        DiscoveryClient dc = new DiscoveryClient("selector-agent", List.of(udpPort1, udpPort2), 1000, 2, 200L, null, candidates -> {
            for (DiscoveredServer s : candidates) {
                if ("srv-beta".equals(s.serverId())) return s;
            }
            return candidates.get(0);
        });

        TelemetryAgent agent = new TelemetryAgent(dc, "selector-agent", 50);
        agents.add(agent);
        agent.start();

        // Must connect to srv-beta (registry2), not srv-alpha
        assertTrue(awaitCondition(5000, () -> registry2.getNode("selector-agent").isPresent()));
        assertFalse(registry1.getNode("selector-agent").isPresent(), "Must not connect to unselected server");
        assertEquals("srv-beta", agent.getSelectedServerId());
        assertEquals(tcpPort2, agent.getServerPort());
    }

    @Test
    @DisplayName("Test 13: Reconnection sticks to previously selected serverId and never silently connects to different server")
    void testReconnectionSticksToPreviouslySelectedServerWhenAnotherServerIsAvailable() throws Exception {
        int udpPort1 = findFreeUdpPort();
        int udpPort2 = findFreeUdpPort();
        while (udpPort2 == udpPort1) udpPort2 = findFreeUdpPort();

        int tcpPort1 = findFreeTcpPort();
        int candidatePort2 = findFreeTcpPort();
        while (candidatePort2 == tcpPort1) candidatePort2 = findFreeTcpPort();
        final int tcpPort2 = candidatePort2;

        // Start Server 1 (primary target)
        NodeRegistry registry1 = new NodeRegistry();
        TelemetryServer server1 = new TelemetryServer("127.0.0.1", tcpPort1, registry1);
        servers.add(server1);
        server1.start();
        DiscoveryServer udp1 = new DiscoveryServer("127.0.0.1", udpPort1, "127.0.0.1", tcpPort1, "primary-target");
        discoveryServers.add(udp1);
        udp1.start();

        // Start Server 2 (secondary/bystander server that remains running)
        NodeRegistry registry2 = new NodeRegistry();
        TelemetryServer server2 = new TelemetryServer("127.0.0.1", tcpPort2, registry2);
        servers.add(server2);
        server2.start();
        DiscoveryServer udp2 = new DiscoveryServer("127.0.0.1", udpPort2, "127.0.0.1", tcpPort2, "bystander-srv");
        discoveryServers.add(udp2);
        udp2.start();

        // Agent targets primary-target initially
        DiscoveryClient dc = new DiscoveryClient("loyal-node", List.of(udpPort1, udpPort2), 1000, 2, 200L, "primary-target", null);
        TelemetryAgent agent = new TelemetryAgent(dc, "loyal-node", 50);
        agent.setInitialBackoffMillis(100);
        agent.setMaxBackoffMillis(500);
        agents.add(agent);
        agent.start();

        // Initially connected to server 1
        assertTrue(awaitCondition(4000, () -> registry1.getNode("loyal-node").isPresent()));
        assertEquals("primary-target", agent.getSelectedServerId());

        // Now STOP Server 1 abruptly (Wi-Fi disconnect from Server 1 / crash)
        udp1.stop();
        discoveryServers.remove(udp1);
        server1.stop();
        servers.remove(server1);

        // Verify agent enters reconnecting state
        assertTrue(awaitCondition(3000, () -> agent.getConnectionState() == ConnectionState.RECONNECTING
                || agent.getConnectionState() == ConnectionState.CONNECTION_LOST));

        // Bystander server 2 is STILL RUNNING and broadcasting.
        // Wait 2 seconds and verify that the agent DOES NOT connect to server 2!
        Thread.sleep(2000);
        assertFalse(registry2.getNode("loyal-node").isPresent(),
                "Agent must NOT silently connect to bystander-srv when primary-target is down");
        assertTrue(agent.getConnectionState() == ConnectionState.RECONNECTING,
                "Agent must continue retrying discovery for primary-target");

        // Now bring Server 1 back online!
        TelemetryServer server1Recovered = new TelemetryServer("127.0.0.1", tcpPort1, registry1);
        servers.add(server1Recovered);
        server1Recovered.start();
        DiscoveryServer udp1Recovered = new DiscoveryServer("127.0.0.1", udpPort1, "127.0.0.1", tcpPort1, "primary-target");
        discoveryServers.add(udp1Recovered);
        udp1Recovered.start();

        // Agent must now automatically reconnect to primary-target!
        assertTrue(awaitCondition(5000, () -> agent.getConnectionState() == ConnectionState.CONNECTED
                && registry1.getNode("loyal-node").isPresent()),
                "Agent must automatically reconnect to primary-target once it is back online");
        assertFalse(registry2.getNode("loyal-node").isPresent(), "Must still not have connected to bystander");
    }

    @Test
    @DisplayName("Test 14: Wi-Fi disconnection followed by reconnection recovery with telemetry resumption")
    void testWifiDisconnectionAndReconnectionRecovery() throws Exception {
        int udpPort = findFreeUdpPort();
        int tcpPort = findFreeTcpPort();
        NodeRegistry registry = new NodeRegistry();

        TelemetryServer server = new TelemetryServer("127.0.0.1", tcpPort, registry);
        servers.add(server);
        server.start();

        DiscoveryServer udpServer = new DiscoveryServer("127.0.0.1", udpPort, "127.0.0.1", tcpPort, "wifi-host");
        discoveryServers.add(udpServer);
        udpServer.start();

        DiscoveryClient dc = new DiscoveryClient("wifi-node", udpPort, 1000, 2, 200L, null);
        TelemetryAgent agent = new TelemetryAgent(dc, "wifi-node", 50);
        agent.setInitialBackoffMillis(100);
        agent.setMaxBackoffMillis(500);
        agents.add(agent);
        agent.start();

        assertTrue(awaitCondition(4000, () -> registry.getNode("wifi-node").isPresent()));
        long initialMessages = agent.getMessagesSent();
        assertTrue(initialMessages > 0, "Initial telemetry messages must be sent");

        // Simulate Wi-Fi drop: disconnect socket and stop listeners
        udpServer.stop();
        discoveryServers.remove(udpServer);
        server.stop();
        servers.remove(server);

        assertTrue(awaitCondition(3000, () -> agent.getConnectionState() == ConnectionState.RECONNECTING
                || agent.getConnectionState() == ConnectionState.CONNECTION_LOST));

        // Simulate Wi-Fi restored after 1 second: restart server
        TelemetryServer restoredServer = new TelemetryServer("127.0.0.1", tcpPort, registry);
        servers.add(restoredServer);
        restoredServer.start();

        DiscoveryServer restoredUdp = new DiscoveryServer("127.0.0.1", udpPort, "127.0.0.1", tcpPort, "wifi-host");
        discoveryServers.add(restoredUdp);
        restoredUdp.start();

        // Agent must automatically reconnect and resume telemetry
        assertTrue(awaitCondition(5000, () -> agent.getConnectionState() == ConnectionState.CONNECTED));
        assertTrue(awaitCondition(3000, () -> agent.getMessagesSent() > initialMessages),
                "Telemetry messages must continue incrementing after reconnection");
    }

    private boolean awaitCondition(long timeoutMillis, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}

