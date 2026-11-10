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
}
