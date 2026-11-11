package com.pulsenet.server;

import com.pulsenet.common.net.NetworkUtils;
import com.pulsenet.common.net.NetworkUtils.LanAddress;
import com.pulsenet.server.dashboard.DashboardRenderer;
import com.pulsenet.server.network.TelemetryServer;
import com.pulsenet.server.registry.NodeRegistry;
import com.pulsenet.server.watchdog.WatchdogSupervisor;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Command-line entry point for running the PulseNet central telemetry aggregator server.
 */
public class ServerMain {

    private static final Logger LOGGER = Logger.getLogger(ServerMain.class.getName());

    /** Log file that receives all server log records while the ANSI dashboard owns the console. */
    public static final String LOG_FILE = "pulsenet-server.log";

    /** How long the startup banner stays on screen before the ANSI dashboard clears it. */
    private static final long BANNER_HOLD_SECONDS = 3;

    public static void main(String[] args) {
        String host = "0.0.0.0";
        int port = 9000;
        int timeoutSeconds = -1; // -1 means run indefinitely
        boolean headless = false;
        double watchdogTimeout = 5.0;
        double readTimeoutSeconds = -1; // -1 means derive from the watchdog timeout

        // Parse CLI parameters
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                    if (i + 1 < args.length) host = args[++i];
                    break;
                case "--port":
                    if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
                    break;
                case "--timeout":
                    if (i + 1 < args.length) timeoutSeconds = Integer.parseInt(args[++i]);
                    break;
                case "--watchdog-timeout":
                    if (i + 1 < args.length) watchdogTimeout = Double.parseDouble(args[++i]);
                    break;
                case "--read-timeout":
                    if (i + 1 < args.length) readTimeoutSeconds = Double.parseDouble(args[++i]);
                    break;
                case "--headless":
                    headless = true;
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    return;
                default:
                    LOGGER.warning("Unknown argument: " + args[i]);
            }
        }

        // In ANSI mode the dashboard clears the screen at 2 Hz; route log records to a file so
        // they do not tear the dashboard. Headless mode keeps normal console logging.
        if (!headless) {
            isolateConsoleLogging(LOG_FILE);
        }

        List<LanAddress> lanAddresses = NetworkUtils.listLanIPv4Addresses();
        String displayHost = (NetworkUtils.isWildcard(host) && !lanAddresses.isEmpty())
                ? lanAddresses.get(0).address()
                : host;

        NodeRegistry registry = new NodeRegistry();
        long readTimeoutMillis = resolveReadTimeoutMillis(readTimeoutSeconds, watchdogTimeout);
        TelemetryServer server = new TelemetryServer(host, port, registry, readTimeoutMillis);
        WatchdogSupervisor watchdog = new WatchdogSupervisor(registry, watchdogTimeout);
        DashboardRenderer dashboard = new DashboardRenderer(registry, host, port, !headless,
                System.out, 500L, displayHost);

        CountDownLatch shutdownLatch = new CountDownLatch(1);

        // Register cooperative shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutdown hook triggered. Stopping PulseNet server subsystems...");
            dashboard.stop();
            watchdog.stop();
            server.stop();
            shutdownLatch.countDown();
            LOGGER.info("PulseNet server stopped successfully.");
        }));

        try {
            server.start();
            watchdog.start();

            String banner = buildBanner(host, server.getPort(), lanAddresses, !headless);
            System.out.print(banner);
            System.out.flush();
            LOGGER.info("Startup banner:\n" + banner);

            LOGGER.info(String.format("PulseNet Aggregator Server initialized on %s:%d (watchdog=%.1fs, read-timeout=%dms)",
                    host, server.getPort(), watchdogTimeout, readTimeoutMillis));

            if (!headless) {
                // Keep the banner readable before the first dashboard frame clears the screen.
                // Waiting on the latch lets Ctrl+C during the hold exit immediately.
                if (shutdownLatch.await(BANNER_HOLD_SECONDS, TimeUnit.SECONDS)) {
                    return;
                }
            }
            dashboard.start();

            if (timeoutSeconds > 0) {
                LOGGER.info(String.format("Running in timed mode. Will terminate automatically in %d seconds...", timeoutSeconds));
                boolean triggered = shutdownLatch.await(timeoutSeconds, TimeUnit.SECONDS);
                if (!triggered) {
                    LOGGER.info("Execution timeout reached. Initiating graceful shutdown...");
                    dashboard.stop();
                    watchdog.stop();
                    server.stop();
                }
            } else {
                shutdownLatch.await();
            }

        } catch (IOException e) {
            LOGGER.severe("Failed to bind TelemetryServer: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Resolves the client socket read timeout.
     *
     * @param readTimeoutSeconds explicit {@code --read-timeout} value, or a negative value if unset
     * @param watchdogTimeout    watchdog timeout in seconds
     * @return the explicit value, or {@code max(3 x watchdogTimeout, 15 s)}, in milliseconds
     */
    static long resolveReadTimeoutMillis(double readTimeoutSeconds, double watchdogTimeout) {
        if (readTimeoutSeconds >= 0) {
            return Math.round(readTimeoutSeconds * 1000.0);
        }
        return Math.max(Math.round(3.0 * watchdogTimeout * 1000.0), 15_000L);
    }

    /**
     * Builds the startup banner telling node operators which address to target.
     *
     * @param bindHost     configured bind address
     * @param port         actual listening port
     * @param lanAddresses candidate LAN IPv4 addresses (first one is advertised)
     * @param logToFile    whether log records are being written to {@link #LOG_FILE}
     * @return the multi-line banner text
     */
    static String buildBanner(String bindHost, int port, List<LanAddress> lanAddresses, boolean logToFile) {
        StringBuilder sb = new StringBuilder();
        String rule = "=".repeat(DashboardRenderer.TABLE_WIDTH);
        sb.append(rule).append('\n');
        sb.append(" PulseNet Aggregator Server listening on ").append(bindHost).append(':').append(port).append('\n');
        sb.append(rule).append('\n');

        if (!NetworkUtils.isWildcard(bindHost)) {
            sb.append(String.format(" Agents should connect to: %s:%d%n", bindHost, port));
        } else if (lanAddresses.isEmpty()) {
            sb.append(" WARNING: no LAN IPv4 address found. Is Wi-Fi/Ethernet connected?\n");
            sb.append(String.format(" Only agents on this machine can connect: 127.0.0.1:%d%n", port));
        } else {
            LanAddress primary = lanAddresses.get(0);
            sb.append(String.format(" Agents should connect to: %s:%d (%s)%n",
                    primary.address(), port, primary.label()));
            for (int i = 1; i < lanAddresses.size(); i++) {
                LanAddress other = lanAddresses.get(i);
                sb.append(String.format("        also reachable at: %s:%d (%s)%n",
                        other.address(), port, other.label()));
            }
        }
        if (logToFile) {
            sb.append(" Detailed logs: ").append(LOG_FILE).append('\n');
        }
        sb.append(rule).append('\n');
        return sb.toString();
    }

    /**
     * Raises every root {@link ConsoleHandler} to {@code SEVERE} and adds a {@link FileHandler}
     * that records all levels to {@code logFile}. Fatal errors still reach the console.
     *
     * @param logFile path of the log file (appended to)
     * @return the installed file handler, or {@code null} if the file could not be opened
     */
    static FileHandler isolateConsoleLogging(String logFile) {
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof ConsoleHandler) {
                handler.setLevel(Level.SEVERE);
            }
        }
        try {
            FileHandler fileHandler = new FileHandler(logFile, true);
            fileHandler.setFormatter(new SimpleFormatter());
            fileHandler.setLevel(Level.ALL);
            root.addHandler(fileHandler);
            return fileHandler;
        } catch (IOException | SecurityException e) {
            System.err.println("[WARN] Could not open " + logFile + " (" + e.getMessage()
                    + "); non-fatal log records will be discarded.");
            return null;
        }
    }

    private static void printUsage() {
        System.out.println("PulseNet Aggregator Server");
        System.out.println("Usage: java -jar pulsenet.jar server [options]");
        System.out.println("Options:");
        System.out.println("  --host <ip>              Listener host bind address (default: 0.0.0.0)");
        System.out.println("  --port <port>            Listener port (default: 9000)");
        System.out.println("  --timeout <seconds>      Run for N seconds then shut down cleanly");
        System.out.println("  --watchdog-timeout <sec> Seconds of silence before marking node OFFLINE (default: 5.0)");
        System.out.println("  --read-timeout <sec>     Drop clients silent this long (default: max(3 x watchdog, 15); 0 = off)");
        System.out.println("  --headless               Disable terminal screen clearing");
    }
}
