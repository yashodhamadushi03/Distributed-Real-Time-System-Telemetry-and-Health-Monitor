package com.pulsenet.server.dashboard;

import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.server.registry.NodeRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DashboardRendererTest {

    @Test
    @DisplayName("Verify ASCII progress bar formatting")
    void testProgressBarFormatting() {
        NodeRegistry registry = new NodeRegistry();
        DashboardRenderer renderer = new DashboardRenderer(registry, "127.0.0.1", 9000, false);

        String bar0 = renderer.renderProgressBar(0.0, 10);
        assertTrue(bar0.contains("[          ]"));
        assertTrue(bar0.contains("0%"));

        String bar50 = renderer.renderProgressBar(50.0, 10);
        assertTrue(bar50.contains("[|||||     ]"));
        assertTrue(bar50.contains("50%"));

        String bar100 = renderer.renderProgressBar(100.0, 10);
        assertTrue(bar100.contains("[||||||||||]"));
        assertTrue(bar100.contains("100%"));
    }

    @Test
    @DisplayName("Verify dashboard table text generation with connected and offline nodes")
    void testDashboardTableRendering() {
        NodeRegistry registry = new NodeRegistry();
        DashboardRenderer renderer = new DashboardRenderer(registry, "127.0.0.1", 9000, false);

        // Empty state
        String emptyOutput = renderer.buildDashboardString();
        assertTrue(emptyOutput.contains("PulseNet: Distributed Telemetry Aggregator"));
        assertTrue(emptyOutput.contains("No client nodes connected"));

        // Populate nodes
        TelemetryPayload p1 = new TelemetryPayload("node-alpha", System.currentTimeMillis(), 42.0, 58.0, 38.0, 184, "HEALTHY");
        TelemetryPayload p2 = new TelemetryPayload("node-beta", System.currentTimeMillis(), 89.0, 91.0, 64.0, 241, "WARNING");
        registry.upsertNode(p1, "192.168.1.10:5012");
        registry.upsertNode(p2, "192.168.1.11:4822");

        String populatedOutput = renderer.buildDashboardString();
        assertTrue(populatedOutput.contains("node-alpha"));
        assertTrue(populatedOutput.contains("node-beta"));
        assertTrue(populatedOutput.contains("HEALTHY"));
        assertTrue(populatedOutput.contains("WARNING"));
        assertTrue(populatedOutput.contains("Active Nodes: 2"));
    }

    @Test
    @DisplayName("Longest IPv4 endpoint (21 chars) and a 20-char node ID render untruncated")
    void testWideColumnsRenderUntruncated() {
        NodeRegistry registry = new NodeRegistry();
        DashboardRenderer renderer = new DashboardRenderer(registry, "0.0.0.0", 9000, false);

        String longAddress = "255.255.255.255:65535";   // 21 chars
        String longNodeId = "desktop-7qk2m1l-labx";     // 20 chars
        assertEquals(21, longAddress.length());
        assertEquals(20, longNodeId.length());

        registry.upsertNode(new TelemetryPayload(longNodeId, System.currentTimeMillis(),
                10.0, 20.0, 30.0, 100, "HEALTHY"), "/" + longAddress);

        String output = renderer.buildDashboardString();
        assertTrue(output.contains(longAddress), "IP:PORT must not be truncated");
        assertTrue(output.contains(longNodeId), "NODE ID must not be truncated");
        assertFalse(output.contains("~"), "No truncation marker expected");

        // Columns stay aligned: node ID padded to 20, address to 21, each followed by one space.
        assertTrue(output.contains(" " + longNodeId + " " + longAddress + " ["));
    }

    @Test
    @DisplayName("Over-long node IDs are still truncated at the column width")
    void testOverlongNodeIdTruncated() {
        NodeRegistry registry = new NodeRegistry();
        DashboardRenderer renderer = new DashboardRenderer(registry, "127.0.0.1", 9000, false);
        registry.upsertNode(new TelemetryPayload("a-very-long-hostname-that-overflows",
                System.currentTimeMillis(), 1.0, 1.0, 1.0, 1, "HEALTHY"), "10.0.0.1:1");
        String output = renderer.buildDashboardString();
        assertTrue(output.contains("a-very-long-hostnam~"));
    }

    @Test
    @DisplayName("Header shows the display host (LAN IP) instead of the wildcard bind address")
    void testHeaderUsesDisplayHost() {
        NodeRegistry registry = new NodeRegistry();
        DashboardRenderer renderer = new DashboardRenderer(registry, "0.0.0.0", 9000, false,
                System.out, 500L, "192.168.137.1");
        String output = renderer.buildDashboardString();
        assertTrue(output.contains("Server: 192.168.137.1:9000"));
        assertFalse(output.contains("0.0.0.0"));

        DashboardRenderer legacy = new DashboardRenderer(registry, "127.0.0.1", 9000, false);
        assertEquals("127.0.0.1", legacy.getDisplayHost(), "Existing constructors keep showing the bind host");
    }

    @Test
    @DisplayName("Verify Internet Connectivity section rendering for ONLINE and LIMITED nodes")
    void testInternetConnectivityDashboardRendering() {
        NodeRegistry registry = new NodeRegistry();
        DashboardRenderer renderer = new DashboardRenderer(registry, "127.0.0.1", 9000, false);

        TelemetryPayload p1 = new TelemetryPayload("node-online", System.currentTimeMillis(), 10.0, 20.0, 30.0, 100, "HEALTHY");
        com.pulsenet.common.model.InternetConnectivityMetrics inetOnline = new com.pulsenet.common.model.InternetConnectivityMetrics();
        inetOnline.setStatus(com.pulsenet.common.model.InternetConnectivityMetrics.STATUS_ONLINE);
        inetOnline.setGatewayIp("192.168.1.1");
        inetOnline.setGatewayReachable(true);
        inetOnline.setDnsTestHost("dns.google");
        inetOnline.setDnsSuccess(true);
        inetOnline.setDnsResolutionTimeMs(15L);
        inetOnline.setHttpsTestEndpoint("https://www.google.com/generate_204");
        inetOnline.setHttpsSuccess(true);
        inetOnline.setHttpStatusCode(204);
        inetOnline.setHttpsResponseTimeMs(42L);
        inetOnline.setLastCheckTimestamp(System.currentTimeMillis());
        inetOnline.setLastSuccessfulCheckTimestamp(System.currentTimeMillis());
        p1.setInternetMetrics(inetOnline);

        registry.upsertNode(p1, "192.168.1.100:54321");

        String output = renderer.buildDashboardString();
        assertTrue(output.contains("Internet Connectivity:"));
        assertTrue(output.contains("Status: ONLINE"));
        assertTrue(output.contains("Gateway: 192.168.1.1"));
        assertTrue(output.contains("Reachability: REACHABLE"));
        assertTrue(output.contains("OK (15 ms via dns.google)"));
        assertTrue(output.contains("HTTP 204 (42 ms"));
    }
}
