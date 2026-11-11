package com.pulsenet.agent;

import java.net.InetAddress;
import java.util.Locale;
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

    /** Fallback ID used only when no hostname can be determined at all. */
    static final String FALLBACK_NODE_ID = "pulsenet-node";

    /**
     * Returns a stable default node ID: the lower-cased short hostname, with no random suffix,
     * so a restarted agent reuses the same ID and the server reports {@code [REC] Node recovered}
     * on the same row instead of adding a duplicate.
     *
     * <p>Resolution order: {@link InetAddress#getLocalHost()} host name, then the
     * {@code COMPUTERNAME} / {@code HOSTNAME} environment variables, then {@link #FALLBACK_NODE_ID}.
     * Any domain suffix (e.g. macOS {@code .local}) is stripped.
     *
     * @return the default node ID; identical across calls on the same machine
     */
    public static String generateDefaultNodeId() {
        String candidate = null;
        try {
            candidate = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            // fall through to environment variables
        }
        if (!isUsableHostName(candidate)) {
            candidate = System.getenv("COMPUTERNAME");
        }
        if (!isUsableHostName(candidate)) {
            candidate = System.getenv("HOSTNAME");
        }
        if (!isUsableHostName(candidate)) {
            return FALLBACK_NODE_ID;
        }
        return normalizeHostName(candidate);
    }

    /**
     * Lower-cases a hostname and strips any domain suffix ({@code Didulas-MBP.local} becomes
     * {@code didulas-mbp}).
     *
     * @param hostName a non-blank host name that is not an IP literal
     * @return the normalized node ID
     */
    static String normalizeHostName(String hostName) {
        String trimmed = hostName.trim().toLowerCase(Locale.ROOT);
        int dot = trimmed.indexOf('.');
        return dot > 0 ? trimmed.substring(0, dot) : trimmed;
    }

    /**
     * @param hostName candidate host name
     * @return true if non-blank and not an IP literal (reverse lookup failures return the IP)
     */
    static boolean isUsableHostName(String hostName) {
        if (hostName == null || hostName.isBlank()) {
            return false;
        }
        String h = hostName.trim();
        boolean ipv4Literal = h.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean ipv6Literal = h.contains(":");
        return !ipv4Literal && !ipv6Literal && !"localhost".equalsIgnoreCase(h);
    }

    private static void printUsage() {
        System.out.println("PulseNet Telemetry Agent");
        System.out.println("Usage: java -cp <jar> com.pulsenet.agent.AgentMain [options]");
        System.out.println("Options:");
        System.out.println("  --host <ip/hostname>   Aggregator server host (default: 127.0.0.1)");
        System.out.println("  --port <port>          Aggregator server port (default: 9000)");
        System.out.println("  --node-id <id>         Unique node identifier (default: lower-cased hostname)");
        System.out.println("  --interval <ms>        Telemetry sample interval in ms (default: 1000)");
    }
}
