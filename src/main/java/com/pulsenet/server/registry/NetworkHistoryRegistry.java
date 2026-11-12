package com.pulsenet.server.registry;

import com.pulsenet.common.model.NetworkMetrics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe bounded in-memory time-series storage for network performance history.
 *
 * <p>Retains a configurable window of recent {@link NetworkHistorySample} snapshots per node ID,
 * preventing unbounded memory growth while exposing backend query endpoints for future frontend/UI graphs.
 */
public class NetworkHistoryRegistry {

    public static final int DEFAULT_RETENTION_SAMPLES = 300;

    private final int maxRetentionSamples;
    private final Map<String, Deque<NetworkHistorySample>> historyByNode = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public NetworkHistoryRegistry() {
        this(DEFAULT_RETENTION_SAMPLES);
    }

    public NetworkHistoryRegistry(int maxRetentionSamples) {
        this.maxRetentionSamples = Math.max(1, maxRetentionSamples);
    }

    /**
     * Ingests a new network metric sample for the specified node ID.
     *
     * @param nodeId    reporting node identifier
     * @param metrics   network metrics snapshot
     * @param timestamp server ingestion timestamp in milliseconds
     */
    public void recordSample(String nodeId, NetworkMetrics metrics, long timestamp) {
        if (nodeId == null || metrics == null) {
            return;
        }

        NetworkHistorySample sample = new NetworkHistorySample(
                timestamp,
                nodeId,
                metrics.getCurrentRttMs(),
                metrics.getAvgRttMs(),
                metrics.getMinRttMs(),
                metrics.getMaxRttMs(),
                metrics.getCurrentJitterMs(),
                metrics.getAvgJitterMs(),
                metrics.getPacketLossPercent(),
                metrics.getConnectionStatus(),
                metrics.getReconnectionCount()
        );

        lock.lock();
        try {
            Deque<NetworkHistorySample> deque = historyByNode.computeIfAbsent(nodeId, k -> new ArrayDeque<>());
            deque.addLast(sample);
            while (deque.size() > maxRetentionSamples) {
                deque.pollFirst();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves up to {@code limit} recent history samples for the given node ID.
     *
     * @param nodeId node identifier
     * @param limit  maximum number of samples to return
     * @return defensive copy list of historical samples ordered from oldest to newest
     */
    public List<NetworkHistorySample> getHistory(String nodeId, int limit) {
        if (nodeId == null) {
            return Collections.emptyList();
        }

        lock.lock();
        try {
            Deque<NetworkHistorySample> deque = historyByNode.get(nodeId);
            if (deque == null || deque.isEmpty()) {
                return Collections.emptyList();
            }
            List<NetworkHistorySample> fullList = new ArrayList<>(deque);
            if (limit <= 0 || limit >= fullList.size()) {
                return fullList;
            }
            return fullList.subList(fullList.size() - limit, fullList.size());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves all recorded samples for the given node ID within the retention window.
     */
    public List<NetworkHistorySample> getHistory(String nodeId) {
        return getHistory(nodeId, maxRetentionSamples);
    }

    /**
     * Clears all historical data (useful between test runs).
     */
    public void clear() {
        lock.lock();
        try {
            historyByNode.clear();
        } finally {
            lock.unlock();
        }
    }

    public int getMaxRetentionSamples() {
        return maxRetentionSamples;
    }
}

