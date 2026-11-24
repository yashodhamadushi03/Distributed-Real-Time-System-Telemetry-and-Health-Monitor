package com.pulsenet.server.registry;

import com.pulsenet.common.model.TelemetryPayload;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe centralized in-memory storage for active node telemetry.
 * All mutations and reads are guarded by a ReentrantLock mutex.
 */
public class NodeRegistry {

    private final Map<String, NodeRecord> nodes = new HashMap<>();
    private final NetworkHistoryRegistry networkHistoryRegistry;
    private final ReentrantLock lock = new ReentrantLock();

    /** CPU percentage above which a node is considered over threshold. */
    public static final double CPU_WARN_THRESHOLD = 85.0;

    /** Memory percentage above which a node is considered over threshold. */
    public static final double MEMORY_WARN_THRESHOLD = 90.0;

    /**
     * Current connection owner per node ID (typically the {@code ClientHandler} instance).
     * Guarded by {@link #lock}. Used to tell a node's live connection apart from stale ones.
     */
    private final Map<String, Object> connectionOwners = new HashMap<>();

    /**
     * Connections whose node ID was taken over by a newer connection, by identity.
     * Guarded by {@link #lock}.
     */
    private final Map<Object, SupersededConnection> superseded = new IdentityHashMap<>();

    /** A superseded connection that keeps sending this much, this long, is a second live agent. */
    private static final int DUPLICATE_MIN_FRAMES = 3;
    private static final long DUPLICATE_MIN_MILLIS = 2_000L;

    // Rolling event log for dashboard rendering (thread-safe, bounded)
    private static final int MAX_EVENTS = 200;
    private final Deque<String> eventLog = new ConcurrentLinkedDeque<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    public NodeRegistry() {
        this(new NetworkHistoryRegistry());
    }

    public NodeRegistry(NetworkHistoryRegistry networkHistoryRegistry) {
        this.networkHistoryRegistry = networkHistoryRegistry != null ? networkHistoryRegistry : new NetworkHistoryRegistry();
    }

    /**
     * Updates an existing node record or inserts a new one with the current system timestamp.
     * Equivalent to {@link #upsertNode(TelemetryPayload, String, Object)} with no connection owner.
     *
     * @param payload Ingested telemetry data.
     * @param remoteAddress Socket origin of the client.
     */
    public void upsertNode(TelemetryPayload payload, String remoteAddress) {
        upsertNode(payload, remoteAddress, null);
    }

    /**
     * Updates an existing node record or inserts a new one, records which connection currently
     * owns the node ID, and emits edge-triggered threshold events.
     *
     * <p>Threshold events fire only on transitions: {@code [WARN]} when CPU (or memory) goes from
     * not-above to above its threshold, and {@code [OK]} when a node that was above at least one
     * threshold drops back below both. All comparisons happen under the registry lock.
     *
     * @param payload         Ingested telemetry data.
     * @param remoteAddress   Socket origin of the client.
     * @param connectionOwner identity token of the delivering connection (e.g. its ClientHandler);
     *                        {@code null} leaves ownership unchanged
     */
    public void upsertNode(TelemetryPayload payload, String remoteAddress, Object connectionOwner) {
        if (payload == null || payload.getNodeId() == null) {
            return;
        }

        long now = System.currentTimeMillis();

        lock.lock();
        try {
            String nodeId = resolveNodeId(payload.getNodeId(), remoteAddress, connectionOwner, now);
            if (nodeId == null) {
                return; // Late frame from a superseded connection
            }
            NodeRecord existing = nodes.get(nodeId);
            TelemetryPayload previousPayload = null;
            boolean recovered = false;
            if (existing == null) {
                NodeRecord newRecord = new NodeRecord(nodeId, payload, remoteAddress, now);
                nodes.put(nodeId, newRecord);
                logEvent(String.format("[SYS]  Node connected: %s (%s)", nodeId, remoteAddress));
            } else {
                previousPayload = existing.getLatestPayload();
                String previousStatus = existing.getStatus();
                existing.update(payload, remoteAddress, now);
                if ("OFFLINE".equals(previousStatus)) {
                    recovered = true;
                    logEvent(String.format("[REC]  Node recovered: %s (%s)", nodeId, remoteAddress));
                }
            }
            if (connectionOwner != null) {
                Object previousOwner = connectionOwners.get(nodeId);
                if (previousOwner != null && previousOwner != connectionOwner) {
                    superseded.put(previousOwner, new SupersededConnection());
                    if (!recovered) {
                        logEvent(String.format("[SYS]  Node reconnected on new connection: %s (%s)",
                                nodeId, remoteAddress));
                    }
                }
                connectionOwners.put(nodeId, connectionOwner);
            }
            if (payload.getNetworkMetrics() != null) {
                networkHistoryRegistry.recordSample(nodeId, payload.getNetworkMetrics(), now);
            }
            emitThresholdTransitions(nodeId, previousPayload, payload);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Works out which node row a frame belongs to. Normally that is simply the reported node ID. If
     * the delivering connection was superseded by a newer one, its frames are ignored at first (they
     * may be leftovers from a half-open socket); if it keeps sending, two live agents are using the
     * same node ID, and it is moved to its own row ({@code "name (2)"}) so neither hides the other.
     * Caller holds the lock.
     *
     * @return the row's node ID, or {@code null} if the frame must be ignored
     */
    private String resolveNodeId(String reportedId, String remoteAddress, Object owner, long now) {
        SupersededConnection state = owner != null ? superseded.get(owner) : null;
        if (state == null) {
            return reportedId;
        }
        if (state.alias != null) {
            return state.alias;
        }
        if (!connectionOwners.containsKey(reportedId)) {
            // The newer connection is gone again; this one may reclaim the ID.
            superseded.remove(owner);
            return reportedId;
        }
        if (state.frames++ == 0) {
            state.firstFrameAt = now;
        }
        if (state.frames < DUPLICATE_MIN_FRAMES || now - state.firstFrameAt < DUPLICATE_MIN_MILLIS) {
            return null;
        }
        int suffix = 2;
        while (connectionOwners.containsKey(reportedId + " (" + suffix + ")")) {
            suffix++;
        }
        state.alias = reportedId + " (" + suffix + ")";
        logEvent(String.format("[WARN] Two agents use node ID '%s'; showing %s as '%s'. Give each agent its own --node-id",
                reportedId, remoteAddress, state.alias));
        return state.alias;
    }

    /** Tracks a connection whose node ID was taken over by a newer connection. */
    private static final class SupersededConnection {
        private int frames;
        private long firstFrameAt;
        private String alias;
    }

    /**
     * Logs threshold crossing events by comparing the previous and new payload. Caller holds the lock.
     */
    private void emitThresholdTransitions(String nodeId, TelemetryPayload previous, TelemetryPayload current) {
        boolean prevCpuHigh = previous != null && previous.getCpuPercent() > CPU_WARN_THRESHOLD;
        boolean prevMemHigh = previous != null && previous.getMemoryPercent() > MEMORY_WARN_THRESHOLD;
        boolean cpuHigh = current.getCpuPercent() > CPU_WARN_THRESHOLD;
        boolean memHigh = current.getMemoryPercent() > MEMORY_WARN_THRESHOLD;

        if (cpuHigh && !prevCpuHigh) {
            logEvent(String.format("[WARN] %s crossed CPU threshold: %.1f%%", nodeId, current.getCpuPercent()));
        }
        if (memHigh && !prevMemHigh) {
            logEvent(String.format("[WARN] %s crossed Memory threshold: %.1f%%", nodeId, current.getMemoryPercent()));
        }
        if ((prevCpuHigh || prevMemHigh) && !cpuHigh && !memHigh) {
            logEvent(String.format("[OK]   %s back below thresholds", nodeId));
        }
    }

    /**
     * Releases a node's connection ownership if {@code owner} is still its current owner.
     *
     * @param nodeId node identifier
     * @param owner  the connection token passed to {@link #upsertNode(TelemetryPayload, String, Object)}
     * @return {@code true} if {@code owner} was the current owner (ownership is cleared);
     *         {@code false} if a newer connection has claimed the node ID (stale connection)
     */
    public boolean releaseConnection(String nodeId, Object owner) {
        if (nodeId == null || owner == null) {
            return false;
        }
        lock.lock();
        try {
            SupersededConnection state = superseded.remove(owner);
            String rowId = state != null && state.alias != null ? state.alias : nodeId;
            if (connectionOwners.get(rowId) == owner) {
                connectionOwners.remove(rowId);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param nodeId node identifier
     * @param owner  connection token
     * @return {@code true} if {@code owner} currently owns {@code nodeId}
     */
    public boolean isCurrentOwner(String nodeId, Object owner) {
        lock.lock();
        try {
            return owner != null && connectionOwners.get(nodeId) == owner;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a snapshot list containing defensive copies of all registered nodes, ordered by node ID.
     *
     * @return List of NodeRecord defensive copies.
     */
    public List<NodeRecord> getAllNodes() {
        lock.lock();
        try {
            List<NodeRecord> snapshot = new ArrayList<>(nodes.size());
            for (NodeRecord record : nodes.values()) {
                snapshot.add(new NodeRecord(record));
            }
            snapshot.sort(Comparator.comparing(NodeRecord::getNodeId, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(NodeRecord::getNodeId));
            return snapshot;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves a defensive copy of a specific node's record.
     *
     * @param nodeId Identifier of the node.
     * @return Optional containing NodeRecord copy, or empty if not present.
     */
    public Optional<NodeRecord> getNode(String nodeId) {
        lock.lock();
        try {
            NodeRecord record = nodes.get(nodeId);
            return record != null ? Optional.of(new NodeRecord(record)) : Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Audits all nodes for missing heartbeats. Transitions silent nodes to OFFLINE.
     *
     * @param timeoutSeconds Inactivity duration before flagging a node offline.
     * @return List of node IDs that transitioned to OFFLINE in this audit sweep.
     */
    public List<String> auditStaleNodes(double timeoutSeconds) {
        long timeoutMillis = (long) (timeoutSeconds * 1000.0);
        long now = System.currentTimeMillis();
        List<String> transitionedNodes = new ArrayList<>();

        lock.lock();
        try {
            for (NodeRecord record : nodes.values()) {
                if (!"OFFLINE".equals(record.getStatus())) {
                    long silenceDuration = now - record.getLastSeenTimestamp();
                    if (silenceDuration > timeoutMillis) {
                        record.setStatus("OFFLINE");
                        transitionedNodes.add(record.getNodeId());
                        logEvent(String.format("[LOST] Heartbeat lost for %s (%d ms). Marked OFFLINE.",
                                record.getNodeId(), silenceDuration));
                    }
                }
            }
        } finally {
            lock.unlock();
        }
        return transitionedNodes;
    }

    /**
     * Appends a timestamped message to the bounded event log.
     */
    public void logEvent(String message) {
        String timestamp;
        synchronized (timeFormat) {
            timestamp = timeFormat.format(new Date());
        }
        eventLog.addLast(timestamp + " " + message);
        while (eventLog.size() > MAX_EVENTS) {
            eventLog.pollFirst();
        }
    }

    /**
     * Retrieves recent event log messages.
     *
     * @param limit Maximum number of recent log lines to retrieve.
     * @return List of recent log messages.
     */
    public List<String> getRecentEvents(int limit) {
        List<String> list = new ArrayList<>(eventLog);
        if (list.size() <= limit) {
            return list;
        }
        return list.subList(list.size() - limit, list.size());
    }

    /**
     * Returns the number of currently tracked nodes.
     */
    public int size() {
        lock.lock();
        try {
            return nodes.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears all stored records (useful between test cases).
     */
    public void clear() {
        lock.lock();
        try {
            nodes.clear();
            connectionOwners.clear();
            superseded.clear();
            eventLog.clear();
            networkHistoryRegistry.clear();
        } finally {
            lock.unlock();
        }
    }

    public NetworkHistoryRegistry getNetworkHistoryRegistry() {
        return networkHistoryRegistry;
    }

    public List<NetworkHistorySample> getNetworkHistory(String nodeId) {
        return networkHistoryRegistry.getHistory(nodeId);
    }

    public List<NetworkHistorySample> getNetworkHistory(String nodeId, int limit) {
        return networkHistoryRegistry.getHistory(nodeId, limit);
    }
}
