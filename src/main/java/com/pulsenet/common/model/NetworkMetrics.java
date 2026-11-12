package com.pulsenet.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.util.Objects;

/**
 * Data transfer object encapsulating advanced networking metrics:
 * Round-Trip Time (RTT latency), network jitter, UDP packet loss, connection uptime,
 * reconnection statistics, and local/remote network topology coordinates.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NetworkMetrics implements Serializable {

    private static final long serialVersionUID = 1L;

    // Latency metrics (milliseconds)
    private double currentRttMs = -1.0;
    private double avgRttMs = -1.0;
    private double minRttMs = -1.0;
    private double maxRttMs = -1.0;

    // Jitter metrics (milliseconds)
    private double currentJitterMs = -1.0;
    private double avgJitterMs = -1.0;

    // Packet loss metrics
    private double packetLossPercent = 0.0; // Rolling window packet loss percent
    private int windowSize = 50; // Rolling window sample size
    private double cumulativeLossPercent = 0.0; // Lifetime cumulative packet loss percent
    private long probesSent = 0L;
    private long probesReceived = 0L;
    private long probesLost = 0L;

    // Connection lifecycle and uptime
    private String connectionStatus = "DISCONNECTED";
    private long connectionUptimeMillis = 0L;
    private long lastConnectTimestamp = 0L;
    private long lastDisconnectTimestamp = 0L;
    private String lastDisconnectReason = null;
    private int reconnectionCount = 0;
    private long lastReconnectTimestamp = 0L;

    // Network topology & endpoint coordinates
    private String connectedServerId;
    private String serverHost;
    private int serverPort;
    private int udpProbePort;
    private String agentIp;
    private int agentSourcePort;
    private String agentHostname;
    private String protocol = "TCP + UDP probes";

    public NetworkMetrics() {
    }

    public NetworkMetrics(NetworkMetrics other) {
        if (other == null) return;
        this.currentRttMs = other.currentRttMs;
        this.avgRttMs = other.avgRttMs;
        this.minRttMs = other.minRttMs;
        this.maxRttMs = other.maxRttMs;
        this.currentJitterMs = other.currentJitterMs;
        this.avgJitterMs = other.avgJitterMs;
        this.packetLossPercent = other.packetLossPercent;
        this.windowSize = other.windowSize;
        this.cumulativeLossPercent = other.cumulativeLossPercent;
        this.probesSent = other.probesSent;
        this.probesReceived = other.probesReceived;
        this.probesLost = other.probesLost;
        this.connectionStatus = other.connectionStatus;
        this.connectionUptimeMillis = other.connectionUptimeMillis;
        this.lastConnectTimestamp = other.lastConnectTimestamp;
        this.lastDisconnectTimestamp = other.lastDisconnectTimestamp;
        this.lastDisconnectReason = other.lastDisconnectReason;
        this.reconnectionCount = other.reconnectionCount;
        this.lastReconnectTimestamp = other.lastReconnectTimestamp;
        this.connectedServerId = other.connectedServerId;
        this.serverHost = other.serverHost;
        this.serverPort = other.serverPort;
        this.udpProbePort = other.udpProbePort;
        this.agentIp = other.agentIp;
        this.agentSourcePort = other.agentSourcePort;
        this.agentHostname = other.agentHostname;
        this.protocol = other.protocol;
    }

    public double getCurrentRttMs() {
        return currentRttMs;
    }

    public void setCurrentRttMs(double currentRttMs) {
        this.currentRttMs = currentRttMs;
    }

    public double getAvgRttMs() {
        return avgRttMs;
    }

    public void setAvgRttMs(double avgRttMs) {
        this.avgRttMs = avgRttMs;
    }

    public double getMinRttMs() {
        return minRttMs;
    }

    public void setMinRttMs(double minRttMs) {
        this.minRttMs = minRttMs;
    }

    public double getMaxRttMs() {
        return maxRttMs;
    }

    public void setMaxRttMs(double maxRttMs) {
        this.maxRttMs = maxRttMs;
    }

    public double getCurrentJitterMs() {
        return currentJitterMs;
    }

    public void setCurrentJitterMs(double currentJitterMs) {
        this.currentJitterMs = currentJitterMs;
    }

    public double getAvgJitterMs() {
        return avgJitterMs;
    }

    public void setAvgJitterMs(double avgJitterMs) {
        this.avgJitterMs = avgJitterMs;
    }

    public double getPacketLossPercent() {
        return packetLossPercent;
    }

    public void setPacketLossPercent(double packetLossPercent) {
        this.packetLossPercent = packetLossPercent;
    }

    public int getWindowSize() {
        return windowSize;
    }

    public void setWindowSize(int windowSize) {
        this.windowSize = windowSize;
    }

    public double getCumulativeLossPercent() {
        return cumulativeLossPercent;
    }

    public void setCumulativeLossPercent(double cumulativeLossPercent) {
        this.cumulativeLossPercent = cumulativeLossPercent;
    }

    public long getProbesSent() {
        return probesSent;
    }

    public void setProbesSent(long probesSent) {
        this.probesSent = probesSent;
    }

    public long getProbesReceived() {
        return probesReceived;
    }

    public void setProbesReceived(long probesReceived) {
        this.probesReceived = probesReceived;
    }

    public long getProbesLost() {
        return probesLost;
    }

    public void setProbesLost(long probesLost) {
        this.probesLost = probesLost;
    }

    public String getConnectionStatus() {
        return connectionStatus;
    }

    public void setConnectionStatus(String connectionStatus) {
        this.connectionStatus = connectionStatus;
    }

    public long getConnectionUptimeMillis() {
        return connectionUptimeMillis;
    }

    public void setConnectionUptimeMillis(long connectionUptimeMillis) {
        this.connectionUptimeMillis = connectionUptimeMillis;
    }

    public long getLastConnectTimestamp() {
        return lastConnectTimestamp;
    }

    public void setLastConnectTimestamp(long lastConnectTimestamp) {
        this.lastConnectTimestamp = lastConnectTimestamp;
    }

    public long getLastDisconnectTimestamp() {
        return lastDisconnectTimestamp;
    }

    public void setLastDisconnectTimestamp(long lastDisconnectTimestamp) {
        this.lastDisconnectTimestamp = lastDisconnectTimestamp;
    }

    public String getLastDisconnectReason() {
        return lastDisconnectReason;
    }

    public void setLastDisconnectReason(String lastDisconnectReason) {
        this.lastDisconnectReason = lastDisconnectReason;
    }

    public int getReconnectionCount() {
        return reconnectionCount;
    }

    public void setReconnectionCount(int reconnectionCount) {
        this.reconnectionCount = reconnectionCount;
    }

    public long getLastReconnectTimestamp() {
        return lastReconnectTimestamp;
    }

    public void setLastReconnectTimestamp(long lastReconnectTimestamp) {
        this.lastReconnectTimestamp = lastReconnectTimestamp;
    }

    public String getConnectedServerId() {
        return connectedServerId;
    }

    public void setConnectedServerId(String connectedServerId) {
        this.connectedServerId = connectedServerId;
    }

    public String getServerHost() {
        return serverHost;
    }

    public void setServerHost(String serverHost) {
        this.serverHost = serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }

    public int getUdpProbePort() {
        return udpProbePort;
    }

    public void setUdpProbePort(int udpProbePort) {
        this.udpProbePort = udpProbePort;
    }

    public String getAgentIp() {
        return agentIp;
    }

    public void setAgentIp(String agentIp) {
        this.agentIp = agentIp;
    }

    public int getAgentSourcePort() {
        return agentSourcePort;
    }

    public void setAgentSourcePort(int agentSourcePort) {
        this.agentSourcePort = agentSourcePort;
    }

    public String getAgentHostname() {
        return agentHostname;
    }

    public void setAgentHostname(String agentHostname) {
        this.agentHostname = agentHostname;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NetworkMetrics that)) return false;
        return Double.compare(that.currentRttMs, currentRttMs) == 0 &&
                Double.compare(that.avgRttMs, avgRttMs) == 0 &&
                Double.compare(that.minRttMs, minRttMs) == 0 &&
                Double.compare(that.maxRttMs, maxRttMs) == 0 &&
                Double.compare(that.currentJitterMs, currentJitterMs) == 0 &&
                Double.compare(that.avgJitterMs, avgJitterMs) == 0 &&
                Double.compare(that.packetLossPercent, packetLossPercent) == 0 &&
                Double.compare(that.cumulativeLossPercent, cumulativeLossPercent) == 0 &&
                windowSize == that.windowSize &&
                probesSent == that.probesSent &&
                probesReceived == that.probesReceived &&
                probesLost == that.probesLost &&
                connectionUptimeMillis == that.connectionUptimeMillis &&
                lastConnectTimestamp == that.lastConnectTimestamp &&
                lastDisconnectTimestamp == that.lastDisconnectTimestamp &&
                reconnectionCount == that.reconnectionCount &&
                lastReconnectTimestamp == that.lastReconnectTimestamp &&
                serverPort == that.serverPort &&
                udpProbePort == that.udpProbePort &&
                agentSourcePort == that.agentSourcePort &&
                Objects.equals(connectionStatus, that.connectionStatus) &&
                Objects.equals(lastDisconnectReason, that.lastDisconnectReason) &&
                Objects.equals(connectedServerId, that.connectedServerId) &&
                Objects.equals(serverHost, that.serverHost) &&
                Objects.equals(agentIp, that.agentIp) &&
                Objects.equals(agentHostname, that.agentHostname) &&
                Objects.equals(protocol, that.protocol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(currentRttMs, avgRttMs, minRttMs, maxRttMs, currentJitterMs, avgJitterMs,
                packetLossPercent, windowSize, cumulativeLossPercent, probesSent, probesReceived, probesLost,
                connectionStatus, connectionUptimeMillis, lastConnectTimestamp, lastDisconnectTimestamp,
                lastDisconnectReason, reconnectionCount, lastReconnectTimestamp, connectedServerId,
                serverHost, serverPort, udpProbePort, agentIp, agentSourcePort, agentHostname, protocol);
    }

    @Override
    public String toString() {
        return "NetworkMetrics{" +
                "currentRttMs=" + currentRttMs +
                ", avgRttMs=" + avgRttMs +
                ", avgJitterMs=" + avgJitterMs +
                ", packetLossPercent=" + packetLossPercent +
                ", windowSize=" + windowSize +
                ", cumulativeLossPercent=" + cumulativeLossPercent +
                ", probesSent=" + probesSent +
                ", probesReceived=" + probesReceived +
                ", probesLost=" + probesLost +
                ", connectionStatus='" + connectionStatus + '\'' +
                ", uptimeMs=" + connectionUptimeMillis +
                ", reconnections=" + reconnectionCount +
                '}';
    }
}

