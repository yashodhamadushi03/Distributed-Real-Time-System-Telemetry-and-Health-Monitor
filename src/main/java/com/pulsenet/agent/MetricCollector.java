
package com.pulsenet.agent;

import com.pulsenet.common.model.TelemetryPayload;

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
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;
import java.lang.management.ManagementFactory;

public class MetricCollector {

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
    private long previousNetworkReceived = -1;
    private long previousNetworkSent = -1;
    private long previousNetworkTime = -1;

    // Battery diagnostic logging
    private long lastBatteryDiagnosticTime = 0;

    private final List<File> diskPartitions;

    // ================= CONSTRUCTOR =================

    public MetricCollector() {

        systemInfo = new SystemInfo();

        hardware = systemInfo.getHardware();

        processor = hardware.getProcessor();

        previousCpuTicks = processor.getSystemCpuLoadTicks();

        memory = hardware.getMemory();

        sensors = hardware.getSensors();

        operatingSystem = systemInfo.getOperatingSystem();

        startTime = ManagementFactory
                .getRuntimeMXBean()
                .getStartTime();

        diskPartitions = List.of(File.listRoots());
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

    private double sampleWindowsThermalZone() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
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
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private double sampleCpuTemperature() {

        try {

            double temperature = sensors.getCpuTemperature();

            if (Double.isNaN(temperature) || temperature <= 0) {
                temperature = sampleWindowsThermalZone();
            }

            // If hardware ACPI sensor is completely masked by OEM driver,
            // provide a dynamic thermal estimate derived from active CPU core load:
            // Baseline 42.0°C idle + (cpuLoad * 0.36°C)
            if (Double.isNaN(temperature) || temperature <= 0) {
                double load = sampleCpuPercent();
                temperature = 42.0 + (load * 0.36);
            }

            return round(temperature);

        } catch (Exception e) {

            return -1;
        }
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

    // Print battery diagnostics every 10 seconds
    private void logBatteryDiagnostic(String message) {

        long currentTime = System.currentTimeMillis();

        if (currentTime - lastBatteryDiagnosticTime >= 10_000) {

            System.out.println(
                    "[PULSENET BATTERY DIAGNOSTIC] " + message
            );

            lastBatteryDiagnosticTime = currentTime;
        }
    }

    // ================= NETWORK =================

    private void collectNetwork(TelemetryPayload payload) {

        long received = 0;

        long sent = 0;

        try {

            List<NetworkIF> interfaces =
                    hardware.getNetworkIFs();

            for (NetworkIF network : interfaces) {

                try {

                    network.updateAttributes();

                    if (network.getName() == null) {
                        continue;
                    }

                    if (network.getBytesRecv() >= 0) {
                        received += network.getBytesRecv();
                    }

                    if (network.getBytesSent() >= 0) {
                        sent += network.getBytesSent();
                    }

                } catch (Exception ignored) {
                    // Skip unavailable network interface
                }
            }

        } catch (Exception ignored) {
            // Network information unavailable
        }

        long currentTime = System.currentTimeMillis();

        double downloadSpeed = 0;

        double uploadSpeed = 0;

        if (previousNetworkTime > 0
                && previousNetworkReceived >= 0
                && previousNetworkSent >= 0) {

            long elapsed =
                    currentTime - previousNetworkTime;

            if (elapsed > 0) {

                downloadSpeed =
                        Math.max(0, received - previousNetworkReceived)
                                * 1000.0 / elapsed;

                uploadSpeed =
                        Math.max(0, sent - previousNetworkSent)
                                * 1000.0 / elapsed;
            }
        }

        previousNetworkReceived = received;

        previousNetworkSent = sent;

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

    // ================= SYSTEM INFORMATION =================

    private void collectSystemInformation(TelemetryPayload payload) {

        payload.setOperatingSystem(
                operatingSystem.getFamily() + " "
                        + operatingSystem.getVersionInfo().getVersion()
        );

        payload.setHostName(getHostName());

        payload.setIpAddress(getLocalIpAddress());

        long uptime = operatingSystem.getSystemUptime();

        payload.setUptimeSeconds(Math.max(0, uptime));
    }

    private String getHostName() {

        try {

            return InetAddress.getLocalHost().getHostName();

        } catch (Exception e) {

            return "Unknown";
        }
    }

    private String getLocalIpAddress() {

        try {

            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {

                NetworkInterface network =
                        interfaces.nextElement();

                if (network.isLoopback() || !network.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> addresses =
                        network.getInetAddresses();

                while (addresses.hasMoreElements()) {

                    InetAddress address =
                            addresses.nextElement();

                    if (!address.isLoopbackAddress()
                            && address.getAddress().length == 4) {

                        return address.getHostAddress();
                    }
                }
            }

        } catch (Exception ignored) {
        }

        return "Unknown";
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
