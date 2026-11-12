package com.pulsenet.server.discovery;

import com.pulsenet.common.discovery.DiscoveryMessage;
import com.pulsenet.common.net.NetworkUtils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Multithreaded UDP Discovery Server running on the PulseNet Central Aggregator.
 *
 * <p>Listens for broadcast discovery requests from agent nodes on the LAN and responds
 * with the server's identity, reachable TCP address, and listening TCP port. Operates
 * independently of and concurrently with the TCP telemetry listener without blocking.
 */
public class DiscoveryServer implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(DiscoveryServer.class.getName());

    public static final int DEFAULT_UDP_PORT = 9001;
    public static final int MAX_DATAGRAM_SIZE = 2048;
    public static final String DEFAULT_SERVER_ID = "pulsenet-central-server";

    private final String bindHost;
    private final int udpPort;
    private final String tcpBindHost;
    private final int tcpPort;
    private final String serverId;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private DatagramSocket socket;
    private Thread listenerThread;

    /**
     * Initializes a discovery server using default UDP port 9001 and default server ID.
     *
     * @param tcpPort TCP port that the TelemetryServer is listening on
     */
    public DiscoveryServer(int tcpPort) {
        this("0.0.0.0", DEFAULT_UDP_PORT, "0.0.0.0", tcpPort, DEFAULT_SERVER_ID);
    }

    /**
     * Initializes a discovery server with full network configuration.
     *
     * @param bindHost    UDP bind address (typically "0.0.0.0" to receive broadcasts)
     * @param udpPort     UDP discovery listening port
     * @param tcpBindHost TCP server host bind address
     * @param tcpPort     TCP server listening port
     * @param serverId    logical identifier for this server instance
     */
    public DiscoveryServer(String bindHost, int udpPort, String tcpBindHost, int tcpPort, String serverId) {
        if (udpPort <= 0 || udpPort > 65535) {
            throw new IllegalArgumentException("Invalid UDP port: " + udpPort);
        }
        if (tcpPort <= 0 || tcpPort > 65535) {
            throw new IllegalArgumentException("Invalid TCP port: " + tcpPort);
        }
        this.bindHost = (bindHost == null || bindHost.isBlank()) ? "0.0.0.0" : bindHost;
        this.udpPort = udpPort;
        this.tcpBindHost = tcpBindHost;
        this.tcpPort = tcpPort;
        this.serverId = (serverId == null || serverId.isBlank()) ? DEFAULT_SERVER_ID : serverId;
    }

    /**
     * Starts the UDP discovery service in a dedicated background daemon thread.
     *
     * @throws SocketException if socket binding fails
     */
    public synchronized void start() throws SocketException {
        if (running.compareAndSet(false, true)) {
            // Bind to UDP port
            InetSocketAddress bindAddress = new InetSocketAddress(bindHost, udpPort);
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(bindAddress);

            listenerThread = new Thread(this, "DiscoveryServer-UDP-" + udpPort);
            listenerThread.setDaemon(true);
            listenerThread.start();

            LOGGER.info(String.format("PulseNet UDP DiscoveryServer listening on %s:%d (advertising TCP %s:%d)",
                    bindHost, udpPort, resolveAdvertisedHost(), tcpPort));
        }
    }

    @Override
    public void run() {
        byte[] buffer = new byte[MAX_DATAGRAM_SIZE];

        while (running.get() && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                handlePacket(packet);

            } catch (SocketException e) {
                if (!running.get()) {
                    LOGGER.info("DiscoveryServer socket closed cleanly.");
                    break;
                }
                LOGGER.log(Level.WARNING, "Discovery socket error: " + e.getMessage());
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.log(Level.WARNING, "I/O error during UDP discovery receive: " + e.getMessage());
                }
            } catch (Exception e) {
                if (running.get()) {
                    LOGGER.log(Level.WARNING, "Unexpected error processing discovery packet: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Inspects, validates and responds to an incoming UDP packet.
     *
     * @param packet received DatagramPacket
     */
    void handlePacket(DatagramPacket packet) {
        try {
            DiscoveryMessage request = DiscoveryMessage.fromBytes(packet.getData(), packet.getOffset(), packet.getLength());
            if (!request.isValidRequest()) {
                LOGGER.fine("Ignored invalid or unsupported discovery message from " + packet.getSocketAddress());
                return;
            }

            String advertisedHost = resolveAdvertisedHost();
            DiscoveryMessage response = DiscoveryMessage.createResponse(serverId, advertisedHost, tcpPort);
            byte[] responseBytes = response.toBytes();

            DatagramPacket replyPacket = new DatagramPacket(
                    responseBytes,
                    responseBytes.length,
                    packet.getSocketAddress()
            );

            socket.send(replyPacket);
            LOGGER.info(String.format("[DISCOVERY] Answered discovery request from agent '%s' at %s (advertised TCP %s:%d)",
                    request.getAgentId(), packet.getSocketAddress(), advertisedHost, tcpPort));

        } catch (Exception ex) {
            LOGGER.fine("Malformed discovery datagram from " + packet.getSocketAddress() + ": " + ex.getMessage());
        }
    }

    /**
     * Determines the most accurate reachable LAN address to advertise to client agents.
     *
     * @return IPv4 string or hostname
     */
    public String resolveAdvertisedHost() {
        return NetworkUtils.advertisedHost(tcpBindHost);
    }

    /**
     * Gracefully stops the discovery server and releases socket resources.
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            LOGGER.info("Stopping DiscoveryServer on port " + udpPort + "...");
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            if (listenerThread != null) {
                listenerThread.interrupt();
            }
            LOGGER.info("DiscoveryServer stopped.");
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getUdpPort() {
        if (socket != null && socket.isBound()) {
            return socket.getLocalPort();
        }
        return udpPort;
    }

    public String getServerId() {
        return serverId;
    }
}

