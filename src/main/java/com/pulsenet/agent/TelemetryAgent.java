package com.pulsenet.agent;

import com.pulsenet.agent.connection.ConnectionManager;
import com.pulsenet.agent.connection.ConnectionState;
import com.pulsenet.agent.discovery.DiscoveryClient;
import com.pulsenet.agent.network.InternetMonitor;
import com.pulsenet.agent.network.NetworkProbeClient;
import com.pulsenet.common.model.NetworkMetrics;
import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.common.protocol.WireProtocol;
import com.pulsenet.server.network.NetworkProbeServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resilient TCP telemetry streaming client.
 * Connects to the central aggregator, samples host metrics, and streams framed JSON payloads.
 * Features automatic reconnect with exponential backoff, UDP auto-discovery, and real-time
 * network latency, jitter, and packet loss probing.
 */
public class TelemetryAgent implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(TelemetryAgent.class.getName());

    private final String nodeId;
    private final long intervalMillis;
    private final MetricCollector metricCollector;
    private final ConnectionManager connectionManager;
    private final NetworkProbeClient networkProbeClient;
    private final InternetMonitor internetMonitor;
    private volatile int probePort = NetworkProbeServer.DEFAULT_PROBE_PORT;

    private volatile boolean followDiscoveredProbePort = true;
    private volatile boolean internetMonitoringEnabled = true;
    private volatile String localHostname;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final Object stateSync = new Object();

    private long initialBackoffMillis = 1000;
    private long maxBackoffMillis = 10000;
    private int connectTimeoutMillis = 5000;

    private Thread workerThread;

    /**
     * Initializes agent targeting explicit server endpoint (manual connection mode).
     *
     * @param serverHost     Aggregator hostname or IP.
     * @param serverPort     Aggregator listener port.
     * @param nodeId         Unique identity string for this host.
     * @param intervalMillis Polling/streaming interval in milliseconds.
     */
    public TelemetryAgent(String serverHost, int serverPort, String nodeId, long intervalMillis) {
        this(serverHost, serverPort, nodeId, intervalMillis, new MetricCollector());
    }

    /**
     * Initializes agent with custom MetricCollector (manual connection mode).
     */
    public TelemetryAgent(String serverHost, int serverPort, String nodeId, long intervalMillis,
                          MetricCollector metricCollector) {
        this.nodeId = requireNodeId(nodeId);
        this.intervalMillis = requireInterval(intervalMillis);
        this.metricCollector = Objects.requireNonNull(metricCollector, "metricCollector must not be null");
        if (serverHost == null || serverHost.isBlank()) {
            throw new IllegalArgumentException("serverHost must not be null or blank");
        }
        if (serverPort < 1 || serverPort > 65_535) {
            throw new IllegalArgumentException("serverPort out of range: " + serverPort);
        }
        this.connectionManager = new ConnectionManager(nodeId, serverHost, serverPort);
        this.networkProbeClient = new NetworkProbeClient(nodeId, serverHost, probePort);
        this.internetMonitor = new InternetMonitor(nodeId);
        registerStateListener();
    }

    /**
     * Initializes agent using automatic UDP discovery to locate the server.
     *
     * @param discoveryClient pre-configured discovery client
     * @param nodeId          Unique identity string for this host
     * @param intervalMillis  Polling/streaming interval in milliseconds
     */
    public TelemetryAgent(DiscoveryClient discoveryClient, String nodeId, long intervalMillis) {
        this(discoveryClient, null, 0, nodeId, intervalMillis, new MetricCollector());
    }

    /**
     * Initializes agent using automatic UDP discovery with optional manual fallback host and custom MetricCollector.
     */
    public TelemetryAgent(DiscoveryClient discoveryClient, String fallbackHost, int fallbackPort,
                          String nodeId, long intervalMillis) {
        this(discoveryClient, fallbackHost, fallbackPort, nodeId, intervalMillis, new MetricCollector());
    }

    /**
     * Initializes agent using automatic UDP discovery with custom MetricCollector.
     */
    public TelemetryAgent(DiscoveryClient discoveryClient, String nodeId, long intervalMillis,
                          MetricCollector metricCollector) {
        this(discoveryClient, null, 0, nodeId, intervalMillis, metricCollector);
    }

    /**
     * Full parameter discovery constructor with manual fallback and custom MetricCollector.
     */
    public TelemetryAgent(DiscoveryClient discoveryClient, String fallbackHost, int fallbackPort,
                          String nodeId, long intervalMillis, MetricCollector metricCollector) {
        this.nodeId = requireNodeId(nodeId);
        this.intervalMillis = requireInterval(intervalMillis);
        this.metricCollector = Objects.requireNonNull(metricCollector, "metricCollector must not be null");
        this.connectionManager = new ConnectionManager(nodeId, fallbackHost, fallbackPort, discoveryClient,
                new com.pulsenet.agent.connection.BackoffCalculator(), 5000, 0, 3, null);
        this.networkProbeClient = new NetworkProbeClient(nodeId, fallbackHost, probePort);
        this.internetMonitor = new InternetMonitor(nodeId);
        registerStateListener();
    }

    private static String requireNodeId(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be null or blank");
        }
        return nodeId;
    }

    private static long requireInterval(long intervalMillis) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis must be > 0: " + intervalMillis);
        }
        return intervalMillis;
    }

    /** Starts the background probes that are enabled and not running yet. */
    private synchronized void startProbes() {
        if (!running.get()) {
            return;
        }
        if (!networkProbeClient.isRunning()) {
            try {
                networkProbeClient.setTarget(connectionManager.getCurrentHost(), probePort);
                networkProbeClient.start();
            } catch (Exception e) {
                LOGGER.warning(String.format("[%s] Failed to start NetworkProbeClient: %s", nodeId, e.getMessage()));
            }
        }
        if (internetMonitoringEnabled && !internetMonitor.isRunning()) {
            internetMonitor.start();
        }
    }

    private void registerStateListener() {
        this.connectionManager.addListener((oldState, newState) -> {
            if (newState == ConnectionState.RECONNECTING || newState == ConnectionState.DISCONNECTED) {
                if (internetMonitor != null) {
                    internetMonitor.triggerImmediateCheck();
                }
            } else if (newState == ConnectionState.CONNECTED) {
                String targetHost = connectionManager.getCurrentHost();
                Socket activeSocket = connectionManager.getActiveSocket();
                if (activeSocket != null && internetMonitor != null) {
                    internetMonitor.setActiveLocalAddress(activeSocket.getLocalAddress());
                }
                int announcedProbePort = connectionManager.getDiscoveredProbePort();
                if (followDiscoveredProbePort && announcedProbePort > 0) {
                    probePort = announcedProbePort;
                }
                networkProbeClient.setTarget(targetHost, probePort);
                startProbes();
                internetMonitor.triggerImmediateCheck();
                synchronized (stateSync) {
                    stateSync.notifyAll();
                }
            }
        });
    }

    /**
     * Starts the telemetry agent in a dedicated background daemon thread. An agent that has been
     * stopped cannot be started again; create a new one.
     */
    public synchronized void start() {
        if (connectionManager.isTerminal()) {
            LOGGER.warning(String.format("[%s] A stopped agent cannot be restarted", nodeId));
            return;
        }
        if (running.compareAndSet(false, true)) {
            startProbes();
            workerThread = new Thread(this, "TelemetryAgent-" + nodeId);
            workerThread.setDaemon(true);
            workerThread.start();
            LOGGER.info(String.format("[%s] Telemetry agent started (interval=%dms)", nodeId, intervalMillis));
        }
    }

    /**
     * Main connection lifecycle and telemetry streaming loop.
     */
    @Override
    public void run() {
        if (!running.get()) {
            return;
        }

        // Initiate initial connection attempt
        connectionManager.connect();

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            if (!connectionManager.isConnected()) {
                synchronized (stateSync) {
                    while (running.get() && !connectionManager.isConnected()) {
                        try {
                            stateSync.wait(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
            }

            Socket activeSocket = connectionManager.getActiveSocket();
            try {
                if (activeSocket != null && !activeSocket.isClosed()) {
                    internetMonitor.setActiveLocalAddress(activeSocket.getLocalAddress());
                    startProbes();
                    WireProtocol.sendFramedMessage(activeSocket.getOutputStream(), buildPayload());
                    messagesSent.incrementAndGet();
                }

                Thread.sleep(intervalMillis);

            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.warning(String.format("[%s] Telemetry send failed: %s", nodeId, e.getMessage()));
                    connectionManager.notifyConnectionLost(activeSocket, e);
                    internetMonitor.triggerImmediateCheck();
                }
            } catch (RuntimeException e) {
                // One failed sample must not end the stream
                LOGGER.log(Level.WARNING, String.format("[%s] Could not collect telemetry: %s", nodeId, e), e);
                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        LOGGER.info(String.format("[%s] Telemetry agent loop terminated.", nodeId));
    }

    /**
     * Samples this machine and attaches the current network and Internet measurements.
     */
    private TelemetryPayload buildPayload() {
        TelemetryPayload payload = metricCollector.collect(nodeId);
        NetworkMetrics netMetrics = new NetworkMetrics();
        networkProbeClient.populateMetrics(netMetrics);
        // Last, so that the TCP connection's source port is reported rather than the probe socket's
        connectionManager.populateConnectionMetrics(netMetrics);
        netMetrics.setAgentHostname(resolveLocalHostname());
        payload.setNetworkMetrics(netMetrics);
        if (internetMonitoringEnabled) {
            payload.setInternetMetrics(internetMonitor.getLatestMetrics());
        }
        return payload;
    }

    /**
     * Signals the agent to terminate cleanly and releases active socket descriptors.
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            if (networkProbeClient != null) {
                networkProbeClient.stop();
            }
            if (internetMonitor != null) {
                internetMonitor.stop();
            }
            connectionManager.stop();
            synchronized (stateSync) {
                stateSync.notifyAll();
            }
            if (workerThread != null) {
                workerThread.interrupt();
            }
        }
    }

    private String resolveLocalHostname() {
        String cached = localHostname;
        if (cached == null) {
            // Cached: the lookup can block for seconds when DNS is down
            cached = lookupLocalHostname();
            localHostname = cached;
        }
        return cached;
    }

    private static String lookupLocalHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            String host = System.getenv("COMPUTERNAME");
            if (host == null) host = System.getenv("HOSTNAME");
            return (host != null && !host.isBlank()) ? host : "unknown-host";
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getMessagesSent() {
        return messagesSent.get();
    }

    public String getNodeId() {
        return nodeId;
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public ConnectionState getConnectionState() {
        return connectionManager.getState();
    }

    public Socket getCurrentSocket() {
        return connectionManager.getActiveSocket();
    }

    public String getServerHost() {
        return connectionManager.getCurrentHost();
    }

    public int getServerPort() {
        return connectionManager.getCurrentPort();
    }

    public void setInitialBackoffMillis(long initialBackoffMillis) {
        this.initialBackoffMillis = initialBackoffMillis;
        this.connectionManager.updateBackoff(initialBackoffMillis, maxBackoffMillis);
    }

    public void setMaxBackoffMillis(long maxBackoffMillis) {
        this.maxBackoffMillis = maxBackoffMillis;
        this.connectionManager.updateBackoff(initialBackoffMillis, maxBackoffMillis);
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.connectionManager.setConnectTimeoutMillis(connectTimeoutMillis);
    }

    public void setDiscoveryClient(DiscoveryClient discoveryClient) {
        this.connectionManager.setDiscoveryClient(discoveryClient);
    }

    public String getSelectedServerId() {
        return this.connectionManager.getSelectedServerId();
    }

    public void setSelectedServerId(String selectedServerId) {
        this.connectionManager.setSelectedServerId(selectedServerId);
    }

    public NetworkProbeClient getNetworkProbeClient() {
        return networkProbeClient;
    }

    public int getProbePort() {
        return probePort;
    }

    public void setProbePort(int probePort) {
        this.probePort = probePort;
        if (networkProbeClient != null) {
            networkProbeClient.setTarget(connectionManager.getCurrentHost(), probePort);
        }
    }

    /**
     * @param follow whether the probe port announced by a discovered server replaces the configured one
     */
    public void setFollowDiscoveredProbePort(boolean follow) {
        this.followDiscoveredProbePort = follow;
    }

    /**
     * Enables or disables Internet checks; when disabled, no Internet metrics are reported.
     * Must be called before {@link #start()}.
     */
    public void setInternetMonitoringEnabled(boolean enabled) {
        this.internetMonitoringEnabled = enabled;
    }

    public InternetMonitor getInternetMonitor() {
        return internetMonitor;
    }
}
