package com.pulsenet.server;

import com.pulsenet.server.dashboard.DashboardRenderer;
import com.pulsenet.server.network.TelemetryServer;
import com.pulsenet.server.registry.NodeRegistry;
import com.pulsenet.server.watchdog.WatchdogSupervisor;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Command-line entry point for running the PulseNet central telemetry aggregator server.
 */
public class ServerMain {

    private static final Logger LOGGER = Logger.getLogger(ServerMain.class.getName());

    public static void main(String[] args) {
        String host = "0.0.0.0";
        int port = 9000;
        int timeoutSeconds = -1; // -1 means run indefinitely
        boolean headless = false;
        double watchdogTimeout = 5.0;

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

        NodeRegistry registry = new NodeRegistry();
        TelemetryServer server = new TelemetryServer(host, port, registry);
        WatchdogSupervisor watchdog = new WatchdogSupervisor(registry, watchdogTimeout);
        DashboardRenderer dashboard = new DashboardRenderer(registry, host, port, !headless);

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
            dashboard.start();

            LOGGER.info(String.format("PulseNet Aggregator Server initialized on %s:%d (watchdog=%.1fs)",
                    host, server.getPort(), watchdogTimeout));

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

    private static void printUsage() {
        System.out.println("PulseNet Aggregator Server");
        System.out.println("Usage: java -cp <jar> com.pulsenet.server.ServerMain [options]");
        System.out.println("Options:");
        System.out.println("  --host <ip>              Listener host bind address (default: 0.0.0.0)");
        System.out.println("  --port <port>            Listener port (default: 9000)");
        System.out.println("  --timeout <seconds>      Run for N seconds then shut down cleanly");
        System.out.println("  --watchdog-timeout <sec> Seconds of silence before marking node OFFLINE (default: 5.0)");
        System.out.println("  --headless               Disable terminal screen clearing");
    }
}
