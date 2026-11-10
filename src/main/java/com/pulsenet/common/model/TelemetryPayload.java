package com.pulsenet.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Serializable;
import java.util.Objects;

/**
 * Data transfer object representing system health and telemetry metrics for a node.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TelemetryPayload implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private String nodeId;
    private long timestamp;
    private double cpuPercent;
    private double memoryPercent;
    private double diskPercent;
    private int processCount;
    private String status;

    /**
     * Default no-arg constructor required for deserialization.
     */
    public TelemetryPayload() {
    }

    /**
     * Full parameter constructor.
     */
    public TelemetryPayload(String nodeId, long timestamp, double cpuPercent,
                            double memoryPercent, double diskPercent,
                            int processCount, String status) {
        this.nodeId = nodeId;
        this.timestamp = timestamp;
        this.cpuPercent = cpuPercent;
        this.memoryPercent = memoryPercent;
        this.diskPercent = diskPercent;
        this.processCount = processCount;
        this.status = status;
    }

    /**
     * Serializes this payload instance to a JSON string.
     *
     * @return JSON string representation.
     * @throws JsonProcessingException if serialization fails.
     */
    public String toJson() throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(this);
    }

    /**
     * Deserializes a JSON string into a TelemetryPayload instance.
     *
     * @param json JSON string representation.
     * @return TelemetryPayload instance.
     * @throws IOException if deserialization fails.
     */
    public static TelemetryPayload fromJson(String json) throws IOException {
        return OBJECT_MAPPER.readValue(json, TelemetryPayload.class);
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public double getCpuPercent() {
        return cpuPercent;
    }

    public void setCpuPercent(double cpuPercent) {
        this.cpuPercent = cpuPercent;
    }

    public double getMemoryPercent() {
        return memoryPercent;
    }

    public void setMemoryPercent(double memoryPercent) {
        this.memoryPercent = memoryPercent;
    }

    public double getDiskPercent() {
        return diskPercent;
    }

    public void setDiskPercent(double diskPercent) {
        this.diskPercent = diskPercent;
    }

    public int getProcessCount() {
        return processCount;
    }

    public void setProcessCount(int processCount) {
        this.processCount = processCount;
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
        if (!(o instanceof TelemetryPayload that)) return false;
        return timestamp == that.timestamp &&
                Double.compare(that.cpuPercent, cpuPercent) == 0 &&
                Double.compare(that.memoryPercent, memoryPercent) == 0 &&
                Double.compare(that.diskPercent, diskPercent) == 0 &&
                processCount == that.processCount &&
                Objects.equals(nodeId, that.nodeId) &&
                Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, timestamp, cpuPercent, memoryPercent, diskPercent, processCount, status);
    }

    @Override
    public String toString() {
        return "TelemetryPayload{" +
                "nodeId='" + nodeId + '\'' +
                ", timestamp=" + timestamp +
                ", cpuPercent=" + cpuPercent +
                ", memoryPercent=" + memoryPercent +
                ", diskPercent=" + diskPercent +
                ", processCount=" + processCount +
                ", status='" + status + '\'' +
                '}';
    }
}
