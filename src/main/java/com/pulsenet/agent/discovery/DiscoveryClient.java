package com.pulsenet.agent.discovery;

import com.pulsenet.common.discovery.DiscoveredServer;
import com.pulsenet.common.discovery.DiscoveryMessage;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client component implementing UDP-based automatic server discovery.
 *
 * <p>Broadcasts discovery probes across all available local network interfaces,
 * listens for responses from PulseNet aggregator servers, and applies deterministic
 * server selection policy.
 */
public class DiscoveryClient {

    private static final Logger LOGGER = Logger.getLogger(DiscoveryClient.class.getName());

    public static final int DEFAULT_UDP_PORT = 9001;
    public static final int DEFAULT_TIMEOUT_MILLIS = 3000;
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final long DEFAULT_RETRY_DELAY_MILLIS = 2000L;
    public static final int RECEIVE_BUFFER_SIZE = 2048;

    private final String agentId;
    private final int udpPort;
    private final int timeoutMillis;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final String preferredServerId;

    public DiscoveryClient(String agentId) {
        this(agentId, DEFAULT_UDP_PORT, DEFAULT_TIMEOUT_MILLIS, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_DELAY_MILLIS, null);
    }

    public DiscoveryClient(String agentId, int udpPort) {
        this(agentId, udpPort, DEFAULT_TIMEOUT_MILLIS, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_DELAY_MILLIS, null);
    }

    public DiscoveryClient(String agentId, int udpPort, int timeoutMillis, int maxAttempts,
                           long retryDelayMillis, String preferredServerId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be null or blank");
        }
        if (udpPort <= 0 || udpPort > 65535) {
            throw new IllegalArgumentException("Invalid UDP port: " + udpPort);
        }
        this.agentId = agentId;
        this.udpPort = udpPort;
        this.timeoutMillis = timeoutMillis;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelayMillis = Math.max(0, retryDelayMillis);
        this.preferredServerId = preferredServerId;
    }

    /**
     * Executes the automatic server discovery protocol.
     *
     * @return Optional containing DiscoveredServer coordinates if found, or empty if no server responded
     */
    public Optional<DiscoveredServer> discover() {
        System.out.println("[DISCOVERY] Starting automatic server discovery...");
        LOGGER.info(String.format("[%s] Initiating server discovery on UDP port %d (attempts=%d, timeout=%dms)",
                agentId, udpPort, maxAttempts, timeoutMillis));

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            System.out.println(String.format("[DISCOVERY] Sending UDP request on port %d (attempt %d/%d)...",
                    udpPort, attempt, maxAttempts));

            List<DiscoveredServer> responses = broadcastAndCollectResponses();
            if (!responses.isEmpty()) {
                DiscoveredServer chosen = selectServer(responses, preferredServerId);
                if (chosen != null) {
                    System.out.println("[DISCOVERY] PulseNet server discovered!");
                    System.out.println("[DISCOVERY] Server ID: " + chosen.serverId());
                    System.out.println("[DISCOVERY] Host IP: " + chosen.resolveConnectHost());
                    System.out.println("[DISCOVERY] TCP Port: " + chosen.tcpPort());
                    System.out.println("[DISCOVERY] Discovery completed successfully.");
                    return Optional.of(chosen);
                }
            }

            if (attempt < maxAttempts && retryDelayMillis > 0) {
                System.out.println(String.format("[DISCOVERY] No server response. Retrying in %d ms...", retryDelayMillis));
                try {
                    Thread.sleep(retryDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.info("Discovery interrupted during attempt backoff delay.");
                    break;
                }
            }
        }

        System.err.println(String.format("[DISCOVERY] No PulseNet server found after %d attempt(s).", maxAttempts));
        return Optional.empty();
    }

    /**
     * Sends broadcast requests and collects valid responses until the discovery window expires.
     */
    List<DiscoveredServer> broadcastAndCollectResponses() {
        Map<String, DiscoveredServer> responses = new LinkedHashMap<>();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(1000); // 1-second chunks for polling deadlines

            DiscoveryMessage request = DiscoveryMessage.createRequest(agentId, preferredServerId);
            byte[] payload = request.toBytes();

            // Transmit request to candidate broadcast and loopback destinations
            List<InetAddress> targets = getBroadcastTargets();
            for (InetAddress target : targets) {
                try {
                    DatagramPacket packet = new DatagramPacket(payload, payload.length, target, udpPort);
                    socket.send(packet);
                } catch (IOException e) {
                    LOGGER.fine("Failed to broadcast on target " + target + ": " + e.getMessage());
                }
            }

            System.out.println("[DISCOVERY] Waiting for server responses...");
            long deadline = System.currentTimeMillis() + timeoutMillis;
            byte[] rxBuffer = new byte[RECEIVE_BUFFER_SIZE];

            while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
                int remainingTimeout = (int) Math.max(1, deadline - System.currentTimeMillis());
                socket.setSoTimeout(Math.min(remainingTimeout, 1000));
                try {
                    DatagramPacket rxPacket = new DatagramPacket(rxBuffer, rxBuffer.length);
                    socket.receive(rxPacket);

                    DiscoveryMessage reply = DiscoveryMessage.fromBytes(
                            rxPacket.getData(),
                            rxPacket.getOffset(),
                            rxPacket.getLength()
                    );

                    if (reply.isValidResponse()) {
                        DiscoveredServer server = new DiscoveredServer(
                                reply.getServerId(),
                                reply.getTcpHost(),
                                reply.getTcpPort(),
                                rxPacket.getAddress()
                        );
                        responses.put(server.serverId(), server);
                        // If we received a response matching our preferred server, we can stop waiting early
                        if (preferredServerId != null && preferredServerId.equals(server.serverId())) {
                            break;
                        }
                        // Give a brief window (200ms) for competing servers to reply, then complete
                        if (preferredServerId == null && deadline - System.currentTimeMillis() > 200) {
                            deadline = System.currentTimeMillis() + 200;
                        }
                    } else {
                        LOGGER.fine("Received non-conforming discovery response from " + rxPacket.getSocketAddress());
                    }
                } catch (SocketTimeoutException e) {
                    // Timeout slice expired; continue until deadline
                }
            }

        } catch (SocketException e) {
            LOGGER.log(Level.WARNING, "Socket failure during UDP discovery: " + e.getMessage(), e);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "I/O error during UDP discovery: " + e.getMessage(), e);
        }

        return new ArrayList<>(responses.values());
    }

    /**
     * Deterministic server selection policy:
     * 1. If preferredServerId is specified, prioritize matching server ID.
     * 2. Otherwise, select the server with lexicographically lowest serverId for consistency.
     *
     * @param candidates        list of discovered servers
     * @param preferredServerId optional preferred server ID
     * @return selected server or null if candidates is empty
     */
    public static DiscoveredServer selectServer(List<DiscoveredServer> candidates, String preferredServerId) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (preferredServerId != null && !preferredServerId.isBlank()) {
            for (DiscoveredServer server : candidates) {
                if (preferredServerId.equals(server.serverId())) {
                    return server;
                }
            }
        }
        // Deterministic sort by serverId
        List<DiscoveredServer> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing(DiscoveredServer::serverId));
        return sorted.get(0);
    }

    /**
     * Gathers all valid broadcast destinations across all network adapters,
     * including global broadcast (255.255.255.255) and loopback (127.0.0.1).
     */
    static List<InetAddress> getBroadcastTargets() {
        Set<InetAddress> targets = new LinkedHashSet<>();

        // 1. Global subnet broadcast
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
        } catch (UnknownHostException ignored) {
        }

        // 2. Loopback (supports test execution on single host)
        try {
            targets.add(InetAddress.getByName("127.0.0.1"));
        } catch (UnknownHostException ignored) {
        }

        // 3. Interface-specific broadcast addresses
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                try {
                    if (!nif.isUp()) continue;
                } catch (SocketException e) {
                    continue;
                }

                for (InterfaceAddress ifAddr : nif.getInterfaceAddresses()) {
                    InetAddress broadcast = ifAddr.getBroadcast();
                    if (broadcast != null) {
                        targets.add(broadcast);
                    }
                }
            }
        } catch (SocketException e) {
            LOGGER.fine("Could not enumerate network interfaces for broadcast: " + e.getMessage());
        }

        return new ArrayList<>(targets);
    }

    public String getAgentId() {
        return agentId;
    }

    public int getUdpPort() {
        return udpPort;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getRetryDelayMillis() {
        return retryDelayMillis;
    }

    public String getPreferredServerId() {
        return preferredServerId;
    }
}

