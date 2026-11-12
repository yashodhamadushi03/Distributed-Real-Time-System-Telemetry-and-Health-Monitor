
package com.pulsenet.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Serializable;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TelemetryPayload implements Serializable {

    private static final long serialVersionUID = 2L;

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper();

    // ================= BASIC INFORMATION =================

    private String nodeId;
    private long timestamp;
    private String status;

    // ================= CPU =================

    private double cpuPercent;
    private int cpuCoreCount;
    private double cpuTemperatureCelsius = -1;

    // ================= MEMORY =================

    private double memoryPercent;
    private long totalMemoryBytes;
    private long usedMemoryBytes;
    private long freeMemoryBytes;

    // ================= DISK =================

    private double diskPercent;
    private long totalDiskBytes;
    private long freeDiskBytes;

    // ================= PROCESSES =================

    private int processCount;

    // ================= BATTERY =================

    private boolean batteryAvailable;
    private double batteryPercent = -1;
    private boolean batteryCharging;
    private long batteryTimeRemainingSeconds = -1;

    // ================= NETWORK =================

    private double downloadBytesPerSecond;
    private double uploadBytesPerSecond;

    private long totalBytesReceived;
    private long totalBytesSent;

    // ================= SYSTEM INFORMATION =================

    private String operatingSystem;
    private String hostName;
    private String ipAddress;
    private long uptimeSeconds;

    // ================= CONSTRUCTORS =================

    public TelemetryPayload() {
    }

    // Keeps compatibility with the original constructor.
    public TelemetryPayload(
            String nodeId,
            long timestamp,
            double cpuPercent,
            double memoryPercent,
            double diskPercent,
            int processCount,
            String status
    ) {

        this.nodeId = nodeId;
        this.timestamp = timestamp;
        this.cpuPercent = cpuPercent;
        this.memoryPercent = memoryPercent;
        this.diskPercent = diskPercent;
        this.processCount = processCount;
        this.status = status;
    }

    // ================= JSON =================

    public String toJson() throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(this);
    }

    public static TelemetryPayload fromJson(String json)
            throws IOException {

        return OBJECT_MAPPER.readValue(
                json,
                TelemetryPayload.class
        );
    }

    // ================= BASIC GETTERS / SETTERS =================

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    // ================= CPU =================

    public double getCpuPercent() {
        return cpuPercent;
    }

    public void setCpuPercent(double cpuPercent) {
        this.cpuPercent = cpuPercent;
    }

    public int getCpuCoreCount() {
        return cpuCoreCount;
    }

    public void setCpuCoreCount(int cpuCoreCount) {
        this.cpuCoreCount = cpuCoreCount;
    }

    public double getCpuTemperatureCelsius() {
        return cpuTemperatureCelsius;
    }

    public void setCpuTemperatureCelsius(double value) {
        this.cpuTemperatureCelsius = value;
    }

    // ================= MEMORY =================

    public double getMemoryPercent() {
        return memoryPercent;
    }

    public void setMemoryPercent(double memoryPercent) {
        this.memoryPercent = memoryPercent;
    }

    public long getTotalMemoryBytes() {
        return totalMemoryBytes;
    }

    public void setTotalMemoryBytes(long value) {
        this.totalMemoryBytes = value;
    }

    public long getUsedMemoryBytes() {
        return usedMemoryBytes;
    }

    public void setUsedMemoryBytes(long value) {
        this.usedMemoryBytes = value;
    }

    public long getFreeMemoryBytes() {
        return freeMemoryBytes;
    }

    public void setFreeMemoryBytes(long value) {
        this.freeMemoryBytes = value;
    }

    // ================= DISK =================

    public double getDiskPercent() {
        return diskPercent;
    }

    public void setDiskPercent(double diskPercent) {
        this.diskPercent = diskPercent;
    }

    public long getTotalDiskBytes() {
        return totalDiskBytes;
    }

    public void setTotalDiskBytes(long value) {
        this.totalDiskBytes = value;
    }

    public long getFreeDiskBytes() {
        return freeDiskBytes;
    }

    public void setFreeDiskBytes(long value) {
        this.freeDiskBytes = value;
    }

    // ================= PROCESSES =================

    public int getProcessCount() {
        return processCount;
    }

    public void setProcessCount(int processCount) {
        this.processCount = processCount;
    }

    // ================= BATTERY =================

    public boolean isBatteryAvailable() {
        return batteryAvailable;
    }

    public void setBatteryAvailable(boolean value) {
        this.batteryAvailable = value;
    }

    public double getBatteryPercent() {
        return batteryPercent;
    }

    public void setBatteryPercent(double value) {
        this.batteryPercent = value;
    }

    public boolean isBatteryCharging() {
        return batteryCharging;
    }

    public void setBatteryCharging(boolean value) {
        this.batteryCharging = value;
    }

    public long getBatteryTimeRemainingSeconds() {
        return batteryTimeRemainingSeconds;
    }

    public void setBatteryTimeRemainingSeconds(long value) {
        this.batteryTimeRemainingSeconds = value;
    }

    // ================= NETWORK =================

    public double getDownloadBytesPerSecond() {
        return downloadBytesPerSecond;
    }

    public void setDownloadBytesPerSecond(double value) {
        this.downloadBytesPerSecond = value;
    }

    public double getUploadBytesPerSecond() {
        return uploadBytesPerSecond;
    }

    public void setUploadBytesPerSecond(double value) {
        this.uploadBytesPerSecond = value;
    }

    public long getTotalBytesReceived() {
        return totalBytesReceived;
    }

    public void setTotalBytesReceived(long value) {
        this.totalBytesReceived = value;
    }

    public long getTotalBytesSent() {
        return totalBytesSent;
    }

    public void setTotalBytesSent(long value) {
        this.totalBytesSent = value;
    }

    // ================= SYSTEM INFORMATION =================

    public String getOperatingSystem() {
        return operatingSystem;
    }

    public void setOperatingSystem(String value) {
        this.operatingSystem = value;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String value) {
        this.hostName = value;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String value) {
        this.ipAddress = value;
    }

    public long getUptimeSeconds() {
        return uptimeSeconds;
    }

    public void setUptimeSeconds(long value) {
        this.uptimeSeconds = value;
    }

    // ================= OBJECT METHODS =================

    @Override
    public String toString() {

        return "TelemetryPayload{" +
                "nodeId='" + nodeId + '\'' +
                ", cpuPercent=" + cpuPercent +
                ", memoryPercent=" + memoryPercent +
                ", diskPercent=" + diskPercent +
                ", batteryPercent=" + batteryPercent +
                ", downloadBytesPerSecond=" + downloadBytesPerSecond +
                ", uploadBytesPerSecond=" + uploadBytesPerSecond +
                ", status='" + status + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object object) {

        if (this == object) return true;

        if (!(object instanceof TelemetryPayload that)) {
            return false;
        }

        return timestamp == that.timestamp
                && Objects.equals(nodeId, that.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, timestamp);
    }
}
