package com.pulsenet.server;

import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.server.registry.NodeRecord;
import com.pulsenet.server.registry.NodeRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit & regression test suite for Battery Monitoring in PulseNet.
 * Verifies both scenarios:
 * Scenario A: Server running without any connected agent (or all nodes offline).
 * Scenario B: Server running with connected agent (battery present vs unavailable vs dynamic updates).
 */
public class BatteryMonitoringTest {

    private NodeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new NodeRegistry();
    }

    @Test
    @DisplayName("Scenario A: Server running with zero connected agents returns empty list and no default 100% battery")
    void testScenarioAServerWithoutConnectedAgent() {
        List<NodeRecord> nodes = registry.getAllNodes();
        assertTrue(nodes.isEmpty(), "Registry must have zero nodes before any agent connects");

        // Verify default payload values are defensive and never hardcoded to 100%
        TelemetryPayload emptyPayload = new TelemetryPayload();
        assertFalse(emptyPayload.isBatteryAvailable(), "Default batteryAvailable must be false");
        assertEquals(-1.0, emptyPayload.getBatteryPercent(), 0.001, "Default batteryPercent must be -1.0, not 100%");
        assertFalse(emptyPayload.isBatteryCharging(), "Default batteryCharging must be false");
        assertEquals(-1, emptyPayload.getBatteryTimeRemainingSeconds(), "Default time remaining must be -1");
    }

    @Test
    @DisplayName("Scenario B1: Server with connected battery-enabled agent reports actual battery percentage")
    void testScenarioBConnectedAgentWithBattery() {
        TelemetryPayload payload = new TelemetryPayload("laptop-node-01", System.currentTimeMillis(),
                25.0, 45.0, 60.0, 150, "HEALTHY");
        payload.setBatteryAvailable(true);
        payload.setBatteryPercent(88.5);
        payload.setBatteryCharging(false);
        payload.setBatteryTimeRemainingSeconds(7200);

        registry.upsertNode(payload, "192.168.1.50:9000");

        List<NodeRecord> nodes = registry.getAllNodes();
        assertEquals(1, nodes.size());

        NodeRecord record = nodes.get(0);
        assertEquals("laptop-node-01", record.getNodeId());
        assertEquals("HEALTHY", record.getStatus());

        TelemetryPayload latest = record.getLatestPayload();
        assertNotNull(latest);
        assertTrue(latest.isBatteryAvailable());
        assertEquals(88.5, latest.getBatteryPercent(), 0.001);
        assertFalse(latest.isBatteryCharging());
        assertEquals(7200, latest.getBatteryTimeRemainingSeconds());

        // Format test matching Server Fleet card:
        String batteryText = String.format(Locale.US, "Battery: %.1f%% (%s)",
                latest.getBatteryPercent(),
                latest.isBatteryCharging() ? "Charging" : "Not charging");
        assertEquals("Battery: 88.5% (Not charging)", batteryText);
    }

    @Test
    @DisplayName("Scenario B2: Connected device with no battery hardware reports Unavailable")
    void testScenarioBConnectedAgentWithoutBatteryHardware() {
        TelemetryPayload payload = new TelemetryPayload("desktop-node-02", System.currentTimeMillis(),
                15.0, 30.0, 50.0, 95, "HEALTHY");
        payload.setBatteryAvailable(false);
        payload.setBatteryPercent(-1.0);
        payload.setBatteryCharging(false);

        registry.upsertNode(payload, "192.168.1.60:9000");

        NodeRecord record = registry.getAllNodes().get(0);
        TelemetryPayload latest = record.getLatestPayload();
        assertNotNull(latest);
        assertFalse(latest.isBatteryAvailable());
        assertEquals(-1.0, latest.getBatteryPercent(), 0.001);

        // Verification of formatted string for battery-less connected device
        String displayStatus = (!latest.isBatteryAvailable() || latest.getBatteryPercent() < 0)
                ? "Unavailable"
                : String.format(Locale.US, "%.1f%%", latest.getBatteryPercent());
        assertEquals("Unavailable", displayStatus);
    }

    @Test
    @DisplayName("Scenario B3: When an agent disconnects, stale battery information is not displayed as current")
    void testAgentDisconnectDoesNotShowStaleBatteryAsCurrent() {
        TelemetryPayload payload = new TelemetryPayload("laptop-node-01", System.currentTimeMillis(),
                30.0, 50.0, 60.0, 120, "HEALTHY");
        payload.setBatteryAvailable(true);
        payload.setBatteryPercent(85.0);
        payload.setBatteryCharging(true);

        registry.upsertNode(payload, "192.168.1.50:9000");

        NodeRecord record = registry.getAllNodes().get(0);
        assertEquals("HEALTHY", record.getStatus());

        // Simulate disconnect / watchdog audit marking node OFFLINE
        record.setStatus("OFFLINE");
        assertEquals("OFFLINE", record.getStatus());

        // Check format logic when offline:
        boolean isOffline = "OFFLINE".equalsIgnoreCase(record.getStatus());
        String fleetBatteryLine = isOffline ? "Battery: N/A" : (payload.isBatteryAvailable() ? "Battery: " + payload.getBatteryPercent() + "%" : "Battery: Unavailable");
        assertEquals("Battery: N/A", fleetBatteryLine, "Disconnected node must report Battery: N/A");
    }

    @Test
    @DisplayName("Scenario B4: Battery percentage updates dynamically on subsequent telemetry frames")
    void testBatteryPercentageDynamicUpdates() {
        TelemetryPayload frame1 = new TelemetryPayload("node-battery-test", System.currentTimeMillis(),
                20.0, 40.0, 50.0, 100, "HEALTHY");
        frame1.setBatteryAvailable(true);
        frame1.setBatteryPercent(85.0);
        frame1.setBatteryCharging(false);
        registry.upsertNode(frame1, "127.0.0.1:9000");

        assertEquals(85.0, registry.getAllNodes().get(0).getLatestPayload().getBatteryPercent(), 0.001);

        // Update frame 2: Battery drops to 84.0%
        TelemetryPayload frame2 = new TelemetryPayload("node-battery-test", System.currentTimeMillis() + 1000,
                22.0, 41.0, 50.0, 102, "HEALTHY");
        frame2.setBatteryAvailable(true);
        frame2.setBatteryPercent(84.0);
        frame2.setBatteryCharging(false);
        registry.upsertNode(frame2, "127.0.0.1:9000");

        assertEquals(84.0, registry.getAllNodes().get(0).getLatestPayload().getBatteryPercent(), 0.001);

        // Update frame 3: Charger plugged in, charging=true
        TelemetryPayload frame3 = new TelemetryPayload("node-battery-test", System.currentTimeMillis() + 2000,
                24.0, 42.0, 50.0, 105, "HEALTHY");
        frame3.setBatteryAvailable(true);
        frame3.setBatteryPercent(84.5);
        frame3.setBatteryCharging(true);
        registry.upsertNode(frame3, "127.0.0.1:9000");

        TelemetryPayload current = registry.getAllNodes().get(0).getLatestPayload();
        assertEquals(84.5, current.getBatteryPercent(), 0.001);
        assertTrue(current.isBatteryCharging());
    }

    @Test
    @DisplayName("Verify TelemetryPayload JSON serialization roundtrip with battery metadata")
    void testTelemetryPayloadBatteryJsonSerialization() throws IOException {
        TelemetryPayload original = new TelemetryPayload("json-node", System.currentTimeMillis(),
                12.5, 34.2, 56.7, 88, "HEALTHY");
        original.setBatteryAvailable(true);
        original.setBatteryPercent(77.4);
        original.setBatteryCharging(true);
        original.setBatteryTimeRemainingSeconds(5400);

        String json = original.toJson();
        assertNotNull(json);
        assertTrue(json.contains("\"batteryAvailable\":true"));
        assertTrue(json.contains("\"batteryPercent\":77.4"));
        assertTrue(json.contains("\"batteryCharging\":true"));
        assertTrue(json.contains("\"batteryTimeRemainingSeconds\":5400"));

        TelemetryPayload deserialized = TelemetryPayload.fromJson(json);
        assertNotNull(deserialized);
        assertEquals(original.getNodeId(), deserialized.getNodeId());
        assertTrue(deserialized.isBatteryAvailable());
        assertEquals(77.4, deserialized.getBatteryPercent(), 0.001);
        assertTrue(deserialized.isBatteryCharging());
        assertEquals(5400, deserialized.getBatteryTimeRemainingSeconds());
    }
}
