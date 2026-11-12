package com.pulsenet.server.dashboard;

import com.pulsenet.common.model.InternetConnectivityMetrics;
import com.pulsenet.common.model.NetworkMetrics;
import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.server.registry.NodeRecord;
import com.pulsenet.server.registry.NodeRegistry;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Terminal dashboard renderer presenting real-time ASCII/ANSI telemetry tables,
 * resource utilization progress bars, status badges, and rolling event logs.
 */
public class DashboardRenderer implements Runnable {

    // ANSI escape sequences
    public static final String ANSI_RESET = "\033[0m";
    public static final String ANSI_BOLD = "\033[1m";
    public static final String ANSI_GREEN = "\033[32m";
    public static final String ANSI_YELLOW = "\033[33m";
    public static final String ANSI_RED = "\033[31m";
    public static final String ANSI_CYAN = "\033[36m";
    public static final String ANSI_GRAY = "\033[90m";
    public static final String ANSI_CLEAR_SCREEN = "\033[H\033[2J";

    /** Column width for NODE ID (fits typical real hostnames such as {@code desktop-7qk2m1l}). */
    public static final int NODE_ID_WIDTH = 20;
    /** Column width for IP:PORT (fits the longest IPv4 endpoint, {@code 255.255.255.255:65535}). */
    public static final int ADDRESS_WIDTH = 21;
    /** Total table width used for the separator lines. */
    public static final int TABLE_WIDTH = 110;

    private static final String ROW_FORMAT =
            " %-" + NODE_ID_WIDTH + "s %-" + ADDRESS_WIDTH + "s %-12s %-12s %-12s %-12s %s\n";

    private final NodeRegistry registry;
    private final String serverHost;
    private final String displayHost;
    private final int serverPort;
    private final boolean ansiEnabled;
    private final PrintStream out;
    private final long refreshIntervalMillis;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread rendererThread;

    public DashboardRenderer(NodeRegistry registry, String serverHost, int serverPort) {
        this(registry, serverHost, serverPort, true, System.out, 500L);
    }

    public DashboardRenderer(NodeRegistry registry, String serverHost, int serverPort, boolean ansiEnabled) {
        this(registry, serverHost, serverPort, ansiEnabled, System.out, 500L);
    }

    public DashboardRenderer(NodeRegistry registry, String serverHost, int serverPort,
                             boolean ansiEnabled, PrintStream out, long refreshIntervalMillis) {
        this(registry, serverHost, serverPort, ansiEnabled, out, refreshIntervalMillis, serverHost);
    }

    /**
     * Full constructor with a separate host shown in the header.
     *
     * @param registry              node registry to render
     * @param serverHost            the bind address (may be the wildcard {@code 0.0.0.0})
     * @param serverPort            the listening port
     * @param ansiEnabled           whether ANSI colours and screen clearing are used
     * @param out                   destination stream
     * @param refreshIntervalMillis refresh period in milliseconds
     * @param displayHost           address shown in the header, e.g. the LAN IP agents should target
     */
    public DashboardRenderer(NodeRegistry registry, String serverHost, int serverPort,
                             boolean ansiEnabled, PrintStream out, long refreshIntervalMillis,
                             String displayHost) {
        this.registry = registry;
        this.serverHost = serverHost;
        this.displayHost = displayHost != null ? displayHost : serverHost;
        this.serverPort = serverPort;
        this.ansiEnabled = ansiEnabled;
        this.out = out;
        this.refreshIntervalMillis = refreshIntervalMillis;
    }

    /**
     * Starts the terminal dashboard refresher in a background daemon thread.
     */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            rendererThread = new Thread(this, "DashboardRenderer");
            rendererThread.setDaemon(true);
            rendererThread.start();
        }
    }

    @Override
    public void run() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                render();
                Thread.sleep(refreshIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                out.println("Error rendering dashboard: " + e.getMessage());
            }
        }
    }

    /**
     * Renders a single dashboard frame to the configured PrintStream.
     */
    public void render() {
        String frame = buildDashboardString();
        if (ansiEnabled) {
            out.print(ANSI_CLEAR_SCREEN);
            out.flush();
        }
        out.print(frame);
        out.flush();
    }

    /**
     * Constructs the structured dashboard text for the current registry state.
     */
    public String buildDashboardString() {
        List<NodeRecord> nodes = registry.getAllNodes();
        List<String> events = registry.getRecentEvents(6);
        long now = System.currentTimeMillis();

        int activeCount = 0;
        for (NodeRecord r : nodes) {
            if (!"OFFLINE".equals(r.getStatus())) {
                activeCount++;
            }
        }

        StringBuilder sb = new StringBuilder();
        String lineSeparator = "=".repeat(TABLE_WIDTH);
        String subSeparator  = "-".repeat(TABLE_WIDTH);

        sb.append(lineSeparator).append("\n");
        sb.append(String.format(" %sPulseNet: Distributed Telemetry Aggregator%s | Server: %s:%d | Active Nodes: %d\n",
                ansiEnabled ? ANSI_BOLD + ANSI_CYAN : "",
                ansiEnabled ? ANSI_RESET : "",
                displayHost, serverPort, activeCount));
        sb.append(lineSeparator).append("\n");

        sb.append(String.format(ROW_FORMAT,
                "NODE ID", "IP:PORT", "CPU LOAD", "MEM USAGE", "DISK USAGE", "ACTIVE PIDS", "STATUS"));
        sb.append(subSeparator).append("\n");

        if (nodes.isEmpty()) {
            sb.append(" (No client nodes connected. Waiting for agent telemetry...)\n");
        } else {
            for (NodeRecord record : nodes) {
                TelemetryPayload payload = record.getLatestPayload();
                String nodeId = truncate(record.getNodeId(), NODE_ID_WIDTH);
                String ipPort = truncate(cleanAddress(record.getRemoteAddress()), ADDRESS_WIDTH);

                double cpu = payload != null ? payload.getCpuPercent() : 0.0;
                double mem = payload != null ? payload.getMemoryPercent() : 0.0;
                double disk = payload != null ? payload.getDiskPercent() : 0.0;
                int pids = payload != null ? payload.getProcessCount() : 0;
                String status = record.getStatus();

                String cpuBar = renderProgressBar(cpu, 6);
                String memBar = renderProgressBar(mem, 6);
                String diskBar = renderProgressBar(disk, 6);

                String statusBadge;
                if ("OFFLINE".equals(status)) {
                    long silenceSec = Math.max(0, (now - record.getLastSeenTimestamp()) / 1000);
                    statusBadge = String.format("OFFLINE (%ds)", silenceSec);
                    if (ansiEnabled) {
                        statusBadge = ANSI_GRAY + statusBadge + ANSI_RESET;
                    }
                } else if ("WARNING".equals(status)) {
                    statusBadge = "WARNING";
                    if (ansiEnabled) {
                        statusBadge = ANSI_BOLD + ANSI_YELLOW + statusBadge + ANSI_RESET;
                    }
                } else {
                    statusBadge = "HEALTHY";
                    if (ansiEnabled) {
                        statusBadge = ANSI_BOLD + ANSI_GREEN + statusBadge + ANSI_RESET;
                    }
                }

                sb.append(String.format(Locale.US, ROW_FORMAT,
                        nodeId, ipPort, cpuBar, memBar, diskBar, Integer.toString(pids), statusBadge));
            }
        }

        // ==========================================
        // DEDICATED NETWORK PERFORMANCE SECTION
        // ==========================================
        sb.append(lineSeparator).append("\n");
        sb.append(String.format(" %sNETWORK PERFORMANCE%s\n",
                ansiEnabled ? ANSI_BOLD + ANSI_CYAN : "",
                ansiEnabled ? ANSI_RESET : ""));
        sb.append(subSeparator).append("\n");

        if (nodes.isEmpty()) {
            sb.append(" (No network performance data available. Waiting for nodes...)\n");
        } else {
            for (NodeRecord record : nodes) {
                TelemetryPayload payload = record.getLatestPayload();
                NetworkMetrics net = payload != null ? payload.getNetworkMetrics() : null;
                long silenceSec = Math.max(0, (now - record.getLastSeenTimestamp()) / 1000);

                sb.append(String.format(" Node ID: %s%s%s",
                        ansiEnabled ? ANSI_BOLD : "",
                        record.getNodeId(),
                        ansiEnabled ? ANSI_RESET : ""));
                if (net != null && net.getAgentHostname() != null && !net.getAgentHostname().isBlank()) {
                    sb.append(String.format(" [Host: %s]", net.getAgentHostname()));
                }
                sb.append("\n");

                String serverEndpoint = (net != null && net.getServerHost() != null && !net.getServerHost().isBlank())
                        ? (net.getServerHost() + ":" + (net.getServerPort() > 0 ? net.getServerPort() : serverPort))
                        : (displayHost + ":" + serverPort);
                String probeEndpoint = (net != null && net.getUdpProbePort() > 0)
                        ? (":" + net.getUdpProbePort() + " (UDP)")
                        : ":9002 (UDP)";
                String protocol = (net != null && net.getProtocol() != null) ? net.getProtocol() : "TCP + UDP probes";
                String connStatus = record.getStatus();
                if (net != null && net.getConnectionStatus() != null && !"OFFLINE".equals(record.getStatus())) {
                    connStatus = net.getConnectionStatus();
                }

                sb.append(String.format(" Server: %s (TCP) | Probe: %s | Protocol: %s | Connection Status: %s\n\n",
                        serverEndpoint, probeEndpoint, protocol, colorizeStatus(connStatus)));

                // Latency
                sb.append(" Latency:\n");
                sb.append(String.format(Locale.US,
                        "   Current RTT: %-12s Average RTT: %-12s Minimum RTT: %-12s Maximum RTT: %-12s\n",
                        formatMs(net != null ? net.getCurrentRttMs() : -1.0),
                        formatMs(net != null ? net.getAvgRttMs() : -1.0),
                        formatMs(net != null ? net.getMinRttMs() : -1.0),
                        formatMs(net != null ? net.getMaxRttMs() : -1.0)));

                // Network Quality
                sb.append(" Network Quality:\n");
                double rollingLoss = net != null ? net.getPacketLossPercent() : 0.0;
                double cumLoss = net != null ? net.getCumulativeLossPercent() : 0.0;
                int wSize = net != null ? net.getWindowSize() : 50;
                double jitter = net != null ? net.getAvgJitterMs() : -1.0;
                String lossDisplay = (net != null && net.getProbesSent() > 0)
                        ? String.format(Locale.US, "%.1f%% (Win %d)", rollingLoss, wSize)
                        : "N/A";
                sb.append(String.format(Locale.US,
                        "   Packet Loss: %-14s Average Jitter: %-12s Quality: %s\n",
                        lossDisplay,
                        formatMs(jitter),
                        qualityBadge(net)));

                // Connection
                sb.append(" Connection:\n");
                long uptime = net != null ? net.getConnectionUptimeMillis() : 0L;
                int reconns = net != null ? net.getReconnectionCount() : 0;
                sb.append(String.format(Locale.US,
                        "   Connection Uptime: %-14s Total Reconnections: %-4d Last Heartbeat: %ds ago\n",
                        formatDuration(uptime),
                        reconns,
                        silenceSec));

                // Probe Statistics
                sb.append(" Probe Statistics:\n");
                long sent = net != null ? net.getProbesSent() : 0L;
                long recvd = net != null ? net.getProbesReceived() : 0L;
                long lost = net != null ? net.getProbesLost() : 0L;
                String cumLossNote = (sent > 0) ? String.format(Locale.US, "(Cum Loss: %.1f%%)", cumLoss) : "(Cum Loss: 0.0%)";
                sb.append(String.format(Locale.US,
                        "   Packets Sent: %-8d Responses Received: %-8d Lost Packets: %-5d %s\n",
                        sent, recvd, lost, cumLossNote));

                // Internet Connectivity
                boolean isNodeOffline = "OFFLINE".equalsIgnoreCase(record.getStatus()) || silenceSec > 5;
                InternetConnectivityMetrics inet = payload != null ? payload.getInternetMetrics() : null;
                sb.append(" Internet Connectivity:\n");
                if (isNodeOffline) {
                    String gw = (inet != null && inet.getGatewayIp() != null) ? inet.getGatewayIp() : "unknown";
                    sb.append(String.format(Locale.US,
                            "   Status: %-12s Gateway: %-15s Reachability: %s\n",
                            colorizeInternetStatus("OFFLINE (Node Disconnected)"),
                            gw,
                            "UNREACHABLE (Node Offline)"));
                    sb.append(String.format(Locale.US,
                            "   DNS Resolution: %-26s External HTTPS: %s\n",
                            "STALE (Node Disconnected)",
                            "STALE (Node Disconnected)"));
                    long lastCheckSec = (inet != null && inet.getLastCheckTimestamp() > 0) ? Math.max(0, (now - inet.getLastCheckTimestamp()) / 1000) : -1L;
                    long lastSuccSec = (inet != null && inet.getLastSuccessfulCheckTimestamp() > 0) ? Math.max(0, (now - inet.getLastSuccessfulCheckTimestamp()) / 1000) : -1L;
                    sb.append(String.format(Locale.US,
                            "   Last Check: %-20s Last Successful Online: %s\n",
                            lastCheckSec >= 0 ? (lastCheckSec + "s ago (Stale)") : "N/A",
                            lastSuccSec >= 0 ? (lastSuccSec + "s ago") : "Never"));
                } else if (inet == null || InternetConnectivityMetrics.STATUS_UNKNOWN.equals(inet.getStatus())) {
                    sb.append(String.format(Locale.US,
                            "   Status: %s | Gateway: %s | DNS: %s | External HTTPS: %s\n",
                            colorizeInternetStatus(inet != null ? inet.getStatus() : InternetConnectivityMetrics.STATUS_UNKNOWN),
                            (inet != null && inet.getGatewayIp() != null) ? inet.getGatewayIp() : "unknown",
                            "N/A", "N/A"));
                } else {
                    String gwStatus = inet.isGatewayReachable() ? "REACHABLE" : "UNREACHABLE";
                    String dnsStatus = inet.isDnsSuccess()
                            ? String.format(Locale.US, "OK (%d ms via %s)", inet.getDnsResolutionTimeMs(), inet.getDnsTestHost())
                            : "FAILED";
                    String httpStatus = inet.isHttpsSuccess()
                            ? String.format(Locale.US, "HTTP %d (%d ms via %s)", inet.getHttpStatusCode(), inet.getHttpsResponseTimeMs(), inet.getHttpsTestEndpoint())
                            : (inet.getHttpStatusCode() > 0 ? "HTTP " + inet.getHttpStatusCode() + " (FAILED)" : "FAILED");
                    long lastCheckSec = inet.getLastCheckTimestamp() > 0 ? Math.max(0, (now - inet.getLastCheckTimestamp()) / 1000) : -1L;
                    long lastSuccSec = inet.getLastSuccessfulCheckTimestamp() > 0 ? Math.max(0, (now - inet.getLastSuccessfulCheckTimestamp()) / 1000) : -1L;

                    sb.append(String.format(Locale.US,
                            "   Status: %-12s Gateway: %-15s Reachability: %s\n",
                            colorizeInternetStatus(inet.getStatus()),
                            inet.getGatewayIp() != null ? inet.getGatewayIp() : "unknown",
                            gwStatus));
                    sb.append(String.format(Locale.US,
                            "   DNS Resolution: %-26s External HTTPS: %s\n",
                            dnsStatus,
                            httpStatus));
                    sb.append(String.format(Locale.US,
                            "   Last Check: %-20s Last Successful Online: %s\n",
                            lastCheckSec >= 0 ? (lastCheckSec + "s ago") : "N/A",
                            lastSuccSec >= 0 ? (lastSuccSec + "s ago") : "Never"));
                }

                sb.append(subSeparator).append("\n");
            }
        }

        sb.append(lineSeparator).append("\n");
        sb.append(" [EVENT LOG]\n");
        if (events.isEmpty()) {
            sb.append(" (No events recorded)\n");
        } else {
            for (String event : events) {
                sb.append(" ").append(colorizeEvent(event)).append("\n");
            }
        }
        sb.append(lineSeparator).append("\n");

        return sb.toString();
    }

    /**
     * Renders a progress bar string, e.g. [|||||   ] 42%
     */
    public String renderProgressBar(double percent, int barWidth) {
        int filled = (int) Math.round((percent / 100.0) * barWidth);
        filled = Math.max(0, Math.min(barWidth, filled));
        int empty = barWidth - filled;

        String bars = "|".repeat(filled);
        String spaces = " ".repeat(empty);

        return String.format(Locale.US, "[%s%s] %2.0f%%", bars, spaces, percent);
    }

    private String colorizeEvent(String event) {
        if (!ansiEnabled) {
            return event;
        }
        if (event.contains("[SYS]")) {
            return ANSI_CYAN + event + ANSI_RESET;
        } else if (event.contains("[WARN]")) {
            return ANSI_YELLOW + event + ANSI_RESET;
        } else if (event.contains("[LOST]")) {
            return ANSI_RED + event + ANSI_RESET;
        } else if (event.contains("[REC]")) {
            return ANSI_GREEN + event + ANSI_RESET;
        }
        return event;
    }

    private String cleanAddress(String addr) {
        if (addr == null) return "unknown";
        if (addr.startsWith("/")) {
            return addr.substring(1);
        }
        return addr;
    }

    private String truncate(String val, int maxLen) {
        if (val == null) return "";
        if (val.length() <= maxLen) return val;
        return val.substring(0, maxLen - 1) + "~";
    }

    private String formatMs(double val) {
        if (val < 0.0) return "N/A";
        return String.format(Locale.US, "%.1f ms", val);
    }

    private String formatDuration(long millis) {
        if (millis <= 0L) return "00m 00s";
        long totalSec = millis / 1000L;
        long hours = totalSec / 3600L;
        long minutes = (totalSec % 3600L) / 60L;
        long seconds = totalSec % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%02dh %02dm %02ds", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02dm %02ds", minutes, seconds);
    }

    private String qualityBadge(NetworkMetrics net) {
        if (net == null || net.getProbesSent() == 0 || net.getCurrentRttMs() < 0) {
            return "N/A";
        }
        double loss = net.getPacketLossPercent();
        double rtt = net.getCurrentRttMs();
        String rating;
        String color;
        if (loss == 0.0 && rtt < 40.0) {
            rating = "EXCELLENT";
            color = ANSI_GREEN;
        } else if (loss < 2.0 && rtt < 100.0) {
            rating = "GOOD";
            color = ANSI_GREEN;
        } else if (loss < 5.0) {
            rating = "FAIR";
            color = ANSI_YELLOW;
        } else {
            rating = "POOR";
            color = ANSI_RED;
        }
        return ansiEnabled ? (ANSI_BOLD + color + rating + ANSI_RESET) : rating;
    }

    private String colorizeStatus(String status) {
        if (!ansiEnabled || status == null) return status != null ? status : "UNKNOWN";
        if ("CONNECTED".equalsIgnoreCase(status) || "HEALTHY".equalsIgnoreCase(status)) {
            return ANSI_BOLD + ANSI_GREEN + status + ANSI_RESET;
        } else if ("RECONNECTING".equalsIgnoreCase(status) || "WARNING".equalsIgnoreCase(status)) {
            return ANSI_BOLD + ANSI_YELLOW + status + ANSI_RESET;
        } else {
            return ANSI_BOLD + ANSI_RED + status + ANSI_RESET;
        }
    }

    private String colorizeInternetStatus(String status) {
        if (!ansiEnabled || status == null) return status != null ? status : "UNKNOWN";
        String upper = status.toUpperCase(Locale.ROOT);
        if (upper.startsWith("ONLINE")) {
            return ANSI_BOLD + ANSI_GREEN + status + ANSI_RESET;
        } else if (upper.contains("LIMITED") || upper.contains("STALE")) {
            return ANSI_BOLD + ANSI_YELLOW + status + ANSI_RESET;
        } else if (upper.contains("OFFLINE")) {
            return ANSI_BOLD + ANSI_RED + status + ANSI_RESET;
        } else {
            return ANSI_GRAY + status + ANSI_RESET;
        }
    }

    /**
     * Stops the dashboard renderer daemon.
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            if (rendererThread != null) {
                rendererThread.interrupt();
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * @return the host shown in the dashboard header
     */
    public String getDisplayHost() {
        return displayHost;
    }

    /**
     * @return the configured bind address (may be the wildcard {@code 0.0.0.0})
     */
    public String getServerHost() {
        return serverHost;
    }
}
