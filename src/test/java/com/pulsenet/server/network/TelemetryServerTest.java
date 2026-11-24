package com.pulsenet.server.network;

import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.common.protocol.WireProtocol;
import com.pulsenet.server.registry.NodeRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryServerTest {

    private final NodeRegistry registry = new NodeRegistry();
    private TelemetryServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private static boolean await(Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.call()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static void send(Socket socket, String nodeId, String status) throws IOException {
        WireProtocol.sendFramedMessage(socket.getOutputStream(),
                new TelemetryPayload(nodeId, System.currentTimeMillis(), 10, 20, 30, 40, status));
    }

    @Test
    @DisplayName("A failed bind leaves the server stopped, and start() works once the port is free")
    void startCanBeRetriedAfterBindFailure() throws Exception {
        int port;
        try (ServerSocket blocker = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = blocker.getLocalPort();
            server = new TelemetryServer("127.0.0.1", port, registry);
            assertThrows(IOException.class, server::start);
            assertFalse(server.isRunning());
        }
        server.start();
        assertTrue(server.isRunning());
        try (Socket client = new Socket("127.0.0.1", port)) {
            send(client, "n1", "HEALTHY");
            assertTrue(await(() -> registry.getNode("n1").isPresent()));
        }
    }

    @Test
    @DisplayName("Connections beyond the limit are closed; a freed slot can be used again")
    void connectionLimit() throws Exception {
        server = new TelemetryServer("127.0.0.1", 0, registry);
        server.setMaxConnections(1);
        server.start();
        Socket first = new Socket("127.0.0.1", server.getPort());
        assertTrue(await(() -> server.getActiveConnectionCount() == 1));
        try (Socket second = new Socket("127.0.0.1", server.getPort())) {
            second.setSoTimeout(3000);
            int read;
            try {
                read = second.getInputStream().read();
            } catch (java.net.SocketException reset) {
                read = -1;
            }
            assertEquals(-1, read, "Connection over the limit must be closed");
        }
        first.close();
        assertTrue(await(() -> server.getActiveConnectionCount() == 0));
        try (Socket third = new Socket("127.0.0.1", server.getPort())) {
            send(third, "n3", "HEALTHY");
            assertTrue(await(() -> registry.getNode("n3").isPresent()));
        }
    }

    @Test
    @DisplayName("Invalid frames do not disturb the connection or the registry")
    void invalidFramesAreIgnored() throws Exception {
        server = new TelemetryServer("127.0.0.1", 0, registry);
        server.start();
        try (Socket client = new Socket("127.0.0.1", server.getPort())) {
            client.getOutputStream().write(WireProtocol.packMessage("null"));
            client.getOutputStream().write(WireProtocol.packMessage("{\"nodeId\":\" \"}"));
            client.getOutputStream().write(WireProtocol.packMessage("{\"nodeId\":\"odd\",\"status\":\"OFFLINE\"}"));
            assertTrue(await(() -> registry.getNode("odd").isPresent()));
            assertEquals("HEALTHY", registry.getNode("odd").orElseThrow().getStatus(),
                    "A node that is sending cannot declare itself OFFLINE");
            assertEquals(1, registry.size());
        }
    }

    @Test
    @DisplayName("A connection that switches node ID gives up the previous one")
    void switchingNodeIdReleasesPreviousOwnership() throws Exception {
        server = new TelemetryServer("127.0.0.1", 0, registry);
        server.start();
        try (Socket client = new Socket("127.0.0.1", server.getPort());
             Socket other = new Socket("127.0.0.1", server.getPort())) {
            send(client, "before", "HEALTHY");
            send(client, "after", "HEALTHY");
            assertTrue(await(() -> registry.getNode("after").isPresent()));
            send(other, "before", "WARNING");
            assertTrue(await(() -> "WARNING".equals(registry.getNode("before").orElseThrow().getStatus())));
            assertTrue(registry.getRecentEvents(100).stream().noneMatch(e -> e.contains("reconnected on new connection")));
        }
    }
}
