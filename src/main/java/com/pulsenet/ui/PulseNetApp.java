package com.pulsenet.ui;

import com.pulsenet.common.model.InternetConnectivityMetrics;
import com.pulsenet.common.model.NetworkMetrics;
import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.common.net.NetworkUtils;
import com.pulsenet.common.net.NetworkUtils.LanAddress;
import com.pulsenet.server.ServerMain;
import com.pulsenet.server.discovery.DiscoveryServer;
import com.pulsenet.server.network.NetworkProbeServer;
import com.pulsenet.server.network.TelemetryServer;
import com.pulsenet.server.registry.NodeRecord;
import com.pulsenet.server.registry.NodeRegistry;
import com.pulsenet.server.watchdog.WatchdogSupervisor;
import com.pulsenet.ui.NodeHistory.Metric;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PulseNet Complete Unified Network Operations Center (NOC) & Real-Time
 * Telemetry Dashboard.
 *
 * <p>
 * Integrates 100% of the features from both branches:
 * <ul>
 * <li><b>Yashodha Branch Features:</b> Multi-protocol backend engine (TCP
 * :9000, UDP :9001 Discovery,
 * UDP :9002 Echo Probes), advanced network telemetry (Current/Avg/Min/Max RTT,
 * RFC 3550 Jitter,
 * Dual Rolling/Cumulative Packet Loss, Probe packet counters), fleet-wide WAN
 * Internet diagnostics
 * (Gateway IP & ICMP reachability, DNS query timing, Google HTTPS transit), and
 * real-time trend graphs.</li>
 * <li><b>Main Branch Features (Chathumi & Pictures):</b> Full hardware system
 * monitoring (OSHI),
 * Server Fleet with exact cards matching reference pictures (CPU Cores, RAM
 * bytes, Disk free/used,
 * Battery % & charging status, Download/Upload bandwidth, OS, Uptime), circular
 * donut ring gauges
 * (Network Health, CPU Usage, Memory Usage, Disk Capacity), interactive
 * topology map,
 * capacity planning, SLA compliance reporting, and deep node drill-down.</li>
 * </ul>
 */
public class PulseNetApp extends Application {

    private static final Logger LOG = Logger.getLogger(PulseNetApp.class.getName());

    // High-contrast, crystal-clear NOC color palette
    private static final String BG_COLOR = "#0A0E17";
    private static final String SIDEBAR_COLOR = "#0D131F";
    private static final String PANEL_BG = "#131C2D";
    private static final String PANEL_ALT = "#19243A";
    private static final String BORDER_COLOR = "#22334C";
    private static final String WHITE_TEXT = "#FFFFFF";
    private static final String MUTED_TEXT = "#90A4AE";
    private static final String SUBTLE_TEXT = "#607D8B";
    private static final String CYAN_ACCENT = "#00E5FF";
    private static final String BLUE_ACCENT = "#2979FF";
    private static final String GREEN_ACCENT = "#00E676";
    private static final String YELLOW_ACCENT = "#FFD600";
    private static final String RED_ACCENT = "#FF1744";
    private static final String PURPLE_ACCENT = "#C084FC";
    private static final String ORANGE_ACCENT = "#FF9100";

    private UiOptions options;

    private NodeRegistry registry;
    private TelemetryServer server;
    private DiscoveryServer discoveryServer;
    private NetworkProbeServer probeServer;
    private WatchdogSupervisor watchdog;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /** Enumerating adapters can take a while, so it happens in the background. */
    private volatile List<LanAddress> lanAddresses = List.of();
    private ScheduledExecutorService lanScanner;

    private ScrollPane scrollPane;
    private VBox contentArea;
    private Label pageTitle;
    private Label pageSubtitle;
    private Label serverChipText;
    private Label serverChipDot;
    private Label serverOnlineBadge;
    private Timeline refreshTimeline;
    private Page currentPage = Page.OVERVIEW;
    private final Map<Page, Button> navButtons = new EnumMap<>(Page.class);
    private String selectedNodeId = null;
    private String alertFilter = "ALL";

    private enum Page {
        OVERVIEW, NODES, NODE_DETAILS, NETWORK_FLEET, DIAGNOSTICS, TOPOLOGY, INTERNET, ALERTS, CAPACITY, SLA, SETTINGS
    }

    // Overview KPIs
    private Label upValue, downValue, avgRttValue, avgJitterValue, packetLossValue, internetStatusValue;
    private Label networkValue, cpuValue, memoryValue, diskValue, forecastLabel, deviceCountLabel;
    private Circle networkArc, cpuArc, memoryArc, diskArc;
    private Label batteryVal, batteryFootVal, downloadSpeedVal, uploadSpeedVal;
    private VBox inventoryBoxList, eventFeedBox;
    private TrendChart hostLoadChart, latencyChart, throughputChart;

    // Node details
    private VBox detailInfoBox, detailProbeBox;
    private TrendChart detailCpuChart, detailMemChart, detailThroughputChart, detailLatencyChart;

    // Topology
    private Pane topologyPane;
    private String topologySignature = "";
    private boolean topologyCompact;

    // One-per-second samples, recorded whichever page is open
    private final NodeHistory fleetHistory = new NodeHistory();
    private final Map<String, NodeHistory> nodeHistories = new HashMap<>();

    @Override
    public void init() {
        Parameters params = getParameters();
        options = UiOptions.parse(params != null ? params.getRaw().toArray(new String[0]) : new String[0]);
    }

    @Override
    public void start(Stage stage) {
        registry = new NodeRegistry();

        long readTimeoutMillis = ServerMain.resolveReadTimeoutMillis(-1, options.watchdogTimeout());
        server = new TelemetryServer(options.host(), options.tcpPort(), registry, readTimeoutMillis);
        discoveryServer = new DiscoveryServer("0.0.0.0", options.discoveryPort(), options.host(), options.tcpPort(),
                options.probePort(), options.serverId());
        probeServer = new NetworkProbeServer("0.0.0.0", options.probePort());
        watchdog = new WatchdogSupervisor(registry, options.watchdogTimeout());

        try {
            server.start();
        } catch (IOException | RuntimeException ex) {
            LOG.log(Level.SEVERE, "Could not start the telemetry server", ex);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("PulseNet Startup Error");
            alert.setHeaderText("Cannot listen on TCP port " + options.tcpPort());
            alert.setContentText("Another PulseNet server may already be running on this computer. Close it, or start "
                    + "this one with a different --port.\n\n" + ex.getMessage());
            alert.showAndWait();
            shutdown();
            return;
        }
        watchdog.start();
        // Agents can still connect by address without UDP, so these failures are reported, not fatal.
        try {
            discoveryServer.start();
        } catch (Exception ex) {
            reportUdpFailure("Discovery", options.discoveryPort(), "agents cannot find this server automatically", ex);
        }
        try {
            probeServer.start();
        } catch (Exception ex) {
            reportUdpFailure("Probe", options.probePort(), "latency and packet loss cannot be measured", ex);
        }

        lanScanner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PulseNet-LAN-Scanner");
            t.setDaemon(true);
            return t;
        });
        lanScanner.scheduleWithFixedDelay(this::scanLanAddresses, 0, 10, TimeUnit.SECONDS);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:" + BG_COLOR + ";");
        root.setLeft(createSidebar());

        VBox mainContainer = new VBox(20);
        mainContainer.setPadding(new Insets(24, 28, 28, 28));
        mainContainer.setStyle("-fx-background-color:" + BG_COLOR + ";");
        mainContainer.getChildren().add(createHeader());

        contentArea = new VBox(20);
        mainContainer.getChildren().add(contentArea);

        scrollPane = new ScrollPane(mainContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle(
                "-fx-background:" + BG_COLOR + ";-fx-background-color:" + BG_COLOR + ";-fx-border-color:transparent;");
        root.setCenter(scrollPane);

        showPage(Page.OVERVIEW);

        Scene scene = new Scene(root, 1420, 860);
        java.net.URL cssUrl = getClass().getResource("/css/pulsenet-dark.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }
        stage.setTitle("PulseNet | Network Operations Center & Real-Time Telemetry Monitor");
        stage.setScene(scene);
        stage.setMaximized(true);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        // 1-second dynamic UI refresh timer
        refreshTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
        refreshTimeline.setCycleCount(Timeline.INDEFINITE);
        refreshTimeline.play();
        tick();
    }

    private void reportUdpFailure(String service, int port, String consequence, Exception ex) {
        LOG.log(Level.WARNING, service + " service could not listen on UDP port " + port, ex);
        registry.logEvent(String.format("[FAIL] %s service could not use UDP port %d: %s (%s)",
                service, port, consequence, ex.getMessage()));
    }

    private void scanLanAddresses() {
        try {
            lanAddresses = NetworkUtils.listLanIPv4Addresses();
        } catch (RuntimeException ex) {
            LOG.log(Level.FINE, "LAN address scan failed", ex);
        }
    }

    // ================= REFRESH =================

    /** Runs once per second on the JavaFX thread. */
    private void tick() {
        List<NodeRecord> nodes = registry.getAllNodes();
        recordHistory(nodes);
        refreshHeader();
        refreshCurrentView(nodes);
    }

    private void recordHistory(List<NodeRecord> nodes) {
        FleetSummary fleet = FleetSummary.of(nodes);
        fleetHistory.add(Metric.CPU, fleet.cpu());
        fleetHistory.add(Metric.MEMORY, fleet.memory());
        fleetHistory.add(Metric.RTT, fleet.rtt());
        fleetHistory.add(Metric.JITTER, fleet.jitter());
        fleetHistory.add(Metric.DOWNLOAD, fleet.online() > 0 ? fleet.download() / 1000.0 : Double.NaN);
        fleetHistory.add(Metric.UPLOAD, fleet.online() > 0 ? fleet.upload() / 1000.0 : Double.NaN);

        for (NodeRecord node : nodes) {
            NodeHistory history = nodeHistories.computeIfAbsent(node.getNodeId(), id -> new NodeHistory());
            boolean offline = FleetSummary.isOffline(node);
            history.addAvailability(!offline);
            TelemetryPayload p = offline ? null : node.getLatestPayload();
            NetworkMetrics net = offline ? null : node.getNetworkMetrics();
            history.add(Metric.CPU, p != null ? p.getCpuPercent() : Double.NaN);
            history.add(Metric.MEMORY, p != null ? p.getMemoryPercent() : Double.NaN);
            history.add(Metric.DOWNLOAD, p != null ? p.getDownloadBytesPerSecond() / 1000.0 : Double.NaN);
            history.add(Metric.UPLOAD, p != null ? p.getUploadBytesPerSecond() / 1000.0 : Double.NaN);
            history.add(Metric.RTT, net != null && net.getCurrentRttMs() >= 0 ? net.getCurrentRttMs() : Double.NaN);
            history.add(Metric.JITTER, net != null && net.getAvgJitterMs() >= 0 ? net.getAvgJitterMs() : Double.NaN);
        }
    }

    private void refreshCurrentView(List<NodeRecord> nodes) {
        switch (currentPage) {
            case OVERVIEW -> refreshOverview(nodes);
            case NODE_DETAILS -> refreshNodeDetails();
            case TOPOLOGY -> refreshTopology(nodes);
            case NODES -> patch(contentArea, buildServerFleet(nodes));
            case NETWORK_FLEET -> patch(contentArea, buildNetworkFleet(nodes));
            case DIAGNOSTICS -> patch(contentArea, buildDiagnostics(nodes));
            case INTERNET -> patch(contentArea, buildInternetHealth(nodes));
            case ALERTS -> patch(contentArea, buildAlerts());
            case CAPACITY -> patch(contentArea, buildCapacity(nodes));
            case SLA -> patch(contentArea, buildReports(nodes));
            case SETTINGS -> patch(contentArea, buildSettings());
        }
    }

    /**
     * Opens a page from the top and fills it with current data.
     */
    private void showPage(Page page) {
        currentPage = page;
        Page navPage = page == Page.NODE_DETAILS ? Page.NODES : page;
        navButtons.forEach((p, button) -> button.setStyle(getNavButtonStyle(p == navPage)));
        contentArea.getChildren().clear();
        scrollPane.setVvalue(0);

        switch (page) {
            case OVERVIEW -> {
                updateHeading("System Overview",
                        "Real-time distributed infrastructure monitoring & advanced network telemetry");
                buildOverview();
            }
            case NODES -> updateHeading("Node Management", "View individual computer telemetry and hardware details");
            case NODE_DETAILS -> {
                updateHeading(selectedNodeId, "Live telemetry, hardware and network details of this node");
                buildNodeDetails();
            }
            case NETWORK_FLEET -> updateHeading("Network Fleet",
                    "RTT Latency, RFC 3550 Jitter, UDP Packet Loss, and Agent Probing");
            case DIAGNOSTICS -> updateHeading("Network Diagnostics",
                    "Fleet RTT Latency Rankings, Jitter Variance, and Packet Loss");
            case TOPOLOGY -> {
                updateHeading("Network Topology", "Interactive Aggregator Hub and Connected Agents Map");
                buildTopology();
            }
            case INTERNET -> updateHeading("Internet & WAN Health",
                    "Fleet-Wide Default Gateway, DNS Resolution, and External HTTPS");
            case ALERTS -> updateHeading("Alerts & Audit Log",
                    "Watchdog Events, Connection Failures, and System Notifications");
            case CAPACITY -> updateHeading("Capacity Planning", "Aggregated Cluster Compute, RAM & Storage Headroom");
            case SLA -> updateHeading("SLA & System Reports", "Measured availability & protocol specifications");
            case SETTINGS -> updateHeading("Settings & Ports",
                    "Server Configuration, Listening Endpoints & Local Interfaces");
        }
        refreshCurrentView(registry.getAllNodes());
    }

    private void showNodeDetails(String nodeId) {
        selectedNodeId = nodeId;
        showPage(Page.NODE_DETAILS);
    }

    /**
     * Updates {@code target} to look like {@code fresh} while keeping the existing controls wherever
     * the structure matches. Rebuilding them every second would reset the scroll position and lose
     * clicks that are in progress.
     */
    private static void patch(Pane target, List<? extends Node> fresh) {
        List<Node> replacement = new ArrayList<>(fresh);
        List<Node> existing = target.getChildren();
        for (int i = 0; i < replacement.size(); i++) {
            Node wanted = replacement.get(i);
            if (i >= existing.size()) {
                existing.add(wanted);
            } else if (!patchNode(existing.get(i), wanted)) {
                existing.set(i, wanted);
            }
        }
        if (existing.size() > replacement.size()) {
            existing.subList(replacement.size(), existing.size()).clear();
        }
    }

    /** @return false if {@code current} cannot be turned into {@code wanted} and must be replaced */
    private static boolean patchNode(Node current, Node wanted) {
        if (current.getClass() != wanted.getClass()) {
            return false;
        }
        if (!Objects.equals(current.getStyle(), wanted.getStyle())) {
            current.setStyle(wanted.getStyle());
        }
        if (current instanceof Labeled label) {
            String text = ((Labeled) wanted).getText();
            if (!Objects.equals(label.getText(), text)) {
                label.setText(text);
            }
            label.setWrapText(((Labeled) wanted).isWrapText());
            if (current instanceof Button button) {
                button.setOnAction(((Button) wanted).getOnAction());
            }
            return true;
        }
        if (current instanceof Pane pane) {
            pane.setPadding(((Pane) wanted).getPadding());
            if (pane instanceof VBox box) {
                box.setAlignment(((VBox) wanted).getAlignment());
            } else if (pane instanceof HBox box) {
                box.setAlignment(((HBox) wanted).getAlignment());
            }
            patch(pane, ((Pane) wanted).getChildren());
        }
        return true;
    }

    // ================= SIDEBAR NAVIGATION =================

    private VBox createSidebar() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(26, 16, 22, 16));
        box.setPrefWidth(240);
        box.setMinWidth(240);
        box.setStyle("-fx-background-color:" + SIDEBAR_COLOR + ";-fx-border-color:transparent " + BORDER_COLOR
                + " transparent transparent;");

        // Brand logo
        VBox brandBox = new VBox(3);
        Label brandTitle = new Label("PulseNet");
        brandTitle.setStyle("-fx-text-fill:" + GREEN_ACCENT + ";-fx-font-size:24px;-fx-font-weight:bold;");
        Label groupLabel = new Label("SYSTEM MONITORING");
        groupLabel.setStyle("-fx-text-fill:" + SUBTLE_TEXT + ";-fx-font-size:10px;-fx-font-weight:bold;");
        groupLabel.setPadding(new Insets(14, 0, 8, 4));
        brandBox.getChildren().addAll(brandTitle, groupLabel);

        box.getChildren().addAll(
                brandBox,
                createNavButton("◈", "Overview", Page.OVERVIEW),
                createNavButton("▣", "Nodes (Server Fleet)", Page.NODES),
                createNavButton("⌁", "Network Fleet", Page.NETWORK_FLEET),
                createNavButton("📈", "Diagnostics", Page.DIAGNOSTICS),
                createNavButton("⌘", "Topology", Page.TOPOLOGY),
                createNavButton("🌐", "Internet & WAN", Page.INTERNET),
                createNavButton("⚠", "Alerts & Events", Page.ALERTS),
                createNavButton("▥", "Capacity Planning", Page.CAPACITY),
                createNavButton("▧", "SLA Reports", Page.SLA),
                createNavButton("⚙", "Settings & Ports", Page.SETTINGS));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Label footerVersion = new Label("PulseNet v2.0  •  Java");
        footerVersion.setStyle("-fx-text-fill:" + SUBTLE_TEXT + ";-fx-font-size:11px;");
        footerVersion.setPadding(new Insets(6, 4, 6, 4));

        box.getChildren().addAll(spacer, footerVersion);
        return box;
    }

    private Button createNavButton(String icon, String text, Page page) {
        Button b = new Button(icon + "   " + text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setPadding(new Insets(10, 14, 10, 14));
        b.setMnemonicParsing(false);
        b.setStyle(getNavButtonStyle(false));
        b.setOnAction(e -> showPage(page));
        navButtons.put(page, b);
        return b;
    }

    private String getNavButtonStyle(boolean active) {
        return "-fx-background-color:" + (active ? "#13233c" : "transparent") +
                ";-fx-text-fill:" + (active ? GREEN_ACCENT : "#94a3b8") +
                ";-fx-font-size:12.5px;-fx-font-weight:" + (active ? "bold" : "normal") +
                ";-fx-background-radius:8;-fx-border-color:" + (active ? GREEN_ACCENT : "transparent") +
                ";-fx-border-width:0 0 0 3;-fx-border-radius:8;-fx-cursor:hand;";
    }

    // ================= HEADER =================

    private HBox createHeader() {
        VBox titles = new VBox(3);
        pageTitle = new Label();
        pageTitle.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:26px;-fx-font-weight:bold;");
        pageSubtitle = new Label();
        pageSubtitle.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:12px;");
        titles.getChildren().addAll(pageTitle, pageSubtitle);
        titles.setMinWidth(230);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Server chip: the address agents should connect to
        HBox serverChip = new HBox(8);
        serverChip.setAlignment(Pos.CENTER);
        serverChip.setMinWidth(Region.USE_PREF_SIZE);
        serverChip.setMaxHeight(Region.USE_PREF_SIZE);
        serverChip.setPadding(new Insets(8, 16, 8, 16));
        serverChip.setStyle("-fx-background-color:#072B28;-fx-border-color:" + GREEN_ACCENT
                + ";-fx-border-radius:18;-fx-background-radius:18;-fx-border-width:1.5;");
        serverChipDot = new Label("●");
        serverChipText = new Label();
        serverChipText.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:11px;-fx-font-weight:bold;");
        serverChip.getChildren().addAll(serverChipDot, serverChipText);

        Button viewOverviewBtn = createHeaderButton("◈  OVERVIEW", "#131C2D", Page.OVERVIEW);
        Button viewNodesBtn = createHeaderButton("▣  NODES", "#131C2D", Page.NODES);
        Button viewFleetBtn = createHeaderButton("⌁  NETWORK FLEET", BLUE_ACCENT, Page.NETWORK_FLEET);

        serverOnlineBadge = new Label("● SERVER ONLINE");
        serverOnlineBadge.setMinWidth(Region.USE_PREF_SIZE);
        serverOnlineBadge.setPadding(new Insets(8, 18, 8, 18));

        HBox h = new HBox(12, titles, spacer, serverChip, viewOverviewBtn, viewNodesBtn, viewFleetBtn,
                serverOnlineBadge);
        h.setAlignment(Pos.CENTER_LEFT);
        // On a narrow window the title needs the room; the sidebar offers the same pages
        for (Button shortcut : List.of(viewOverviewBtn, viewNodesBtn, viewFleetBtn)) {
            shortcut.visibleProperty().bind(h.widthProperty().greaterThan(1150));
            shortcut.managedProperty().bind(shortcut.visibleProperty());
        }
        refreshHeader();
        return h;
    }

    private Button createHeaderButton(String text, String background, Page page) {
        Button b = new Button(text);
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setStyle("-fx-background-color:" + background + ";-fx-border-color:" + BORDER_COLOR
                + ";-fx-border-radius:18;-fx-text-fill:white;-fx-font-size:11px;-fx-font-weight:bold;-fx-background-radius:18;-fx-padding:8 14 8 14;-fx-cursor:hand;");
        b.setOnAction(e -> showPage(page));
        return b;
    }

    private void refreshHeader() {
        boolean running = server.isRunning();
        boolean degraded = running && !(discoveryServer.isRunning() && probeServer.isRunning());
        serverChipText.setText("AGENTS CONNECT TO  " + connectAddress());
        serverChipDot.setStyle("-fx-text-fill:" + (!running ? RED_ACCENT : degraded ? YELLOW_ACCENT : GREEN_ACCENT)
                + ";-fx-font-size:13px;");
        serverOnlineBadge.setText(!running ? "● SERVER OFFLINE" : degraded ? "● SERVER DEGRADED" : "● SERVER ONLINE");
        String colors = !running
                ? "-fx-background-color:#4c0519;-fx-text-fill:#fb7185;-fx-border-color:#e11d48;"
                : degraded
                ? "-fx-background-color:#422006;-fx-text-fill:#fde047;-fx-border-color:#ca8a04;"
                : "-fx-background-color:#064e3b;-fx-text-fill:#34d399;-fx-border-color:#059669;";
        serverOnlineBadge.setStyle(colors
                + "-fx-border-radius:20;-fx-background-radius:20;-fx-font-size:11px;-fx-font-weight:bold;");
    }

    /** The address other laptops should use, falling back to loopback when there is no network. */
    private String connectAddress() {
        String host = options.host();
        if (NetworkUtils.isWildcard(host)) {
            List<LanAddress> addresses = lanAddresses;
            host = addresses.isEmpty() ? "127.0.0.1" : addresses.get(0).address();
        }
        return host + ":" + options.tcpPort();
    }

    private void updateHeading(String title, String subtitle) {
        pageTitle.setText(title);
        pageSubtitle.setText(subtitle);
    }

    // ================= VIEW 1: COMPLETE NOC OVERVIEW =================

    private void buildOverview() {
        // 1. Top KPI Row (6 cards: Nodes Online, Nodes Offline, Avg RTT, Avg Jitter,
        // Rolling Loss %, Internet Access)
        HBox kpiRow = new HBox(14);
        upValue = createKpiCard(kpiRow, "NODES ONLINE", GREEN_ACCENT, "✓", "0", "Operational devices");
        downValue = createKpiCard(kpiRow, "NODES OFFLINE", RED_ACCENT, "×", "0", "Disconnected devices");
        avgRttValue = createKpiCard(kpiRow, "AVG RTT LATENCY", CYAN_ACCENT, "⌁", "N/A", "UDP probe round-trip");
        avgJitterValue = createKpiCard(kpiRow, "AVG JITTER (RFC 3550)", YELLOW_ACCENT, "△", "N/A",
                "Statistical packet jitter");
        packetLossValue = createKpiCard(kpiRow, "ROLLING LOSS %", GREEN_ACCENT, "◌", "N/A", "50-probe sliding window");
        internetStatusValue = createKpiCard(kpiRow, "INTERNET ACCESS", GREEN_ACCENT, "🌐", "N/A",
                "Gateway & DNS transit");

        // 2. Circular Donut Ring Gauges Row + Forecast Box (4 Gauges + Sizing Box)
        Label resourceHeading = createSectionTitle("Resource Utilization & Network Health");
        HBox gaugeRow = new HBox(14);
        HBox.setHgrow(gaugeRow, Priority.ALWAYS);

        VBox netGaugeBox = createDonutGauge("NETWORK HEALTH", GREEN_ACCENT, "⌁", "Share of nodes online");
        VBox cpuGaugeBox = createDonutGauge("CPU USAGE", CYAN_ACCENT, "◉", "Average online CPU utilization");
        VBox memGaugeBox = createDonutGauge("MEMORY USAGE", BLUE_ACCENT, "▤", "Average online RAM utilization");
        VBox diskGaugeBox = createDonutGauge("DISK USAGE", PURPLE_ACCENT, "◌", "Average online storage utilization");

        networkValue = getDonutValueLabel(netGaugeBox);
        cpuValue = getDonutValueLabel(cpuGaugeBox);
        memoryValue = getDonutValueLabel(memGaugeBox);
        diskValue = getDonutValueLabel(diskGaugeBox);

        networkArc = getDonutArcCircle(netGaugeBox);
        cpuArc = getDonutArcCircle(cpuGaugeBox);
        memoryArc = getDonutArcCircle(memGaugeBox);
        diskArc = getDonutArcCircle(diskGaugeBox);

        VBox forecastBox = new VBox(10);
        forecastBox.setPadding(new Insets(18));
        forecastBox.setMinWidth(190);
        forecastBox.setPrefWidth(290);
        forecastBox.setStyle(getPanelStyle());
        forecastBox.getChildren().add(createSectionTitle("◈  FLEET STATUS"));
        forecastLabel = new Label("Waiting for telemetry from monitored devices.");
        forecastLabel.setWrapText(true);
        forecastLabel.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:11px;");
        deviceCountLabel = new Label();
        deviceCountLabel.setStyle("-fx-text-fill:" + CYAN_ACCENT + ";-fx-font-size:10px;-fx-font-weight:bold;");
        forecastBox.getChildren().addAll(forecastLabel, deviceCountLabel);

        gaugeRow.getChildren().addAll(netGaugeBox, cpuGaugeBox, memGaugeBox, diskGaugeBox, forecastBox);

        // 3. Network and Battery Row (BATTERY, DOWNLOAD, UPLOAD)
        Label netBatteryHeading = createSectionTitle("Network and Battery");
        HBox netBatteryRow = new HBox(14);
        VBox batteryCard = createStatCard("BATTERY", "N/A", "Waiting for agents...", GREEN_ACCENT);
        VBox downloadCard = createStatCard("DOWNLOAD", "N/A", "Incoming traffic, all online nodes", BLUE_ACCENT);
        VBox uploadCard = createStatCard("UPLOAD", "N/A", "Outgoing traffic, all online nodes", PURPLE_ACCENT);
        batteryVal = (Label) batteryCard.getChildren().get(1);
        batteryFootVal = (Label) batteryCard.getChildren().get(2);
        downloadSpeedVal = (Label) downloadCard.getChildren().get(1);
        uploadSpeedVal = (Label) uploadCard.getChildren().get(1);
        netBatteryRow.getChildren().addAll(batteryCard, downloadCard, uploadCard);

        // 4. Real-Time Graphs & Trend Performance
        Label chartsHeading = createSectionTitle("Real-Time Graphs & Trend Performance");
        hostLoadChart = new TrendChart("AVERAGE SYSTEM LOAD (%)", "Usage %", true, 240, "CPU Load", "Memory");
        latencyChart = new TrendChart("AVERAGE NETWORK RTT LATENCY & JITTER (ms)", "Latency (ms)", false, 240,
                "Avg RTT (ms)", "Avg Jitter (ms)");
        throughputChart = new TrendChart("TOTAL NETWORK THROUGHPUT (DOWNLOAD & UPLOAD SPEED - KB/s)", "Speed (KB/s)",
                false, 220, "Download (Incoming)", "Upload (Outgoing)");
        HBox row1Charts = new HBox(14, hostLoadChart.box, latencyChart.box);

        // 5. Fleet Inventory & Network Status, next to the live topology map
        Label invHeading = createSectionTitle("Fleet Inventory & Live Topology");
        VBox invPanel = new VBox(10);
        invPanel.setStyle(getPanelStyle());
        invPanel.setPadding(new Insets(16));
        inventoryBoxList = new VBox(8);
        invPanel.getChildren().add(inventoryBoxList);
        GridPane fleetRow = createResponsiveRow(invPanel, createTopologyPanel("Infrastructure Topology Map", true));

        // 6. Recent Activity & Watchdog Events Log
        Label actHeading = createSectionTitle("Watchdog & Real-Time Connection Events");
        VBox actPanel = new VBox(10);
        actPanel.setStyle(getPanelStyle());
        actPanel.setPadding(new Insets(16));
        eventFeedBox = new VBox(6);
        actPanel.getChildren().add(eventFeedBox);

        contentArea.getChildren().addAll(
                kpiRow,
                resourceHeading,
                gaugeRow,
                netBatteryHeading,
                netBatteryRow,
                chartsHeading,
                row1Charts,
                throughputChart.box,
                invHeading,
                fleetRow,
                actHeading,
                actPanel);
    }

    private void refreshOverview(List<NodeRecord> nodes) {
        FleetSummary fleet = FleetSummary.of(nodes);

        // KPIs
        upValue.setText(String.valueOf(fleet.online()));
        downValue.setText(String.valueOf(fleet.offline()));
        avgRttValue.setText(formatMs(fleet.rtt()));
        avgJitterValue.setText(formatMs(fleet.jitter()));
        packetLossValue.setText(formatPercent(fleet.loss()));
        setTextColor(packetLossValue, fleet.loss() > 0 ? RED_ACCENT : GREEN_ACCENT);
        internetStatusValue.setText(fleet.internetReporting() == 0 ? "N/A"
                : fleet.internetOnline() + "/" + fleet.internetReporting() + " ONLINE");
        setTextColor(internetStatusValue, fleet.internetReporting() == 0 ? MUTED_TEXT
                : fleet.internetOnline() == fleet.internetReporting() ? GREEN_ACCENT
                : fleet.internetOnline() > 0 ? YELLOW_ACCENT : RED_ACCENT);

        // Circular gauges
        setDonutPercent(networkArc, networkValue,
                fleet.total() > 0 ? fleet.online() * 100.0 / fleet.total() : Double.NaN);
        setDonutPercent(cpuArc, cpuValue, fleet.cpu());
        setDonutPercent(memoryArc, memoryValue, fleet.memory());
        setDonutPercent(diskArc, diskValue, fleet.disk());

        // Fleet status box
        if (fleet.total() == 0) {
            forecastLabel.setText("No agents are registered yet. Start an agent on another laptop, or point it at "
                    + connectAddress() + ".");
        } else if (fleet.offline() > 0) {
            forecastLabel.setText(String.format(Locale.US, "Alert: %d node(s) OFFLINE out of %d registered devices.",
                    fleet.offline(), fleet.total()));
        } else if (fleet.warnings() > 0) {
            forecastLabel.setText(String.format(Locale.US, "%d of %d node(s) report WARNING (high CPU, memory or disk).",
                    fleet.warnings(), fleet.total()));
        } else {
            forecastLabel.setText(String.format(Locale.US, "All %d node(s) reporting live telemetry. RTT: %s. Loss: %s.",
                    fleet.online(), avgRttValue.getText(), packetLossValue.getText()));
        }
        deviceCountLabel.setText("REGISTERED DEVICES:  " + fleet.total());

        // Network and Battery
        if (fleet.batteryNodes() > 0) {
            batteryVal.setText(formatPercent(fleet.battery()));
            batteryFootVal.setText(fleet.batteryNodes() + " battery-enabled node(s)");
        } else if (fleet.online() > 0) {
            batteryVal.setText("Unavailable");
            batteryFootVal.setText("No battery-enabled node(s)");
        } else {
            batteryVal.setText("N/A");
            batteryFootVal.setText(fleet.total() > 0 ? "All nodes offline" : "Waiting for agents...");
        }
        downloadSpeedVal.setText(fleet.online() > 0 ? formatSpeed(fleet.download()) : "N/A");
        uploadSpeedVal.setText(fleet.online() > 0 ? formatSpeed(fleet.upload()) : "N/A");

        hostLoadChart.update(fleetHistory, Metric.CPU, Metric.MEMORY);
        latencyChart.update(fleetHistory, Metric.RTT, Metric.JITTER);
        throughputChart.update(fleetHistory, Metric.DOWNLOAD, Metric.UPLOAD);

        patch(inventoryBoxList, buildInventorySummary(nodes));
        refreshTopology(nodes);
        patch(eventFeedBox, buildEventLabels(registry.getRecentEvents(12), "ALL", "No recent events recorded."));
    }

    private List<Node> buildInventorySummary(List<NodeRecord> nodes) {
        List<Node> rows = new ArrayList<>();
        if (nodes.isEmpty()) {
            rows.add(createDetailLabel("No agents connected. Waiting for telemetry on " + connectAddress() + "..."));
            return rows;
        }

        for (NodeRecord n : nodes) {
            TelemetryPayload p = n.getLatestPayload();
            NetworkMetrics net = n.getNetworkMetrics();
            boolean isOffline = FleetSummary.isOffline(n);

            HBox row = new HBox(12);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(10, 14, 10, 14));
            row.setStyle("-fx-background-color:" + (isOffline ? "#241318" : PANEL_ALT) +
                    ";-fx-background-radius:8;-fx-border-color:" + (isOffline ? RED_ACCENT : BORDER_COLOR)
                    + ";-fx-border-radius:8;");

            VBox left = new VBox(2);
            Label name = new Label(n.getNodeId());
            name.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:13px;-fx-font-weight:bold;");
            Label addr = new Label(n.getAgentHostname() + "  •  " + address(n));
            addr.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:11px;");
            left.getChildren().addAll(name, addr);

            Region push = new Region();
            HBox.setHgrow(push, Priority.ALWAYS);

            VBox statsBox = new VBox(2);
            statsBox.setAlignment(Pos.CENTER_RIGHT);
            Label netLbl = new Label(isOffline ? "Last seen " + lastSeen(n)
                    : "RTT: " + formatMs(net != null ? net.getAvgRttMs() : -1.0)
                    + "  |  Loss: " + formatPercent(FleetSummary.packetLoss(net)));
            netLbl.setStyle(
                    "-fx-text-fill:" + CYAN_ACCENT + ";-fx-font-size:11px;-fx-font-family:'Consolas', monospace;");

            Label hwLbl = new Label(p != null && !isOffline
                    ? String.format(Locale.US, "CPU %.1f%%  RAM %.1f%%  DISK %.1f%%", p.getCpuPercent(),
                            p.getMemoryPercent(), p.getDiskPercent())
                    : "CPU N/A  RAM N/A  DISK N/A");
            hwLbl.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:11px;");
            statsBox.getChildren().addAll(netLbl, hwLbl);

            Button inspectBtn = createSmallButton("DETAILS");
            inspectBtn.setOnAction(e -> showNodeDetails(n.getNodeId()));

            Label badge = createStatusBadge(n);
            for (Region fixed : List.of(netLbl, hwLbl, statsBox, badge, inspectBtn)) {
                fixed.setMinWidth(Region.USE_PREF_SIZE); // only the address may be shortened
            }
            row.getChildren().addAll(left, push, statsBox, badge, inspectBtn);
            rows.add(row);
        }
        return rows;
    }

    /** Event lines matching {@code filter}, newest first. */
    private List<Node> buildEventLabels(List<String> events, String filter, String emptyText) {
        List<Node> labels = new ArrayList<>();
        for (int i = events.size() - 1; i >= 0; i--) {
            String event = events.get(i);
            if (!matchesFilter(event, filter)) {
                continue;
            }
            String color = GREEN_ACCENT;
            if (event.contains("[WARN]"))
                color = YELLOW_ACCENT;
            else if (event.contains("[LOST]") || event.contains("[FAIL]"))
                color = RED_ACCENT;
            else if (event.contains("[SYS]"))
                color = CYAN_ACCENT;

            Label l = new Label("●  " + event);
            l.setWrapText(true);
            l.setStyle("-fx-text-fill:" + color + ";-fx-font-size:12px;-fx-font-family:'Consolas', monospace;");
            labels.add(l);
        }
        if (labels.isEmpty()) {
            labels.add(createDetailLabel(emptyText));
        }
        return labels;
    }

    // ================= VIEW 2: SERVER FLEET / NODE MANAGEMENT =================

    private List<Node> buildServerFleet(List<NodeRecord> nodes) {
        List<Node> content = new ArrayList<>();
        content.add(createSectionTitle("All Registered Nodes"));
        if (nodes.isEmpty()) {
            content.add(createEmptyStateWidget("No agents connected. Start an agent on another laptop, or point it at "
                    + connectAddress() + "."));
            return content;
        }

        for (NodeRecord node : nodes) {
            TelemetryPayload p = node.getLatestPayload();

            VBox card = new VBox(14);
            card.setPadding(new Insets(24));
            card.setStyle(getPanelStyle());

            // Header line: Node name + Status badge
            HBox head = new HBox(12);
            head.setAlignment(Pos.CENTER_LEFT);
            Label nodeName = new Label(node.getNodeId());
            nodeName.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:22px;-fx-font-weight:bold;");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button inspectBtn = createSmallButton("DRILL-DOWN CHARTS →");
            inspectBtn.setOnAction(e -> showNodeDetails(node.getNodeId()));

            Button inspectNetBtn = createSmallButton("VIEW NETWORK PROBES →");
            inspectNetBtn.setOnAction(e -> showPage(Page.NETWORK_FLEET));

            head.getChildren().addAll(nodeName, spacer, inspectBtn, inspectNetBtn, createStatusBadge(node));

            Label addrLine = new Label("Address: " + address(node) + staleNote(node));
            addrLine.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:13px;");
            card.getChildren().addAll(head, addrLine);

            if (p != null) {
                boolean traffic = p.getTotalBytesReceived() > 0 || p.getTotalBytesSent() > 0;
                VBox detailsList = new VBox(8);
                detailsList.setPadding(new Insets(8, 0, 4, 0));
                detailsList.getChildren().addAll(
                        createDetailLabel(String.format(Locale.US, "CPU: %.1f%% | Cores: %s", p.getCpuPercent(),
                                cores(p))),
                        createDetailLabel(String.format(Locale.US, "Memory: %.1f%% | Used: %s / %s", p.getMemoryPercent(),
                                formatGb(p.getUsedMemoryBytes(), p.getTotalMemoryBytes()),
                                formatGb(p.getTotalMemoryBytes(), p.getTotalMemoryBytes()))),
                        createDetailLabel(String.format(Locale.US, "Disk: %.1f%% | Free: %s", p.getDiskPercent(),
                                formatGb(p.getFreeDiskBytes(), p.getTotalDiskBytes()))),
                        createDetailLabel("Temperature: " + temperature(p)),
                        createDetailLabel("Battery: " + battery(node)),
                        createDetailLabel("Download: " + (traffic ? formatSpeed(p.getDownloadBytesPerSecond()) : "N/A")),
                        createDetailLabel("Upload: " + (traffic ? formatSpeed(p.getUploadBytesPerSecond()) : "N/A")),
                        createDetailLabel("Network received: "
                                + (traffic ? formatDataSize(p.getTotalBytesReceived()) : "N/A")),
                        createDetailLabel("Network sent: " + (traffic ? formatDataSize(p.getTotalBytesSent()) : "N/A")),
                        createDetailLabel("Processes: " + p.getProcessCount()),
                        createDetailLabel("Operating System: " + safe(p.getOperatingSystem())),
                        createDetailLabel("Hostname: " + safe(p.getHostName())),
                        createDetailLabel("IP Address: " + safe(p.getIpAddress())),
                        createDetailLabel("Uptime: " + formatUptime(p.getUptimeSeconds())));
                card.getChildren().add(detailsList);
            } else {
                card.getChildren().add(createDetailLabel("Waiting for first telemetry payload..."));
            }
            content.add(card);
        }
        return content;
    }

    private Label createDetailLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill:#cbd5e1;-fx-font-size:13px;");
        return l;
    }

    // ================= VIEW 3: NODE DETAILS DRILL-DOWN =================

    private void buildNodeDetails() {
        Button backBtn = createSmallButton("← BACK TO ALL NODES");
        backBtn.setOnAction(e -> showPage(Page.NODES));

        detailInfoBox = new VBox(20);
        detailCpuChart = new TrendChart("CPU PERFORMANCE", "Usage %", true, 200, "CPU");
        detailMemChart = new TrendChart("MEMORY PERFORMANCE", "Usage %", true, 200, "Memory");
        detailThroughputChart = new TrendChart("BANDWIDTH SPEED (KB/s)", "Speed (KB/s)", false, 200,
                "Download", "Upload");
        detailLatencyChart = new TrendChart("UDP RTT & JITTER (ms)", "Latency (ms)", false, 200,
                "RTT Latency", "Jitter");
        detailProbeBox = new VBox(20);

        contentArea.getChildren().addAll(
                backBtn,
                detailInfoBox,
                createSectionTitle("Resource Performance"),
                new HBox(16, detailCpuChart.box, detailMemChart.box),
                createSectionTitle("Network Performance & Probe Latency Graphs"),
                new HBox(16, detailThroughputChart.box, detailLatencyChart.box),
                detailProbeBox);
    }

    private void refreshNodeDetails() {
        NodeRecord node = registry.getNode(selectedNodeId).orElse(null);
        if (node == null) {
            patch(detailInfoBox, List.of(
                    createEmptyStateWidget("Device '" + selectedNodeId + "' was not found in registry.")));
            return;
        }
        TelemetryPayload p = node.getLatestPayload();
        NetworkMetrics net = node.getNetworkMetrics();
        InternetConnectivityMetrics im = node.getInternetMetrics();

        HBox statusRow = new HBox(12, createStatusBadge(node),
                createDetailLabel("Address: " + address(node) + staleNote(node)));
        statusRow.setAlignment(Pos.CENTER_LEFT);

        VBox hwCard = createCard("Hardware Information",
                "CPU Temperature: " + temperature(p),
                "Battery: " + battery(node),
                "CPU Logical Cores: " + cores(p),
                "RAM: " + (p == null ? "N/A" : formatGb(p.getUsedMemoryBytes(), p.getTotalMemoryBytes()) + " used / "
                        + formatGb(p.getTotalMemoryBytes(), p.getTotalMemoryBytes()) + " total"),
                "Disk: " + (p == null ? "N/A"
                        : formatGb(p.getTotalDiskBytes() - p.getFreeDiskBytes(), p.getTotalDiskBytes()) + " used / "
                        + formatGb(p.getTotalDiskBytes(), p.getTotalDiskBytes()) + " total"));
        VBox sysCard = createCard("System Information",
                "Operating System: " + (p != null ? safe(p.getOperatingSystem()) : "N/A"),
                "System Uptime: " + (p != null ? formatUptime(p.getUptimeSeconds()) : "N/A"));
        patch(detailInfoBox, List.of(statusRow, hwCard, sysCard));

        NodeHistory history = nodeHistories.getOrDefault(node.getNodeId(), new NodeHistory());
        detailCpuChart.update(history, Metric.CPU);
        detailMemChart.update(history, Metric.MEMORY);
        detailThroughputChart.update(history, Metric.DOWNLOAD, Metric.UPLOAD);
        detailLatencyChart.update(history, Metric.RTT, Metric.JITTER);

        GridPane netGrid = new GridPane();
        netGrid.setHgap(32);
        netGrid.setVgap(10);
        double loss = FleetSummary.packetLoss(net);
        addGridItem(netGrid, 0, 0, "Current RTT", formatMs(net != null ? net.getCurrentRttMs() : -1.0), CYAN_ACCENT);
        addGridItem(netGrid, 1, 0, "Average RTT", formatMs(net != null ? net.getAvgRttMs() : -1.0), CYAN_ACCENT);
        addGridItem(netGrid, 2, 0, "Packet Loss", formatPercent(loss), lossColor(loss));
        addGridItem(netGrid, 3, 0, "RFC 3550 Jitter", formatMs(net != null ? net.getAvgJitterMs() : -1.0),
                YELLOW_ACCENT);
        addInternetItems(netGrid, 1, node, im);

        VBox probeCard = new VBox(12);
        probeCard.setStyle(getPanelStyle());
        probeCard.setPadding(new Insets(18));
        probeCard.getChildren().add(netGrid);
        patch(detailProbeBox, List.of(createSectionTitle("Network Probes & Internet Routing"), probeCard));
    }

    private VBox createCard(String title, String... lines) {
        VBox card = new VBox(10);
        card.setPadding(new Insets(20));
        card.setStyle(getPanelStyle());
        card.getChildren().add(createSectionTitle(title));
        for (String line : lines) {
            card.getChildren().add(createDetailLabel(line));
        }
        return card;
    }

    /** Adds Internet access, gateway, DNS and HTTPS results; unmeasured values read N/A. */
    private void addInternetItems(GridPane grid, int row, NodeRecord node, InternetConnectivityMetrics im) {
        String status = internetStatus(node);
        addGridItem(grid, 0, row, "Internet Access", status, internetColor(status));
        boolean measured = isInternetMeasured(node);
        addGridItem(grid, 1, row, "Default Gateway", gateway(node) + (!measured ? ""
                : im.isGatewayReachable() ? " (reachable)" : " (unreachable)"),
                measured && !im.isGatewayReachable() ? RED_ACCENT : WHITE_TEXT);
        addGridItem(grid, 2, row, "DNS Resolution", dns(node),
                measured && !im.isDnsSuccess() ? RED_ACCENT : WHITE_TEXT);
        addGridItem(grid, 3, row, "External HTTPS", https(node),
                !measured ? MUTED_TEXT : im.isHttpsSuccess() ? GREEN_ACCENT : RED_ACCENT);
    }

    // ================= VIEW 4: NETWORK FLEET =================

    private List<Node> buildNetworkFleet(List<NodeRecord> nodes) {
        List<Node> content = new ArrayList<>();
        if (nodes.isEmpty()) {
            content.add(createEmptyStateWidget(
                    "No agent nodes connected. Waiting for telemetry on " + connectAddress() + "..."));
            return content;
        }

        for (NodeRecord node : nodes) {
            NetworkMetrics net = node.getNetworkMetrics();
            boolean isOffline = FleetSummary.isOffline(node);

            VBox card = new VBox(14);
            card.setPadding(new Insets(22));
            card.setStyle(getPanelStyle());

            HBox head = new HBox(12);
            head.setAlignment(Pos.CENTER_LEFT);
            Label name = new Label(node.getNodeId());
            name.setStyle("-fx-text-fill:" + CYAN_ACCENT + ";-fx-font-size:18px;-fx-font-weight:bold;");

            Label hostPill = new Label("[Host: " + node.getAgentHostname() + "]");
            hostPill.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:12px;");

            Label ipPill = new Label("[IP: " + address(node) + "]");
            ipPill.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:12px;");

            Region push = new Region();
            HBox.setHgrow(push, Priority.ALWAYS);

            Button inspectBtn = createSmallButton("DRILL-DOWN");
            inspectBtn.setOnAction(e -> showNodeDetails(node.getNodeId()));

            Label statusBadge = createPillBadge(isOffline ? "• OFFLINE" : "• CONNECTED",
                    isOffline ? RED_ACCENT : GREEN_ACCENT);
            head.getChildren().addAll(name, hostPill, ipPill, push, inspectBtn, statusBadge);

            String serverHost = net != null && net.getServerHost() != null ? net.getServerHost() : options.host();
            int agentProbePort = net != null && net.getUdpProbePort() > 0 ? net.getUdpProbePort() : options.probePort();
            Label transportLine = new Label(String.format("Server: %s:%d (TCP) | Probe: :%d (UDP) | Status: %s%s",
                    serverHost, options.tcpPort(), agentProbePort, node.getStatus(), staleNote(node)));
            transportLine.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:11px;");

            // Latency & Quality
            GridPane grid = new GridPane();
            grid.setHgap(36);
            grid.setVgap(10);

            addGridItem(grid, 0, 0, "Current RTT", formatMs(net != null ? net.getCurrentRttMs() : -1.0), CYAN_ACCENT);
            addGridItem(grid, 1, 0, "Average RTT", formatMs(net != null ? net.getAvgRttMs() : -1.0), CYAN_ACCENT);
            addGridItem(grid, 2, 0, "Minimum RTT", formatMs(net != null ? net.getMinRttMs() : -1.0), GREEN_ACCENT);
            addGridItem(grid, 3, 0, "Maximum RTT", formatMs(net != null ? net.getMaxRttMs() : -1.0), YELLOW_ACCENT);

            double rollingLoss = FleetSummary.packetLoss(net);
            double cumLoss = Double.isNaN(rollingLoss) ? Double.NaN : net.getCumulativeLossPercent();
            addGridItem(grid, 0, 1, "Packet Loss (Rolling)", formatPercent(rollingLoss), lossColor(rollingLoss));
            addGridItem(grid, 1, 1, "Cumulative Loss", formatPercent(cumLoss), lossColor(cumLoss));
            addGridItem(grid, 2, 1, "RFC 3550 Jitter", formatMs(net != null ? net.getAvgJitterMs() : -1.0),
                    YELLOW_ACCENT);
            String quality = isOffline ? "N/A" : getQualityString(net);
            addGridItem(grid, 3, 1, "Quality Rating", quality, switch (quality) {
                case "OPTIMAL" -> GREEN_ACCENT;
                case "FAIR" -> YELLOW_ACCENT;
                case "DEGRADED" -> RED_ACCENT;
                default -> MUTED_TEXT;
            });

            addGridItem(grid, 0, 2, "Probes Sent", String.valueOf(net != null ? net.getProbesSent() : 0), WHITE_TEXT);
            addGridItem(grid, 1, 2, "Probes Received", String.valueOf(net != null ? net.getProbesReceived() : 0),
                    GREEN_ACCENT);
            addGridItem(grid, 2, 2, "Probes Lost", String.valueOf(net != null ? net.getProbesLost() : 0),
                    (net != null && net.getProbesLost() > 0 ? RED_ACCENT : WHITE_TEXT));
            addGridItem(grid, 3, 2, "Reconnections", String.valueOf(net != null ? net.getReconnectionCount() : 0),
                    WHITE_TEXT);

            addInternetItems(grid, 3, node, node.getInternetMetrics());

            card.getChildren().addAll(head, transportLine, new Separator(), grid);
            content.add(card);
        }
        return content;
    }

    private String getQualityString(NetworkMetrics net) {
        double loss = FleetSummary.packetLoss(net);
        if (Double.isNaN(loss))
            return "N/A";
        double rtt = net.getAvgRttMs();
        if (loss > 5.0 || rtt > 150.0)
            return "DEGRADED";
        if (loss > 0.0 || rtt > 60.0)
            return "FAIR";
        return "OPTIMAL";
    }

    // ================= VIEW 5: NETWORK DIAGNOSTICS =================

    private List<Node> buildDiagnostics(List<NodeRecord> nodes) {
        List<NodeRecord> ranked = new ArrayList<>(nodes);
        // Unmeasured and offline nodes cannot be ranked: they come last
        ranked.sort(Comparator.comparingDouble(n -> {
            NetworkMetrics m = n.getNetworkMetrics();
            return m != null && m.getAvgRttMs() >= 0 && !FleetSummary.isOffline(n)
                    ? m.getAvgRttMs() : Double.MAX_VALUE;
        }));

        VBox card = new VBox(14);
        card.setStyle(getPanelStyle());
        card.setPadding(new Insets(20));
        card.getChildren().add(createSectionTitle("Lowest-to-Highest RTT Performance"));

        if (ranked.isEmpty()) {
            card.getChildren().add(createDetailLabel("No nodes registered for latency ranking."));
        }
        int rank = 1;
        for (NodeRecord n : ranked) {
            NetworkMetrics net = FleetSummary.isOffline(n) ? null : n.getNetworkMetrics();
            boolean measured = net != null && net.getAvgRttMs() >= 0;
            HBox row = new HBox(14);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(10));
            row.setStyle("-fx-background-color:" + PANEL_ALT + ";-fx-background-radius:6;");

            Label rLbl = new Label(measured ? "#" + rank++ : "–");
            rLbl.setStyle("-fx-text-fill:" + GREEN_ACCENT + ";-fx-font-weight:bold;");

            Label name = new Label(n.getNodeId());
            name.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-weight:bold;");

            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);

            Label val = new Label("Avg RTT: " + formatMs(measured ? net.getAvgRttMs() : -1.0)
                    + "  |  Jitter: " + formatMs(measured ? net.getAvgJitterMs() : -1.0)
                    + "  |  Loss: " + formatPercent(FleetSummary.packetLoss(net)));
            val.setStyle("-fx-text-fill:" + CYAN_ACCENT + ";");

            row.getChildren().addAll(rLbl, name, sp, val, createStatusBadge(n));
            card.getChildren().add(row);
        }
        return List.of(card);
    }

    // ================= VIEW 6: INTERACTIVE TOPOLOGY =================

    private void buildTopology() {
        contentArea.getChildren().add(createTopologyPanel("Live Agent-to-Server Communication Topology", false));
    }

    /**
     * Creates the panel holding the topology map of the page being built.
     *
     * @param compact {@code true} for the shorter map on the overview
     */
    private VBox createTopologyPanel(String title, boolean compact) {
        VBox panel = new VBox(14);
        panel.setPadding(new Insets(20));
        panel.setStyle(getPanelStyle());

        topologyCompact = compact;
        topologySignature = "";
        topologyPane = new Pane();
        topologyPane.setMinWidth(0);
        topologyPane.setPrefWidth(300); // the panel decides the width, not the shapes drawn inside
        topologyPane.widthProperty().addListener((obs, oldW, newW) -> refreshTopology(registry.getAllNodes()));

        HBox legend = new HBox(16, createLegendItem("HEALTHY", GREEN_ACCENT),
                createLegendItem("WARNING", YELLOW_ACCENT), createLegendItem("OFFLINE", RED_ACCENT));
        panel.getChildren().addAll(createSectionTitle(title), topologyPane, legend);
        return panel;
    }

    private Label createLegendItem(String text, String color) {
        Label item = new Label("● " + text);
        item.setStyle("-fx-text-fill:" + color + ";-fx-font-size:10px;-fx-font-weight:bold;");
        return item;
    }

    /** Places two panels side by side, or the right one on top when the window is narrow. */
    private GridPane createResponsiveRow(Region left, Region right) {
        GridPane row = new GridPane();
        row.setHgap(14);
        row.setVgap(14);
        Runnable arrange = () -> {
            boolean stacked = row.getWidth() > 0 && row.getWidth() < 1100;
            if (row.getChildren().isEmpty() || stacked != (row.getColumnConstraints().size() == 1)) {
                ColumnConstraints first = new ColumnConstraints();
                first.setPercentWidth(stacked ? 100 : 57);
                ColumnConstraints second = new ColumnConstraints();
                second.setPercentWidth(43);
                row.getColumnConstraints().setAll(stacked ? List.of(first) : List.of(first, second));
                row.getChildren().setAll(left, right);
                GridPane.setConstraints(left, 0, stacked ? 1 : 0);
                GridPane.setConstraints(right, stacked ? 0 : 1, 0);
            }
        };
        arrange.run();
        // Later, because the children of a layout must not change while it is being laid out
        row.widthProperty().addListener((obs, oldW, newW) -> Platform.runLater(arrange));
        return row;
    }

    /** Redraws only when something visible changed, so nodes stay clickable between updates. */
    private void refreshTopology(List<NodeRecord> nodes) {
        StringBuilder signature = new StringBuilder().append((int) topologyPane.getWidth());
        for (NodeRecord n : nodes) {
            signature.append('|').append(n.getNodeId()).append('=').append(n.getStatus());
        }
        if (!signature.toString().equals(topologySignature)) {
            topologySignature = signature.toString();
            drawTopology(topologyPane, nodes);
        }
    }

    private void drawTopology(Pane pane, List<NodeRecord> nodes) {
        final double cellWidth = 120;
        final double rowHeight = 90;
        final double agentTop = topologyCompact ? 160 : 200;
        double w = pane.getWidth() > 0 ? Math.max(260, pane.getWidth()) : 400;
        double cx = w / 2;
        double serverY = 40;

        int perRow = Math.max(1, (int) ((w - 40) / cellWidth));
        int rows = (nodes.size() + perRow - 1) / perRow;
        pane.setPrefHeight(Math.max(topologyCompact ? 260 : 450, agentTop + rows * rowHeight));

        List<Node> links = new ArrayList<>();
        List<Node> shapes = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            NodeRecord n = nodes.get(i);
            int row = i / perRow;
            int inRow = Math.min(perRow, nodes.size() - row * perRow);
            double ax = cx + (i % perRow - (inRow - 1) / 2.0) * cellWidth;
            double ay = agentTop + row * rowHeight;
            String color = statusColor(n);

            Line link = new Line(cx, serverY, ax, ay);
            link.setStroke(Color.web(color));
            link.setOpacity(0.7);
            link.setStrokeWidth(1.8);
            links.add(link);

            Rectangle agentBox = new Rectangle(ax - 18, ay - 14, 36, 28);
            agentBox.setArcWidth(8);
            agentBox.setArcHeight(8);
            agentBox.setFill(Color.web(FleetSummary.isOffline(n) ? "#3b1219" : "#112e28"));
            agentBox.setStroke(Color.web(color));
            agentBox.setStrokeWidth(1.5);
            agentBox.setStyle("-fx-cursor:hand;");
            agentBox.setOnMouseClicked(e -> showNodeDetails(n.getNodeId()));

            Label nodeLbl = new Label(n.getNodeId());
            nodeLbl.setTooltip(new Tooltip(n.getNodeId() + "\n" + address(n) + "\n" + n.getStatus()));
            nodeLbl.setAlignment(Pos.CENTER);
            nodeLbl.setPrefWidth(cellWidth - 8);
            nodeLbl.setStyle("-fx-background-color:" + PANEL_BG + ";-fx-text-fill:" + WHITE_TEXT
                    + ";-fx-font-size:10px;-fx-font-weight:bold;-fx-cursor:hand;");
            nodeLbl.setLayoutX(ax - (cellWidth - 8) / 2);
            nodeLbl.setLayoutY(ay + 18);
            nodeLbl.setOnMouseClicked(e -> showNodeDetails(n.getNodeId()));
            shapes.add(agentBox);
            shapes.add(nodeLbl);
        }

        // Central Server Hub, drawn over the links
        Circle serverCircle = new Circle(cx, serverY, 22, Color.web("#064e3b"));
        serverCircle.setStroke(Color.web(GREEN_ACCENT));
        serverCircle.setStrokeWidth(2.5);
        Label serverText = new Label(String.format("PULSENET SERVER  (TCP %d | UDP %d)", options.tcpPort(),
                options.probePort()));
        serverText.setAlignment(Pos.CENTER);
        serverText.setPrefWidth(240);
        serverText.setStyle("-fx-background-color:" + PANEL_BG + ";-fx-text-fill:" + GREEN_ACCENT
                + ";-fx-font-size:10px;-fx-font-weight:bold;");
        serverText.setLayoutX(cx - 120);
        serverText.setLayoutY(serverY + 28);

        pane.getChildren().setAll(links);
        pane.getChildren().addAll(shapes);
        pane.getChildren().addAll(serverCircle, serverText);

        if (nodes.isEmpty()) {
            Label wait = new Label("Waiting for agents to connect...");
            wait.setStyle("-fx-text-fill:" + SUBTLE_TEXT + ";-fx-font-size:12px;");
            wait.setAlignment(Pos.CENTER);
            wait.setPrefWidth(240);
            wait.setLayoutX(cx - 120);
            wait.setLayoutY(agentTop);
            pane.getChildren().add(wait);
        }
    }

    // ================= VIEW 7: INTERNET & WAN HEALTH =================

    private List<Node> buildInternetHealth(List<NodeRecord> nodes) {
        VBox card = new VBox(14);
        card.setStyle(getPanelStyle());
        card.setPadding(new Insets(20));
        card.getChildren().add(createSectionTitle("Agent Internet Access Audit Table"));

        if (nodes.isEmpty()) {
            card.getChildren().add(createDetailLabel("No nodes connected to test WAN connectivity."));
        }
        for (NodeRecord n : nodes) {
            HBox row = new HBox(16);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(12));
            row.setStyle("-fx-background-color:" + PANEL_ALT + ";-fx-background-radius:6;");

            Label name = new Label(n.getNodeId());
            name.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-weight:bold;");

            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);

            Label details = new Label("Gateway: " + gateway(n) + "  |  DNS: " + dns(n) + "  |  External: " + https(n));
            details.setStyle("-fx-text-fill:" + CYAN_ACCENT + ";-fx-font-family:'Consolas', monospace;");

            String status = internetStatus(n);
            row.getChildren().addAll(name, sp, details, createPillBadge("• " + status, internetColor(status)));
            card.getChildren().add(row);
        }
        return List.of(card);
    }

    private static boolean isInternetMeasured(NodeRecord node) {
        InternetConnectivityMetrics im = node.getInternetMetrics();
        return im != null && !FleetSummary.isOffline(node) && im.getStatus() != null
                && !InternetConnectivityMetrics.STATUS_UNKNOWN.equalsIgnoreCase(im.getStatus());
    }

    private static String internetStatus(NodeRecord node) {
        if (FleetSummary.isOffline(node)) {
            return "NODE OFFLINE";
        }
        return isInternetMeasured(node) ? node.getInternetMetrics().getStatus().toUpperCase(Locale.ROOT) : "N/A";
    }

    private static String internetColor(String status) {
        return switch (status) {
            case InternetConnectivityMetrics.STATUS_ONLINE -> GREEN_ACCENT;
            case InternetConnectivityMetrics.STATUS_LIMITED -> YELLOW_ACCENT;
            case InternetConnectivityMetrics.STATUS_OFFLINE, "NODE OFFLINE" -> RED_ACCENT;
            default -> MUTED_TEXT;
        };
    }

    private static String gateway(NodeRecord node) {
        InternetConnectivityMetrics im = node.getInternetMetrics();
        return isInternetMeasured(node) && im.getGatewayIp() != null && !im.getGatewayIp().isBlank()
                ? im.getGatewayIp() : "N/A";
    }

    private static String dns(NodeRecord node) {
        if (!isInternetMeasured(node)) {
            return "N/A";
        }
        InternetConnectivityMetrics im = node.getInternetMetrics();
        return im.isDnsSuccess() ? im.getDnsResolutionTimeMs() + " ms" : "FAILED";
    }

    private static String https(NodeRecord node) {
        if (!isInternetMeasured(node)) {
            return "N/A";
        }
        InternetConnectivityMetrics im = node.getInternetMetrics();
        if (im.isHttpsSuccess()) {
            return "HTTPS " + im.getHttpStatusCode() + " (" + im.getHttpsResponseTimeMs() + " ms)";
        }
        return im.getHttpStatusCode() > 0 ? "HTTPS " + im.getHttpStatusCode() + " (FAILED)" : "UNREACHABLE";
    }

    // ================= VIEW 8: ALERTS & EVENTS =================

    private List<Node> buildAlerts() {
        HBox filterBar = new HBox(10);
        filterBar.getChildren().addAll(
                createFilterButton("ALL"),
                createFilterButton("CRITICAL"),
                createFilterButton("WARNING"),
                createFilterButton("RECOVERY"),
                createFilterButton("SYS"));

        VBox logBox = new VBox(10);
        logBox.setStyle(getPanelStyle());
        logBox.setPadding(new Insets(20));
        logBox.getChildren().addAll(buildEventLabels(registry.getRecentEvents(200), alertFilter,
                "ALL".equals(alertFilter) ? "No events logged yet." : "No " + alertFilter + " events."));
        return List.of(filterBar, logBox);
    }

    private Button createFilterButton(String filter) {
        Button b = new Button(filter);
        b.setStyle("-fx-background-color:" + (filter.equals(alertFilter) ? GREEN_ACCENT : PANEL_ALT) +
                ";-fx-text-fill:" + (filter.equals(alertFilter) ? "#000000" : WHITE_TEXT) +
                ";-fx-font-weight:bold;-fx-background-radius:6;-fx-cursor:hand;");
        b.setOnAction(e -> {
            alertFilter = filter;
            refreshCurrentView(registry.getAllNodes());
        });
        return b;
    }

    private boolean matchesFilter(String event, String filter) {
        return switch (filter) {
            case "CRITICAL" -> event.contains("[LOST]") || event.contains("[FAIL]");
            case "WARNING" -> event.contains("[WARN]");
            case "RECOVERY" -> event.contains("[REC]") || event.contains("[OK]");
            case "SYS" -> event.contains("[SYS]");
            default -> true;
        };
    }

    // ================= VIEW 9: CAPACITY PLANNING =================

    private List<Node> buildCapacity(List<NodeRecord> nodes) {
        int totalCores = 0;
        int reporting = 0;
        long totalRam = 0;
        long usedRam = 0;
        long totalDisk = 0;
        long freeDisk = 0;

        for (NodeRecord n : nodes) {
            TelemetryPayload p = n.getLatestPayload();
            if (p != null && !FleetSummary.isOffline(n) && p.getTotalMemoryBytes() > 0) {
                reporting++;
                totalCores += p.getCpuCoreCount();
                totalRam += p.getTotalMemoryBytes();
                usedRam += p.getUsedMemoryBytes();
                totalDisk += p.getTotalDiskBytes();
                freeDisk += p.getFreeDiskBytes();
            }
        }

        VBox card = createCard("Aggregated Cluster Resources",
                String.format("Online nodes reporting hardware capacity: %d of %d", reporting, nodes.size()),
                "Total Logical CPU Cores: " + (totalCores > 0 ? totalCores : "N/A"),
                "Cluster RAM: " + (totalRam > 0 ? String.format(Locale.US, "%s used / %s total (%.1f%% Allocated)",
                        formatGb(usedRam, totalRam), formatGb(totalRam, totalRam), usedRam * 100.0 / totalRam) : "N/A"),
                "Cluster Disk Storage: " + (totalDisk > 0 ? formatGb(totalDisk - freeDisk, totalDisk) + " used / "
                        + formatGb(totalDisk, totalDisk) + " total" : "N/A"),
                "Available Memory Headroom: " + formatGb(totalRam - usedRam, totalRam),
                "Available Disk Headroom: " + formatGb(freeDisk, totalDisk));
        return List.of(card);
    }

    // ================= VIEW 10: SLA REPORTS =================

    private List<Node> buildReports(List<NodeRecord> nodes) {
        FleetSummary fleet = FleetSummary.of(nodes);

        VBox card = createCard("Infrastructure Availability",
                "Nodes online now: " + (fleet.total() > 0
                        ? String.format(Locale.US, "%.2f%%", fleet.online() * 100.0 / fleet.total()) : "N/A"),
                "Total Registered Fleet: " + fleet.total() + " Devices",
                "Active Online Agents: " + fleet.online() + " (" + fleet.warnings() + " with warnings)",
                "Offline Agents: " + fleet.offline());

        List<String> lines = new ArrayList<>();
        for (NodeRecord n : nodes) {
            NodeHistory history = nodeHistories.get(n.getNodeId());
            lines.add(n.getNodeId() + ": " + (history != null ? String.format(Locale.US, "%.2f%%",
                    history.availabilityPercent()) : "N/A"));
        }
        if (lines.isEmpty()) {
            lines.add("No nodes observed yet.");
        }
        VBox perNode = createCard("Measured availability per node (since it first connected to this window)",
                lines.toArray(new String[0]));

        VBox protocol = createCard("Wire Protocol Framing Specification",
                "TCP Framing: Low-Level BSD Socket with 4-byte signed Big-Endian integer length prefix.",
                "UDP Probes: 1-second interval echo probes with sequence numbers and a 50-probe loss window.",
                String.format(Locale.US, "Watchdog Audit: nodes silent for more than %.1f seconds are marked OFFLINE.",
                        options.watchdogTimeout()));
        return List.of(card, perNode, protocol);
    }

    // ================= VIEW 11: SETTINGS & PORTS =================

    private List<Node> buildSettings() {
        VBox config = createCard("Active Server Configuration",
                "Server Bind Address: " + options.host(),
                "TCP Telemetry Port: " + options.tcpPort() + state(server.isRunning(), "Listening"),
                "UDP Discovery Port: " + options.discoveryPort() + state(discoveryServer.isRunning(), "Advertising"),
                "UDP Network Probe Port: " + options.probePort() + state(probeServer.isRunning(), "Echoing"),
                "Open agent connections: " + server.getActiveConnectionCount() + " of " + server.getMaxConnections(),
                "Watchdog Audit Timeout: " + options.watchdogTimeout() + " seconds",
                "Server Identifier: " + options.serverId());

        List<String> lines = new ArrayList<>();
        for (LanAddress la : lanAddresses) {
            lines.add(String.format("•  %s: %s  (Adapter: %s)", la.label(), la.address(), la.displayName()));
        }
        if (lines.isEmpty()) {
            lines.add("•  127.0.0.1 (loopback only: this computer is not on a network)");
        }
        lines.add("Agents: java -jar pulsenet.jar agent --discover      or      java -jar pulsenet.jar agent --host "
                + connectAddress().replace(":", " --port "));
        lines.add("Other laptops cannot connect? Run scripts\\open-firewall.ps1 as Administrator on this computer.");
        return List.of(config, createCard("Detected LAN IP Addresses (For Agents)", lines.toArray(new String[0])));
    }

    private static String state(boolean running, String activity) {
        return running ? " (Active / " + activity + ")" : " (NOT AVAILABLE)";
    }

    // ================= NODE TEXT HELPERS =================

    private static String statusColor(NodeRecord node) {
        if (FleetSummary.isOffline(node)) {
            return RED_ACCENT;
        }
        return "WARNING".equalsIgnoreCase(node.getStatus()) ? YELLOW_ACCENT : GREEN_ACCENT;
    }

    private Label createStatusBadge(NodeRecord node) {
        return createPillBadge("• " + (FleetSummary.isOffline(node) ? "OFFLINE" : node.getStatus()), statusColor(node));
    }

    private static String address(NodeRecord node) {
        String address = node.getRemoteAddress();
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        return address.startsWith("/") ? address.substring(1) : address;
    }

    private static String lastSeen(NodeRecord node) {
        long seconds = Math.max(0, (System.currentTimeMillis() - node.getLastSeenTimestamp()) / 1000);
        return seconds < 60 ? seconds + " s ago" : formatUptime(seconds) + " ago";
    }

    /** Marks figures of an offline node as old. */
    private static String staleNote(NodeRecord node) {
        return FleetSummary.isOffline(node) ? "   •   Last seen " + lastSeen(node) + " (showing last known values)" : "";
    }

    private static String cores(TelemetryPayload p) {
        return p != null && p.getCpuCoreCount() > 0 ? String.valueOf(p.getCpuCoreCount()) : "N/A";
    }

    private static String temperature(TelemetryPayload p) {
        return p != null && p.getCpuTemperatureCelsius() > 0
                ? String.format(Locale.US, "%.1f °C", p.getCpuTemperatureCelsius()) : "Unavailable";
    }

    private static String battery(NodeRecord node) {
        TelemetryPayload p = node.getLatestPayload();
        if (p == null || FleetSummary.isOffline(node)) {
            return "N/A";
        }
        return p.isBatteryAvailable() && p.getBatteryPercent() >= 0
                ? String.format(Locale.US, "%.1f%% (%s)", p.getBatteryPercent(),
                        p.isBatteryCharging() ? "Charging" : "Not charging")
                : "Unavailable";
    }

    private static String lossColor(double loss) {
        return Double.isNaN(loss) ? MUTED_TEXT : loss > 0 ? RED_ACCENT : GREEN_ACCENT;
    }

    /** @return the size in GB, or N/A if {@code total} shows that the node does not report it */
    private static String formatGb(long bytes, long total) {
        return total > 0 ? String.format(Locale.US, "%.2f GB", Math.max(0, bytes) / (double) (1L << 30)) : "N/A";
    }

    private static String formatPercent(double percent) {
        return Double.isNaN(percent) ? "N/A" : String.format(Locale.US, "%.1f%%", percent);
    }

    private static void setTextColor(Label label, String color) {
        String style = label.getStyle().replaceAll("-fx-text-fill:[^;]*;", "-fx-text-fill:" + color + ";");
        if (!style.equals(label.getStyle())) {
            label.setStyle(style);
        }
    }

    // ================= TREND CHART =================

    /** A titled line chart of the most recent samples; seconds without a measurement are left out. */
    private final class TrendChart {

        private final VBox box = new VBox(8);
        private final List<XYChart.Series<Number, Number>> seriesList = new ArrayList<>();

        TrendChart(String title, String yLabel, boolean percent, double height, String... seriesNames) {
            box.setStyle(getPanelStyle());
            box.setPadding(new Insets(14));
            // Equal shares of the row, whatever the axis labels measure
            box.setMinWidth(0);
            box.setPrefWidth(400);
            HBox.setHgrow(box, Priority.ALWAYS);

            Label heading = new Label(title);
            heading.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:13px;-fx-font-weight:bold;");

            NumberAxis x = new NumberAxis(-NodeHistory.CAPACITY, 0, 10);
            x.setLabel("Seconds (0 = now)");
            NumberAxis y = percent ? new NumberAxis(0, 100, 20) : new NumberAxis();
            y.setLabel(yLabel);
            LineChart<Number, Number> chart = new LineChart<>(x, y);
            chart.setAnimated(false);
            chart.setCreateSymbols(false);
            chart.setVerticalZeroLineVisible(false);
            chart.setLegendVisible(seriesNames.length > 1);
            chart.setPrefHeight(height);
            chart.setMinHeight(height);
            for (String name : seriesNames) {
                XYChart.Series<Number, Number> series = new XYChart.Series<>();
                series.setName(name);
                seriesList.add(series);
                chart.getData().add(series);
            }
            box.getChildren().addAll(heading, chart);
        }

        /** Shows one metric per series, in order. */
        void update(NodeHistory history, Metric... metrics) {
            for (int s = 0; s < metrics.length; s++) {
                double[] values = history.values(metrics[s]);
                List<XYChart.Data<Number, Number>> points = new ArrayList<>(values.length);
                for (int i = 0; i < values.length; i++) {
                    if (!Double.isNaN(values[i])) {
                        points.add(new XYChart.Data<>(i - (values.length - 1), values[i]));
                    }
                }
                seriesList.get(s).getData().setAll(points);
            }
        }
    }

    // ================= UI HELPER FACTORIES =================

    private Label createKpiCard(HBox row, String title, String accent, String icon, String defaultVal, String footer) {
        VBox card = new VBox(6);
        card.setPadding(new Insets(14, 16, 14, 16));
        card.setMinHeight(95);
        card.setStyle(getPanelStyle());
        HBox.setHgrow(card, Priority.ALWAYS);

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        Label head = new Label(title);
        head.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:11px;-fx-font-weight:bold;");
        head.setWrapText(true);
        head.setMinHeight(Region.USE_PREF_SIZE);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label sym = new Label(icon);
        sym.setStyle("-fx-text-fill:" + accent + ";-fx-font-size:14px;");
        sym.setMinWidth(Region.USE_PREF_SIZE);
        top.getChildren().addAll(head, gap, sym);

        Label value = new Label(defaultVal);
        value.setStyle("-fx-text-fill:" + accent + ";-fx-font-size:24px;-fx-font-weight:bold;");

        Label foot = new Label(footer);
        foot.setStyle("-fx-text-fill:" + SUBTLE_TEXT + ";-fx-font-size:10px;");
        foot.setWrapText(true);
        foot.setMinHeight(Region.USE_PREF_SIZE);

        card.getChildren().addAll(top, value, foot);
        row.getChildren().add(card);
        return value;
    }

    private VBox createStatCard(String title, String value, String footer, String accentColor) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(20));
        card.setStyle(getPanelStyle());
        HBox.setHgrow(card, Priority.ALWAYS);

        Label head = new Label(title);
        head.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:11px;-fx-font-weight:bold;");

        Label val = new Label(value);
        val.setStyle("-fx-text-fill:" + accentColor + ";-fx-font-size:26px;-fx-font-weight:bold;");

        Label foot = new Label(footer);
        foot.setStyle("-fx-text-fill:" + SUBTLE_TEXT + ";-fx-font-size:11px;");

        card.getChildren().addAll(head, val, foot);
        return card;
    }

    private VBox createDonutGauge(String title, String accent, String icon, String footer) {
        VBox box = new VBox(8);
        box.setAlignment(Pos.CENTER);
        HBox.setHgrow(box, Priority.ALWAYS);
        box.setStyle(getPanelStyle());
        box.setPadding(new Insets(16, 12, 16, 12));

        Label heading = new Label(title);
        heading.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:11px;-fx-font-weight:bold;");

        Circle track = new Circle(44, Color.TRANSPARENT);
        track.setStroke(Color.web("#1c2b42"));
        track.setStrokeWidth(9);

        Circle arc = new Circle(44, Color.TRANSPARENT);
        arc.setStroke(Color.web(accent));
        arc.setStrokeWidth(9);
        arc.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        arc.setRotate(-90);

        Label value = new Label("0%");
        value.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:22px;-fx-font-weight:bold;");

        Label glyph = new Label(icon);
        glyph.setStyle("-fx-text-fill:" + accent + ";-fx-font-size:9px;");

        VBox center = new VBox(2, value, glyph);
        center.setAlignment(Pos.CENTER);

        StackPane dial = new StackPane(track, arc, center);
        dial.setMinSize(110, 110);
        dial.setPrefSize(110, 110);
        dial.setMaxSize(110, 110);

        Label foot = new Label(footer);
        foot.setStyle("-fx-text-fill:" + SUBTLE_TEXT + ";-fx-font-size:10px;");

        box.getChildren().addAll(heading, dial, foot);
        return box;
    }

    private Label getDonutValueLabel(VBox box) {
        StackPane dial = (StackPane) box.getChildren().get(1);
        VBox center = (VBox) dial.getChildren().get(2);
        return (Label) center.getChildren().get(0);
    }

    private Circle getDonutArcCircle(VBox box) {
        StackPane dial = (StackPane) box.getChildren().get(1);
        return (Circle) dial.getChildren().get(1);
    }

    private void setDonutPercent(Circle arc, Label value, double percent) {
        double p = Math.max(0, Math.min(100, Double.isNaN(percent) ? 0 : percent));
        double circumference = 2 * Math.PI * 44;
        arc.getStrokeDashArray().setAll(circumference * p / 100, circumference);
        arc.setVisible(p > 0); // a zero-length dash with round caps would still paint a dot
        value.setText(Double.isNaN(percent) ? "N/A" : String.format(Locale.US, "%.0f%%", p));
    }

    private Label createPillBadge(String text, String color) {
        Label l = new Label(text);
        l.setStyle("-fx-background-color:#162844;-fx-text-fill:" + color + ";-fx-border-color:" + color
                + ";-fx-border-radius:12;-fx-background-radius:12;-fx-font-size:11px;-fx-font-weight:bold;-fx-padding:3 10 3 10;");
        return l;
    }

    private Button createSmallButton(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color:#1e293b;-fx-text-fill:" + WHITE_TEXT
                + ";-fx-font-size:11px;-fx-font-weight:bold;-fx-background-radius:6;-fx-padding:5 12 5 12;-fx-cursor:hand;");
        return b;
    }

    private Label createSectionTitle(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:16px;-fx-font-weight:bold;");
        return l;
    }

    private void addGridItem(GridPane g, int col, int row, String title, String val, String color) {
        VBox b = new VBox(2);
        Label t = new Label(title);
        t.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:11px;");
        Label v = new Label(val);
        v.setStyle("-fx-text-fill:" + color + ";-fx-font-size:13px;-fx-font-weight:bold;");
        b.getChildren().addAll(t, v);
        g.add(b, col, row);
    }

    private VBox createEmptyStateWidget(String message) {
        VBox b = new VBox(8);
        b.setAlignment(Pos.CENTER);
        b.setPadding(new Insets(40));
        b.setStyle(getPanelStyle());
        Label l = new Label(message);
        l.setStyle("-fx-text-fill:" + SUBTLE_TEXT + ";-fx-font-size:13px;");
        b.getChildren().add(l);
        return b;
    }

    private String getPanelStyle() {
        return "-fx-background-color:" + PANEL_BG + ";-fx-background-radius:12;-fx-border-color:" + BORDER_COLOR
                + ";-fx-border-radius:12;";
    }

    private String formatSpeed(double bytesPerSec) {
        if (bytesPerSec >= 1_000_000.0) {
            return String.format(Locale.US, "%.2f MB/s", bytesPerSec / 1_000_000.0);
        } else if (bytesPerSec >= 1_000.0) {
            return String.format(Locale.US, "%.2f KB/s", bytesPerSec / 1_000.0);
        } else {
            return String.format(Locale.US, "%.2f B/s", bytesPerSec);
        }
    }

    private String formatDataSize(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0);
        } else if (bytes >= 1_000_000L) {
            return String.format(Locale.US, "%.2f MB", bytes / 1_000_000.0);
        } else if (bytes >= 1_000L) {
            return String.format(Locale.US, "%.2f KB", bytes / 1_000.0);
        } else {
            return bytes + " B";
        }
    }

    private static String formatUptime(long seconds) {
        if (seconds <= 0)
            return "N/A";
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long mins = (seconds % 3600) / 60;
        if (days > 0)
            return String.format(Locale.US, "%dd %dh %dm", days, hours, mins);
        if (hours > 0)
            return String.format(Locale.US, "%dh %dm", hours, mins);
        return String.format(Locale.US, "%dm", mins);
    }

    private String formatMs(double ms) {
        if (!(ms >= 0)) // negative or NaN: not measured
            return "N/A";
        return String.format(Locale.US, "%.1f ms", ms);
    }

    private String safe(String s) {
        return (s == null || s.isBlank()) ? "N/A" : s;
    }

    // ================= SHUTDOWN =================

    @Override
    public void stop() {
        shutdown();
    }

    private void shutdown() {
        if (!shuttingDown.compareAndSet(false, true))
            return;
        LOG.info("PulseNet NOC Dashboard shutting down...");

        if (refreshTimeline != null)
            refreshTimeline.stop();
        if (lanScanner != null)
            lanScanner.shutdownNow();
        if (watchdog != null)
            watchdog.stop();
        if (discoveryServer != null)
            discoveryServer.stop();
        if (probeServer != null)
            probeServer.stop();
        if (server != null)
            server.stop();

        Platform.exit();
    }

    public static void main(String[] args) {
        if (UiOptions.wantsHelp(args)) {
            UiOptions.printUsage();
            return;
        }
        UiOptions.parse(args); // reports invalid options before any window opens
        launch(args);
    }
}
