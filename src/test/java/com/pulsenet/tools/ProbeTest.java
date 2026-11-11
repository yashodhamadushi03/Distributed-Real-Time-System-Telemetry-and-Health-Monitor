package com.pulsenet.tools;

import com.pulsenet.server.network.TelemetryServer;
import com.pulsenet.server.registry.NodeRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeTest {

    @Test
    @DisplayName("Probe reports REFUSED against a closed ephemeral port")
    void refusedOnClosedPort() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            port = ss.getLocalPort();
        }
        Probe.Result result = Probe.probe("127.0.0.1", port, 2000);
        assertEquals(Probe.Status.REFUSED, result.status(), result.message());
        assertTrue(result.message().startsWith("REFUSED:"));
    }

    @Test
    @DisplayName("Probe reports OK against a listening ServerSocket")
    void okOnListeningSocket() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Probe.Result result = Probe.probe("127.0.0.1", ss.getLocalPort(), 2000);
            assertEquals(Probe.Status.OK, result.status(), result.message());
            assertTrue(result.message().startsWith("OK: server reachable"));
        }
    }

    @Test
    @DisplayName("Probing a live TelemetryServer creates no registry entry")
    void probeCreatesNoPhantomNode() throws Exception {
        NodeRegistry registry = new NodeRegistry();
        TelemetryServer server = new TelemetryServer("127.0.0.1", 0, registry);
        server.start();
        try {
            assertTrue(Probe.probe("127.0.0.1", server.getPort(), 2000).isOk());
            Thread.sleep(300);
            assertEquals(0, registry.size(), "A probe must not register a node");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("Unresolvable host maps to UNKNOWN_HOST")
    void unknownHost() {
        Probe.Result result = Probe.probe("no-such-host.invalid", 9000, 2000);
        assertEquals(Probe.Status.UNKNOWN_HOST, result.status(), result.message());
    }

    @Test
    @DisplayName("CLI returns 2 without --host and 0 when the server is reachable")
    void cliExitCodes() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        assertEquals(Probe.EXIT_USAGE, Probe.run(new String[0], out));
        try (ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            int status = Probe.run(new String[]{"--host", "127.0.0.1", "--port",
                    String.valueOf(ss.getLocalPort())}, out);
            assertEquals(Probe.EXIT_OK, status);
        }
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("OK: server reachable"));
    }
}
