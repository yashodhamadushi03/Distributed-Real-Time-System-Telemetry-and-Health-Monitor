package com.pulsenet.ui;

import com.pulsenet.common.model.InternetConnectivityMetrics;
import com.pulsenet.common.model.NetworkMetrics;
import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.server.discovery.DiscoveryServer;
import com.pulsenet.server.network.NetworkProbeServer;
import com.pulsenet.server.network.TelemetryServer;
import com.pulsenet.server.registry.NodeRecord;
import com.pulsenet.server.registry.NodeRegistry;
import com.pulsenet.server.watchdog.WatchdogSupervisor;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PulseNet desktop dashboard.
 *
 * <p>Runs the same server components as {@code ServerMain} (TCP telemetry, UDP discovery, UDP probe,
 * watchdog) inside one window and shows what the agents report. Pages: Overview, Nodes (with a
 * per-node detail view), Network and Events. All UI updates happen on the JavaFX thread from a
 * one-second timer; network threads only write to the {@link NodeRegistry}.
 */
public class PulseNetApp extends Application {

    private static final Logger LOG = Logger.getLogger(PulseNetApp.class.getName());

    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final int DEFAULT_TCP_PORT = 9000;
    public static final int DEFAULT_UDP_DISCOVERY_PORT = 9001;
    public static final int DEFAULT_UDP_PROBE_PORT = 9002;
    public static final double DEFAULT_WATCHDOG_TIMEOUT = 15.0;

    /** Number of points kept for each chart line. */
    private static final int HISTORY = 60;

    // Palette: navy, slate and teal, plus muted amber / coral used only for warning / offline.
    private static final String BG = "#0B132B";
    private static final String PANEL = "#1C2541";
    private static final String LINE = "#3A506B";
    private static final String ACCENT = "#5BC0BE";
    private static final String TEXT = "#E6EDF3";
    private static final String MUTED = "#9FB0C6";
    private static final String WARN = "#E9C46A";
    private static final String BAD = "#E76F51";
    private static final String FONT = "-fx-font-family:'Segoe UI','Helvetica Neue',Arial,sans-serif;";

    private static final Pattern EVENT_LINE = Pattern.compile("^(\\d\\d:\\d\\d:\\d\\d)\\s+\\[(\\w+)\\]\\s+(.*)$");

    private int tcpPort = DEFAULT_TCP_PORT;
    private int udpDiscoveryPort = DEFAULT_UDP_DISCOVERY_PORT;
    private int udpProbePort = DEFAULT_UDP_PROBE_PORT;
    private String serverId = "pulsenet-noc-server";

    private NodeRegistry registry;
    private TelemetryServer server;
    private DiscoveryServer discoveryServer;
    private NetworkProbeServer probeServer;
    private WatchdogSupervisor watchdog;
    private Timeline refreshTimeline;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private VBox pageHost;
    private final Map<String, Button> navButtons = new LinkedHashMap<>();
    private String currentPage = "Overview";
    private String selectedNodeId;

    // Rolling history (only touched on the JavaFX thread).
    private final Deque<Double> cpuHistory = new ArrayDeque<>();
    private final Deque<Double> memHistory = new ArrayDeque<>();
    private final Deque<Double> rttHistory = new ArrayDeque<>();
    private final Deque<Double> nodeCpuHistory = new ArrayDeque<>();
    private final Deque<Double> nodeMemHistory = new ArrayDeque<>();

    // Widgets of the page that is currently shown.
    private final Map<String, Label> statValues = new HashMap<>();
    private LineChart<Number, Number> loadChart;
    private LineChart<Number, Number> latencyChart;
    private LineChart<Number, Number> nodeChart;
    private Table nodesTable;
    private Table networkTable;
    private Label emptyHint;
    private VBox eventList;
    private List<String> lastEvents = List.of();
    private final Map<String, Label> facts = new LinkedHashMap<>();
    private Label detailStatus;

    /** One table cell: text plus an optional colour. */
    private record Cell(String text, String color) {
        static Cell of(String text) {
            return new Cell(text, TEXT);
        }
    }

    /** Fleet-wide averages over nodes that are currently online. */
    private record Summary(int up, int down, double cpu, double mem, double rtt) {
    }

    @Override
    public void init() {
        List<String> raw = getParameters() != null ? getParameters().getRaw() : List.of();
        for (int i = 0; i + 1 < raw.size(); i++) {
            try {
                switch (raw.get(i)) {
                    case "--port" -> tcpPort = Integer.parseInt(raw.get(++i));
                    case "--udp-port" -> udpDiscoveryPort = Integer.parseInt(raw.get(++i));
                    case "--probe-port", "--udp-probe-port" -> udpProbePort = Integer.parseInt(raw.get(++i));
                    case "--server-id" -> serverId = raw.get(++i);
                    default -> {
                    }
                }
            } catch (NumberFormatException ignored) {
                // keep the default for a malformed port
            }
        }
    }

    @Override
    public void start(Stage stage) {
        registry = new NodeRegistry();
        server = new TelemetryServer(DEFAULT_HOST, tcpPort, registry);
        discoveryServer = new DiscoveryServer(DEFAULT_HOST, udpDiscoveryPort, DEFAULT_HOST, tcpPort, udpProbePort,
                serverId);
        probeServer = new NetworkProbeServer(DEFAULT_HOST, udpProbePort);
        watchdog = new WatchdogSupervisor(registry, DEFAULT_WATCHDOG_TIMEOUT);

        try {
            server.start();
            discoveryServer.start();
            probeServer.start();
            watchdog.start();
        } catch (IOException ex) {
            LOG.log(Level.SEVERE, "Could not start PulseNet servers", ex);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("PulseNet");
            alert.setHeaderText("Could not open the network ports");
            alert.setContentText(String.format("TCP %d or UDP %d/%d may already be in use.%n%s",
                    tcpPort, udpDiscoveryPort, udpProbePort, ex.getMessage()));
            alert.showAndWait();
            shutdown();
            return;
        }

        pageHost = new VBox();
        pageHost.setStyle("-fx-background-color:" + BG + ";");
        ScrollPane scroll = new ScrollPane(pageHost);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background:" + BG + ";-fx-background-color:" + BG + ";");

        HBox root = new HBox(buildSidebar(), scroll);
        HBox.setHgrow(scroll, Priority.ALWAYS);
        root.setStyle("-fx-background-color:" + BG + ";" + FONT);

        Scene scene = new Scene(root, 1180, 740);
        java.net.URL css = getClass().getResource("/css/pulsenet-dark.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setTitle("PulseNet");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        navigate("Overview");
        refreshTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        refreshTimeline.setCycleCount(Timeline.INDEFINITE);
        refreshTimeline.play();
    }

    // ---------------------------------------------------------------- layout

    private VBox buildSidebar() {
        Label brand = label("PulseNet", 20, ACCENT, true);
        brand.setPadding(new Insets(0, 0, 18, 12));

        VBox nav = new VBox(4, brand);
        for (String page : List.of("Overview", "Nodes", "Network", "Events")) {
            Button b = new Button(page);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            b.setCursor(Cursor.HAND);
            b.setOnAction(e -> navigate(page));
            navButtons.put(page, b);
            nav.getChildren().add(b);
        }

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Label ports = label(String.format("Listening on%nTCP %d  UDP %d, %d", tcpPort, udpDiscoveryPort,
                udpProbePort), 11, MUTED, false);
        ports.setPadding(new Insets(0, 0, 0, 12));

        VBox sidebar = new VBox(nav, spacer, ports);
        sidebar.setPadding(new Insets(24, 12, 20, 12));
        sidebar.setPrefWidth(190);
        sidebar.setMinWidth(190);
        sidebar.setStyle("-fx-background-color:" + BG + ";-fx-border-color:" + PANEL + ";-fx-border-width:0 1 0 0;");
        return sidebar;
    }

    private void styleNav(String activePage) {
        navButtons.forEach((name, b) -> {
            boolean active = name.equals(activePage);
            b.setStyle("-fx-background-radius:6;-fx-padding:9 12;-fx-font-size:14px;-fx-cursor:hand;"
                    + (active
                            ? "-fx-background-color:" + PANEL + ";-fx-text-fill:" + ACCENT + ";-fx-font-weight:bold;"
                            : "-fx-background-color:transparent;-fx-text-fill:" + MUTED + ";"));
        });
    }

    private void navigate(String page) {
        currentPage = page;
        statValues.clear();
        facts.clear();
        emptyHint = null;
        lastEvents = List.of();
        styleNav("Node".equals(page) ? "Nodes" : page);

        VBox content = new VBox(20);
        content.setPadding(new Insets(28));
        switch (page) {
            case "Nodes" -> content.getChildren().addAll(heading("Nodes"), buildNodesPage());
            case "Node" -> content.getChildren().addAll(buildNodeHeader(), buildNodeDetail());
            case "Network" -> content.getChildren().addAll(heading("Network"), buildNetworkPage());
            case "Events" -> content.getChildren().addAll(heading("Events"), buildEventsPage());
            default -> content.getChildren().addAll(heading("Overview"), buildOverviewPage());
        }
        pageHost.getChildren().setAll(content);
        refreshView();
    }

    // ---------------------------------------------------------------- pages

    private Node buildOverviewPage() {
        HBox stats = new HBox(14);
        for (String name : List.of("Online", "Offline", "CPU", "Memory", "Latency")) {
            VBox card = statCard(name);
            HBox.setHgrow(card, Priority.ALWAYS);
            stats.getChildren().add(card);
        }

        loadChart = createChart(100, "CPU", "Memory");
        latencyChart = createChart(0, "Round trip (ms)");
        VBox load = panel(label("CPU and memory, average %", 13, MUTED, false), loadChart);
        VBox latency = panel(label("Network round-trip time, average ms", 13, MUTED, false), latencyChart);
        HBox charts = new HBox(14, load, latency);
        HBox.setHgrow(load, Priority.ALWAYS);
        HBox.setHgrow(latency, Priority.ALWAYS);
        load.setMaxWidth(Double.MAX_VALUE);
        latency.setMaxWidth(Double.MAX_VALUE);
        load.setPrefWidth(10);
        latency.setPrefWidth(10);

        emptyHint = emptyHint();
        return new VBox(20, stats, charts, emptyHint);
    }

    private Node buildNodesPage() {
        nodesTable = new Table(new String[] { "Node", "Status", "CPU", "Memory", "Disk", "Battery", "Uptime" },
                new double[] { 24, 14, 10, 12, 10, 14, 16 }, id -> {
                    selectedNodeId = id;
                    nodeCpuHistory.clear();
                    nodeMemHistory.clear();
                    navigate("Node");
                });
        emptyHint = emptyHint();
        return new VBox(12, panel(nodesTable.grid), emptyHint);
    }

    private Node buildNetworkPage() {
        networkTable = new Table(new String[] { "Node", "Latency", "Jitter", "Packet loss", "Internet", "Reconnects" },
                new double[] { 24, 16, 14, 16, 16, 14 }, null);
        emptyHint = emptyHint();
        Label note = label("Latency, jitter and loss are measured with UDP probes between each agent and this server.",
                12, MUTED, false);
        return new VBox(12, panel(networkTable.grid), note, emptyHint);
    }

    private Node buildEventsPage() {
        eventList = new VBox(2);
        emptyHint = label("Nothing has happened yet.", 14, MUTED, false);
        return new VBox(12, panel(eventList), emptyHint);
    }

    private Node buildNodeHeader() {
        Button back = new Button("< Nodes");
        back.setCursor(Cursor.HAND);
        back.setStyle("-fx-background-color:transparent;-fx-text-fill:" + ACCENT + ";-fx-font-size:13px;-fx-padding:0;");
        back.setOnAction(e -> navigate("Nodes"));
        detailStatus = label("", 14, MUTED, true);
        HBox row = new HBox(14, heading(selectedNodeId == null ? "Node" : selectedNodeId), detailStatus);
        row.setAlignment(Pos.BASELINE_LEFT);
        return new VBox(6, back, row);
    }

    private Node buildNodeDetail() {
        GridPane grid = new GridPane();
        grid.setHgap(28);
        grid.setVgap(10);
        String[] keys = { "Address", "Host name", "System", "CPU", "Memory", "Disk", "Temperature", "Battery",
                "Processes", "Uptime", "Last seen" };
        for (int i = 0; i < keys.length; i++) {
            Label value = label("-", 14, TEXT, false);
            facts.put(keys[i], value);
            grid.add(label(keys[i], 13, MUTED, false), 0, i);
            grid.add(value, 1, i);
        }
        nodeChart = createChart(100, "CPU", "Memory");
        return new HBox(14, panel(grid), growing(panel(label("CPU and memory, %", 13, MUTED, false), nodeChart)));
    }

    // ---------------------------------------------------------------- refresh

    private void tick() {
        List<NodeRecord> nodes = registry.getAllNodes();
        Summary s = summarize(nodes);
        push(cpuHistory, s.cpu());
        push(memHistory, s.mem());
        if (s.rtt() >= 0) {
            push(rttHistory, s.rtt());
        }
        if (selectedNodeId != null) {
            registry.getNode(selectedNodeId).map(NodeRecord::getLatestPayload).ifPresent(p -> {
                push(nodeCpuHistory, p.getCpuPercent());
                push(nodeMemHistory, p.getMemoryPercent());
            });
        }
        refreshView();
    }

    private void refreshView() {
        if (registry == null) {
            return;
        }
        List<NodeRecord> nodes = new ArrayList<>(registry.getAllNodes());
        nodes.sort(Comparator.comparing(NodeRecord::getNodeId, String.CASE_INSENSITIVE_ORDER));
        switch (currentPage) {
            case "Nodes" -> refreshNodes(nodes);
            case "Node" -> refreshNodeDetail();
            case "Network" -> refreshNetwork(nodes);
            case "Events" -> refreshEvents();
            default -> refreshOverview(nodes);
        }
    }

    private void refreshOverview(List<NodeRecord> nodes) {
        Summary s = summarize(nodes);
        statValues.get("Online").setText(String.valueOf(s.up()));
        Label off = statValues.get("Offline");
        off.setText(String.valueOf(s.down()));
        setColor(off, s.down() > 0 ? BAD : TEXT);
        statValues.get("CPU").setText(percent(s.cpu()));
        statValues.get("Memory").setText(percent(s.mem()));
        statValues.get("Latency").setText(s.rtt() >= 0 ? String.format(Locale.US, "%.1f ms", s.rtt()) : "-");
        plot(loadChart, 0, cpuHistory);
        plot(loadChart, 1, memHistory);
        plot(latencyChart, 0, rttHistory);
        showEmptyHint(nodes.isEmpty());
    }

    private void refreshNodes(List<NodeRecord> nodes) {
        Map<String, Cell[]> rows = new LinkedHashMap<>();
        for (NodeRecord n : nodes) {
            TelemetryPayload p = n.getLatestPayload();
            boolean off = isOffline(n);
            rows.put(n.getNodeId(), new Cell[] {
                    new Cell(n.getNodeId(), TEXT),
                    statusCell(n.getStatus()),
                    off || p == null ? dash() : load(p.getCpuPercent(), NodeRegistry.CPU_WARN_THRESHOLD),
                    off || p == null ? dash() : load(p.getMemoryPercent(), NodeRegistry.MEMORY_WARN_THRESHOLD),
                    off || p == null ? dash() : Cell.of(percent(p.getDiskPercent())),
                    off || p == null ? dash() : Cell.of(battery(p)),
                    off || p == null ? dash() : Cell.of(uptime(p.getUptimeSeconds())) });
        }
        nodesTable.update(rows);
        showEmptyHint(nodes.isEmpty());
    }

    private void refreshNetwork(List<NodeRecord> nodes) {
        Map<String, Cell[]> rows = new LinkedHashMap<>();
        for (NodeRecord n : nodes) {
            NetworkMetrics net = n.getNetworkMetrics();
            InternetConnectivityMetrics inet = n.getInternetMetrics();
            boolean noData = isOffline(n) || net == null || net.getAvgRttMs() < 0;
            String internet = inet == null ? "-" : capitalize(inet.getStatus());
            String internetColor = inet == null ? MUTED
                    : switch (inet.getStatus() == null ? "" : inet.getStatus()) {
                        case InternetConnectivityMetrics.STATUS_ONLINE -> TEXT;
                        case InternetConnectivityMetrics.STATUS_LIMITED -> WARN;
                        case InternetConnectivityMetrics.STATUS_OFFLINE -> BAD;
                        default -> MUTED;
                    };
            rows.put(n.getNodeId(), new Cell[] {
                    Cell.of(n.getNodeId()),
                    noData ? dash() : Cell.of(ms(net.getAvgRttMs())),
                    noData ? dash() : Cell.of(ms(net.getAvgJitterMs())),
                    noData ? dash() : new Cell(percent(net.getPacketLossPercent()),
                            net.getPacketLossPercent() > 0 ? WARN : TEXT),
                    isOffline(n) ? dash() : new Cell(internet, internetColor),
                    net == null ? dash() : Cell.of(String.valueOf(net.getReconnectionCount())) });
        }
        networkTable.update(rows);
        showEmptyHint(nodes.isEmpty());
    }

    private void refreshEvents() {
        List<String> events = new ArrayList<>(registry.getRecentEvents(50));
        java.util.Collections.reverse(events);
        if (!events.equals(lastEvents)) {
            lastEvents = events;
            eventList.getChildren().clear();
            for (String line : events) {
                eventList.getChildren().add(eventRow(line));
            }
        }
        emptyHint.setVisible(events.isEmpty());
        emptyHint.setManaged(events.isEmpty());
    }

    private void refreshNodeDetail() {
        Optional<NodeRecord> found = selectedNodeId == null ? Optional.empty() : registry.getNode(selectedNodeId);
        if (found.isEmpty() || found.get().getLatestPayload() == null) {
            detailStatus.setText("not found");
            return;
        }
        NodeRecord n = found.get();
        TelemetryPayload p = n.getLatestPayload();
        Cell status = statusCell(n.getStatus());
        detailStatus.setText(status.text());
        setColor(detailStatus, status.color());

        long secondsAgo = Math.max(0, (System.currentTimeMillis() - n.getLastSeenTimestamp()) / 1000);
        facts.get("Address").setText(safe(n.getRemoteAddress()));
        facts.get("Host name").setText(safe(p.getHostName()));
        facts.get("System").setText(safe(p.getOperatingSystem()));
        facts.get("CPU").setText(String.format(Locale.US, "%s, %d cores", percent(p.getCpuPercent()),
                p.getCpuCoreCount()));
        facts.get("Memory").setText(String.format(Locale.US, "%s of %s", bytes(p.getUsedMemoryBytes()),
                bytes(p.getTotalMemoryBytes())));
        facts.get("Disk").setText(String.format(Locale.US, "%s used, %s free", percent(p.getDiskPercent()),
                bytes(p.getFreeDiskBytes())));
        facts.get("Temperature").setText(p.getCpuTemperatureCelsius() > 0
                ? String.format(Locale.US, "%.0f C", p.getCpuTemperatureCelsius()) : "not available");
        facts.get("Battery").setText(battery(p));
        facts.get("Processes").setText(String.valueOf(p.getProcessCount()));
        facts.get("Uptime").setText(uptime(p.getUptimeSeconds()));
        facts.get("Last seen").setText(secondsAgo + " s ago");

        plot(nodeChart, 0, nodeCpuHistory);
        plot(nodeChart, 1, nodeMemHistory);
    }

    // ---------------------------------------------------------------- data helpers

    private Summary summarize(List<NodeRecord> nodes) {
        int up = 0;
        int down = 0;
        int measured = 0;
        int probed = 0;
        double cpu = 0;
        double mem = 0;
        double rtt = 0;
        for (NodeRecord n : nodes) {
            if (isOffline(n)) {
                down++;
                continue;
            }
            up++;
            TelemetryPayload p = n.getLatestPayload();
            if (p != null) {
                cpu += p.getCpuPercent();
                mem += p.getMemoryPercent();
                measured++;
            }
            NetworkMetrics net = n.getNetworkMetrics();
            if (net != null && net.getAvgRttMs() >= 0) {
                rtt += net.getAvgRttMs();
                probed++;
            }
        }
        return new Summary(up, down, measured > 0 ? cpu / measured : 0, measured > 0 ? mem / measured : 0,
                probed > 0 ? rtt / probed : -1);
    }

    private static boolean isOffline(NodeRecord n) {
        return n.getStatus() == null || "OFFLINE".equalsIgnoreCase(n.getStatus());
    }

    private static void push(Deque<Double> history, double value) {
        history.addLast(value);
        while (history.size() > HISTORY) {
            history.removeFirst();
        }
    }

    private static Cell statusCell(String status) {
        if (status == null || "OFFLINE".equalsIgnoreCase(status)) {
            return new Cell("Offline", BAD);
        }
        if ("WARNING".equalsIgnoreCase(status)) {
            return new Cell("Warning", WARN);
        }
        return new Cell("Healthy", ACCENT);
    }

    private static Cell load(double pct, double warnAbove) {
        return new Cell(percent(pct), pct > warnAbove ? WARN : TEXT);
    }

    private static Cell dash() {
        return new Cell("-", MUTED);
    }

    private static String battery(TelemetryPayload p) {
        if (!p.isBatteryAvailable() || p.getBatteryPercent() < 0) {
            return "none";
        }
        return percent(p.getBatteryPercent()) + (p.isBatteryCharging() ? " charging" : "");
    }

    private static String percent(double v) {
        return String.format(Locale.US, "%.0f%%", v);
    }

    private static String ms(double v) {
        return v < 0 ? "-" : String.format(Locale.US, "%.1f ms", v);
    }

    private static String bytes(long b) {
        if (b >= 1_000_000_000L) {
            return String.format(Locale.US, "%.1f GB", b / 1_000_000_000.0);
        }
        return String.format(Locale.US, "%.0f MB", b / 1_000_000.0);
    }

    private static String uptime(long seconds) {
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        if (d > 0) {
            return d + "d " + h + "h";
        }
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    /** "ONLINE" becomes "Online". */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return "-";
        }
        return s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- widget helpers

    private static Label label(String text, double size, String color, boolean bold) {
        Label l = new Label(text);
        l.setStyle(String.format(Locale.US, "-fx-text-fill:%s;-fx-font-size:%.0fpx;%s", color, size,
                bold ? "-fx-font-weight:bold;" : ""));
        return l;
    }

    private static void setColor(Label l, String color) {
        String style = l.getStyle().replaceAll("-fx-text-fill:[^;]*;", "");
        l.setStyle("-fx-text-fill:" + color + ";" + style);
    }

    private static Label heading(String text) {
        return label(text, 24, TEXT, true);
    }

    private static VBox panel(Node... children) {
        VBox box = new VBox(12, children);
        box.setPadding(new Insets(16));
        box.setStyle("-fx-background-color:" + PANEL + ";-fx-background-radius:8;");
        return box;
    }

    private static Node growing(VBox box) {
        HBox.setHgrow(box, Priority.ALWAYS);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private VBox statCard(String name) {
        Label value = label("-", 28, TEXT, true);
        statValues.put(name, value);
        VBox card = new VBox(4, label(name, 13, MUTED, false), value);
        card.setPadding(new Insets(14, 16, 14, 16));
        card.setMaxWidth(Double.MAX_VALUE);
        card.setStyle("-fx-background-color:" + PANEL + ";-fx-background-radius:8;");
        return card;
    }

    private Label emptyHint() {
        Label hint = label("No nodes yet. On another machine run:  java -jar pulsenet.jar agent --discover",
                14, MUTED, false);
        hint.setWrapText(true);
        return hint;
    }

    private void showEmptyHint(boolean show) {
        if (emptyHint != null) {
            emptyHint.setVisible(show);
            emptyHint.setManaged(show);
        }
    }

    private Node eventRow(String line) {
        Matcher m = EVENT_LINE.matcher(line);
        String time = "";
        String tag = "SYS";
        String message = line;
        if (m.matches()) {
            time = m.group(1);
            tag = m.group(2);
            message = m.group(3);
        }
        String color = switch (tag) {
            case "WARN" -> WARN;
            case "LOST" -> BAD;
            case "OK", "REC" -> ACCENT;
            default -> MUTED;
        };
        Label dot = label("\u25CF", 10, color, false);
        Label when = label(time, 13, MUTED, false);
        when.setMinWidth(64);
        HBox row = new HBox(10, dot, when, label(message, 14, TEXT, false));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(7, 0, 7, 0));
        return row;
    }

    private static LineChart<Number, Number> createChart(double max, String... names) {
        NumberAxis x = new NumberAxis(0, HISTORY - 1, 10);
        x.setAutoRanging(false);
        x.setTickLabelsVisible(false);
        x.setTickMarkVisible(false);
        x.setMinorTickVisible(false);
        NumberAxis y = new NumberAxis();
        y.setMinorTickVisible(false);
        y.setForceZeroInRange(true);
        if (max > 0) {
            y.setAutoRanging(false);
            y.setLowerBound(0);
            y.setUpperBound(max);
            y.setTickUnit(max / 4);
        }
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setLegendVisible(names.length > 1);
        chart.setVerticalGridLinesVisible(false);
        chart.setHorizontalZeroLineVisible(false);
        chart.setMinHeight(230);
        chart.setPrefHeight(230);
        for (String name : names) {
            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            series.setName(name);
            chart.getData().add(series);
        }
        return chart;
    }

    /** Replaces one chart line with the history, right-aligned so new samples appear on the right. */
    private static void plot(LineChart<Number, Number> chart, int series, Collection<Double> values) {
        List<XYChart.Data<Number, Number>> points = new ArrayList<>(values.size());
        int i = HISTORY - values.size();
        for (double v : values) {
            points.add(new XYChart.Data<>(i++, v));
        }
        chart.getData().get(series).getData().setAll(points);
    }

    /** Grid-based table whose rows are only rebuilt when the set of nodes changes, so clicks stay reliable. */
    private static final class Table {
        final GridPane grid = new GridPane();
        private final String[] headers;
        private final Consumer<String> onClick;
        private List<String> ids = List.of();
        private final Map<String, Label[]> cells = new HashMap<>();

        Table(String[] headers, double[] widths, Consumer<String> onClick) {
            this.headers = headers;
            this.onClick = onClick;
            for (double w : widths) {
                ColumnConstraints c = new ColumnConstraints();
                c.setPercentWidth(w);
                grid.getColumnConstraints().add(c);
            }
            rebuild(List.of());
        }

        void update(Map<String, Cell[]> rows) {
            List<String> keys = new ArrayList<>(rows.keySet());
            if (!keys.equals(ids)) {
                rebuild(keys);
            }
            rows.forEach((id, row) -> {
                Label[] labels = cells.get(id);
                for (int i = 0; i < labels.length; i++) {
                    labels[i].setText(row[i].text());
                    labels[i].setStyle(cellStyle(row[i].color(), false));
                }
            });
        }

        private void rebuild(List<String> keys) {
            ids = keys;
            cells.clear();
            grid.getChildren().clear();
            for (int c = 0; c < headers.length; c++) {
                Label h = new Label(headers[c]);
                h.setStyle(cellStyle(MUTED, true));
                grid.add(h, c, 0);
            }
            for (int r = 0; r < keys.size(); r++) {
                Label[] row = new Label[headers.length];
                for (int c = 0; c < headers.length; c++) {
                    Label l = new Label();
                    l.setMaxWidth(Double.MAX_VALUE);
                    GridPane.setHgrow(l, Priority.ALWAYS);
                    if (onClick != null) {
                        String id = keys.get(r);
                        l.setCursor(Cursor.HAND);
                        l.setOnMouseClicked(e -> onClick.accept(id));
                    }
                    row[c] = l;
                    grid.add(l, c, r + 1);
                }
                cells.put(keys.get(r), row);
            }
        }

        private static String cellStyle(String color, boolean header) {
            return "-fx-text-fill:" + color + ";-fx-font-size:" + (header ? 12 : 14) + "px;"
                    + "-fx-padding:" + (header ? "0 12 10 0" : "11 12 11 0") + ";"
                    + "-fx-max-width:infinity;"
                    + (header ? "" : "-fx-border-color:transparent transparent rgba(58,80,107,0.45) transparent;");
        }
    }

    // ---------------------------------------------------------------- shutdown

    @Override
    public void stop() {
        shutdown();
    }

    private void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        LOG.info("PulseNet dashboard shutting down...");
        if (refreshTimeline != null) {
            refreshTimeline.stop();
        }
        if (watchdog != null) {
            watchdog.stop();
        }
        if (discoveryServer != null) {
            discoveryServer.stop();
        }
        if (probeServer != null) {
            probeServer.stop();
        }
        if (server != null) {
            server.stop();
        }
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
