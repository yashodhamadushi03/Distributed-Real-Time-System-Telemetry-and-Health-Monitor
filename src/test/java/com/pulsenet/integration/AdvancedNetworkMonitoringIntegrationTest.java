package com.pulsenet.integration;

import com.pulsenet.agent.TelemetryAgent;
import com.pulsenet.common.model.NetworkMetrics;
import com.pulsenet.server.dashboard.DashboardRenderer;
import com.pulsenet.server.network.NetworkProbeServer;
import com.pulsenet.server.network.TelemetryServer;
import com.pulsenet.server.registry.NetworkHistorySample;
import com.pulsenet.server.registry.NodeRecord;
import com.pulsenet.server.registry.NodeRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AdvancedNetworkMonitoringIntegrationTest {

    @Test
    @DisplayName("End-to-End: UDP Probing, TCP Telemetry Streaming, Topology, and Terminal Dashboard Rendering")
    void testEndToEndNetworkMonitoringAndDashboard() throws Exception {
        NodeRegistry registry = new NodeRegistry();

        // Find available ephemeral ports
        int tcpPort;
        try (ServerSocket s = new ServerSocket(0)) {
            tcpPort = s.getLocalPort();
        }
        int probePort = tcpPort + 10;

        TelemetryServer telemetryServer = new TelemetryServer("127.0.0.1", tcpPort, registry);
        NetworkProbeServer probeServer = new NetworkProbeServer("127.0.0.1", probePort);

        telemetryServer.start();
        probeServer.start();

        String nodeId = "monitored-agent-01";
        TelemetryAgent agent = new TelemetryAgent("127.0.0.1", tcpPort, nodeId, 50L);
        agent.setProbePort(probePort);
        agent.start();

        try {
            // Wait for telemetry frames to arrive with network metrics
            long start = System.currentTimeMillis();
            NodeRecord record = null;
            while (System.currentTimeMillis() - start < 5000) {
                Optional<NodeRecord> opt = registry.getNode(nodeId);
                if (opt.isPresent() && opt.get().getNetworkMetrics() != null
                        && opt.get().getNetworkMetrics().getProbesReceived() > 0) {
                    record = opt.get();
                    break;
                }
                Thread.sleep(100);
            }

            assertNotNull(record, "Node record with network metrics should have arrived");
            NetworkMetrics metrics = record.getNetworkMetrics();
            assertNotNull(metrics, "Network metrics must be populated in TelemetryPayload");

            // Verify Latency (RTT)
            assertTrue(metrics.getCurrentRttMs() >= 0.0, "Current RTT should be non-negative");
            assertTrue(metrics.getAvgRttMs() >= 0.0, "Average RTT should be non-negative");

            // Verify Packet Loss & Probes
            assertTrue(metrics.getProbesSent() > 0, "Probes sent should be > 0");
            assertTrue(metrics.getProbesReceived() > 0, "Probes received should be > 0");
            assertEquals(0.0, metrics.getPacketLossPercent(), "Local packet loss should be 0%");

            // Verify Connection Uptime & Status
            assertEquals("CONNECTED", metrics.getConnectionStatus());
            assertTrue(metrics.getConnectionUptimeMillis() > 0, "Uptime should be positive");
            assertEquals(0, metrics.getReconnectionCount(), "Initial connection count should be 0");

            // Verify Topology details
            assertNotNull(record.getAgentHostname(), "Hostname should be populated");
            assertEquals("TCP + UDP probes", record.getProtocol());

            // Verify Network Performance History
            List<NetworkHistorySample> history = registry.getNetworkHistory(nodeId);
            assertFalse(history.isEmpty(), "History registry should contain recorded samples");
            assertTrue(history.get(history.size() - 1).currentRttMs() >= 0.0, "Latest history sample should have valid RTT");

            // Verify Dashboard Terminal Output Rendering
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DashboardRenderer dashboard = new DashboardRenderer(registry, "127.0.0.1", tcpPort, false, new PrintStream(out), 500L);
            String dashboardText = dashboard.buildDashboardString();

            assertNotNull(dashboardText);
            assertTrue(dashboardText.contains("NETWORK PERFORMANCE"), "Dashboard must contain NETWORK PERFORMANCE section");
            assertTrue(dashboardText.contains("Latency:"), "Dashboard must show Latency section");
            assertTrue(dashboardText.contains("Current RTT:"), "Dashboard must show Current RTT");
            assertTrue(dashboardText.contains("Network Quality:"), "Dashboard must show Network Quality");
            assertTrue(dashboardText.contains("Packet Loss:"), "Dashboard must show Packet Loss");
            assertTrue(dashboardText.contains("Average Jitter:"), "Dashboard must show Average Jitter");
            assertTrue(dashboardText.contains("Connection Uptime:"), "Dashboard must show Connection Uptime");
            assertTrue(dashboardText.contains("Probe Statistics:"), "Dashboard must show Probe Statistics");
            assertTrue(dashboardText.contains("Packets Sent:"), "Dashboard must show Packets Sent");

            // Verify Internet Connectivity Metrics
            assertNotNull(record.getInternetMetrics(), "Internet metrics must be populated in TelemetryPayload");
            assertTrue(dashboardText.contains("Internet Connectivity:"), "Dashboard must show Internet Connectivity section");
            assertTrue(dashboardText.contains("Gateway:"), "Dashboard must show Gateway info");
            assertTrue(dashboardText.contains("DNS"), "Dashboard must show DNS info");
            assertTrue(dashboardText.contains("External HTTPS:"), "Dashboard must show External HTTPS info");
            assertTrue(dashboardText.contains("Cum Loss:"), "Dashboard must show cumulative loss indicator");

            // Verify Stale / Disconnected Internet state rendering when node goes OFFLINE
            agent.stop();
            Thread.sleep(50);
            registry.auditStaleNodes(0.001);
            String offlineDashboardText = dashboard.buildDashboardString();
            assertTrue(offlineDashboardText.contains("OFFLINE (Node Disconnected)"), "Dashboard must show OFFLINE status when node is disconnected");
            assertTrue(offlineDashboardText.contains("UNREACHABLE (Node Offline)"), "Dashboard must show UNREACHABLE gateway when node is offline");
            assertTrue(offlineDashboardText.contains("STALE (Node Disconnected)"), "Dashboard must show STALE DNS / HTTPS when node is offline");

        } finally {
            agent.stop();
            probeServer.stop();
            telemetryServer.stop();
        }
    }

    @Test
    @DisplayName("Real Agent Lifecycle: Automatic start on connect, live metrics collection, and recovery after network drop")
    void testPhysicalAgentNetworkMonitoringAndReconnectionRecovery() throws Exception {
        NodeRegistry registry = new NodeRegistry();

        int tcpPort;
        try (ServerSocket s = new ServerSocket(0)) {
            tcpPort = s.getLocalPort();
        }
        int probePort = tcpPort + 20;

        TelemetryServer telemetryServer = new TelemetryServer("127.0.0.1", tcpPort, registry);
        NetworkProbeServer probeServer = new NetworkProbeServer("127.0.0.1", probePort);

        telemetryServer.start();
        probeServer.start();

        String nodeId = "desktop-af94s61";
        TelemetryAgent agent = new TelemetryAgent("127.0.0.1", tcpPort, nodeId, 50L);
        agent.setProbePort(probePort);
        agent.setInitialBackoffMillis(200);
        agent.setMaxBackoffMillis(1000);
        agent.start();

        try {
            // 1. Wait for initial connection and live metrics arrival
            long start = System.currentTimeMillis();
            NodeRecord record = null;
            while (System.currentTimeMillis() - start < 5000) {
                Optional<NodeRecord> opt = registry.getNode(nodeId);
                if (opt.isPresent() && opt.get().getNetworkMetrics() != null
                        && opt.get().getNetworkMetrics().getProbesReceived() > 0) {
                    record = opt.get();
                    break;
                }
                Thread.sleep(100);
            }

            assertNotNull(record, "Physical node desktop-af94s61 record must be created");
            NetworkMetrics net1 = record.getNetworkMetrics();
            assertNotNull(net1);
            assertTrue(net1.getCurrentRttMs() >= 0.0);
            assertTrue(net1.getProbesSent() > 0);
            assertTrue(net1.getProbesReceived() > 0);
            assertEquals(0, net1.getReconnectionCount());
            assertTrue(net1.getConnectionUptimeMillis() > 0);
            assertNotNull(record.getInternetMetrics(), "Internet metrics must be populated");

            long initialProbesSent = net1.getProbesSent();

            // 2. Simulate Wi-Fi drop: abruptly drop active client socket
            agent.getConnectionManager().notifyConnectionLost(new java.net.SocketException("Simulated Wi-Fi disconnect"));

            // 3. Wait for automatic reconnection
            start = System.currentTimeMillis();
            boolean reconnected = false;
            while (System.currentTimeMillis() - start < 6000) {
                Optional<NodeRecord> opt = registry.getNode(nodeId);
                if (opt.isPresent() && opt.get().getNetworkMetrics() != null
                        && opt.get().getNetworkMetrics().getReconnectionCount() >= 1
                        && opt.get().getNetworkMetrics().getProbesSent() > initialProbesSent) {
                    record = opt.get();
                    reconnected = true;
                    break;
                }
                Thread.sleep(150);
            }

            assertTrue(reconnected, "Agent desktop-af94s61 must automatically reconnect and resume probes without process restart");
            NetworkMetrics net2 = record.getNetworkMetrics();
            assertTrue(net2.getReconnectionCount() >= 1, "Reconnection count must be >= 1");
            assertTrue(net2.getProbesSent() > initialProbesSent, "Probe transmission must continue after reconnection");
            assertEquals("CONNECTED", net2.getConnectionStatus());

        } finally {
            agent.stop();
            probeServer.stop();
            telemetryServer.stop();
        }
    }
}

