package com.pulsenet.agent;

import com.pulsenet.agent.connection.ConnectionManager;
import com.pulsenet.agent.connection.ConnectionState;
import com.pulsenet.agent.discovery.DiscoveryClient;
import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.common.protocol.WireProtocol;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Resilient TCP telemetry streaming client.
 * Connects to the central aggregator, samples host metrics, and streams framed JSON payloads.
 * Features automatic reconnect with exponential backoff and optional UDP auto-discovery.
 */
public class TelemetryAgent implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(TelemetryAgent.class.getName());

    private final String nodeId;
    private final long intervalMillis;
    private final MetricCollector metricCollector;
    private final ConnectionManager connectionManager;

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
        this.nodeId = nodeId;
        this.intervalMillis = intervalMillis;
        this.metricCollector = metricCollector;
        this.connectionManager = new ConnectionManager(nodeId, serverHost, serverPort);
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
        this.nodeId = nodeId;
        this.intervalMillis = intervalMillis;
        this.metricCollector = metricCollector;
        this.connectionManager = new ConnectionManager(nodeId, fallbackHost, fallbackPort, discoveryClient,
                new com.pulsenet.agent.connection.BackoffCalculator(), 5000, 0, 3, null);
        registerStateListener();
    }

    private void registerStateListener() {
        this.connectionManager.addListener((oldState, newState) -> {
            if (newState == ConnectionState.CONNECTED) {
                synchronized (stateSync) {
                    stateSync.notifyAll();
                }
            }
        });
    }

    /**
     * Starts the telemetry agent in a dedicated background daemon thread.
     */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
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

            try {
                OutputStream out = connectionManager.getActiveOutputStream();
                if (out != null) {
                    TelemetryPayload payload = metricCollector.collect(nodeId);
                    WireProtocol.sendFramedMessage(out, payload);
                    messagesSent.incrementAndGet();
                }

                Thread.sleep(intervalMillis);

            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.warning(String.format("[%s] Telemetry send failed: %s", nodeId, e.getMessage()));
                    connectionManager.notifyConnectionLost(e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        LOGGER.info(String.format("[%s] Telemetry agent loop terminated.", nodeId));
    }

    /**
     * Signals the agent to terminate cleanly and releases active socket descriptors.
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            connectionManager.stop();
            synchronized (stateSync) {
                stateSync.notifyAll();
            }
            if (workerThread != null) {
                workerThread.interrupt();
            }
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
}
