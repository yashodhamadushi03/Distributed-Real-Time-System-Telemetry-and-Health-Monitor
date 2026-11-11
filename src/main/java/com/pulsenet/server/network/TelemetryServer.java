package com.pulsenet.server.network;

import com.pulsenet.server.registry.NodeRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Multithreaded TCP server listening for incoming client agent telemetry connections.
 * Dispatches each accepted connection to a dedicated ClientHandler worker thread.
 */
public class TelemetryServer implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(TelemetryServer.class.getName());

    private final String host;
    private final int port;
    private final NodeRegistry registry;

    /** Default client read timeout: max(3 x default 5 s watchdog timeout, 15 s). */
    public static final long DEFAULT_READ_TIMEOUT_MILLIS = 15_000L;

    private final long readTimeoutMillis;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread listenerThread;
    private final Set<Socket> activeClientSockets = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public TelemetryServer(int port) {
        this("0.0.0.0", port, new NodeRegistry());
    }

    public TelemetryServer(String host, int port, NodeRegistry registry) {
        this(host, port, registry, DEFAULT_READ_TIMEOUT_MILLIS);
    }

    /**
     * @param host              bind address
     * @param port              listen port (0 = ephemeral)
     * @param registry          shared node registry
     * @param readTimeoutMillis {@code SO_TIMEOUT} applied to each accepted client socket; a client
     *                          that sends nothing for this long (half-open socket) is disconnected.
     *                          {@code 0} disables the timeout.
     */
    public TelemetryServer(String host, int port, NodeRegistry registry, long readTimeoutMillis) {
        if (readTimeoutMillis < 0 || readTimeoutMillis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("readTimeoutMillis out of range: " + readTimeoutMillis);
        }
        this.host = host;
        this.port = port;
        this.registry = registry;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /**
     * Starts the TCP listener on a background thread.
     */
    public synchronized void start() throws IOException {
        if (running.compareAndSet(false, true)) {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(host, port));

            listenerThread = new Thread(this, "TelemetryServer-Listener-" + getPort());
            listenerThread.setDaemon(true);
            listenerThread.start();

            LOGGER.info(String.format("PulseNet TelemetryServer listening on %s:%d", host, getPort()));
        }
    }

    /**
     * Main accept loop dispatching worker threads.
     */
    @Override
    public void run() {
        while (running.get() && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                clientSocket.setKeepAlive(true);
                clientSocket.setSoTimeout((int) readTimeoutMillis);

                activeClientSockets.add(clientSocket);

                ClientHandler handler = new ClientHandler(clientSocket, registry);
                Thread worker = new Thread(() -> {
                    try {
                        handler.run();
                    } finally {
                        activeClientSockets.remove(clientSocket);
                    }
                }, "ClientHandler-" + clientSocket.getRemoteSocketAddress());
                worker.setDaemon(true);
                worker.start();

            } catch (SocketException e) {
                if (!running.get()) {
                    LOGGER.info("Server socket closed gracefully.");
                    break;
                }
                LOGGER.log(Level.WARNING, "Socket accept error: " + e.getMessage());
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.log(Level.WARNING, "I/O error in accept loop: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Gracefully stops the listener and closes all connected client sockets.
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            LOGGER.info("Stopping TelemetryServer on port " + getPort() + "...");
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }

            // Close all active client connections
            for (Socket client : activeClientSockets) {
                if (client != null && !client.isClosed()) {
                    try {
                        client.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            activeClientSockets.clear();

            if (listenerThread != null) {
                listenerThread.interrupt();
            }
            LOGGER.info("TelemetryServer stopped.");
        }
    }

    public int getPort() {
        if (serverSocket != null && serverSocket.isBound()) {
            return serverSocket.getLocalPort();
        }
        return port;
    }

    /**
     * @return the {@code SO_TIMEOUT} applied to accepted client sockets, in milliseconds
     */
    public long getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    /**
     * @return number of client sockets whose handler threads are still running
     */
    public int getActiveConnectionCount() {
        return activeClientSockets.size();
    }

    public NodeRegistry getRegistry() {
        return registry;
    }

    public boolean isRunning() {
        return running.get();
    }
}
