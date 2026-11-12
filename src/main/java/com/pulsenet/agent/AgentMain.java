package com.pulsenet.agent;

import com.pulsenet.agent.discovery.DiscoveryClient;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/**
 * Command-line entry point for running a standalone PulseNet telemetry agent.
 *
 * <p>Supports both manual connection mode (explicit {@code --host} and {@code --port})
 * and UDP automatic discovery mode ({@code --discover}).
 */
public class AgentMain {

    private static final Logger LOGGER = Logger.getLogger(AgentMain.class.getName());

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 9000;
        String nodeId = generateDefaultNodeId();
        long intervalMillis = 1000L;
        boolean discover = false;
        int udpPort = DiscoveryClient.DEFAULT_UDP_PORT;
        String preferredServerId = null;
        boolean hostExplicitlySet = false;

        // Parse command line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--discover":
                case "--auto-discover":
                    discover = true;
                    break;
                case "--udp-port":
                    if (i + 1 < args.length) udpPort = Integer.parseInt(args[++i]);
                    break;
                case "--server-id":
                    if (i + 1 < args.length) preferredServerId = args[++i];
                    break;
                case "--host":
                    if (i + 1 < args.length) {
                        host = args[++i];
                        hostExplicitlySet = true;
                    }
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

        TelemetryAgent agent;
        if (discover) {
            LOGGER.info(String.format("Initializing PulseNet Telemetry Agent [Node: %s] in AUTO-DISCOVERY mode (UDP: %d)",
                    nodeId, udpPort));
            DiscoveryClient discoveryClient = new DiscoveryClient(nodeId, udpPort, 3000, 3, 2000L, preferredServerId);
            discoveryClient.setServerSelector(new com.pulsenet.agent.discovery.InteractiveConsoleSelector());

            String selectedHost = hostExplicitlySet ? host : null;
            int selectedPort = hostExplicitlySet ? port : 0;
            String selectedServerId = preferredServerId;

            if (!hostExplicitlySet) {
                while (true) {
                    Optional<com.pulsenet.common.discovery.DiscoveredServer> discovered = discoveryClient.discover();
                    if (discovered.isPresent()) {
                        com.pulsenet.common.discovery.DiscoveredServer target = discovered.get();
                        selectedServerId = target.serverId();
                        selectedHost = target.resolveConnectHost();
                        selectedPort = target.tcpPort();
                        break;
                    } else {
                        // Scenario C: No servers discovered
                        System.out.println(String.format("[DISCOVERY] No PulseNet servers found on the local network (UDP port: %d).", udpPort));
                        if (System.console() != null) {
                            System.out.println("[DISCOVERY] Options:");
                            System.out.println("  [1] Retry discovery");
                            System.out.println("  [2] Connect manually (specify host & port)");
                            System.out.println("  [3] Exit");
                            System.out.print("Select an option [1-3] (default 1): ");
                            java.util.Scanner sc = new java.util.Scanner(System.in);
                            if (sc.hasNextLine()) {
                                String choice = sc.nextLine().trim();
                                if ("2".equals(choice)) {
                                    System.out.print("Enter server host: ");
                                    if (sc.hasNextLine()) selectedHost = sc.nextLine().trim();
                                    System.out.print("Enter server port (default 9000): ");
                                    if (sc.hasNextLine()) {
                                        String pStr = sc.nextLine().trim();
                                        selectedPort = pStr.isBlank() ? 9000 : Integer.parseInt(pStr);
                                    }
                                    break;
                                } else if ("3".equals(choice) || "q".equalsIgnoreCase(choice)) {
                                    return;
                                } else {
                                    continue; // Retry discovery
                                }
                            }
                        }
                        // Non-interactive fallback: proceed to background retries
                        break;
                    }
                }
            }

            if (selectedHost != null && selectedPort > 0) {
                // Discovery mode with resolved target coordinates and saved server ID
                agent = new TelemetryAgent(discoveryClient, selectedHost, selectedPort, nodeId, intervalMillis);
                if (selectedServerId != null) {
                    agent.setSelectedServerId(selectedServerId);
                }
            } else {
                agent = new TelemetryAgent(discoveryClient, nodeId, intervalMillis);
            }
        } else {
            LOGGER.info(String.format("Initializing PulseNet Telemetry Agent [Node: %s] -> %s:%d (Interval: %dms)",
                    nodeId, host, port, intervalMillis));
            agent = new TelemetryAgent(host, port, nodeId, intervalMillis);
        }

        CountDownLatch shutdownLatch = new CountDownLatch(1);

        // Register JVM cooperative shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[SHUTDOWN] Shutdown signal received. Stopping Telemetry Agent...");
            LOGGER.info("Shutdown signal received. Stopping Telemetry Agent...");
            agent.stop();
            shutdownLatch.countDown();
            LOGGER.info("Telemetry Agent stopped cleanly.");
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
        System.out.println("  --discover             Enable UDP-based automatic server discovery on LAN");
        System.out.println("  --udp-port <port>      UDP discovery port (default: 9001)");
        System.out.println("  --server-id <id>       Preferred server ID when multiple servers are present");
        System.out.println("  --host <ip/hostname>   Aggregator server host (default: 127.0.0.1)");
        System.out.println("  --port <port>          Aggregator server TCP port (default: 9000)");
        System.out.println("  --node-id <id>         Unique node identifier (default: lower-cased hostname)");
        System.out.println("  --interval <ms>        Telemetry sample interval in ms (default: 1000)");
    }
}
