package com.pulsenet.common.discovery;

import com.pulsenet.common.net.NetworkUtils;

import java.net.InetAddress;
import java.util.Objects;

/**
 * Encapsulates the network coordinates of a PulseNet aggregator server discovered via UDP broadcast.
 *
 * @param serverId        unique identifier of the discovered server
 * @param tcpHost         advertised TCP host/IP reported in the discovery response
 * @param tcpPort         TCP listener port reported in the discovery response
 * @param responseAddress physical source IP address of the UDP packet containing the response
 * @param udpProbePort    UDP port for latency, jitter, and packet loss probing
 */
public record DiscoveredServer(String serverId, String tcpHost, int tcpPort, InetAddress responseAddress, int udpProbePort) {

    public static final int DEFAULT_PROBE_PORT = 9002;

    public DiscoveredServer {
        Objects.requireNonNull(serverId, "serverId must not be null");
        if (tcpPort <= 0 || tcpPort > 65535) {
            throw new IllegalArgumentException("Invalid tcpPort: " + tcpPort);
        }
        if (udpProbePort <= 0 || udpProbePort > 65535) {
            udpProbePort = DEFAULT_PROBE_PORT;
        }
    }

    /**
     * Backward-compatible constructor defaulting UDP probe port to 9002.
     */
    public DiscoveredServer(String serverId, String tcpHost, int tcpPort, InetAddress responseAddress) {
        this(serverId, tcpHost, tcpPort, responseAddress, DEFAULT_PROBE_PORT);
    }

    /**
     * Resolves the most reliable hostname or IP to establish the TCP connection.
     *
     * <p>Under network constraints where the server binds to a wildcard address or advertises
     * an interface unreachable by this specific client, the source address of the UDP response
     * packet is preferred as the verified reachable route.
     *
     * @return reachable IP string or hostname
     */
    public String resolveConnectHost() {
        if (responseAddress != null) {
            String sourceIp = responseAddress.getHostAddress();
            // If the advertised host is empty, wildcard, or if the source is remote but advertised is loopback
            if (tcpHost == null || tcpHost.isBlank() || NetworkUtils.isWildcard(tcpHost)) {
                return sourceIp;
            }
            if (!responseAddress.isLoopbackAddress() && "127.0.0.1".equals(tcpHost)) {
                return sourceIp;
            }
        }
        return (tcpHost != null && !tcpHost.isBlank()) ? tcpHost : "127.0.0.1";
    }
}

