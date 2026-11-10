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
    private final ReentrantLock lock = new ReentrantLock();

    // Rolling event log for dashboard rendering (thread-safe, bounded)
    private static final int MAX_EVENTS = 50;
    private final Deque<String> eventLog = new ConcurrentLinkedDeque<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    /**
     * Updates an existing node record or inserts a new one with the current system timestamp.
     *
     * @param payload Ingested telemetry data.
     * @param remoteAddress Socket origin of the client.
     */
    public void upsertNode(TelemetryPayload payload, String remoteAddress) {
        if (payload == null || payload.getNodeId() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        String nodeId = payload.getNodeId();

        lock.lock();
        try {
            NodeRecord existing = nodes.get(nodeId);
            if (existing == null) {
                NodeRecord newRecord = new NodeRecord(nodeId, payload, remoteAddress, now);
                nodes.put(nodeId, newRecord);
                logEvent(String.format("[SYS]  Node connected: %s (%s)", nodeId, remoteAddress));
            } else {
                String previousStatus = existing.getStatus();
                existing.update(payload, remoteAddress, now);
                if ("OFFLINE".equals(previousStatus)) {
                    existing.setStatus(payload.getStatus());
                    logEvent(String.format("[REC]  Node recovered: %s (%s)", nodeId, remoteAddress));
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a snapshot list containing defensive copies of all registered nodes.
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
            eventLog.clear();
        } finally {
            lock.unlock();
        }
    }
}
