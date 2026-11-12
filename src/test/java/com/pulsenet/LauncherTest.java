package com.pulsenet;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies subcommand dispatch of the executable JAR {@link Launcher}.
 */
class LauncherTest {

    private static String utf8(ByteArrayOutputStream buffer) {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static PrintStream stream(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer, true, StandardCharsets.UTF_8);
    }

    @Test
    void unknownSubcommandPrintsUsageAndReturnsNonZero() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int status = Launcher.run(new String[]{"bogus"}, stream(out), stream(err));

        assertNotEquals(0, status, "Unknown subcommand must return a non-zero status");
        assertTrue(utf8(err).contains("unknown subcommand 'bogus'"));
        assertTrue(utf8(err).contains("Usage: java -jar pulsenet.jar"));
    }

    @Test
    void missingSubcommandReturnsNonZero() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int status = Launcher.run(new String[0], stream(out), stream(err));

        assertNotEquals(0, status);
        assertTrue(utf8(err).contains("Usage: java -jar pulsenet.jar"));
    }

    @Test
    void helpListsAllSubcommandsAndReturnsZero() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int status = Launcher.run(new String[]{"--help"}, stream(out), stream(err));

        assertEquals(0, status);
        String text = utf8(out);
        assertTrue(text.contains("server"));
        assertTrue(text.contains("agent"));
        assertTrue(text.contains("cluster"));
        assertTrue(text.contains("probe"));
        assertTrue(text.contains("stress"));
        assertTrue(text.contains("ui"));
    }

    @Test
    void serverHelpDelegatesToServerMain() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(stream(captured));
            int status = Launcher.run(new String[]{"server", "--help"});
            assertEquals(0, status);
        } finally {
            System.setOut(originalOut);
        }
        assertTrue(utf8(captured).contains("PulseNet Aggregator Server"),
                "server --help must print ServerMain usage, got: " + utf8(captured));
    }

    @Test
    void agentHelpDelegatesToAgentMain() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(stream(captured));
            int status = Launcher.run(new String[]{"agent", "--help"});
            assertEquals(0, status);
        } finally {
            System.setOut(originalOut);
        }
        assertTrue(utf8(captured).contains("PulseNet Telemetry Agent"));
    }
}
