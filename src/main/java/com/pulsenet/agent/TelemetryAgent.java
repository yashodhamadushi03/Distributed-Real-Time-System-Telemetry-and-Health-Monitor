package com.pulsenet.agent;

import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.common.protocol.WireProtocol;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resilient TCP telemetry streaming client.
 * Connects to the central aggregator, samples host metrics, and streams framed JSON payloads.
 * Features automatic reconnect with exponential backoff on network failures.
 */
public class TelemetryAgent implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(TelemetryAgent.class.getName());

    private final String serverHost;
    private final int serverPort;
    private final String nodeId;
    private final long intervalMillis;
    private final MetricCollector metricCollector;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong messagesSent = new AtomicLong(0);

    private long initialBackoffMillis = 1000;
    private long maxBackoffMillis = 10000;
    private int connectTimeoutMillis = 5000;

    private volatile Socket currentSocket;
    private Thread workerThread;

    /**
     * Initializes agent with target server endpoint and sampling interval.
     *
     * @param serverHost Aggregator hostname or IP.
     * @param serverPort Aggregator listener port.
     * @param nodeId Unique identity string for this host.
     * @param intervalMillis Polling/streaming interval in milliseconds.
     */
    public TelemetryAgent(String serverHost, int serverPort, String nodeId, long intervalMillis) {
        this(serverHost, serverPort, nodeId, intervalMillis, new MetricCollector());
    }

    /**
     * Initializes agent with custom MetricCollector (useful for testing and simulation).
     */
    public TelemetryAgent(String serverHost, int serverPort, String nodeId, long intervalMillis,
                          MetricCollector metricCollector) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.nodeId = nodeId;
        this.intervalMillis = intervalMillis;
        this.metricCollector = metricCollector;
    }

    /**
     * Starts the telemetry agent in a dedicated background daemon thread.
     */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            workerThread = new Thread(this, "TelemetryAgent-" + nodeId);
            workerThread.setDaemon(true);
            workerThread.start();
            LOGGER.info(String.format("[%s] Telemetry agent started targeting %s:%d (interval=%dms)",
                    nodeId, serverHost, serverPort, intervalMillis));
        }
    }

    /**
     * Main connection lifecycle loop with automatic exponential backoff reconnection.
     */
    @Override
    public void run() {
        running.set(true);
        long currentBackoff = initialBackoffMillis;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            Socket socket = null;
            try {
                LOGGER.info(String.format("[%s] Attempting connection to %s:%d...", nodeId, serverHost, serverPort));
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.connect(new InetSocketAddress(serverHost, serverPort), connectTimeoutMillis);
                this.currentSocket = socket;

                LOGGER.info(String.format("[%s] Connected successfully to %s:%d", nodeId, serverHost, serverPort));
                currentBackoff = initialBackoffMillis; // Reset backoff on successful connection

                OutputStream out = socket.getOutputStream();

                // Streaming loop
                while (running.get() && !socket.isClosed() && socket.isConnected()) {
                    TelemetryPayload payload = metricCollector.collect(nodeId);
                    WireProtocol.sendFramedMessage(out, payload);
                    messagesSent.incrementAndGet();

                    try {
                        Thread.sleep(intervalMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

            } catch (IOException e) {
                if (!running.get()) {
                    LOGGER.info(String.format("[%s] Agent stopping cleanly.", nodeId));
                    break;
                }
                LOGGER.log(Level.WARNING, String.format("[%s] Socket error (%s). Reconnecting in %d ms...",
                        nodeId, e.getMessage(), currentBackoff));
                try {
                    Thread.sleep(currentBackoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // Exponential backoff with ceiling
                currentBackoff = Math.min(currentBackoff * 2, maxBackoffMillis);
            } finally {
                closeQuietly(socket);
                this.currentSocket = null;
            }
        }
        LOGGER.info(String.format("[%s] Telemetry agent stopped.", nodeId));
    }

    /**
     * Signals the agent to terminate cleanly and closes active socket descriptors.
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            closeQuietly(currentSocket);
            if (workerThread != null) {
                workerThread.interrupt();
            }
        }
    }

    private void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
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

    public void setInitialBackoffMillis(long initialBackoffMillis) {
        this.initialBackoffMillis = initialBackoffMillis;
    }

    public void setMaxBackoffMillis(long maxBackoffMillis) {
        this.maxBackoffMillis = maxBackoffMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }
}
