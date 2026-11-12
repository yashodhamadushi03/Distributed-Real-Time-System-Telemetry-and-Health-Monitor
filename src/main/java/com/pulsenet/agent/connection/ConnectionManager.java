package com.pulsenet.agent.connection;

import com.pulsenet.agent.discovery.DiscoveryClient;
import com.pulsenet.common.discovery.DiscoveredServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the complete TCP socket lifecycle and automatic reconnection for an agent node.
 *
 * <p>Features:
 * <ul>
 *   <li>Thread-safe state transitions via {@link ConnectionState}</li>
 *   <li>Exponential backoff with randomized jitter via {@link BackoffCalculator}</li>
 *   <li>Seamless integration with {@link DiscoveryClient}</li>
 *   <li>Automatic resource disposal preventing duplicate sockets or thread leaks</li>
 *   <li>Cooperative graceful shutdown</li>
 * </ul>
 */
public class ConnectionManager {

    private static final Logger LOGGER = Logger.getLogger(ConnectionManager.class.getName());

    private final String nodeId;
    private volatile BackoffCalculator backoffCalculator;
    private volatile DiscoveryClient discoveryClient;
    private volatile int connectTimeoutMillis;
    private final int maxReconnectAttempts; // <= 0 means unlimited
    private final int maxDirectRetriesBeforeRediscover;

    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final AtomicInteger retryCounter = new AtomicInteger(0);
    private final List<ConnectionStateListener> listeners = new CopyOnWriteArrayList<>();
    private final ReentrantLock connectionLock = new ReentrantLock();

    private volatile String currentHost;
    private volatile int currentPort;
    private volatile Socket activeSocket;
    private volatile OutputStream activeOutputStream;
    private volatile InputStream activeInputStream;

    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    /**
     * Initializes connection manager with explicit host and port (manual mode).
     */
    public ConnectionManager(String nodeId, String host, int port) {
        this(nodeId, host, port, null, new BackoffCalculator(), 5000, 0, 3, null);
    }

    /**
     * Initializes connection manager with discovery client (automatic discovery mode).
     */
    public ConnectionManager(String nodeId, DiscoveryClient discoveryClient) {
        this(nodeId, null, 0, discoveryClient, new BackoffCalculator(), 5000, 0, 3, null);
    }

    /**
     * Full parameter constructor.
     */
    public ConnectionManager(String nodeId, String host, int port, DiscoveryClient discoveryClient,
                             BackoffCalculator backoffCalculator, int connectTimeoutMillis,
                             int maxReconnectAttempts, int maxDirectRetriesBeforeRediscover,
                             ScheduledExecutorService scheduler) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be null or blank");
        }
        this.nodeId = nodeId;
        this.currentHost = host;
        this.currentPort = port;
        this.discoveryClient = discoveryClient;
        this.backoffCalculator = backoffCalculator != null ? backoffCalculator : new BackoffCalculator();
        this.connectTimeoutMillis = connectTimeoutMillis > 0 ? connectTimeoutMillis : 5000;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.maxDirectRetriesBeforeRediscover = maxDirectRetriesBeforeRediscover > 0 ? maxDirectRetriesBeforeRediscover : 3;

        if (scheduler != null) {
            this.scheduler = scheduler;
            this.ownsScheduler = false;
        } else {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ConnectionManager-Scheduler-" + nodeId);
                t.setDaemon(true);
                return t;
            });
            this.ownsScheduler = true;
        }
    }

    /**
     * Registers a listener for state change notifications.
     */
    public void addListener(ConnectionStateListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Updates backoff configuration dynamically.
     */
    public void updateBackoff(long initialDelayMs, long maxDelayMs) {
        this.backoffCalculator = new BackoffCalculator(initialDelayMs, maxDelayMs);
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public void setDiscoveryClient(DiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    /**
     * Establishes initial connection to the server (either via discovery or configured host:port).
     * If the server is not immediately reachable, schedules automatic reconnection.
     *
     * @return true if successfully connected on this call
     */
    public boolean connect() {
        if (isTerminal()) {
            return false;
        }

        connectionLock.lock();
        try {
            if (state.get() == ConnectionState.CONNECTED) {
                return true;
            }

            // If in discovery mode, attempt discovery
            if (discoveryClient != null) {
                boolean discovered = performDiscovery();
                if (!discovered) {
                    if (currentHost != null && !currentHost.isBlank() && currentPort > 0) {
                        System.out.println(String.format("[DISCOVERY] Discovery yielded no server. Falling back to configured host %s:%d",
                                currentHost, currentPort));
                    } else {
                        transitionTo(ConnectionState.RECONNECTING);
                        scheduleReconnect();
                        return false;
                    }
                }
            }

            boolean ok = attemptDirectConnect();
            if (!ok) {
                transitionTo(ConnectionState.RECONNECTING);
                scheduleReconnect();
            }
            return ok;
        } finally {
            connectionLock.unlock();
        }
    }

    /**
     * Runs UDP discovery to find the server coordinates.
     */
    private boolean performDiscovery() {
        if (discoveryClient == null) {
            return false;
        }
        Optional<DiscoveredServer> serverOpt = discoveryClient.discover();
        if (serverOpt.isPresent()) {
            DiscoveredServer server = serverOpt.get();
            this.currentHost = server.resolveConnectHost();
            this.currentPort = server.tcpPort();
            LOGGER.info(String.format("[%s] Discovered server '%s' targeting %s:%d",
                    nodeId, server.serverId(), currentHost, currentPort));
            return true;
        }
        return false;
    }

    /**
     * Attempts a single synchronous TCP socket connection to currentHost:currentPort.
     */
    private boolean attemptDirectConnect() {
        if (isTerminal()) {
            return false;
        }

        transitionTo(ConnectionState.CONNECTING);
        System.out.println(String.format("[CONNECTION] Establishing TCP connection to %s:%d...", currentHost, currentPort));
        LOGGER.info(String.format("[%s] Attempting TCP connection to %s:%d...", nodeId, currentHost, currentPort));

        Socket socket = null;
        try {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(currentHost, currentPort), connectTimeoutMillis);

            this.activeOutputStream = socket.getOutputStream();
            this.activeInputStream = socket.getInputStream();
            this.activeSocket = socket;

            retryCounter.set(0); // Reset retry counter on success
            transitionTo(ConnectionState.CONNECTED);
            System.out.println(String.format("[CONNECTION] Connected successfully to %s:%d [Node: %s]",
                    currentHost, currentPort, nodeId));
            LOGGER.info(String.format("[%s] Connected successfully to %s:%d", nodeId, currentHost, currentPort));

            spawnConnectionMonitor(socket, this.activeInputStream);
            return true;

        } catch (IOException e) {
            closeQuietly(socket);
            this.activeSocket = null;
            this.activeOutputStream = null;
            this.activeInputStream = null;

            LOGGER.log(Level.WARNING, String.format("[%s] TCP connection failed to %s:%d: %s",
                    nodeId, currentHost, currentPort, e.getMessage()));
            return false;
        }
    }

    /**
     * Spawns a dedicated daemon thread to detect remote EOF or socket drop immediately.
     */
    private void spawnConnectionMonitor(Socket socket, InputStream in) {
        Thread monitorThread = new Thread(() -> {
            byte[] probeBuf = new byte[64];
            try {
                while (isConnected() && socket == activeSocket && !socket.isClosed()) {
                    int read = in.read(probeBuf);
                    if (read == -1) {
                        // Remote peer closed socket (EOF)
                        if (isConnected() && socket == activeSocket) {
                            notifyConnectionLost(new IOException("Remote peer closed connection (EOF)"));
                        }
                        break;
                    }
                }
            } catch (SocketException e) {
                if (isConnected() && socket == activeSocket) {
                    notifyConnectionLost(e);
                }
            } catch (IOException e) {
                if (isConnected() && socket == activeSocket) {
                    notifyConnectionLost(e);
                }
            }
        }, "ConnectionMonitor-" + nodeId);
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    /**
     * Signals that the active connection was severed unexpectedly and triggers automatic reconnection.
     *
     * @param cause exception that triggered the connection loss
     */
    public void notifyConnectionLost(Throwable cause) {
        if (isTerminal()) {
            return;
        }

        connectionLock.lock();
        try {
            ConnectionState current = state.get();
            if (current != ConnectionState.CONNECTED && current != ConnectionState.CONNECTING) {
                return;
            }

            transitionTo(ConnectionState.CONNECTION_LOST);
            String reason = cause != null ? cause.getMessage() : "Connection severed";
            System.err.println(String.format("[CONNECTION] Connection lost (%s). Preparing to reconnect...", reason));
            LOGGER.warning(String.format("[%s] Connection lost (%s). Cleaning up and initiating reconnection...", nodeId, reason));

            closeOldResources();

            transitionTo(ConnectionState.RECONNECTING);
            scheduleReconnect();

        } finally {
            connectionLock.unlock();
        }
    }

    /**
     * Schedules the next reconnection attempt using exponential backoff with jitter.
     */
    public void scheduleReconnect() {
        if (isTerminal()) {
            return;
        }

        int attempt = retryCounter.incrementAndGet();
        if (maxReconnectAttempts > 0 && attempt > maxReconnectAttempts) {
            System.err.println(String.format("[RECONNECT] Maximum reconnect attempts (%d) reached. Stopping.", maxReconnectAttempts));
            transitionTo(ConnectionState.DISCONNECTED);
            return;
        }

        long delayMs = backoffCalculator.calculateJitteredDelay(attempt);
        System.out.println(String.format("[RECONNECT] Attempt %d: Reconnecting in %d ms (target: %s:%d)...",
                attempt, delayMs, currentHost, currentPort));
        LOGGER.info(String.format("[%s] Reconnect attempt %d scheduled in %d ms", nodeId, attempt, delayMs));

        try {
            scheduler.schedule(this::executeReconnectAttempt, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            if (!isTerminal()) {
                LOGGER.log(Level.WARNING, "Scheduler rejected reconnect task: " + e.getMessage());
            }
        }
    }

    /**
     * Executes the scheduled reconnect attempt.
     */
    private void executeReconnectAttempt() {
        if (isTerminal()) {
            return;
        }

        connectionLock.lock();
        try {
            if (state.get() != ConnectionState.RECONNECTING) {
                return;
            }

            // Check if we should re-run UDP discovery because direct reconnects failed repeatedly
            int attempt = retryCounter.get();
            if (discoveryClient != null && attempt > maxDirectRetriesBeforeRediscover && (attempt % maxDirectRetriesBeforeRediscover == 1)) {
                System.out.println("[RECONNECT] Multiple reconnects failed; re-running UDP discovery...");
                performDiscovery();
            }

            boolean connected = attemptDirectConnect();
            if (connected) {
                System.out.println(String.format("[RECONNECT] Connection successfully restored to %s:%d! [Node ID: %s]",
                        currentHost, currentPort, nodeId));
                LOGGER.info(String.format("[%s] Connection successfully restored to %s:%d", nodeId, currentHost, currentPort));
            } else {
                scheduleReconnect();
            }
        } finally {
            connectionLock.unlock();
        }
    }

    /**
     * Closes the active socket and input/output streams cleanly.
     */
    private void closeOldResources() {
        Socket s = activeSocket;
        activeSocket = null;
        activeOutputStream = null;
        activeInputStream = null;
        closeQuietly(s);
    }

    private void closeQuietly(Socket s) {
        if (s != null && !s.isClosed()) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Updates state atomically and notifies listeners.
     */
    public boolean transitionTo(ConnectionState newState) {
        ConnectionState oldState = state.get();
        if (oldState == newState) {
            return false;
        }
        // Terminal states cannot transition to active states
        if (oldState == ConnectionState.STOPPED && newState != ConnectionState.STOPPED) {
            return false;
        }
        state.set(newState);
        LOGGER.fine(String.format("[%s] Connection state changed: %s -> %s", nodeId, oldState, newState));
        for (ConnectionStateListener listener : listeners) {
            try {
                listener.onStateChanged(oldState, newState);
            } catch (Exception ex) {
                LOGGER.warning("Error in connection state listener: " + ex.getMessage());
            }
        }
        return true;
    }

    /**
     * Initiates graceful shutdown: cancels pending retries, closes active sockets, releases resources.
     */
    public void stop() {
        connectionLock.lock();
        try {
            if (transitionTo(ConnectionState.STOPPING)) {
                LOGGER.info(String.format("[%s] ConnectionManager stopping cleanly...", nodeId));
                closeOldResources();

                if (ownsScheduler) {
                    scheduler.shutdownNow();
                }

                transitionTo(ConnectionState.STOPPED);
                LOGGER.info(String.format("[%s] ConnectionManager stopped.", nodeId));
            }
        } finally {
            connectionLock.unlock();
        }
    }

    public ConnectionState getState() {
        return state.get();
    }

    public boolean isConnected() {
        return state.get() == ConnectionState.CONNECTED && activeSocket != null && !activeSocket.isClosed();
    }

    public boolean isTerminal() {
        return state.get().isTerminal();
    }

    public Socket getActiveSocket() {
        return activeSocket;
    }

    public OutputStream getActiveOutputStream() {
        return activeOutputStream;
    }

    public InputStream getActiveInputStream() {
        return activeInputStream;
    }

    public String getCurrentHost() {
        return currentHost;
    }

    public int getCurrentPort() {
        return currentPort;
    }

    public int getRetryCount() {
        return retryCounter.get();
    }

    public String getNodeId() {
        return nodeId;
    }

    public BackoffCalculator getBackoffCalculator() {
        return backoffCalculator;
    }

    public DiscoveryClient getDiscoveryClient() {
        return discoveryClient;
    }
}
