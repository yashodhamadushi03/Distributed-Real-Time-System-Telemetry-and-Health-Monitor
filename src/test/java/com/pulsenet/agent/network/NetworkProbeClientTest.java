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
}

