package com.pulsenet.server.registry;

import com.pulsenet.common.model.TelemetryPayload;

import java.util.Objects;

/**
 * Server-side representation of a tracked node, encapsulating its latest telemetry,
 * network origin address, and local timestamp of last heartbeat receipt.
 */
public class NodeRecord {

    private final String nodeId;
    private TelemetryPayload latestPayload;
    private String remoteAddress;
    private long lastSeenTimestamp;
    private String status;

    public NodeRecord(String nodeId, TelemetryPayload payload, String remoteAddress, long lastSeenTimestamp) {
        this.nodeId = nodeId;
        this.latestPayload = payload;
        this.remoteAddress = remoteAddress;
        this.lastSeenTimestamp = lastSeenTimestamp;
        this.status = payload != null ? payload.getStatus() : "HEALTHY";
    }

    /**
     * Copy constructor for defensive copying.
     */
    public NodeRecord(NodeRecord other) {
        this.nodeId = other.nodeId;
        this.latestPayload = other.latestPayload;
        this.remoteAddress = other.remoteAddress;
        this.lastSeenTimestamp = other.lastSeenTimestamp;
        this.status = other.status;
    }

    public synchronized void update(TelemetryPayload payload, String remoteAddress, long timestamp) {
        this.latestPayload = payload;
        this.remoteAddress = remoteAddress;
        this.lastSeenTimestamp = timestamp;
        if (payload != null && !"OFFLINE".equals(this.status)) {
            this.status = payload.getStatus();
        }
    }

    public String getNodeId() {
        return nodeId;
    }

    public TelemetryPayload getLatestPayload() {
        return latestPayload;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public long getLastSeenTimestamp() {
        return lastSeenTimestamp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeRecord that)) return false;
        return lastSeenTimestamp == that.lastSeenTimestamp &&
                Objects.equals(nodeId, that.nodeId) &&
                Objects.equals(remoteAddress, that.remoteAddress) &&
                Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, remoteAddress, lastSeenTimestamp, status);
    }

    @Override
    public String toString() {
        return "NodeRecord{" +
                "nodeId='" + nodeId + '\'' +
                ", remoteAddress='" + remoteAddress + '\'' +
                ", lastSeenTimestamp=" + lastSeenTimestamp +
                ", status='" + status + '\'' +
                ", latestPayload=" + latestPayload +
                '}';
    }
}
