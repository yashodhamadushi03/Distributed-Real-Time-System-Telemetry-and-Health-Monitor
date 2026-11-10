package com.pulsenet.agent;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/**
 * Command-line entry point for running a standalone PulseNet telemetry agent.
 */
public class AgentMain {

    private static final Logger LOGGER = Logger.getLogger(AgentMain.class.getName());

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 9000;
        String nodeId = generateDefaultNodeId();
        long intervalMillis = 1000L;

        // Parse command line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                    if (i + 1 < args.length) host = args[++i];
                    break;
                case "--port":
                    if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
                    break;
                case "--node-id":
                    if (i + 1 < args.length) nodeId = args[++i];
                    break;
                case "--interval":
                    if (i + 1 < args.length) intervalMillis = Long.parseLong(args[++i]);
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    return;
                default:
                    LOGGER.warning("Unknown argument: " + args[i]);
            }
        }

        LOGGER.info(String.format("Initializing PulseNet Telemetry Agent [Node: %s] -> %s:%d (Interval: %dms)",
                nodeId, host, port, intervalMillis));

        TelemetryAgent agent = new TelemetryAgent(host, port, nodeId, intervalMillis);
        CountDownLatch shutdownLatch = new CountDownLatch(1);

        // Register JVM cooperative shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutdown signal received. Stopping Telemetry Agent...");
            agent.stop();
            shutdownLatch.countDown();
        }));

        agent.start();

        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String generateDefaultNodeId() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            String shortId = UUID.randomUUID().toString().substring(0, 4);
            return host.toLowerCase() + "-" + shortId;
        } catch (Exception e) {
            return "node-" + UUID.randomUUID().toString().substring(0, 6);
        }
    }

    private static void printUsage() {
        System.out.println("PulseNet Telemetry Agent");
        System.out.println("Usage: java -cp <jar> com.pulsenet.agent.AgentMain [options]");
        System.out.println("Options:");
        System.out.println("  --host <ip/hostname>   Aggregator server host (default: 127.0.0.1)");
        System.out.println("  --port <port>          Aggregator server port (default: 9000)");
        System.out.println("  --node-id <id>         Unique node identifier (default: <hostname>-<rand>)");
        System.out.println("  --interval <ms>        Telemetry sample interval in ms (default: 1000)");
    }
}
