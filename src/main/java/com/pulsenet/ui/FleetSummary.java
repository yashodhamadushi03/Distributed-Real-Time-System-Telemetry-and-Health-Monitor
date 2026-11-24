package com.pulsenet.ui;

import com.pulsenet.common.model.InternetConnectivityMetrics;
import com.pulsenet.common.model.NetworkMetrics;
import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.server.registry.NodeRecord;

import java.util.List;

/**
 * Fleet-wide figures shown on the overview page, computed from the online nodes only. A value that
 * no node has measured is {@link Double#NaN}, so it can never be mistaken for a measured zero.
 *
 * @param total             registered nodes
 * @param online            nodes that are not OFFLINE
 * @param warnings          online nodes reporting WARNING
 * @param cpu               average CPU percent
 * @param memory            average memory percent
 * @param disk              average disk percent
 * @param rtt               average round-trip time in milliseconds
 * @param jitter            average jitter in milliseconds
 * @param loss              average rolling packet loss percent
 * @param battery           average battery percent
 * @param batteryNodes      online nodes reporting a battery
 * @param download          total download in bytes per second
 * @param upload            total upload in bytes per second
 * @param internetOnline    online nodes with verified Internet access
 * @param internetReporting online nodes that report Internet checks
 */
record FleetSummary(int total, int online, int warnings, double cpu, double memory, double disk,
                    double rtt, double jitter, double loss, double battery, int batteryNodes,
                    double download, double upload, int internetOnline, int internetReporting) {

    static FleetSummary of(List<NodeRecord> nodes) {
        int online = 0;
        int warnings = 0;
        int batteryNodes = 0;
        int internetOnline = 0;
        int internetReporting = 0;
        double download = 0;
        double upload = 0;
        Average cpu = new Average();
        Average memory = new Average();
        Average disk = new Average();
        Average rtt = new Average();
        Average jitter = new Average();
        Average loss = new Average();
        Average battery = new Average();

        for (NodeRecord node : nodes) {
            if (isOffline(node)) {
                continue;
            }
            online++;
            if ("WARNING".equalsIgnoreCase(node.getStatus())) {
                warnings++;
            }
            TelemetryPayload p = node.getLatestPayload();
            if (p != null) {
                cpu.add(p.getCpuPercent());
                memory.add(p.getMemoryPercent());
                disk.add(p.getDiskPercent());
                download += p.getDownloadBytesPerSecond();
                upload += p.getUploadBytesPerSecond();
                if (p.isBatteryAvailable() && p.getBatteryPercent() >= 0) {
                    battery.add(p.getBatteryPercent());
                    batteryNodes++;
                }
            }
            NetworkMetrics net = node.getNetworkMetrics();
            if (net != null) {
                rtt.addMeasured(net.getAvgRttMs());
                jitter.addMeasured(net.getAvgJitterMs());
                loss.addMeasured(packetLoss(net));
            }
            InternetConnectivityMetrics inet = node.getInternetMetrics();
            if (inet != null && !InternetConnectivityMetrics.STATUS_UNKNOWN.equalsIgnoreCase(inet.getStatus())) {
                internetReporting++;
                if (InternetConnectivityMetrics.STATUS_ONLINE.equalsIgnoreCase(inet.getStatus())) {
                    internetOnline++;
                }
            }
        }
        return new FleetSummary(nodes.size(), online, warnings, cpu.value(), memory.value(), disk.value(),
                rtt.value(), jitter.value(), loss.value(), battery.value(), batteryNodes,
                download, upload, internetOnline, internetReporting);
    }

    static boolean isOffline(NodeRecord node) {
        return node.getStatus() == null || "OFFLINE".equalsIgnoreCase(node.getStatus());
    }

    /**
     * @return rolling packet loss percent, or {@link Double#NaN} if no probe has completed yet
     */
    static double packetLoss(NetworkMetrics net) {
        return net != null && net.getProbesReceived() + net.getProbesLost() > 0
                ? net.getPacketLossPercent()
                : Double.NaN;
    }

    int offline() {
        return total - online;
    }

    private static final class Average {
        private double sum;
        private int count;

        void add(double value) {
            sum += value;
            count++;
        }

        /** Adds the value unless it is the "not measured" marker (negative or NaN). */
        void addMeasured(double value) {
            if (value >= 0) {
                add(value);
            }
        }

        double value() {
            return count > 0 ? sum / count : Double.NaN;
        }
    }
}
