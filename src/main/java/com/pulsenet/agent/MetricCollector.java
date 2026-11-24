
package com.pulsenet.agent;

import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.common.net.NetworkUtils;

import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.NetworkIF;
import oshi.hardware.PowerSource;
import oshi.hardware.Sensors;
import oshi.software.os.OperatingSystem;

import java.io.File;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.lang.management.ManagementFactory;

public class MetricCollector {

    private static final Logger LOGGER = Logger.getLogger(MetricCollector.class.getName());

    private final SystemInfo systemInfo;
    private final HardwareAbstractionLayer hardware;
    private final CentralProcessor processor;
    private final GlobalMemory memory;
    private final Sensors sensors;
    private final OperatingSystem operatingSystem;

    private final long startTime;

    // CPU tracking
    private long[] previousCpuTicks;

    // Network tracking
    private Map<String, long[]> previousNetworkCounters = new HashMap<>();
    private long previousNetworkTime = -1;

    private final List<File> diskPartitions;

    // ================= CONSTRUCTOR =================

    public MetricCollector() {
        this(true);
    }

    /**
     * @param hardwareAccess {@code false} for collectors that generate their own values and must not
     *                       pay for (or depend on) reading this machine's hardware
     */
    protected MetricCollector(boolean hardwareAccess) {

        startTime = ManagementFactory
                .getRuntimeMXBean()
                .getStartTime();

        if (!hardwareAccess) {
            systemInfo = null;
            hardware = null;
            processor = null;
            memory = null;
            sensors = null;
            operatingSystem = null;
            diskPartitions = List.of();
            return;
        }

        systemInfo = new SystemInfo();

        hardware = systemInfo.getHardware();

        processor = hardware.getProcessor();

        previousCpuTicks = processor.getSystemCpuLoadTicks();

        memory = hardware.getMemory();

        sensors = hardware.getSensors();

        operatingSystem = systemInfo.getOperatingSystem();

        File[] roots = File.listRoots();

        diskPartitions = roots != null ? List.of(roots) : List.of();
    }

    // ================= MAIN COLLECTION METHOD =================

    public synchronized TelemetryPayload collect(String nodeId) {

        TelemetryPayload payload = new TelemetryPayload();

        payload.setNodeId(nodeId);

        payload.setTimestamp(System.currentTimeMillis());

        // CPU
        double cpu = sampleCpuPercent();

        payload.setCpuPercent(round(cpu));

        payload.setCpuCoreCount(
                processor.getLogicalProcessorCount()
        );

        payload.setCpuTemperatureCelsius(
                sampleCpuTemperature()
        );

        // MEMORY
        long totalMemory = memory.getTotal();

        long availableMemory = memory.getAvailable();

        long usedMemory = Math.max(
                0,
                totalMemory - availableMemory
        );

        double memoryPercent = percentage(
                usedMemory,
                totalMemory
        );

        payload.setTotalMemoryBytes(totalMemory);

        payload.setUsedMemoryBytes(usedMemory);

        payload.setFreeMemoryBytes(availableMemory);

        payload.setMemoryPercent(round(memoryPercent));

        // DISK
        long totalDisk = 0;

        long freeDisk = 0;

        for (File partition : diskPartitions) {

            if (partition.exists()) {

                totalDisk += partition.getTotalSpace();

                freeDisk += partition.getUsableSpace();
            }
        }

        long usedDisk = Math.max(
                0,
                totalDisk - freeDisk
        );

        payload.setTotalDiskBytes(totalDisk);

        payload.setFreeDiskBytes(freeDisk);

        payload.setDiskPercent(
                round(percentage(usedDisk, totalDisk))
        );

        // PROCESSES
        payload.setProcessCount(
                sampleProcessCount()
        );

        // BATTERY
        collectBattery(payload);

        // NETWORK
        collectNetwork(payload);

        // SYSTEM INFORMATION
        collectSystemInformation(payload);

        // HEALTH STATUS
        payload.setStatus(
                determineStatus(
                        payload.getCpuPercent(),
                        payload.getMemoryPercent(),
                        payload.getDiskPercent()
                )
        );

        return payload;
    }

    // ================= CPU =================

    public synchronized double sampleCpuPercent() {

        double cpuLoad =
                processor.getSystemCpuLoadBetweenTicks(previousCpuTicks);

        previousCpuTicks = processor.getSystemCpuLoadTicks();

        if (Double.isNaN(cpuLoad)
                || !Double.isFinite(cpuLoad)
                || cpuLoad < 0) {

            return 0;
        }

        return clamp(cpuLoad * 100.0);
    }

    private enum ThermalZoneProperty {
        Temperature
    }

    private volatile boolean thermalZoneSupported = true;

    private double sampleWindowsThermalZone() {
        if (!thermalZoneSupported || !System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return -1;
        }
        try {
            oshi.util.platform.windows.WmiQueryHandler handler = oshi.util.platform.windows.WmiQueryHandler.createInstance();
            com.sun.jna.platform.win32.COM.WbemcliUtil.WmiQuery<ThermalZoneProperty> query =
                    new com.sun.jna.platform.win32.COM.WbemcliUtil.WmiQuery<>(
                            "Win32_PerfFormattedData_Counters_ThermalZoneInformation",
                            ThermalZoneProperty.class
                    );
            com.sun.jna.platform.win32.COM.WbemcliUtil.WmiResult<ThermalZoneProperty> result = handler.queryWMI(query);
            if (result == null || result.getResultCount() == 0) {
                thermalZoneSupported = false;
                return -1;
            }
            for (int i = 0; i < result.getResultCount(); i++) {
                long rawVal = oshi.util.platform.windows.WmiUtil.getUint32asLong(result, ThermalZoneProperty.Temperature, i);
                double celsius = -1;
                if (rawVal > 2000 && rawVal < 4500) {
                    // Stored in tenths of Kelvin (e.g., 3300 = 330.0 K = 56.85 °C)
                    celsius = (rawVal / 10.0) - 273.15;
                } else if (rawVal > 200 && rawVal < 450) {
                    // Stored in Kelvin (e.g., 330 = 330 K = 56.85 °C)
                    celsius = rawVal - 273.15;
                }
                if (celsius > 10.0 && celsius < 125.0) {
                    return celsius;
                }
            }
            thermalZoneSupported = false;
        } catch (Throwable ignored) {
            thermalZoneSupported = false;
        }
        return -1;
    }

    private long lastTempSampleTime = 0;
    private double cachedCpuTemperature = -1;

    private synchronized double sampleCpuTemperature() {
        long now = System.currentTimeMillis();
        long maxAge = cachedCpuTemperature > 0 ? 3_000 : 30_000;
        if (lastTempSampleTime > 0 && (now - lastTempSampleTime) < maxAge) {
            return cachedCpuTemperature;
        }

        // -1 means "this machine exposes no CPU temperature". Never estimate one: an invented
        // reading is indistinguishable from a measured one on the dashboard.
        double temperature = -1;

        try {

            temperature = sensors.getCpuTemperature();

            if (!Double.isFinite(temperature) || temperature <= 0) {
                temperature = sampleWindowsThermalZone();
            }

        } catch (Exception e) {

            temperature = -1;
        }

        cachedCpuTemperature = Double.isFinite(temperature) && temperature > 0 ? round(temperature) : -1;
        lastTempSampleTime = now;
        return cachedCpuTemperature;
    }

    // ================= BATTERY =================

    private void collectBattery(TelemetryPayload payload) {

        try {

            List<PowerSource> powerSources =
                    hardware.getPowerSources();

            if (powerSources.isEmpty()) {

                payload.setBatteryAvailable(false);
                payload.setBatteryPercent(-1);
                payload.setBatteryCharging(false);
                payload.setBatteryTimeRemainingSeconds(-1);

                logBatteryDiagnostic(
                        "No battery detected by OSHI."
                );

                return;
            }

            PowerSource battery = powerSources.get(0);

            // Read actual capacity values
            double currentCapacity =
                    battery.getCurrentCapacity();

            double maximumCapacity =
                    battery.getMaxCapacity();

            // Calculate battery percentage from capacity values
            double batteryPercent = -1;

            if (Double.isFinite(currentCapacity)
                    && Double.isFinite(maximumCapacity)
                    && currentCapacity >= 0
                    && maximumCapacity > 0
                    && currentCapacity <= maximumCapacity) {

                batteryPercent =
                        (currentCapacity / maximumCapacity) * 100.0;

            } else {

                // Fallback to OSHI percentage if capacities are invalid
                double oshiPercentage =
                        battery.getRemainingCapacityPercent() * 100.0;

                if (Double.isFinite(oshiPercentage)
                        && oshiPercentage >= 0
                        && oshiPercentage <= 100) {

                    batteryPercent = oshiPercentage;
                }
            }

            payload.setBatteryAvailable(true);

            payload.setBatteryPercent(
                    batteryPercent >= 0
                            ? round(clamp(batteryPercent))
                            : -1
            );

            payload.setBatteryCharging(
                    battery.isCharging()
            );

            // Remaining battery time
            double remainingTime =
                    battery.getTimeRemainingEstimated();

            payload.setBatteryTimeRemainingSeconds(
                    remainingTime >= 0 && Double.isFinite(remainingTime)
                            ? (long) remainingTime
                            : -1
            );

            // Diagnostic information
            String diagnostic = String.format(
                    "Battery: %s | Calculated Percentage: %.2f%%"
                            + " | OSHI Percentage: %.2f%%"
                            + " | Current Capacity: %.0f"
                            + " | Maximum Capacity: %.0f"
                            + " | Charging: %s",
                    battery.getName(),
                    batteryPercent,
                    battery.getRemainingCapacityPercent() * 100.0,
                    currentCapacity,
                    maximumCapacity,
                    battery.isCharging()
            );

            logBatteryDiagnostic(diagnostic);

        } catch (Exception e) {

            payload.setBatteryAvailable(false);
            payload.setBatteryPercent(-1);
            payload.setBatteryCharging(false);
            payload.setBatteryTimeRemainingSeconds(-1);

            logBatteryDiagnostic(
                    "Battery collection failed: " + e.getMessage()
            );
        }
    }

    private void logBatteryDiagnostic(String message) {

        LOGGER.fine("Battery diagnostic: " + message);
    }

    // ================= NETWORK =================

    private void collectNetwork(TelemetryPayload payload) {

        long received = 0;

        long sent = 0;

        long receivedDelta = 0;

        long sentDelta = 0;

        Map<String, long[]> counters = new HashMap<>();

        try {

            for (NetworkIF network : physicalInterfaces(hardware.getNetworkIFs())) {

                try {

                    network.updateAttributes();

                    long in = Math.max(0, network.getBytesRecv());

                    long out = Math.max(0, network.getBytesSent());

                    received += in;

                    sent += out;

                    counters.put(network.getName(), new long[]{in, out});

                    // Per-interface deltas: an adapter that appears or resets must not look like a burst
                    long[] previous = previousNetworkCounters.get(network.getName());

                    if (previous != null) {
                        receivedDelta += Math.max(0, in - previous[0]);
                        sentDelta += Math.max(0, out - previous[1]);
                    }

                } catch (Exception ignored) {
                    // Skip unavailable network interface
                }
            }

        } catch (Exception ignored) {
            // Network information unavailable
        }

        long currentTime = System.currentTimeMillis();

        long elapsed = currentTime - previousNetworkTime;

        boolean measurable = previousNetworkTime > 0 && elapsed > 0;

        double downloadSpeed = measurable ? receivedDelta * 1000.0 / elapsed : 0;

        double uploadSpeed = measurable ? sentDelta * 1000.0 / elapsed : 0;

        previousNetworkCounters = counters;

        previousNetworkTime = currentTime;

        payload.setTotalBytesReceived(received);

        payload.setTotalBytesSent(sent);

        payload.setDownloadBytesPerSecond(
                round(downloadSpeed)
        );

        payload.setUploadBytesPerSecond(
                round(uploadSpeed)
        );
    }

    /**
     * Leaves out virtual adapters (Hyper-V, VPN, ...), whose traffic is already counted on the physical
     * adapter it travels through. Falls back to every named interface if nothing physical is left.
     */
    private static List<NetworkIF> physicalInterfaces(List<NetworkIF> interfaces) {
        List<NetworkIF> named = interfaces.stream().filter(n -> n.getName() != null).toList();
        List<NetworkIF> physical = named.stream()
                .filter(n -> !NetworkUtils.isVirtualInterface(n.getName(), n.getDisplayName()))
                .toList();
        return physical.isEmpty() ? named : physical;
    }

    // ================= SYSTEM INFORMATION =================

    private String cachedOperatingSystem = null;
    private String cachedHostName = null;
    private String cachedLocalIp = null;
    private long lastIpCheckTime = 0;

    private void collectSystemInformation(TelemetryPayload payload) {
        if (cachedOperatingSystem == null) {
            cachedOperatingSystem = operatingSystem.getFamily() + " "
                    + operatingSystem.getVersionInfo().getVersion();
        }
        payload.setOperatingSystem(cachedOperatingSystem);

        payload.setHostName(getHostName());

        payload.setIpAddress(getLocalIpAddress());

        long uptime = operatingSystem.getSystemUptime();

        payload.setUptimeSeconds(Math.max(0, uptime));
    }

    private String getHostName() {
        if (cachedHostName != null) {
            return cachedHostName;
        }

        // Fast path: environment variables (avoids DNS / reverse lookup lag)
        String envComputer = System.getenv("COMPUTERNAME");
        if (envComputer != null && !envComputer.isBlank()) {
            cachedHostName = envComputer.trim();
            return cachedHostName;
        }

        String envHost = System.getenv("HOSTNAME");
        if (envHost != null && !envHost.isBlank()) {
            cachedHostName = envHost.trim();
            return cachedHostName;
        }

        try {
            cachedHostName = InetAddress.getLocalHost().getHostName();
            return cachedHostName;
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private String getLocalIpAddress() {
        long now = System.currentTimeMillis();
        if (cachedLocalIp != null && (now - lastIpCheckTime) < 5000) {
            return cachedLocalIp;
        }

        List<NetworkUtils.LanAddress> addresses = NetworkUtils.listLanIPv4Addresses();
        if (!addresses.isEmpty()) {
            cachedLocalIp = addresses.get(0).address();
            lastIpCheckTime = now;
        }

        return cachedLocalIp != null ? cachedLocalIp : "Unknown";
    }

    // ================= PROCESS COUNT =================

    public int sampleProcessCount() {

        try {

            return operatingSystem.getProcessCount();

        } catch (Exception e) {

            return 0;
        }
    }

    // ================= HEALTH STATUS =================

    public String determineStatus(
            double cpuPercent,
            double memoryPercent,
            double diskPercent
    ) {

        if (cpuPercent > 85
                || memoryPercent > 90
                || diskPercent > 95) {

            return "WARNING";
        }

        return "HEALTHY";
    }

    // ================= UTILITY METHODS =================

    private double percentage(long used, long total) {

        if (total <= 0) {
            return 0;
        }

        return clamp(
                ((double) used / total) * 100.0
        );
    }

    private double clamp(double value) {

        return Math.max(
                0,
                Math.min(100, value)
        );
    }

    private double round(double value) {

        return Math.round(value * 10.0) / 10.0;
    }

    // ================= GETTERS =================

    public long getStartTime() {
        return startTime;
    }
}
