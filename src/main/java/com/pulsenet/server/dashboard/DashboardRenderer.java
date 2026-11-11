package com.pulsenet.server.dashboard;

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
