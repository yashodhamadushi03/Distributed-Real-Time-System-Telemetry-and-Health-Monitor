package com.pulsenet.agent;

import com.pulsenet.common.model.TelemetryPayload;
import com.sun.management.OperatingSystemMXBean;

import java.io.File;
import java.lang.management.ManagementFactory;

/**
 * Metric collector sampling host operating system telemetry:
 * CPU utilization, physical memory usage, disk storage saturation, and active processes.
 */
public class MetricCollector {

    private final OperatingSystemMXBean osBean;
    private final File rootPartition;

    /**
     * Initializes metric collector with system management beans.
     */
    public MetricCollector() {
        this.osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.rootPartition = resolveRootPartition();
    }

    /**
     * Resolves the primary filesystem partition.
     */
    private File resolveRootPartition() {
        File[] roots = File.listRoots();
        if (roots != null && roots.length > 0) {
            return roots[0];
        }
        return new File(".");
    }

    /**
     * Samples the current host metrics and packages them into a TelemetryPayload.
     *
     * @param nodeId Unique identifier of the reporting node.
     * @return Populated TelemetryPayload instance.
     */
    public TelemetryPayload collect(String nodeId) {
        long timestamp = System.currentTimeMillis();

        double cpuPercent = sampleCpuPercent();
        double memoryPercent = sampleMemoryPercent();
        double diskPercent = sampleDiskPercent();
        int processCount = sampleProcessCount();
        String status = determineStatus(cpuPercent, memoryPercent, diskPercent);

        return new TelemetryPayload(
                nodeId,
                timestamp,
                roundToOneDecimal(cpuPercent),
                roundToOneDecimal(memoryPercent),
                roundToOneDecimal(diskPercent),
                processCount,
                status
        );
    }

    /**
     * Reads system-wide CPU utilization percentage (0.0% - 100.0%).
     */
    public double sampleCpuPercent() {
        double cpuLoad = osBean.getCpuLoad();
        // getCpuLoad() can return negative during the initial sampling interval
        if (cpuLoad < 0.0 || Double.isNaN(cpuLoad)) {
            cpuLoad = osBean.getProcessCpuLoad();
        }
        if (cpuLoad < 0.0 || Double.isNaN(cpuLoad)) {
            return 0.0;
        }
        return Math.min(100.0, Math.max(0.0, cpuLoad * 100.0));
    }

    /**
     * Calculates system physical memory saturation percentage.
     */
    public double sampleMemoryPercent() {
        long totalMemory = osBean.getTotalMemorySize();
        long freeMemory = osBean.getFreeMemorySize();
        if (totalMemory <= 0) {
            return 0.0;
        }
        long usedMemory = totalMemory - freeMemory;
        double memPercent = ((double) usedMemory / totalMemory) * 100.0;
        return Math.min(100.0, Math.max(0.0, memPercent));
    }

    /**
     * Calculates storage saturation percentage on the primary partition.
     */
    public double sampleDiskPercent() {
        long totalSpace = rootPartition.getTotalSpace();
        long usableSpace = rootPartition.getUsableSpace();
        if (totalSpace <= 0) {
            return 0.0;
        }
        long usedSpace = totalSpace - usableSpace;
        double diskPercent = ((double) usedSpace / totalSpace) * 100.0;
        return Math.min(100.0, Math.max(0.0, diskPercent));
    }

    /**
     * Counts the total number of running OS processes.
     */
    public int sampleProcessCount() {
        try {
            return (int) ProcessHandle.allProcesses().count();
        } catch (SecurityException | UnsupportedOperationException e) {
            return 1;
        }
    }

    /**
     * Evaluates health threshold: WARNING if CPU > 85% or Memory > 90%, else HEALTHY.
     */
    public String determineStatus(double cpuPercent, double memoryPercent, double diskPercent) {
        if (cpuPercent > 85.0 || memoryPercent > 90.0 || diskPercent > 95.0) {
            return "WARNING";
        }
        return "HEALTHY";
    }

    private double roundToOneDecimal(double val) {
        return Math.round(val * 10.0) / 10.0;
    }
}
