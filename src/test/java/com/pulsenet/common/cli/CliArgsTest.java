package com.pulsenet.common.cli;

import com.pulsenet.Launcher;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class CliArgsTest {

    @Test
    void readsValidValues() {
        String[] args = {"--port", "9100", "--interval", " 250 ", "--watchdog-timeout", "2.5"};
        assertEquals(9100, CliArgs.port(args, 0));
        assertEquals(250, CliArgs.number(args, 2, 100, 1000));
        assertEquals(2.5, CliArgs.decimal(args, 4, 0.5, 10));
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(CliArgs.UsageException.class, () -> CliArgs.port(new String[]{"--port"}, 0));
        assertThrows(CliArgs.UsageException.class, () -> CliArgs.port(new String[]{"--port", "abc"}, 0));
        assertThrows(CliArgs.UsageException.class, () -> CliArgs.port(new String[]{"--port", "70000"}, 0));
        assertThrows(CliArgs.UsageException.class, () -> CliArgs.decimal(new String[]{"-w", "NaN"}, 0, 0, 1));
        assertThrows(CliArgs.UsageException.class, () -> CliArgs.text(new String[]{"--host", " "}, 0));
    }

    @Test
    void launcherReportsInvalidOptionsWithoutStackTrace() {
        for (String command : new String[]{"server", "agent", "cluster", "ui"}) {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int status = Launcher.run(new String[]{command, "--port", "abc"}, System.out, new PrintStream(err));
            assertNotEquals(Launcher.EXIT_OK, status, command);
            assertTrue(err.toString().contains("--port must be"), command + ": " + err);
        }
    }
}
