package com.pulsenet.server.network;

import com.pulsenet.common.network.NetworkProbePacket;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-performance, stateless UDP probe echo responder for PulseNet network performance monitoring.
 *
 * <p>Listens for sequence-numbered {@link NetworkProbePacket} request datagrams from agent nodes
 * and immediately echoes a response containing the original sequence number, client send timestamp,
 * and server reception timestamp.
 *
 * <p>Operates independently of TCP telemetry streaming and UDP server discovery, allowing pure
 * application-layer Round-Trip Time (RTT), jitter, and packet loss measurements.
 */
public class NetworkProbeServer implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(NetworkProbeServer.class.getName());

    public static final int DEFAULT_PROBE_PORT = 9002;
    public static final int MAX_BUFFER_SIZE = 1024;

    private final String bindHost;
    private final int probePort;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private DatagramSocket socket;
    private Thread listenerThread;

    public NetworkProbeServer() {
        this("0.0.0.0", DEFAULT_PROBE_PORT);
    }

    public NetworkProbeServer(int probePort) {
        this("0.0.0.0", probePort);
    }

    public NetworkProbeServer(String bindHost, int probePort) {
        if (probePort <= 0 || probePort > 65535) {
            throw new IllegalArgumentException("Invalid UDP probe port: " + probePort);
        }
        this.bindHost = (bindHost == null || bindHost.isBlank()) ? "0.0.0.0" : bindHost;
        this.probePort = probePort;
    }

    /**
     * Starts the UDP probe listener in a dedicated background daemon thread.
     *
     * @throws SocketException if socket binding fails
     */
    public synchronized void start() throws SocketException {
        if (running.compareAndSet(false, true)) {
            InetSocketAddress bindAddress = new InetSocketAddress(bindHost, probePort);
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(bindAddress);

            listenerThread = new Thread(this, "NetworkProbeServer-UDP-" + getProbePort());
            listenerThread.setDaemon(true);
            listenerThread.start();

            LOGGER.info(String.format("PulseNet NetworkProbeServer listening on %s:%d (UDP)", bindHost, getProbePort()));
        }
    }

    @Override
    public void run() {
        byte[] buffer = new byte[MAX_BUFFER_SIZE];

        while (running.get() && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket rxPacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(rxPacket);

                long echoTime = System.currentTimeMillis();
                NetworkProbePacket request = NetworkProbePacket.fromBytes(
                        rxPacket.getData(), rxPacket.getOffset(), rxPacket.getLength()
                );

                if (request != null && request.isRequest()) {
                    NetworkProbePacket response = NetworkProbePacket.createResponse(request, echoTime);
                    byte[] txBytes = response.toBytes();

                    DatagramPacket txPacket = new DatagramPacket(
                            txBytes, txBytes.length, rxPacket.getSocketAddress()
                    );
                    socket.send(txPacket);
                }

            } catch (SocketException e) {
                if (!running.get()) {
                    LOGGER.info("NetworkProbeServer socket closed cleanly.");
                    break;
                }
                LOGGER.log(Level.WARNING, "NetworkProbeServer socket error: " + e.getMessage());
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.log(Level.WARNING, "I/O error during UDP probe receive/send: " + e.getMessage());
                }
            } catch (Exception e) {
                if (running.get()) {
                    LOGGER.log(Level.WARNING, "Unexpected error processing UDP probe: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Gracefully stops the probe listener and releases socket resources.
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            LOGGER.info("Stopping NetworkProbeServer on port " + probePort + "...");
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            if (listenerThread != null) {
                listenerThread.interrupt();
            }
            LOGGER.info("NetworkProbeServer stopped.");
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getProbePort() {
        if (socket != null && socket.isBound()) {
            return socket.getLocalPort();
        }
        return probePort;
    }
}

