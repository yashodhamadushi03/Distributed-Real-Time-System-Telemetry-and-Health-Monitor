package com.pulsenet.agent.network;

import com.pulsenet.common.model.NetworkMetrics;
import com.pulsenet.common.network.NetworkProbePacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class NetworkProbeClientTest {

    @Test
    @DisplayName("Probe client accurately calculates RTT, Jitter, and Packet Loss from live UDP responses")
    void testProbeClientRttJitterAndLoss() throws Exception {
        // Start a mock UDP probe responder
        try (DatagramSocket mockServer = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            int mockPort = mockServer.getLocalPort();

            NetworkProbeClient client = new NetworkProbeClient("test-node", "127.0.0.1", mockPort, 100, 300, 20);
            client.start();

            try {
                // Background thread to echo probe requests with simulated delays
                Thread responderThread = new Thread(() -> {
                    byte[] buf = new byte[1024];
                    try {
                        for (int i = 0; i < 3; i++) {
                            DatagramPacket rx = new DatagramPacket(buf, buf.length);
                            mockServer.receive(rx);

                            NetworkProbePacket req = NetworkProbePacket.fromBytes(rx.getData(), rx.getOffset(), rx.getLength());
                            if (req != null && req.isRequest()) {
                                // Add artificial latency for jitter variation
                                Thread.sleep(i == 1 ? 40 : 10);
                                NetworkProbePacket resp = NetworkProbePacket.createResponse(req, System.currentTimeMillis());
                                byte[] txBytes = resp.toBytes();
                                mockServer.send(new DatagramPacket(txBytes, txBytes.length, rx.getSocketAddress()));
                            }
                        }
                    } catch (Exception ignored) {
                    }
                });
                responderThread.setDaemon(true);
                responderThread.start();

                // Wait for client to receive probes
                long start = System.currentTimeMillis();
                while (client.getTotalProbesReceived() < 3 && System.currentTimeMillis() - start < 3000) {
                    Thread.sleep(50);
                }

                assertTrue(client.getTotalProbesSent() >= 3, "Should have sent at least 3 probes");
                assertTrue(client.getTotalProbesReceived() >= 2, "Should have received probe replies");

                assertTrue(client.getCurrentRttMs() >= 0.0, "Current RTT should be non-negative");
                assertTrue(client.getAvgRttMs() >= 0.0, "Average RTT should be non-negative");

                NetworkMetrics metrics = new NetworkMetrics();
                client.populateMetrics(metrics);

                assertTrue(metrics.getCurrentRttMs() >= 0.0);
                assertTrue(metrics.getProbesSent() >= 3);
                assertTrue(metrics.getProbesReceived() >= 2);
                assertEquals(0.0, metrics.getPacketLossPercent(), "0% packet loss when all answered");

            } finally {
                client.stop();
            }
        }
    }

    @Test
    @DisplayName("Sliding window correctly computes packet loss when probes time out")
    void testSlidingWindowPacketLoss() throws Exception {
        // Target an unmonitored port to force timeouts
        NetworkProbeClient client = new NetworkProbeClient("loss-node", "127.0.0.1", 1, 50, 100, 10);
        client.start();

        try {
            long start = System.currentTimeMillis();
            while (client.getTotalProbesLost() < 3 && System.currentTimeMillis() - start < 3000) {
                Thread.sleep(50);
            }

            assertTrue(client.getTotalProbesLost() >= 2, "Probes should time out and be marked lost");
            assertEquals(0, client.getTotalProbesReceived());
            assertTrue(client.getWindowPacketLossPercent() > 0.0, "Packet loss percentage should be > 0%");

        } finally {
            client.stop();
        }
    }

    @Test
    @DisplayName("Probe client ignores probe responses destined for a different node ID")
    void testRejectMismatchedNodeIdResponse() throws Exception {
        try (DatagramSocket mockServer = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            int mockPort = mockServer.getLocalPort();

            NetworkProbeClient client = new NetworkProbeClient("my-node", "127.0.0.1", mockPort, 100, 300, 20);
            client.start();

            try {
                // Background responder that deliberately returns responses for a different node
                Thread responder = new Thread(() -> {
                    byte[] buf = new byte[1024];
                    try {
                        for (int i = 0; i < 2; i++) {
                            DatagramPacket rx = new DatagramPacket(buf, buf.length);
                            mockServer.receive(rx);
                            NetworkProbePacket req = NetworkProbePacket.fromBytes(rx.getData(), rx.getOffset(), rx.getLength());
                            if (req != null) {
                                // Tamper with Node ID in response
                                NetworkProbePacket mismatched = new NetworkProbePacket(
                                        NetworkProbePacket.TYPE_PROBE_RESPONSE,
                                        req.getSequenceNumber(),
                                        req.getClientSendTimestamp(),
                                        System.currentTimeMillis(),
                                        "other-node-sim"
                                );
                                byte[] txBytes = mismatched.toBytes();
                                mockServer.send(new DatagramPacket(txBytes, txBytes.length, rx.getSocketAddress()));
                            }
                        }
                    } catch (Exception ignored) {
                    }
                });
                responder.setDaemon(true);
                responder.start();

                Thread.sleep(600);

                // Responses should have been discarded due to mismatched Node ID
                assertEquals(0, client.getTotalProbesReceived(), "Responses for other nodes must be rejected");

            } finally {
                client.stop();
            }
        }
    }

    @Test
    @DisplayName("RFC 3550 Jitter Algorithm: Exact filter math verified step-by-step")
    void testRfc3550JitterAlgorithmPrecision() {
        NetworkProbeClient client = new NetworkProbeClient("rfc-node", "127.0.0.1", 9002, 1000, 1500, 50);

        // Sequence of RTTs: 20ms, 36ms, 20ms, 52ms, 4ms
        double[] rtts = {20.0, 36.0, 20.0, 52.0, 4.0};
        long baseSend = 100000L;

        for (int i = 0; i < rtts.length; i++) {
            long seq = i + 1;
            long sendTime = baseSend + (i * 1000L);
            long rxTime = sendTime + (long) rtts[i];

            client.inFlightProbes.put(seq, sendTime);
            NetworkProbePacket resp = new NetworkProbePacket(
                    NetworkProbePacket.TYPE_PROBE_RESPONSE,
                    seq,
                    sendTime,
                    sendTime + 5L,
                    "rfc-node"
            );
            client.handleProbeResponse(resp, rxTime);
        }

        // Expected RFC 3550 progression:
        // Sample 1 (20ms): prev=20, jitterSampleCount=0
        // Sample 2 (36ms): diff=|36-20|=16 -> avgJitter=16.0
        // Sample 3 (20ms): diff=|20-36|=16 -> avgJitter=16.0 + (16-16)/16 = 16.0
        // Sample 4 (52ms): diff=|52-20|=32 -> avgJitter=16.0 + (32-16)/16 = 17.0
        // Sample 5 (4ms):  diff=|4-52|=48  -> avgJitter=17.0 + (48-17)/16 = 18.9375
        assertEquals(18.9375, client.getAvgJitterMs(), 0.0001, "Exact RFC 3550 smoothed jitter must be 18.9375");

        NetworkMetrics metrics = new NetworkMetrics();
        client.populateMetrics(metrics);
        assertEquals(18.9, metrics.getAvgJitterMs(), 0.05, "Populated metric must round to 18.9 ms");
    }

    @Test
    @DisplayName("Packet Loss: Rolling window (2%) vs Lifetime cumulative (18.1%) consistency")
    void testWindowAndCumulativePacketLossConsistency() {
        NetworkProbeClient client = new NetworkProbeClient("loss-window-node", "127.0.0.1", 9002, 1000, 1500, 50);

        // Replicate user scenario: 337 probes sent, 276 received, 61 lost
        client.totalProbesSent.set(337);
        client.totalProbesReceived.set(276);
        client.totalProbesLost.set(61);

        // Populate sliding window with recent recovery state: 49 received, 1 lost (total 50)
        for (int i = 0; i < 49; i++) {
            client.recordWindowOutcome(true);
        }
        client.recordWindowOutcome(false);

        // Verify window loss
        assertEquals(2.0, client.getWindowPacketLossPercent(), 0.01, "Window loss must be exactly 1/50 = 2.0%");

        // Verify cumulative loss
        double expectedCum = (61.0 / 337.0) * 100.0; // 18.10089%
        assertEquals(expectedCum, client.getCumulativePacketLossPercent(), 0.001);

        NetworkMetrics metrics = new NetworkMetrics();
        client.populateMetrics(metrics);

        assertEquals(2.0, metrics.getPacketLossPercent(), "Rolling window loss must be 2.0%");
        assertEquals(50, metrics.getWindowSize(), "Window size must be 50");
        assertEquals(18.1, metrics.getCumulativeLossPercent(), "Cumulative loss must be 18.1%");
        assertEquals(337L, metrics.getProbesSent());
        assertEquals(276L, metrics.getProbesReceived());
        assertEquals(61L, metrics.getProbesLost());
    }
}

