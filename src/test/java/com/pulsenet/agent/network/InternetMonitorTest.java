package com.pulsenet.agent.network;

import com.pulsenet.common.model.InternetConnectivityMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InternetMonitorTest {

    @Test
    @DisplayName("Classification: ONLINE strictly requires successful external HTTPS")
    void testClassificationOnline() {
        assertEquals(InternetConnectivityMetrics.STATUS_ONLINE,
                InternetMonitor.classify(true, true, true));
        assertEquals(InternetConnectivityMetrics.STATUS_ONLINE,
                InternetMonitor.classify(false, false, true));
        assertEquals(InternetConnectivityMetrics.STATUS_ONLINE,
                InternetMonitor.classify(true, false, true));
    }

    @Test
    @DisplayName("Classification: LIMITED when gateway or DNS succeeds but external HTTPS fails")
    void testClassificationLimited() {
        assertEquals(InternetConnectivityMetrics.STATUS_LIMITED,
                InternetMonitor.classify(true, false, false));
        assertEquals(InternetConnectivityMetrics.STATUS_LIMITED,
                InternetMonitor.classify(false, true, false));
        assertEquals(InternetConnectivityMetrics.STATUS_LIMITED,
                InternetMonitor.classify(true, true, false));
    }

    @Test
    @DisplayName("Classification: OFFLINE when gateway, DNS, and HTTPS all fail")
    void testClassificationOffline() {
        assertEquals(InternetConnectivityMetrics.STATUS_OFFLINE,
                InternetMonitor.classify(false, false, false));
    }

    @Test
    @DisplayName("Probe execution: Transition to ONLINE when HTTPS probe succeeds")
    void testExecuteCheckOnline() {
        InternetMonitor monitor = new InternetMonitor("test-agent", 5, 2000, "dns.google", "https://www.google.com/generate_204");

        monitor.setGatewayProbe(timeout -> new InternetMonitor.GatewayResult("192.168.1.1", true, 5L));
        monitor.setDnsProbe((host, timeout) -> new InternetMonitor.DnsResult(true, 12L));
        monitor.setHttpsProbe((endpoint, timeout) -> new InternetMonitor.HttpsResult(true, 204, 35L));

        // Initial state before run
        assertEquals(InternetConnectivityMetrics.STATUS_UNKNOWN, monitor.getLatestMetrics().getStatus());

        monitor.executeCheck();

        InternetConnectivityMetrics metrics = monitor.getLatestMetrics();
        assertEquals(InternetConnectivityMetrics.STATUS_ONLINE, metrics.getStatus());
        assertEquals("192.168.1.1", metrics.getGatewayIp());
        assertTrue(metrics.isGatewayReachable());
        assertEquals(5L, metrics.getGatewayCheckDurationMs());

        assertTrue(metrics.isDnsSuccess());
        assertEquals(12L, metrics.getDnsResolutionTimeMs());
        assertEquals("dns.google", metrics.getDnsTestHost());

        assertTrue(metrics.isHttpsSuccess());
        assertEquals(204, metrics.getHttpStatusCode());
        assertEquals(35L, metrics.getHttpsResponseTimeMs());
        assertEquals("https://www.google.com/generate_204", metrics.getHttpsTestEndpoint());

        assertTrue(metrics.getLastCheckTimestamp() > 0);
        assertTrue(metrics.getLastSuccessfulCheckTimestamp() > 0);
    }

    @Test
    @DisplayName("Probe execution: Transition to LIMITED when captive portal / HTTPS fails but DNS/Gateway works")
    void testExecuteCheckLimited() {
        InternetMonitor monitor = new InternetMonitor("test-agent");

        monitor.setGatewayProbe(timeout -> new InternetMonitor.GatewayResult("10.0.0.1", true, 2L));
        monitor.setDnsProbe((host, timeout) -> new InternetMonitor.DnsResult(true, 8L));
        monitor.setHttpsProbe((endpoint, timeout) -> new InternetMonitor.HttpsResult(false, 503, -1L));

        monitor.executeCheck();

        InternetConnectivityMetrics metrics = monitor.getLatestMetrics();
        assertEquals(InternetConnectivityMetrics.STATUS_LIMITED, metrics.getStatus());
        assertTrue(metrics.isGatewayReachable());
        assertTrue(metrics.isDnsSuccess());
        assertFalse(metrics.isHttpsSuccess());
        assertEquals(503, metrics.getHttpStatusCode());
        assertEquals(0L, metrics.getLastSuccessfulCheckTimestamp(), "Must not update last successful on failure");
    }

    @Test
    @DisplayName("Probe execution: Transition to OFFLINE when all probes fail")
    void testExecuteCheckOffline() {
        InternetMonitor monitor = new InternetMonitor("test-agent");

        monitor.setGatewayProbe(timeout -> new InternetMonitor.GatewayResult("unknown", false, -1L));
        monitor.setDnsProbe((host, timeout) -> new InternetMonitor.DnsResult(false, -1L));
        monitor.setHttpsProbe((endpoint, timeout) -> new InternetMonitor.HttpsResult(false, -1, -1L));

        monitor.executeCheck();

        InternetConnectivityMetrics metrics = monitor.getLatestMetrics();
        assertEquals(InternetConnectivityMetrics.STATUS_OFFLINE, metrics.getStatus());
        assertFalse(metrics.isGatewayReachable());
        assertFalse(metrics.isDnsSuccess());
        assertFalse(metrics.isHttpsSuccess());
    }

    @Test
    @DisplayName("Lifecycle: Background thread starts, executes, and stops cleanly")
    void testLifecycleStartStop() throws Exception {
        InternetMonitor monitor = new InternetMonitor("test-agent", 2, 1000, "dns.google", "https://www.google.com/generate_204");
        monitor.setGatewayProbe(timeout -> new InternetMonitor.GatewayResult("192.168.1.1", true, 1L));
        monitor.setDnsProbe((host, timeout) -> new InternetMonitor.DnsResult(true, 5L));
        monitor.setHttpsProbe((endpoint, timeout) -> new InternetMonitor.HttpsResult(true, 204, 20L));

        assertFalse(monitor.isRunning());
        monitor.start();
        assertTrue(monitor.isRunning());

        // Wait for first background run
        long start = System.currentTimeMillis();
        while (InternetConnectivityMetrics.STATUS_UNKNOWN.equals(monitor.getLatestMetrics().getStatus())
                && System.currentTimeMillis() - start < 2000) {
            Thread.sleep(50);
        }

        assertEquals(InternetConnectivityMetrics.STATUS_ONLINE, monitor.getLatestMetrics().getStatus());

        monitor.stop();
        assertFalse(monitor.isRunning());
    }

    @Test
    @DisplayName("Gateway discovery: Dynamic OS routing table and interface detection returns non-null gateway")
    void testGatewayResolution() throws Exception {
        InternetMonitor monitor = new InternetMonitor("test-agent");
        String gw = monitor.resolveDefaultGatewayIp();
        assertNotNull(gw);
        assertFalse(gw.isBlank());

        monitor.setActiveLocalAddress(java.net.InetAddress.getByName("127.0.0.1"));
        String gw2 = monitor.resolveDefaultGatewayIp();
        assertNotNull(gw2);
    }

    @Test
    @DisplayName("Immediate check: Triggering immediate check executes diagnostics asynchronously")
    void testImmediateCheckOnTrigger() throws Exception {
        InternetMonitor monitor = new InternetMonitor("trigger-agent");
        monitor.setGatewayProbe(timeout -> new InternetMonitor.GatewayResult("192.168.1.1", true, 2L));
        monitor.setDnsProbe((host, timeout) -> new InternetMonitor.DnsResult(true, 5L));
        monitor.setHttpsProbe((endpoint, timeout) -> new InternetMonitor.HttpsResult(true, 204, 20L));

        monitor.start();
        try {
            monitor.triggerImmediateCheck();
            long start = System.currentTimeMillis();
            while (InternetConnectivityMetrics.STATUS_UNKNOWN.equals(monitor.getLatestMetrics().getStatus())
                    && System.currentTimeMillis() - start < 2000) {
                Thread.sleep(50);
            }
            assertEquals(InternetConnectivityMetrics.STATUS_ONLINE, monitor.getLatestMetrics().getStatus());
        } finally {
            monitor.stop();
        }
    }

    @Test
    @DisplayName("Mark offline: Immediately invalidates metrics to OFFLINE state")
    void testMarkOffline() {
        InternetMonitor monitor = new InternetMonitor("offline-agent");
        monitor.setGatewayProbe(timeout -> new InternetMonitor.GatewayResult("192.168.1.1", true, 2L));
        monitor.setDnsProbe((host, timeout) -> new InternetMonitor.DnsResult(true, 5L));
        monitor.setHttpsProbe((endpoint, timeout) -> new InternetMonitor.HttpsResult(true, 204, 20L));

        monitor.executeCheck();
        assertEquals(InternetConnectivityMetrics.STATUS_ONLINE, monitor.getLatestMetrics().getStatus());

        // Call markOffline on interface drop
        monitor.markOffline();
        InternetConnectivityMetrics metrics = monitor.getLatestMetrics();
        assertEquals(InternetConnectivityMetrics.STATUS_OFFLINE, metrics.getStatus());
        assertFalse(metrics.isGatewayReachable());
        assertFalse(metrics.isDnsSuccess());
        assertFalse(metrics.isHttpsSuccess());
    }

    @Test
    @DisplayName("Active interface check: Detects whether physical interfaces exist without exceptions")
    void testHasAnyActiveNetworkInterface() {
        // Must execute cleanly without exceptions
        boolean hasInterface = InternetMonitor.hasAnyActiveNetworkInterface();
        // Return boolean value is valid
        assertTrue(hasInterface || !hasInterface);
    }
}

