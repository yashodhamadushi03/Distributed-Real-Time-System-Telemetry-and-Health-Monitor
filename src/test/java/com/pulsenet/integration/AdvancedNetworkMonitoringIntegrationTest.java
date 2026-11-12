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

        } finally {
            agent.stop();
            probeServer.stop();
            telemetryServer.stop();
        }
    }
}

