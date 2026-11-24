package com.pulsenet.server.network;

import com.pulsenet.server.registry.NodeRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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

    /** Default upper bound on simultaneously connected clients. */
    public static final int DEFAULT_MAX_CONNECTIONS = 512;

    /** Pause after an unexpected accept failure so a persistent error cannot spin the CPU. */
    private static final long ACCEPT_ERROR_PAUSE_MILLIS = 100L;

    private final long readTimeoutMillis;
    private volatile int maxConnections = DEFAULT_MAX_CONNECTIONS;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;
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
     * Starts the TCP listener on a background thread. If binding fails the server stays stopped, so
     * {@code start()} can be retried once the port is free.
     */
    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(host, port));
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        serverSocket = socket;
        running.set(true);

        listenerThread = new Thread(this, "TelemetryServer-Listener-" + getPort());
        listenerThread.setDaemon(true);
        listenerThread.start();

        LOGGER.info(String.format("PulseNet TelemetryServer listening on %s:%d", host, getPort()));
    }

    /**
     * Main accept loop dispatching worker threads.
     */
    @Override
    public void run() {
        // Captured locally so a listener left over from a previous start() can never serve a newer socket.
        final ServerSocket listener = serverSocket;
        while (running.get() && listener != null && !listener.isClosed()) {
            Socket clientSocket = null;
            try {
                clientSocket = listener.accept();
                dispatch(clientSocket);
            } catch (IOException e) {
                closeQuietly(clientSocket);
                if (!running.get() || listener.isClosed()) {
                    LOGGER.info("Server socket closed gracefully.");
                    break;
                }
                LOGGER.log(Level.WARNING, "Error accepting client connection: " + e.getMessage());
                if (clientSocket == null) {
                    pauseAfterAcceptError();
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                // E.g. "unable to create native thread": drop this client, keep the listener alive.
                closeQuietly(clientSocket);
                LOGGER.log(Level.WARNING, "Could not service client connection: " + e.getMessage());
                pauseAfterAcceptError();
            }
        }
    }

    /**
     * Configures an accepted socket and hands it to its own worker thread.
     */
    private void dispatch(Socket clientSocket) throws IOException {
        if (activeClientSockets.size() >= maxConnections) {
            LOGGER.warning("Connection limit (" + maxConnections + ") reached; rejecting "
                    + clientSocket.getRemoteSocketAddress());
            closeQuietly(clientSocket);
            return;
        }
        clientSocket.setTcpNoDelay(true);
        clientSocket.setKeepAlive(true);
        clientSocket.setSoTimeout((int) readTimeoutMillis);

        activeClientSockets.add(clientSocket);
        boolean started = false;
        try {
            if (!running.get()) {
                // stop() ran between accept() and registration and may have missed this socket.
                return;
            }
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
            started = true;
        } finally {
            if (!started) {
                activeClientSockets.remove(clientSocket);
                closeQuietly(clientSocket);
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void pauseAfterAcceptError() {
        try {
            Thread.sleep(ACCEPT_ERROR_PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
                closeQuietly(client);
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

    /**
     * @return maximum number of simultaneously connected clients
     */
    public int getMaxConnections() {
        return maxConnections;
    }

    /**
     * @param maxConnections maximum number of simultaneously connected clients (at least 1)
     */
    public void setMaxConnections(int maxConnections) {
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections must be >= 1: " + maxConnections);
        }
        this.maxConnections = maxConnections;
    }

    public NodeRegistry getRegistry() {
        return registry;
    }

    public boolean isRunning() {
        return running.get();
    }
}
