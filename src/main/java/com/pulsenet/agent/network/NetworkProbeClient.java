package com.pulsenet.agent.network;

import com.pulsenet.common.model.NetworkMetrics;
import com.pulsenet.common.network.NetworkProbePacket;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Autonomous, multithreaded network probing client running on each PulseNet telemetry agent node.
 *
 * <p>Measures:
 * <ul>
 *   <li><b>Latency (Round-Trip Time / RTT):</b> Accurate millisecond request-response probes tracking
 *       current, minimum, maximum, and cumulative average RTT.</li>
 *   <li><b>Network Jitter:</b> Real-time variation between successive RTT measurements smoothed via
 *       the standard RFC 3550 / RFC 1889 filter algorithm.</li>
 *   <li><b>Packet Loss:</b> Sequence-numbered UDP probes evaluated over a sliding measurement window
 *       with deterministic timeout detection for lost or delayed packets.</li>
 * </ul>
 */
public class NetworkProbeClient implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(NetworkProbeClient.class.getName());

    public static final int DEFAULT_PROBE_INTERVAL_MILLIS = 1000;
    public static final int DEFAULT_PROBE_TIMEOUT_MILLIS = 1500;
    public static final int DEFAULT_WINDOW_SIZE = 50;
    public static final int BUFFER_SIZE = 1024;

    private final String nodeId;
    private final long probeIntervalMillis;
    private final long probeTimeoutMillis;
    private final int windowSize;

    private volatile String targetHost;
    private volatile int targetProbePort;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    // Cumulative counters
    final AtomicLong totalProbesSent = new AtomicLong(0);
    final AtomicLong totalProbesReceived = new AtomicLong(0);
    final AtomicLong totalProbesLost = new AtomicLong(0);

    // In-flight probe records: sequence -> sendTimestamp
    final ConcurrentMap<Long, Long> inFlightProbes = new ConcurrentHashMap<>();

    // Sliding window of recent probe outcomes: true = received, false = lost
    final Deque<Boolean> outcomeWindow = new ArrayDeque<>();
    private final ReentrantLock statsLock = new ReentrantLock();

    // Latency statistics
    private double currentRttMs = -1.0;
    private double minRttMs = -1.0;
    private double maxRttMs = -1.0;
    private double sumRttMs = 0.0;
    private long rttSampleCount = 0L;

    // Jitter statistics (RFC 3550 filter)
    private double previousRttMs = -1.0;
    private double currentJitterMs = -1.0;
    private double avgJitterMs = -1.0;
    private long jitterSampleCount = 0L;

    private DatagramSocket socket;
    private Thread rxThread;
    private ScheduledExecutorService txScheduler;

    public NetworkProbeClient(String nodeId, String targetHost, int targetProbePort) {
        this(nodeId, targetHost, targetProbePort, DEFAULT_PROBE_INTERVAL_MILLIS,
                DEFAULT_PROBE_TIMEOUT_MILLIS, DEFAULT_WINDOW_SIZE);
    }

    public NetworkProbeClient(String nodeId, String targetHost, int targetProbePort,
                              long probeIntervalMillis, long probeTimeoutMillis, int windowSize) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId cannot be null");
        this.targetHost = targetHost;
        this.targetProbePort = targetProbePort;
        this.probeIntervalMillis = Math.max(100L, probeIntervalMillis);
        this.probeTimeoutMillis = Math.max(200L, probeTimeoutMillis);
        this.windowSize = Math.max(10, windowSize);
    }

    /**
     * Dynamically updates the probe target coordinates (e.g., following server reconnection or IP shift).
     *
     * @param targetHost      new target host address
     * @param targetProbePort new target probe port
     */
    public void setTarget(String targetHost, int targetProbePort) {
        this.targetHost = targetHost;
        this.targetProbePort = targetProbePort;
    }

    /**
     * Starts the probe receiver daemon thread and scheduled transmitter.
     */
    public synchronized void start() throws SocketException {
        if (running.compareAndSet(false, true)) {
            this.socket = new DatagramSocket(null);
            this.socket.setReuseAddress(true);
            this.socket.bind(new InetSocketAddress(0)); // Bind to ephemeral UDP port

            rxThread = new Thread(this, "NetworkProbeClient-Rx-" + nodeId);
            rxThread.setDaemon(true);
            rxThread.start();

            txScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NetworkProbeClient-Tx-" + nodeId);
                t.setDaemon(true);
                return t;
            });

            txScheduler.scheduleAtFixedRate(this::sendProbe, 200, probeIntervalMillis, TimeUnit.MILLISECONDS);
            LOGGER.info(String.format("[%s] NetworkProbeClient active (target %s:%d, interval=%dms)",
                    nodeId, targetHost, targetProbePort, probeIntervalMillis));
        }
    }

    /**
     * Dispatches a single sequence-numbered probe request packet and sweeps expired in-flight probes.
     */
    private void sendProbe() {
        if (!running.get() || socket == null || socket.isClosed()) {
            return;
        }

        String host = this.targetHost;
        int port = this.targetProbePort;
        if (host == null || host.isBlank() || port <= 0) {
            return; // Target endpoint not yet discovered/resolved
        }

        try {
            long seq = sequenceCounter.incrementAndGet();
            long now = System.currentTimeMillis();

            inFlightProbes.put(seq, now);
            totalProbesSent.incrementAndGet();

            NetworkProbePacket probe = NetworkProbePacket.createRequest(seq, now, nodeId);
            byte[] bytes = probe.toBytes();

            InetAddress targetAddr = InetAddress.getByName(host);
            DatagramPacket txPacket = new DatagramPacket(bytes, bytes.length, targetAddr, port);
            socket.send(txPacket);

            // Audit in-flight probes for timeouts
            auditInFlightTimeouts(now);

        } catch (Exception e) {
            LOGGER.log(Level.FINE, "[" + nodeId + "] Error sending network probe: " + e.getMessage());
        }
    }

    /**
     * Audits probes currently in-flight; probes older than probeTimeoutMillis are marked lost.
     */
    private void auditInFlightTimeouts(long currentTime) {
        Iterator<Map.Entry<Long, Long>> it = inFlightProbes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> entry = it.next();
            long sendTime = entry.getValue();
            if (currentTime - sendTime > probeTimeoutMillis) {
                it.remove();
                totalProbesLost.incrementAndGet();
                recordWindowOutcome(false);
            }
        }
    }

    /**
     * Continuous receiver loop processing incoming probe responses.
     */
    @Override
    public void run() {
        byte[] buffer = new byte[BUFFER_SIZE];

        while (running.get() && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket rxPacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(rxPacket);

                long rxTime = System.currentTimeMillis();
                NetworkProbePacket packet = NetworkProbePacket.fromBytes(
                        rxPacket.getData(), rxPacket.getOffset(), rxPacket.getLength()
                );

                if (packet != null && packet.isResponse()) {
                    handleProbeResponse(packet, rxTime);
                }

            } catch (SocketException e) {
                if (!running.get()) {
                    break;
                }
                LOGGER.log(Level.WARNING, "[" + nodeId + "] Probe socket error: " + e.getMessage());
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.log(Level.FINE, "[" + nodeId + "] I/O error on probe receive: " + e.getMessage());
                }
            } catch (Exception e) {
                if (running.get()) {
                    LOGGER.log(Level.WARNING, "[" + nodeId + "] Unexpected error parsing probe response: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Processes a valid probe response, updating RTT, Jitter, and Packet Loss statistics.
     */
    void handleProbeResponse(NetworkProbePacket response, long rxTime) {
        if (response.getNodeId() != null && !response.getNodeId().isBlank() && !this.nodeId.equals(response.getNodeId())) {
            return; // Reject probe response belonging to another node
        }

        long seq = response.getSequenceNumber();
        Long sendTime = inFlightProbes.remove(seq);

        if (sendTime == null) {
            // Already timed out or duplicate response
            return;
        }

        double rtt = Math.max(0.0, (double) (rxTime - sendTime));
        totalProbesReceived.incrementAndGet();
        recordWindowOutcome(true);

        statsLock.lock();
        try {
            currentRttMs = rtt;
            if (rttSampleCount == 0) {
                minRttMs = rtt;
                maxRttMs = rtt;
            } else {
                minRttMs = Math.min(minRttMs, rtt);
                maxRttMs = Math.max(maxRttMs, rtt);
            }
            sumRttMs += rtt;
            rttSampleCount++;

            // Network Jitter Calculation (RFC 3550 Smoothing Filter)
            if (previousRttMs >= 0.0) {
                double diff = Math.abs(rtt - previousRttMs);
                currentJitterMs = diff;

                if (jitterSampleCount == 0) {
                    avgJitterMs = diff;
                } else {
                    // RFC 3550: J = J + (|D| - J) / 16
                    avgJitterMs = avgJitterMs + (diff - avgJitterMs) / 16.0;
                }
                jitterSampleCount++;
            }
            previousRttMs = rtt;

        } finally {
            statsLock.unlock();
        }
    }

    /**
     * Records a probe outcome (true = received, false = lost) into the sliding window.
     */
    void recordWindowOutcome(boolean received) {
        statsLock.lock();
        try {
            outcomeWindow.addLast(received);
            while (outcomeWindow.size() > windowSize) {
                outcomeWindow.pollFirst();
            }
        } finally {
            statsLock.unlock();
        }
    }

    /**
     * Computes the current packet loss percentage over the sliding window.
     *
     * @return packet loss percentage (0.0 to 100.0)
     */
    public double getWindowPacketLossPercent() {
        statsLock.lock();
        try {
            if (outcomeWindow.isEmpty()) {
                return 0.0;
            }
            int lost = 0;
            for (boolean received : outcomeWindow) {
                if (!received) {
                    lost++;
                }
            }
            return ((double) lost / outcomeWindow.size()) * 100.0;
        } finally {
            statsLock.unlock();
        }
    }

    /**
     * Populates the supplied NetworkMetrics instance with a consistent snapshot of current measurements.
     *
     * @param metrics target NetworkMetrics object to populate
     */
    public void populateMetrics(NetworkMetrics metrics) {
        if (metrics == null) return;

        statsLock.lock();
        try {
            metrics.setCurrentRttMs(roundToOneDecimal(currentRttMs));
            metrics.setMinRttMs(roundToOneDecimal(minRttMs));
            metrics.setMaxRttMs(roundToOneDecimal(maxRttMs));
            double avg = (rttSampleCount > 0) ? (sumRttMs / rttSampleCount) : -1.0;
            metrics.setAvgRttMs(roundToOneDecimal(avg));

            metrics.setCurrentJitterMs(roundToOneDecimal(currentJitterMs));
            metrics.setAvgJitterMs(roundToOneDecimal(avgJitterMs));

            metrics.setWindowSize(windowSize);
            metrics.setPacketLossPercent(roundToOneDecimal(getWindowPacketLossPercent()));
            long sent = totalProbesSent.get();
            long recvd = totalProbesReceived.get();
            long lost = totalProbesLost.get();
            double cumLoss = (sent > 0) ? ((double) lost / (double) sent) * 100.0 : 0.0;
            metrics.setCumulativeLossPercent(roundToOneDecimal(cumLoss));
            metrics.setProbesSent(sent);
            metrics.setProbesReceived(recvd);
            metrics.setProbesLost(lost);

            metrics.setServerHost(targetHost);
            metrics.setUdpProbePort(targetProbePort);

            if (socket != null && socket.isBound()) {
                metrics.setAgentSourcePort(socket.getLocalPort());
            }

        } finally {
            statsLock.unlock();
        }
    }

    private double roundToOneDecimal(double val) {
        if (val < 0.0) return -1.0;
        return Math.round(val * 10.0) / 10.0;
    }

    /**
     * Stops the probe client, releasing socket and scheduler resources.
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            if (txScheduler != null) {
                txScheduler.shutdownNow();
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            if (rxThread != null) {
                rxThread.interrupt();
            }
            inFlightProbes.clear();
            LOGGER.info("[" + nodeId + "] NetworkProbeClient stopped.");
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public double getCurrentRttMs() {
        statsLock.lock();
        try {
            return currentRttMs;
        } finally {
            statsLock.unlock();
        }
    }

    public double getAvgRttMs() {
        statsLock.lock();
        try {
            return (rttSampleCount > 0) ? (sumRttMs / rttSampleCount) : -1.0;
        } finally {
            statsLock.unlock();
        }
    }

    public double getAvgJitterMs() {
        statsLock.lock();
        try {
            return avgJitterMs;
        } finally {
            statsLock.unlock();
        }
    }

    public long getTotalProbesSent() {
        return totalProbesSent.get();
    }

    public long getTotalProbesReceived() {
        return totalProbesReceived.get();
    }

    public long getTotalProbesLost() {
        return totalProbesLost.get();
    }

    public double getCumulativePacketLossPercent() {
        long sent = totalProbesSent.get();
        long lost = totalProbesLost.get();
        return (sent > 0) ? ((double) lost / (double) sent) * 100.0 : 0.0;
    }

    public int getWindowSize() {
        return windowSize;
    }
}

