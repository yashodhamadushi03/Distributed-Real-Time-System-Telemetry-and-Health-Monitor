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
        if (payload != null) {
            this.status = payload.getStatus() != null ? payload.getStatus() : "HEALTHY";
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

    public com.pulsenet.common.model.NetworkMetrics getNetworkMetrics() {
        return latestPayload != null ? latestPayload.getNetworkMetrics() : null;
    }

    public com.pulsenet.common.model.InternetConnectivityMetrics getInternetMetrics() {
        return latestPayload != null ? latestPayload.getInternetMetrics() : null;
    }

    public String getAgentHostname() {
        com.pulsenet.common.model.NetworkMetrics nm = getNetworkMetrics();
        return (nm != null && nm.getAgentHostname() != null) ? nm.getAgentHostname() : "unknown";
    }

    public String getConnectedServerId() {
        com.pulsenet.common.model.NetworkMetrics nm = getNetworkMetrics();
        return (nm != null && nm.getConnectedServerId() != null) ? nm.getConnectedServerId() : "unknown";
    }

    public String getProtocol() {
        com.pulsenet.common.model.NetworkMetrics nm = getNetworkMetrics();
        return (nm != null && nm.getProtocol() != null) ? nm.getProtocol() : "TCP";
    }

    public String getConnectionStatus() {
        com.pulsenet.common.model.NetworkMetrics nm = getNetworkMetrics();
        return (nm != null && nm.getConnectionStatus() != null) ? nm.getConnectionStatus() : status;
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
