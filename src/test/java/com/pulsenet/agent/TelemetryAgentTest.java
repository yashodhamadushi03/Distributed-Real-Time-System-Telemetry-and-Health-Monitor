package com.pulsenet.agent;

import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.common.protocol.WireProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryAgentTest {

    @Test
    @DisplayName("Exit Gate: Stream 5 telemetry messages from TelemetryAgent to a temporary ServerSocket")
    void testStreamFiveTelemetryMessages() throws Exception {
        int targetMessages = 5;
        List<TelemetryPayload> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(targetMessages);

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try (Socket clientSocket = serverSocket.accept();
                     InputStream in = clientSocket.getInputStream()) {
                    while (receivedMessages.size() < targetMessages) {
                        String json = WireProtocol.receiveFramedMessage(in);
                        if (json == null) break;
                        TelemetryPayload payload = TelemetryPayload.fromJson(json);
                        receivedMessages.add(payload);
                        latch.countDown();
                    }
                } catch (Exception ignored) {
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            TelemetryAgent agent = new TelemetryAgent("127.0.0.1", port, "test-node-01", 50);
            agent.start();

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            agent.stop();

            assertTrue(completed, "Should receive 5 telemetry messages within timeout");
            assertEquals(targetMessages, receivedMessages.size());

            for (TelemetryPayload payload : receivedMessages) {
                assertEquals("test-node-01", payload.getNodeId());
                assertTrue(payload.getTimestamp() > 0);
                assertTrue(payload.getCpuPercent() >= 0.0 && payload.getCpuPercent() <= 100.0);
                assertTrue(payload.getMemoryPercent() >= 0.0 && payload.getMemoryPercent() <= 100.0);
                assertTrue(payload.getDiskPercent() >= 0.0 && payload.getDiskPercent() <= 100.0);
                assertTrue(payload.getProcessCount() > 0);
                assertNotNull(payload.getStatus());
            }
        }
    }

    @Test
    @DisplayName("Verify agent auto-reconnect resilience after socket drop")
    void testAgentReconnectResilience() throws Exception {
        List<String> receivedBatches = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstMessageLatch = new CountDownLatch(1);
        CountDownLatch secondMessageLatch = new CountDownLatch(1);

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try {
                    // First connection: accept, read 1 message, then abruptly close socket
                    Socket socket1 = serverSocket.accept();
                    InputStream in1 = socket1.getInputStream();
                    String msg1 = WireProtocol.receiveFramedMessage(in1);
                    if (msg1 != null) {
                        receivedBatches.add("batch1");
                        firstMessageLatch.countDown();
                    }
                    socket1.close(); // Abrupt drop!

                    // Second connection: accept reconnect, read next message
                    Socket socket2 = serverSocket.accept();
                    InputStream in2 = socket2.getInputStream();
                    String msg2 = WireProtocol.receiveFramedMessage(in2);
                    if (msg2 != null) {
                        receivedBatches.add("batch2");
                        secondMessageLatch.countDown();
                    }
                    socket2.close();
                } catch (Exception ignored) {
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            TelemetryAgent agent = new TelemetryAgent("127.0.0.1", port, "reconnect-node", 50);
            agent.setInitialBackoffMillis(100); // Fast retry for testing
            agent.setMaxBackoffMillis(500);
            agent.start();

            assertTrue(firstMessageLatch.await(3, TimeUnit.SECONDS), "First message should arrive");
            assertTrue(secondMessageLatch.await(4, TimeUnit.SECONDS), "Agent should reconnect and send second message");
            agent.stop();

            assertEquals(2, receivedBatches.size());
            assertEquals("batch1", receivedBatches.get(0));
            assertEquals("batch2", receivedBatches.get(1));
        }
    }

    @Test
    @DisplayName("Verify MetricCollector hardware and OS telemetry sampling")
    void testMetricCollectorSampling() {
        MetricCollector collector = new MetricCollector();
        TelemetryPayload payload = collector.collect("host-metrics-test");

        assertNotNull(payload);
        assertEquals("host-metrics-test", payload.getNodeId());
        assertTrue(payload.getTimestamp() > 0);
        assertTrue(payload.getCpuPercent() >= 0.0 && payload.getCpuPercent() <= 100.0);
        assertTrue(payload.getMemoryPercent() >= 0.0 && payload.getMemoryPercent() <= 100.0);
        assertTrue(payload.getDiskPercent() >= 0.0 && payload.getDiskPercent() <= 100.0);
        assertTrue(payload.getProcessCount() >= 1);
        assertTrue("HEALTHY".equals(payload.getStatus()) || "WARNING".equals(payload.getStatus()));
    }
}
