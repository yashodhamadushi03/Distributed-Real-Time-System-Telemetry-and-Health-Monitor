package com.pulsenet.ui;

import com.pulsenet.common.cli.CliArgs;
import com.pulsenet.common.model.InternetConnectivityMetrics;
import com.pulsenet.common.model.NetworkMetrics;
import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.server.registry.NodeRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FleetSummaryTest {

    private static NodeRecord node(String id, double cpu, String status, NetworkMetrics net, String internet) {
        TelemetryPayload p = new TelemetryPayload(id, 1, cpu, 50, 60, 10, status, net);
        if (internet != null) {
            InternetConnectivityMetrics im = new InternetConnectivityMetrics();
            im.setStatus(internet);
            p.setInternetMetrics(im);
        }
        return new NodeRecord(id, p, "x", System.currentTimeMillis());
    }

    private static NetworkMetrics measured(double rtt, double jitter, double loss) {
        NetworkMetrics net = new NetworkMetrics();
        net.setAvgRttMs(rtt);
        net.setAvgJitterMs(jitter);
        net.setPacketLossPercent(loss);
        net.setProbesReceived(10);
        return net;
    }

    @Test
    @DisplayName("Nothing measured means N/A, not zero")
    void emptyFleet() {
        FleetSummary s = FleetSummary.of(List.of());
        assertEquals(0, s.total());
        assertTrue(Double.isNaN(s.cpu()) && Double.isNaN(s.rtt()) && Double.isNaN(s.loss()));

        FleetSummary unmeasured = FleetSummary.of(List.of(node("a", 1, "HEALTHY", new NetworkMetrics(), "UNKNOWN")));
        assertTrue(Double.isNaN(unmeasured.rtt()) && Double.isNaN(unmeasured.jitter()) && Double.isNaN(unmeasured.loss()));
        assertEquals(0, unmeasured.internetReporting());
    }

    @Test
    @DisplayName("Offline nodes are left out of averages and warnings are counted")
    void averages() {
        NodeRecord offline = node("c", 99, "HEALTHY", measured(500, 50, 100), "ONLINE");
        offline.setStatus("OFFLINE");
        FleetSummary s = FleetSummary.of(List.of(
                node("a", 10, "HEALTHY", measured(2, -1, 0), "ONLINE"),
                node("b", 30, "WARNING", measured(4, 3, 10), "LIMITED"), offline));
        assertEquals(3, s.total());
        assertEquals(2, s.online());
        assertEquals(1, s.offline());
        assertEquals(1, s.warnings());
        assertEquals(20, s.cpu());
        assertEquals(3, s.rtt());
        assertEquals(3, s.jitter(), "The unmeasured jitter (-1) must not be averaged in");
        assertEquals(5, s.loss());
        assertEquals(1, s.internetOnline());
        assertEquals(2, s.internetReporting());
    }

    @Test
    @DisplayName("History keeps the newest samples and measures availability")
    void history() {
        NodeHistory h = new NodeHistory();
        assertTrue(Double.isNaN(h.availabilityPercent()));
        for (int i = 0; i < NodeHistory.CAPACITY + 5; i++) {
            h.add(NodeHistory.Metric.CPU, i);
        }
        double[] values = h.values(NodeHistory.Metric.CPU);
        assertEquals(NodeHistory.CAPACITY, values.length);
        assertEquals(5, values[0]);
        assertEquals(0, h.values(NodeHistory.Metric.RTT).length);
        h.addAvailability(true);
        h.addAvailability(false);
        assertEquals(50, h.availabilityPercent());
    }

    @Test
    @DisplayName("Each UI server announces its own identifier; invalid ports are refused")
    void options() {
        UiOptions defaults = UiOptions.parse(new String[0]);
        assertEquals(9000, defaults.tcpPort());
        assertTrue(defaults.serverId().startsWith("pulsenet-"));
        assertNotEquals(defaults.serverId(), UiOptions.parse(new String[]{"--port", "9100"}).serverId());
        assertThrows(CliArgs.UsageException.class, () -> UiOptions.parse(new String[]{"--port", "x"}));
        assertThrows(CliArgs.UsageException.class,
                () -> UiOptions.parse(new String[]{"--udp-port", "9002"}), "Discovery and probe ports must differ");
    }
}
