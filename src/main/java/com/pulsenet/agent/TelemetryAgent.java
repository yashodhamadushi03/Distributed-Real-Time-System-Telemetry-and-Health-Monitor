
package com.pulsenet.agent;

import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.common.protocol.WireProtocol;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TelemetryAgent implements Runnable {

    private static final Logger LOGGER =
            Logger.getLogger(TelemetryAgent.class.getName());

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

    // ================= CONSTRUCTORS =================

    public TelemetryAgent(
            String serverHost,
            int serverPort,
            String nodeId,
            long intervalMillis
    ) {

        this(
                serverHost,
                serverPort,
                nodeId,
                intervalMillis,
                new MetricCollector()
        );
    }

    public TelemetryAgent(
            String serverHost,
            int serverPort,
            String nodeId,
            long intervalMillis,
            MetricCollector metricCollector
    ) {

        if (serverHost == null || serverHost.isBlank()) {
            throw new IllegalArgumentException("Server host cannot be empty");
        }

        if (serverPort < 1 || serverPort > 65535) {
            throw new IllegalArgumentException("Invalid server port");
        }

        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("Node ID cannot be empty");
        }

        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("Interval must be positive");
        }

        if (metricCollector == null) {
            throw new IllegalArgumentException("MetricCollector cannot be null");
        }

        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.nodeId = nodeId;
        this.intervalMillis = intervalMillis;
        this.metricCollector = metricCollector;
    }

    // ================= START =================

    public synchronized void start() {

        if (running.compareAndSet(false, true)) {

            workerThread = new Thread(
                    this,
                    "TelemetryAgent-" + nodeId
            );

            workerThread.setDaemon(true);
            workerThread.start();

            LOGGER.info(
                    String.format(
                            "[%s] Agent started. Target: %s:%d",
                            nodeId,
                            serverHost,
                            serverPort
                    )
            );
        }
    }

    // ================= MAIN CONNECTION LOOP =================

    @Override
    public void run() {

        running.set(true);

        long currentBackoff = initialBackoffMillis;

        while (running.get()
                && !Thread.currentThread().isInterrupted()) {

            Socket socket = null;

            try {

                LOGGER.info(
                        String.format(
                                "[%s] Connecting to %s:%d...",
                                nodeId,
                                serverHost,
                                serverPort
                        )
                );

                socket = new Socket();

                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);

                socket.connect(
                        new InetSocketAddress(serverHost, serverPort),
                        connectTimeoutMillis
                );

                currentSocket = socket;

                LOGGER.info(
                        String.format(
                                "[%s] Connected successfully!",
                                nodeId
                        )
                );

                // Reset backoff after successful connection.
                currentBackoff = initialBackoffMillis;

                OutputStream outputStream =
                        socket.getOutputStream();

                // ================= TELEMETRY STREAMING =================

                while (running.get()
                        && !socket.isClosed()
                        && socket.isConnected()) {

                    try {

                        // Collect actual system metrics.
                        TelemetryPayload payload =
                                metricCollector.collect(nodeId);

                        // Send complete JSON telemetry.
                        WireProtocol.sendFramedMessage(
                                outputStream,
                                payload
                        );

                        long count = messagesSent.incrementAndGet();

                        LOGGER.fine(
                                String.format(
                                        "[%s] Telemetry sent. Messages: %d",
                                        nodeId,
                                        count
                                )
                        );

                    } catch (RuntimeException e) {

                        LOGGER.log(
                                Level.WARNING,
                                "[" + nodeId + "] Metric collection failed",
                                e
                        );

                        // Continue attempting the next sample.
                    }

                    try {

                        Thread.sleep(intervalMillis);

                    } catch (InterruptedException e) {

                        Thread.currentThread().interrupt();
                        break;
                    }
                }

            } catch (IOException e) {

                if (!running.get()) {
                    break;
                }

                LOGGER.log(
                        Level.WARNING,
                        String.format(
                                "[%s] Connection error: %s. Retrying in %d ms",
                                nodeId,
                                e.getMessage(),
                                currentBackoff
                        )
                );

                try {

                    Thread.sleep(currentBackoff);

                } catch (InterruptedException interruptedException) {

                    Thread.currentThread().interrupt();
                    break;
                }

                currentBackoff = Math.min(
                        currentBackoff * 2,
                        maxBackoffMillis
                );

            } finally {

                closeQuietly(socket);

                currentSocket = null;
            }
        }

        LOGGER.info(
                "[" + nodeId + "] Telemetry agent stopped."
        );
    }

    // ================= STOP =================

    public synchronized void stop() {

        if (running.compareAndSet(true, false)) {

            LOGGER.info(
                    "[" + nodeId + "] Stopping telemetry agent..."
            );

            closeQuietly(currentSocket);

            if (workerThread != null) {
                workerThread.interrupt();
            }
        }
    }

    // ================= SOCKET CLEANUP =================

    private void closeQuietly(Socket socket) {

        if (socket != null && !socket.isClosed()) {

            try {

                socket.close();

            } catch (IOException ignored) {
            }
        }
    }

    // ================= CONFIGURATION =================

    public void setInitialBackoffMillis(long value) {

        if (value <= 0) {
            throw new IllegalArgumentException(
                    "Initial backoff must be positive"
            );
        }

        initialBackoffMillis = value;
    }

    public void setMaxBackoffMillis(long value) {

        if (value <= 0) {
            throw new IllegalArgumentException(
                    "Maximum backoff must be positive"
            );
        }

        maxBackoffMillis = value;
    }

    public void setConnectTimeoutMillis(int value) {

        if (value <= 0) {
            throw new IllegalArgumentException(
                    "Connection timeout must be positive"
            );
        }

        connectTimeoutMillis = value;
    }

    // ================= GETTERS =================

    public boolean isRunning() {
        return running.get();
    }

    public long getMessagesSent() {
        return messagesSent.get();
    }

    public String getNodeId() {
        return nodeId;
    }
}
