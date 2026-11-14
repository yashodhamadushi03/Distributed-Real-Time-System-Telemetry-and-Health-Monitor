package com.pulsenet.ui;

import com.pulsenet.common.model.InternetConnectivityMetrics;
import com.pulsenet.common.model.NetworkMetrics;
import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.common.net.NetworkUtils;
import com.pulsenet.common.net.NetworkUtils.LanAddress;
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
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PulseNet Network Operations Center (NOC) - Interactive Graphical User Interface.
 *
 * <p>Integrates the central telemetry aggregator, UDP node auto-discovery, UDP probe reflection,
 * and watchdog monitoring into a real-time dark-mode operational dashboard.
 *
 * <p>Visualizes:
 * <ul>
 *   <li><b>Advanced Network Telemetry:</b> Real-time RTT latency (Current, Avg, Min, Max),
 *       RFC 3550 smoothed network jitter, dual-window packet loss (rolling window of 50 probes
 *       vs. lifetime cumulative loss), and probe packet counters.</li>
 *   <li><b>Agent Internet Connectivity:</b> Independent agent WAN reachability status,
 *       default gateway discovery and ICMP/TCP reachability, periodic DNS resolution speed,
 *       lightweight external HTTPS transit verification, and automatic disconnect staleness handling.</li>
 *   <li><b>Fleet Health & Topology:</b> System resource utilization (CPU, RAM, Disk, PIDs),
 *       interactive node topology map, real-time trend charts, and watchdog event logs.</li>
 * </ul>
 */
public class PulseNetApp extends Application {

    private static final Logger LOG = Logger.getLogger(PulseNetApp.class.getName());

    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final int DEFAULT_TCP_PORT = 9000;
    public static final int DEFAULT_UDP_DISCOVERY_PORT = 9001;
    public static final int DEFAULT_UDP_PROBE_PORT = 9002;
    public static final double DEFAULT_WATCHDOG_TIMEOUT = 5.0;
    private static final int HISTORY_POINTS = 40;

    // Dark-mode NOC color palette - high-contrast & high clarity
    private static final String BG_COLOR = "#0A0E17";
    private static final String SIDEBAR_COLOR = "#101622";
    private static final String PANEL_BG = "#131C2D";
    private static final String PANEL_ALT = "#19243A";
    private static final String BORDER_COLOR = "#22334C";
    private static final String CYAN_ACCENT = "#00E5FF";
    private static final String BLUE_ACCENT = "#2979FF";
    private static final String GREEN_ACCENT = "#00E676";
    private static final String YELLOW_ACCENT = "#FFD600";
    private static final String RED_ACCENT = "#FF1744";
    private static final String WHITE_TEXT = "#FFFFFF";
    private static final String MUTED_TEXT = "#90A4AE";

    private int tcpPort = DEFAULT_TCP_PORT;
    private int udpDiscoveryPort = DEFAULT_UDP_DISCOVERY_PORT;
    private int udpProbePort = DEFAULT_UDP_PROBE_PORT;
    private String serverId = "pulsenet-noc-server";

    private NodeRegistry registry;
    private TelemetryServer server;
    private DiscoveryServer discoveryServer;
    private NetworkProbeServer probeServer;
    private WatchdogSupervisor watchdog;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private VBox contentArea;
    private Label pageTitle;
    private Label pageSubtitle;
    private Label liveStatusBadge;
    private Timeline refreshTimeline;
    private String currentPage = "NOC Overview";
    private final List<Button> navButtons = new ArrayList<>();
    private String selectedNodeId = null;

    // Overview KPIs
    private Label upValue, downValue, avgRttValue, avgJitterValue, packetLossValue, internetStatusValue;
    private Label networkValue, cpuValue, memoryValue, diskValue, forecastLabel, deviceCountLabel;
    private Circle networkArc, cpuArc, memoryArc, diskArc;
    private VBox inventoryBoxList, eventFeedBox;
    private Pane topologyPane;

    // Trend Charts
    private LineChart<Number, Number> systemChart;
    private LineChart<Number, Number> latencyChart;
    private XYChart.Series<Number, Number> cpuSeries;
    private XYChart.Series<Number, Number> memorySeries;
    private XYChart.Series<Number, Number> rttSeries;
    private XYChart.Series<Number, Number> jitterSeries;
    private int chartTick = 0;

    @Override
    public void init() {
        // Parse application arguments (e.g. --port 9000 --udp-port 9001 --probe-port 9002)
        Parameters params = getParameters();
        if (params != null) {
            List<String> raw = params.getRaw();
            for (int i = 0; i < raw.size(); i++) {
                String arg = raw.get(i);
                if ("--port".equals(arg) && i + 1 < raw.size()) {
                    try { tcpPort = Integer.parseInt(raw.get(++i)); } catch (Exception ignored) {}
                } else if ("--udp-port".equals(arg) && i + 1 < raw.size()) {
                    try { udpDiscoveryPort = Integer.parseInt(raw.get(++i)); } catch (Exception ignored) {}
                } else if (("--probe-port".equals(arg) || "--udp-probe-port".equals(arg)) && i + 1 < raw.size()) {
                    try { udpProbePort = Integer.parseInt(raw.get(++i)); } catch (Exception ignored) {}
                } else if ("--server-id".equals(arg) && i + 1 < raw.size()) {
                    serverId = raw.get(++i);
                }
            }
        }
    }

    @Override
    public void start(Stage stage) {
        registry = new NodeRegistry();

        // 1. Start core Telemetry Server (TCP 9000)
        server = new TelemetryServer(DEFAULT_HOST, tcpPort, registry);

        // 2. Start UDP Discovery Server (UDP 9001)
        discoveryServer = new DiscoveryServer(DEFAULT_HOST, udpDiscoveryPort, DEFAULT_HOST, tcpPort, udpProbePort, serverId);

        // 3. Start UDP Network Probe Server (UDP 9002) for RTT Latency and RFC 3550 Jitter
        probeServer = new NetworkProbeServer(DEFAULT_HOST, udpProbePort);

        // 4. Start Watchdog Supervisor
        watchdog = new WatchdogSupervisor(registry, DEFAULT_WATCHDOG_TIMEOUT);

        try {
            server.start();
            discoveryServer.start();
            probeServer.start();
            watchdog.start();
            LOG.info(String.format("PulseNet NOC Servers active: TCP %s:%d, UDP Discovery :%d, UDP Probe :%d",
                    DEFAULT_HOST, tcpPort, udpDiscoveryPort, udpProbePort));
        } catch (IOException ex) {
            LOG.log(Level.SEVERE, "Could not start PulseNet servers", ex);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("PulseNet Startup Error");
            alert.setHeaderText("Failed to bind network listening ports");
            alert.setContentText(String.format("TCP Port %d or UDP Ports %d/%d may already be in use.\n%s",
                    tcpPort, udpDiscoveryPort, udpProbePort, ex.getMessage()));
            alert.showAndWait();
            shutdown();
            return;
        }

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:" + BG_COLOR + ";");
        root.setLeft(createSidebar());

        VBox mainLayout = new VBox(18);
        mainLayout.setPadding(new Insets(24, 28, 28, 28));
        mainLayout.setStyle("-fx-background-color:" + BG_COLOR + ";");
        mainLayout.getChildren().add(createHeader());

        contentArea = new VBox(18);
        mainLayout.getChildren().add(contentArea);

        ScrollPane scroll = new ScrollPane(mainLayout);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background:" + BG_COLOR + ";-fx-background-color:" + BG_COLOR + ";");
        root.setCenter(scroll);

        showOverview();

        Scene scene = new Scene(root, 1420, 860);
        stage.setTitle("PulseNet | Network Operations Center & Real-Time Telemetry Monitor");
        stage.setWidth(1420);
        stage.setHeight(860);
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        stage.setMaximized(true);
        stage.setScene(scene);
        stage.centerOnScreen();
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        // 1-second dynamic UI refresh timer
        refreshTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshCurrentView()));
        refreshTimeline.setCycleCount(Timeline.INDEFINITE);
        refreshTimeline.play();
        refreshCurrentView();
    }

    // ================= SIDEBAR & NAVIGATION =================

    private VBox createSidebar() {
        VBox box = new VBox(9);
        box.setPadding(new Insets(26, 16, 20, 16));
        box.setPrefWidth(235);
        box.setMinWidth(235);
        box.setStyle("-fx-background-color:" + SIDEBAR_COLOR + ";-fx-border-color:transparent " + BORDER_COLOR + " transparent transparent;");

        // Branding
        HBox brand = new HBox(12);
        brand.setAlignment(Pos.CENTER_LEFT);
        Label mark = new Label("P");
        mark.setStyle("-fx-text-fill:" + CYAN_ACCENT + ";-fx-font-size:26px;-fx-font-weight:bold;-fx-border-color:" + CYAN_ACCENT + ";-fx-border-radius:8;-fx-padding:2 10 2 10;");
        VBox brandText = new VBox(2);
        Label name = new Label("PULSENET");
        name.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:16px;-fx-font-weight:bold;");
        Label sub = new Label("NOC & NETWORK TELEMETRY");
        sub.setStyle("-fx-text-fill:" + CYAN_ACCENT + ";-fx-font-size:8px;-fx-font-weight:bold;");
        brandText.getChildren().addAll(name, sub);
        brand.getChildren().addAll(mark, brandText);

        Label group = createMutedLabel("MONITORING & TELEMETRY");
        group.setPadding(new Insets(22, 0, 8, 8));

        box.getChildren().addAll(
                brand,
                group,
                createNavButton("▣", "NOC Overview", this::showOverview),
                createNavButton("⌁", "Network Fleet", this::showNetworkFleet),
                createNavButton("▤", "Server Fleet", this::showServerFleet),
                createNavButton("⌘", "Topology", this::showTopology),
                createNavButton("⚠", "Alerts & Events", this::showAlerts),
                createNavButton("⚙", "Settings & Ports", this::showSettings)
        );

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Label system = new Label("●  SYSTEM ONLINE");
        system.setMaxWidth(Double.MAX_VALUE);
        system.setAlignment(Pos.CENTER);
        system.setPadding(new Insets(10, 8, 10, 8));
        system.setStyle("-fx-background-color:#062C32;-fx-text-fill:" + GREEN_ACCENT + ";-fx-background-radius:7;-fx-font-size:10px;-fx-font-weight:bold;");

        Label endpoints = createMutedLabel("TCP :" + tcpPort + "  •  UDP :" + udpProbePort);
        endpoints.setAlignment(Pos.CENTER);
        endpoints.setMaxWidth(Double.MAX_VALUE);

        box.getChildren().addAll(spacer, system, endpoints);
        return box;
    }

    private Button createNavButton(String icon, String text, Runnable action) {
        Button b = new Button(icon + "   " + text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setPadding(new Insets(11, 12, 11, 12));
        b.setStyle(getNavButtonStyle(false));
        b.setOnAction(e -> {
            navButtons.forEach(btn -> btn.setStyle(getNavButtonStyle(btn == b)));
            action.run();
        });
        navButtons.add(b);
        if ("NOC Overview".equals(text)) b.setStyle(getNavButtonStyle(true));
        return b;
    }

    private String getNavButtonStyle(boolean active) {
        return "-fx-background-color:" + (active ? "#073451" : "transparent") +
                ";-fx-text-fill:" + (active ? CYAN_ACCENT : "#B7C7D9") +
                ";-fx-font-size:12px;-fx-font-weight:" + (active ? "bold" : "normal") +
                ";-fx-background-radius:7;-fx-border-color:" + (active ? CYAN_ACCENT : "transparent") +
                ";-fx-border-width:0 0 0 3;-fx-border-radius:7;-fx-cursor:hand;";
    }

    // ================= HEADER =================

    private HBox createHeader() {
        VBox titles = new VBox(4);
        pageSubtitle = new Label();
        pageSubtitle.setStyle("-fx-text-fill:" + CYAN_ACCENT + ";-fx-font-size:11px;-fx-font-weight:bold;");
        pageTitle = new Label();
        pageTitle.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:26px;-fx-font-weight:bold;");
        titles.getChildren().addAll(pageSubtitle, pageTitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Clear Server Status & Port Banner
        HBox serverChip = new HBox(8);
        serverChip.setAlignment(Pos.CENTER);
        serverChip.setPadding(new Insets(8, 16, 8, 16));
        serverChip.setStyle("-fx-background-color:#072B28;-fx-border-color:" + GREEN_ACCENT + ";-fx-border-radius:18;-fx-background-radius:18;-fx-border-width:1.5;");
        Label dot = new Label("●");
        dot.setStyle("-fx-text-fill:" + GREEN_ACCENT + ";-fx-font-size:13px;");
        Label srvText = new Label("SERVER RUNNING  (TCP :" + tcpPort + " | PROBE UDP :" + udpProbePort + ")");
        srvText.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:11px;-fx-font-weight:bold;");
        serverChip.getChildren().addAll(dot, srvText);

        Button viewOverviewBtn = new Button("▣  OVERVIEW");
        viewOverviewBtn.setStyle("-fx-background-color:#131C2D;-fx-border-color:" + BORDER_COLOR + ";-fx-text-fill:white;-fx-font-size:11px;-fx-font-weight:bold;-fx-background-radius:18;-fx-padding:8 14 8 14;-fx-cursor:hand;");
        viewOverviewBtn.setOnAction(e -> showOverview());

        Button viewFleetBtn = new Button("⌁  NETWORK FLEET");
        viewFleetBtn.setStyle("-fx-background-color:" + BLUE_ACCENT + ";-fx-text-fill:white;-fx-font-size:11px;-fx-font-weight:bold;-fx-background-radius:18;-fx-padding:8 14 8 14;-fx-cursor:hand;");
        viewFleetBtn.setOnAction(e -> showNetworkFleet());

        liveStatusBadge = new Label("●  LIVE TELEMETRY");
        liveStatusBadge.setPadding(new Insets(8, 14, 8, 14));
        liveStatusBadge.setStyle("-fx-background-color:#131C2D;-fx-text-fill:" + GREEN_ACCENT + ";-fx-border-color:" + BORDER_COLOR + ";-fx-border-radius:18;-fx-background-radius:18;-fx-font-size:10px;-fx-font-weight:bold;");

        HBox h = new HBox(12, titles, spacer, serverChip, viewOverviewBtn, viewFleetBtn, liveStatusBadge);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private void updateHeading(String title, String subtitle) {
        pageTitle.setText(title);
        pageSubtitle.setText(subtitle);
    }

    // ================= VIEW 1: NOC OVERVIEW =================

    private void showOverview() {
        currentPage = "NOC Overview";
        updateHeading("NOC OVERVIEW", "REAL-TIME INFRASTRUCTURE VISIBILITY & ADVANCED NETWORK HEALTH");
        contentArea.getChildren().clear();

        // 6 Top KPI Cards
        HBox kpiRow = new HBox(12);
        upValue = createKpiCard(kpiRow, "NODES ONLINE", GREEN_ACCENT, "✓");
        downValue = createKpiCard(kpiRow, "NODES OFFLINE", RED_ACCENT, "×");
        avgRttValue = createKpiCard(kpiRow, "AVG RTT LATENCY", CYAN_ACCENT, "⌁");
        avgJitterValue = createKpiCard(kpiRow, "AVG JITTER (RFC 3550)", YELLOW_ACCENT, "△");
        packetLossValue = createKpiCard(kpiRow, "ROLLING LOSS %", GREEN_ACCENT, "◌");
        internetStatusValue = createKpiCard(kpiRow, "INTERNET ACCESS", GREEN_ACCENT, "🌐");

        // Circular Gauges Row + Forecast Box
        HBox gaugesRow = new HBox(14);
        gaugesRow.setAlignment(Pos.CENTER);
        gaugesRow.setPadding(new Insets(16));
        gaugesRow.setStyle(getPanelStyle());
        HBox.setHgrow(gaugesRow, Priority.ALWAYS);

        VBox g1 = createGaugeWidget("NETWORK HEALTH", GREEN_ACCENT, "⌁");
        VBox g2 = createGaugeWidget("CPU UTILIZATION", RED_ACCENT, "◉");
        VBox g3 = createGaugeWidget("MEMORY USAGE", RED_ACCENT, "▤");
        VBox g4 = createGaugeWidget("DISK CAPACITY", RED_ACCENT, "◌");

        networkValue = getGaugeLabel(g1); cpuValue = getGaugeLabel(g2);
        memoryValue = getGaugeLabel(g3); diskValue = getGaugeLabel(g4);
        networkArc = getGaugeCircle(g1); cpuArc = getGaugeCircle(g2);
        memoryArc = getGaugeCircle(g3); diskArc = getGaugeCircle(g4);
        gaugesRow.getChildren().addAll(g1, g2, g3, g4);

        VBox forecastBox = new VBox(10);
        forecastBox.setPadding(new Insets(18));
        forecastBox.setMinWidth(270);
        forecastBox.setPrefWidth(290);
        forecastBox.setStyle(getPanelStyle());
        forecastBox.getChildren().add(createHeadingLabel("◈  TELEMETRY FORECAST"));
        forecastLabel = new Label("Waiting for telemetry from monitored devices.");
        forecastLabel.setWrapText(true);
        forecastLabel.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:11px;");
        deviceCountLabel = new Label("REGISTERED DEVICES                           0");
        deviceCountLabel.setStyle("-fx-text-fill:" + CYAN_ACCENT + ";-fx-font-size:10px;-fx-font-weight:bold;");
        forecastBox.getChildren().addAll(forecastLabel, deviceCountLabel);

        HBox upperRow = new HBox(14, gaugesRow, forecastBox);

        // Lower Section: Fleet Inventory + Events Feed + Topology
        HBox lowerRow = new HBox(14);

        VBox inventoryCard = new VBox(12);
        inventoryCard.setPadding(new Insets(16));
        inventoryCard.setStyle(getPanelStyle());
        HBox invHeader = new HBox(8);
        invHeader.getChildren().add(createHeadingLabel("♧  FLEET INVENTORY & NETWORK METRICS"));
        Region push1 = new Region(); HBox.setHgrow(push1, Priority.ALWAYS);
        Button navFleet = createSmallButton("OPEN FULL FLEET →");
        navFleet.setOnAction(e -> showNetworkFleet());
        invHeader.getChildren().addAll(push1, navFleet);
        inventoryBoxList = new VBox(8);
        inventoryCard.getChildren().addAll(invHeader, inventoryBoxList);
        HBox.setHgrow(inventoryCard, Priority.ALWAYS);

        VBox eventsCard = new VBox(12);
        eventsCard.setPadding(new Insets(16));
        eventsCard.setMinWidth(380);
        eventsCard.setPrefWidth(420);
        eventsCard.setStyle(getPanelStyle());
        eventsCard.getChildren().add(createHeadingLabel("⚠  WATCHDOG & CONNECTION EVENTS"));
        eventFeedBox = new VBox(7);
        eventsCard.getChildren().add(eventFeedBox);

        lowerRow.getChildren().addAll(inventoryCard, eventsCard);

        // Real-Time Charts Row
        systemChart = createLineChart("HOST SYSTEM LOAD (%)", "%");
        cpuSeries = addSeries(systemChart, "CPU Load");
        memorySeries = addSeries(systemChart, "Memory");

        latencyChart = createLineChart("LIVE NETWORK RTT LATENCY & JITTER (ms)", "ms");
        rttSeries = addSeries(latencyChart, "Current RTT (ms)");
        jitterSeries = addSeries(latencyChart, "Avg Jitter (ms)");

        HBox chartsRow = new HBox(14, createChartContainer(systemChart), createChartContainer(latencyChart));

        contentArea.getChildren().addAll(kpiRow, upperRow, chartsRow, lowerRow);
        refreshOverview();
    }

    private void refreshOverview() {
        if (!"NOC Overview".equals(currentPage) || registry == null) return;

        List<NodeRecord> nodes = new ArrayList<>(registry.getAllNodes());
        nodes.sort(Comparator.comparing(NodeRecord::getNodeId, String.CASE_INSENSITIVE_ORDER));

        int up = 0, down = 0, degraded = 0, onlineInternet = 0;
        double cpuSum = 0, memSum = 0, diskSum = 0;
        double rttSum = 0, jitterSum = 0, lossSum = 0;
        int activeNodeCount = 0;

        for (NodeRecord n : nodes) {
            String status = n.getStatus() == null ? "UNKNOWN" : n.getStatus();
            boolean isOffline = "OFFLINE".equalsIgnoreCase(status);
            if (isOffline) {
                down++;
            } else {
                up++;
            }

            TelemetryPayload p = n.getLatestPayload();
            NetworkMetrics net = n.getNetworkMetrics();
            InternetConnectivityMetrics inet = n.getInternetMetrics();

            if (!isOffline && p != null) {
                cpuSum += p.getCpuPercent();
                memSum += p.getMemoryPercent();
                diskSum += p.getDiskPercent();
                activeNodeCount++;
            }

            if (!isOffline && net != null && net.getCurrentRttMs() >= 0.0) {
                rttSum += net.getCurrentRttMs();
                if (net.getAvgJitterMs() >= 0.0) jitterSum += net.getAvgJitterMs();
                lossSum += net.getPacketLossPercent();
            }

            if (!isOffline && inet != null && InternetConnectivityMetrics.STATUS_ONLINE.equalsIgnoreCase(inet.getStatus())) {
                onlineInternet++;
            }
        }

        // Update KPI values
        upValue.setText(String.valueOf(up));
        downValue.setText(String.valueOf(down));

        double avgRtt = activeNodeCount > 0 ? (rttSum / activeNodeCount) : 0.0;
        double avgJitter = activeNodeCount > 0 ? (jitterSum / activeNodeCount) : 0.0;
        double avgLoss = activeNodeCount > 0 ? (lossSum / activeNodeCount) : 0.0;

        avgRttValue.setText(activeNodeCount > 0 ? String.format(Locale.US, "%.1f ms", avgRtt) : "N/A");
        avgJitterValue.setText(activeNodeCount > 0 ? String.format(Locale.US, "%.1f ms", avgJitter) : "N/A");
        packetLossValue.setText(activeNodeCount > 0 ? String.format(Locale.US, "%.1f%%", avgLoss) : "0.0%");
        internetStatusValue.setText(up > 0 ? String.format(Locale.US, "%d/%d ONLINE", onlineInternet, up) : "OFFLINE");

        // Update circular gauges
        double c = activeNodeCount == 0 ? 0 : cpuSum / activeNodeCount;
        double m = activeNodeCount == 0 ? 0 : memSum / activeNodeCount;
        double d = activeNodeCount == 0 ? 0 : diskSum / activeNodeCount;

        setGauge(networkArc, networkValue, nodes.isEmpty() ? 100 : up * 100.0 / nodes.size());
        setGauge(cpuArc, cpuValue, c);
        setGauge(memoryArc, memoryValue, m);
        setGauge(diskArc, diskValue, 100 - d);

        forecastLabel.setText(nodes.isEmpty()
                ? "No infrastructure agents are registered yet. Start an agent to begin live monitoring."
                : down == 0
                ? String.format("All %d node(s) reporting live telemetry. RTT: %.1fms, Loss: %.1f%%.", up, avgRtt, avgLoss)
                : String.format("%d node(s) offline. %d active node(s) operational.", down, up));
        deviceCountLabel.setText("REGISTERED DEVICES                           " + nodes.size());

        // Update Inventory Rows
        refreshInventoryList(nodes);

        // Update Events Feed
        refreshEventFeed();

        // Update Trend Charts
        chartTick++;
        appendChartPoint(cpuSeries, c);
        appendChartPoint(memorySeries, m);
        appendChartPoint(rttSeries, avgRtt);
        appendChartPoint(jitterSeries, avgJitter);

        liveStatusBadge.setText("●  LIVE TELEMETRY  •  SERVER ONLINE (: " + tcpPort + ")");
    }

    private void refreshInventoryList(List<NodeRecord> nodes) {
        inventoryBoxList.getChildren().clear();
        if (nodes.isEmpty()) {
            Label empty = createMutedLabel("No agents registered. Waiting for incoming telemetry on TCP " + tcpPort + "...");
            empty.setPadding(new Insets(20));
            inventoryBoxList.getChildren().add(empty);
            return;
        }

        for (NodeRecord n : nodes) {
            boolean online = !"OFFLINE".equalsIgnoreCase(n.getStatus());
            NetworkMetrics net = n.getNetworkMetrics();
            InternetConnectivityMetrics inet = n.getInternetMetrics();

            HBox row = new HBox(12);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(10, 14, 10, 14));
            row.setStyle("-fx-background-color:" + PANEL_ALT + ";-fx-border-color:" + BORDER_COLOR + ";-fx-border-radius:7;-fx-background-radius:7;");

            Label icon = new Label("▣");
            icon.setStyle("-fx-text-fill:" + (online ? GREEN_ACCENT : RED_ACCENT) + ";-fx-font-size:16px;");

            VBox idBox = new VBox(2);
            Label nodeName = new Label(n.getNodeId());
            nodeName.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:11px;-fx-font-weight:bold;");
            Label hostInfo = createMutedLabel(n.getAgentHostname() + "  •  " + (n.getRemoteAddress() != null ? n.getRemoteAddress() : "N/A"));
            idBox.getChildren().addAll(nodeName, hostInfo);

            Region push = new Region();
            HBox.setHgrow(push, Priority.ALWAYS);

            // Network telemetry summary badges
            String rttStr = (net != null && net.getCurrentRttMs() >= 0.0) ? String.format(Locale.US, "%.1f ms", net.getCurrentRttMs()) : "N/A";
            String lossStr = (net != null && net.getProbesSent() > 0) ? String.format(Locale.US, "%.1f%%", net.getPacketLossPercent()) : "0.0%";
            String inetStr = (inet != null) ? inet.getStatus() : "UNKNOWN";

            VBox netSummary = new VBox(2);
            netSummary.setAlignment(Pos.CENTER_RIGHT);
            Label rttBadge = new Label("RTT: " + rttStr + "  |  Loss: " + lossStr);
            rttBadge.setStyle("-fx-text-fill:" + (online ? CYAN_ACCENT : MUTED_TEXT) + ";-fx-font-size:9px;-fx-font-weight:bold;");
            Label inetBadge = new Label("Internet: " + inetStr);
            inetBadge.setStyle("-fx-text-fill:" + (InternetConnectivityMetrics.STATUS_ONLINE.equals(inetStr) ? GREEN_ACCENT : RED_ACCENT) + ";-fx-font-size:8px;");
            netSummary.getChildren().addAll(rttBadge, inetBadge);

            Label statusBadge = createStatusBadge(n.getStatus());

            Button inspectBtn = createSmallButton("DETAILS");
            inspectBtn.setOnAction(e -> showNodeDetails(n.getNodeId()));

            row.getChildren().addAll(icon, idBox, push, netSummary, statusBadge, inspectBtn);
            inventoryBoxList.getChildren().add(row);
        }
    }

    private void refreshEventFeed() {
        eventFeedBox.getChildren().clear();
        List<String> recentEvents = registry.getRecentEvents(8);
        if (recentEvents.isEmpty()) {
            eventFeedBox.getChildren().add(createMutedLabel("No connection or threshold events recorded."));
            return;
        }

        for (String ev : recentEvents) {
            Label l = new Label(ev);
            l.setWrapText(true);
            String color = CYAN_ACCENT;
            if (ev.contains("[WARN]") || ev.contains("[LOST]")) color = RED_ACCENT;
            else if (ev.contains("[REC]") || ev.contains("[OK]")) color = GREEN_ACCENT;
            l.setStyle("-fx-text-fill:" + color + ";-fx-font-size:9px;-fx-font-family:monospace;");
            eventFeedBox.getChildren().add(l);
        }
    }

    // ================= VIEW 2: NETWORK FLEET (COMMAND PROMPT EQUIVALENT) =================

    private void showNetworkFleet() {
        currentPage = "Network Fleet";
        updateHeading("NETWORK FLEET", "RTT LATENCY, RFC 3550 JITTER, PACKET LOSS, AND AGENT INTERNET CONNECTIVITY");
        contentArea.getChildren().clear();
        refreshNetworkFleet();
    }

    private void refreshNetworkFleet() {
        if (!"Network Fleet".equals(currentPage)) return;
        contentArea.getChildren().clear();

        List<NodeRecord> nodes = new ArrayList<>(registry.getAllNodes());
        nodes.sort(Comparator.comparing(NodeRecord::getNodeId, String.CASE_INSENSITIVE_ORDER));

        if (nodes.isEmpty()) {
            contentArea.getChildren().add(createEmptyStateWidget("No agent nodes connected. Waiting for telemetry streaming on TCP port " + tcpPort + "..."));
            return;
        }

        long now = System.currentTimeMillis();

        for (NodeRecord node : nodes) {
            TelemetryPayload payload = node.getLatestPayload();
            NetworkMetrics net = node.getNetworkMetrics();
            InternetConnectivityMetrics inet = node.getInternetMetrics();
            boolean isOffline = "OFFLINE".equalsIgnoreCase(node.getStatus());
            long silenceSec = Math.max(0, (now - node.getLastSeenTimestamp()) / 1000);

            VBox card = new VBox(14);
            card.setPadding(new Insets(20, 22, 20, 22));
            card.setStyle(getPanelStyle());

            // 1. CARD HEADER
            HBox header = new HBox(12);
            header.setAlignment(Pos.CENTER_LEFT);

            Label title = new Label("Node ID: " + node.getNodeId());
            title.setStyle("-fx-text-fill:" + CYAN_ACCENT + ";-fx-font-size:16px;-fx-font-weight:bold;");

            Label hostPill = new Label("[Host: " + node.getAgentHostname() + "]");
            hostPill.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:12px;");

            Label ipPill = new Label("[IP: " + (node.getRemoteAddress() != null ? node.getRemoteAddress() : "unknown") + "]");
            ipPill.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:11px;");

            Region push = new Region();
            HBox.setHgrow(push, Priority.ALWAYS);

            Label connBadge = createStatusBadge(isOffline ? "OFFLINE" : (net != null && net.getConnectionStatus() != null ? net.getConnectionStatus() : "CONNECTED"));

            header.getChildren().addAll(title, hostPill, ipPill, push, connBadge);

            // 2. ENDPOINT COORDINATES
            String serverEndpoint = (net != null && net.getServerHost() != null && !net.getServerHost().isBlank())
                    ? (net.getServerHost() + ":" + (net.getServerPort() > 0 ? net.getServerPort() : tcpPort))
                    : (DEFAULT_HOST + ":" + tcpPort);
            String probeEndpoint = (net != null && net.getUdpProbePort() > 0)
                    ? (":" + net.getUdpProbePort() + " (UDP)")
                    : ":" + udpProbePort + " (UDP)";
            String protocol = (net != null && net.getProtocol() != null) ? net.getProtocol() : "TCP + UDP probes";

            Label transportLine = new Label(String.format("Server: %s (TCP) | Probe: %s | Protocol: %s",
                    serverEndpoint, probeEndpoint, protocol));
            transportLine.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:11px;");

            Separator sep1 = new Separator();

            // 3. LATENCY (RTT) SECTION
            VBox latencySection = new VBox(6);
            Label latHeader = createHeadingLabel("LATENCY (ROUND-TRIP TIME):");
            latHeader.setStyle("-fx-text-fill:" + CYAN_ACCENT + ";-fx-font-size:12px;-fx-font-weight:bold;");

            GridPane latGrid = new GridPane();
            latGrid.setHgap(36);
            latGrid.setVgap(6);

            addGridItem(latGrid, 0, 0, "Current RTT", formatMs(net != null ? net.getCurrentRttMs() : -1.0), CYAN_ACCENT);
            addGridItem(latGrid, 1, 0, "Average RTT", formatMs(net != null ? net.getAvgRttMs() : -1.0), CYAN_ACCENT);
            addGridItem(latGrid, 2, 0, "Minimum RTT", formatMs(net != null ? net.getMinRttMs() : -1.0), GREEN_ACCENT);
            addGridItem(latGrid, 3, 0, "Maximum RTT", formatMs(net != null ? net.getMaxRttMs() : -1.0), YELLOW_ACCENT);
            latencySection.getChildren().addAll(latHeader, latGrid);

            // 4. NETWORK QUALITY & RFC 3550 JITTER
            VBox qualitySection = new VBox(6);
            Label qHeader = createHeadingLabel("NETWORK QUALITY & JITTER (RFC 3550):");
            qHeader.setStyle("-fx-text-fill:" + YELLOW_ACCENT + ";-fx-font-size:12px;-fx-font-weight:bold;");

            GridPane qGrid = new GridPane();
            qGrid.setHgap(36);
            qGrid.setVgap(6);

            double rollingLoss = net != null ? net.getPacketLossPercent() : 0.0;
            double cumLoss = net != null ? net.getCumulativeLossPercent() : 0.0;
            int winSize = net != null ? net.getWindowSize() : 50;
            double jitter = net != null ? net.getAvgJitterMs() : -1.0;

            String rollingLossStr = (net != null && net.getProbesSent() > 0) ? String.format(Locale.US, "%.1f%% (Win %d)", rollingLoss, winSize) : "0.0%";
            String cumLossStr = (net != null && net.getProbesSent() > 0) ? String.format(Locale.US, "%.1f%%", cumLoss) : "0.0%";
            String lossColor = rollingLoss > 0.0 ? RED_ACCENT : GREEN_ACCENT;
            String cumLossColor = cumLoss > 0.0 ? RED_ACCENT : GREEN_ACCENT;

            addGridItem(qGrid, 0, 0, "Packet Loss (Rolling)", rollingLossStr, lossColor);
            addGridItem(qGrid, 1, 0, "Cumulative Loss", cumLossStr, cumLossColor);
            addGridItem(qGrid, 2, 0, "Average Jitter", formatMs(jitter), YELLOW_ACCENT);
            addGridItem(qGrid, 3, 0, "Quality Rating", getQualityString(net), GREEN_ACCENT);
            qualitySection.getChildren().addAll(qHeader, qGrid);

            // 5. CONNECTION LIFECYCLE & PROBE STATISTICS
            VBox probeSection = new VBox(6);
            Label pHeader = createHeadingLabel("CONNECTION & PROBE COUNTERS:");
            pHeader.setStyle("-fx-text-fill:" + GREEN_ACCENT + ";-fx-font-size:12px;-fx-font-weight:bold;");

            GridPane pGrid = new GridPane();
            pGrid.setHgap(36);
            pGrid.setVgap(6);

            long uptimeMs = net != null ? net.getConnectionUptimeMillis() : 0L;
            int reconns = net != null ? net.getReconnectionCount() : 0;
            long sent = net != null ? net.getProbesSent() : 0L;
            long recvd = net != null ? net.getProbesReceived() : 0L;
            long lost = net != null ? net.getProbesLost() : 0L;

            addGridItem(pGrid, 0, 0, "Connection Uptime", formatDuration(uptimeMs), GREEN_ACCENT);
            addGridItem(pGrid, 1, 0, "Reconnections", String.valueOf(reconns), reconns > 0 ? YELLOW_ACCENT : GREEN_ACCENT);
            addGridItem(pGrid, 2, 0, "Last Heartbeat", silenceSec + "s ago", GREEN_ACCENT);
            addGridItem(pGrid, 3, 0, "Packets Sent", String.valueOf(sent), WHITE_TEXT);

            addGridItem(pGrid, 0, 1, "Responses Received", String.valueOf(recvd), GREEN_ACCENT);
            addGridItem(pGrid, 1, 1, "Lost Packets", String.valueOf(lost), lost > 0 ? RED_ACCENT : GREEN_ACCENT);
            addGridItem(pGrid, 2, 1, "Agent UDP Port", net != null && net.getAgentSourcePort() > 0 ? String.valueOf(net.getAgentSourcePort()) : "dynamic", MUTED_TEXT);
            addGridItem(pGrid, 3, 1, "Probe Target", serverEndpoint, CYAN_ACCENT);
            probeSection.getChildren().addAll(pHeader, pGrid);

            // 6. AGENT INTERNET CONNECTIVITY
            VBox internetSection = new VBox(6);
            Label inetHeader = createHeadingLabel("AGENT INTERNET CONNECTIVITY:");
            inetHeader.setStyle("-fx-text-fill:" + BLUE_ACCENT + ";-fx-font-size:12px;-fx-font-weight:bold;");

            GridPane inetGrid = new GridPane();
            inetGrid.setHgap(36);
            inetGrid.setVgap(6);

            if (isOffline || silenceSec > 5) {
                // Invalidate stale data when disconnected
                String gw = (inet != null && inet.getGatewayIp() != null) ? inet.getGatewayIp() : "unknown";
                addGridItem(inetGrid, 0, 0, "Internet Status", "OFFLINE (Node Disconnected)", RED_ACCENT);
                addGridItem(inetGrid, 1, 0, "Default Gateway", gw + " (UNREACHABLE)", RED_ACCENT);
                addGridItem(inetGrid, 2, 0, "DNS Resolution", "STALE (Node Disconnected)", RED_ACCENT);
                addGridItem(inetGrid, 3, 0, "External HTTPS", "STALE (Node Disconnected)", RED_ACCENT);
            } else if (inet != null && !InternetConnectivityMetrics.STATUS_UNKNOWN.equals(inet.getStatus())) {
                boolean isOnline = InternetConnectivityMetrics.STATUS_ONLINE.equalsIgnoreCase(inet.getStatus());
                String gwStatus = inet.isGatewayReachable() ? "REACHABLE" : "UNREACHABLE";
                String dnsStatus = inet.isDnsSuccess()
                        ? String.format(Locale.US, "OK (%d ms via %s)", inet.getDnsResolutionTimeMs(), inet.getDnsTestHost())
                        : "FAILED";
                String httpStatus = inet.isHttpsSuccess()
                        ? String.format(Locale.US, "HTTP %d (%d ms via %s)", inet.getHttpStatusCode(), inet.getHttpsResponseTimeMs(), inet.getHttpsTestEndpoint())
                        : "FAILED";

                String statusColor = isOnline ? GREEN_ACCENT : YELLOW_ACCENT;
                addGridItem(inetGrid, 0, 0, "Internet Status", inet.getStatus(), statusColor);
                addGridItem(inetGrid, 1, 0, "Default Gateway", inet.getGatewayIp() + " (" + gwStatus + ")", inet.isGatewayReachable() ? GREEN_ACCENT : RED_ACCENT);
                addGridItem(inetGrid, 2, 0, "DNS Resolution", dnsStatus, inet.isDnsSuccess() ? GREEN_ACCENT : RED_ACCENT);
                addGridItem(inetGrid, 3, 0, "External HTTPS", httpStatus, inet.isHttpsSuccess() ? GREEN_ACCENT : RED_ACCENT);
            } else {
                addGridItem(inetGrid, 0, 0, "Internet Status", "UNKNOWN / Checking...", MUTED_TEXT);
                addGridItem(inetGrid, 1, 0, "Default Gateway", "unknown", MUTED_TEXT);
                addGridItem(inetGrid, 2, 0, "DNS Resolution", "N/A", MUTED_TEXT);
                addGridItem(inetGrid, 3, 0, "External HTTPS", "N/A", MUTED_TEXT);
            }
            internetSection.getChildren().addAll(inetHeader, inetGrid);

            card.getChildren().addAll(header, transportLine, sep1, latencySection, qualitySection, probeSection, internetSection);
            contentArea.getChildren().add(card);
        }
    }

    // ================= VIEW 3: SERVER FLEET & NODE DETAILS =================

    private void showServerFleet() {
        currentPage = "Server Fleet";
        updateHeading("SERVER FLEET", "MONITORED AGENT HARDWARE, SYSTEM STATUS & PROCESS INVENTORY");
        contentArea.getChildren().clear();

        List<NodeRecord> nodes = new ArrayList<>(registry.getAllNodes());
        nodes.sort(Comparator.comparing(NodeRecord::getNodeId, String.CASE_INSENSITIVE_ORDER));

        if (nodes.isEmpty()) {
            contentArea.getChildren().add(createEmptyStateWidget("No agent nodes registered. Start an agent to begin telemetry ingestion."));
            return;
        }

        for (NodeRecord node : nodes) {
            TelemetryPayload p = node.getLatestPayload();
            boolean online = !"OFFLINE".equalsIgnoreCase(node.getStatus());

            HBox card = new HBox(16);
            card.setPadding(new Insets(16, 20, 16, 20));
            card.setAlignment(Pos.CENTER_LEFT);
            card.setStyle(getPanelStyle());

            Label glyph = new Label("▣");
            glyph.setStyle("-fx-text-fill:" + (online ? GREEN_ACCENT : RED_ACCENT) + ";-fx-font-size:22px;");

            VBox info = new VBox(3);
            Label name = new Label(node.getNodeId());
            name.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:14px;-fx-font-weight:bold;");
            Label sub = createMutedLabel("Host: " + node.getAgentHostname() + "  |  IP: " + node.getRemoteAddress());
            info.getChildren().addAll(name, sub);

            Region push = new Region();
            HBox.setHgrow(push, Priority.ALWAYS);

            Label cpuPill = new Label(p != null ? String.format(Locale.US, "CPU: %.1f%%", p.getCpuPercent()) : "CPU: N/A");
            cpuPill.setStyle("-fx-text-fill:" + CYAN_ACCENT + ";-fx-font-size:11px;-fx-font-weight:bold;");

            Label memPill = new Label(p != null ? String.format(Locale.US, "RAM: %.1f%%", p.getMemoryPercent()) : "RAM: N/A");
            memPill.setStyle("-fx-text-fill:" + YELLOW_ACCENT + ";-fx-font-size:11px;-fx-font-weight:bold;");

            Label statusPill = createStatusBadge(node.getStatus());

            Button detailBtn = createSmallButton("INSPECT TELEMETRY →");
            detailBtn.setOnAction(e -> showNodeDetails(node.getNodeId()));

            card.getChildren().addAll(glyph, info, push, cpuPill, memPill, statusPill, detailBtn);
            contentArea.getChildren().add(card);
        }
    }

    private void showNodeDetails(String nodeId) {
        selectedNodeId = nodeId;
        currentPage = "Device Details";
        updateHeading("DEVICE DETAILS: " + nodeId, "COMPREHENSIVE TELEMETRY, LATENCY METRICS, AND INTERNET HEALTH");
        contentArea.getChildren().clear();

        Button backButton = createSmallButton("← BACK TO FLEET");
        backButton.setOnAction(e -> showServerFleet());
        contentArea.getChildren().add(backButton);

        NodeRecord selectedNode = registry.getNode(nodeId).orElse(null);
        if (selectedNode == null) {
            contentArea.getChildren().add(createEmptyStateWidget("Device '" + nodeId + "' not found in active registry."));
            return;
        }

        TelemetryPayload p = selectedNode.getLatestPayload();
        NetworkMetrics net = selectedNode.getNetworkMetrics();
        InternetConnectivityMetrics inet = selectedNode.getInternetMetrics();

        // Main Details Card
        VBox card = new VBox(18);
        card.setPadding(new Insets(24));
        card.setStyle(getPanelStyle());

        HBox top = new HBox(12);
        top.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(selectedNode.getNodeId());
        title.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:20px;-fx-font-weight:bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        top.getChildren().addAll(title, spacer, createStatusBadge(selectedNode.getStatus()));
        card.getChildren().add(top);

        Separator s1 = new Separator();
        card.getChildren().add(s1);

        // Grid 1: System Telemetry
        Label sysTitle = createHeadingLabel("SYSTEM & HOST RESOURCES");
        GridPane sysGrid = new GridPane();
        sysGrid.setHgap(36);
        sysGrid.setVgap(12);

        addGridItem(sysGrid, 0, 0, "CPU Load", p != null ? String.format(Locale.US, "%.1f%%", p.getCpuPercent()) : "N/A");
        addGridItem(sysGrid, 1, 0, "Memory Usage", p != null ? String.format(Locale.US, "%.1f%%", p.getMemoryPercent()) : "N/A");
        addGridItem(sysGrid, 2, 0, "Disk Utilization", p != null ? String.format(Locale.US, "%.1f%%", p.getDiskPercent()) : "N/A");
        addGridItem(sysGrid, 3, 0, "Process Count", p != null ? String.valueOf(p.getProcessCount()) : "N/A");

        addGridItem(sysGrid, 0, 1, "Agent Hostname", selectedNode.getAgentHostname());
        addGridItem(sysGrid, 1, 1, "Remote Socket", selectedNode.getRemoteAddress());
        addGridItem(sysGrid, 2, 1, "Connection Status", selectedNode.getConnectionStatus());
        addGridItem(sysGrid, 3, 1, "Last Heartbeat", Math.max(0, (System.currentTimeMillis() - selectedNode.getLastSeenTimestamp()) / 1000) + "s ago");

        card.getChildren().addAll(sysTitle, sysGrid);

        Separator s2 = new Separator();
        card.getChildren().add(s2);

        // Grid 2: Advanced Network Telemetry
        Label netTitle = createHeadingLabel("ADVANCED NETWORK MONITORING & PROBES");
        GridPane netGrid = new GridPane();
        netGrid.setHgap(36);
        netGrid.setVgap(12);

        addGridItem(netGrid, 0, 0, "Current RTT", formatMs(net != null ? net.getCurrentRttMs() : -1.0));
        addGridItem(netGrid, 1, 0, "Average RTT", formatMs(net != null ? net.getAvgRttMs() : -1.0));
        addGridItem(netGrid, 2, 0, "Min RTT", formatMs(net != null ? net.getMinRttMs() : -1.0));
        addGridItem(netGrid, 3, 0, "Max RTT", formatMs(net != null ? net.getMaxRttMs() : -1.0));

        addGridItem(netGrid, 0, 1, "Average Jitter (RFC 3550)", formatMs(net != null ? net.getAvgJitterMs() : -1.0));
        addGridItem(netGrid, 1, 1, "Rolling Loss (50 probes)", net != null ? String.format(Locale.US, "%.1f%%", net.getPacketLossPercent()) : "0.0%");
        addGridItem(netGrid, 2, 1, "Cumulative Loss", net != null ? String.format(Locale.US, "%.1f%%", net.getCumulativeLossPercent()) : "0.0%");
        addGridItem(netGrid, 3, 1, "Network Quality", getQualityString(net));

        addGridItem(netGrid, 0, 2, "Probes Sent", net != null ? String.valueOf(net.getProbesSent()) : "0");
        addGridItem(netGrid, 1, 2, "Responses Received", net != null ? String.valueOf(net.getProbesReceived()) : "0");
        addGridItem(netGrid, 2, 2, "Lost Packets", net != null ? String.valueOf(net.getProbesLost()) : "0");
        addGridItem(netGrid, 3, 2, "Connection Uptime", formatDuration(net != null ? net.getConnectionUptimeMillis() : 0L));

        card.getChildren().addAll(netTitle, netGrid);

        Separator s3 = new Separator();
        card.getChildren().add(s3);

        // Grid 3: Internet Connectivity
        Label inetTitle = createHeadingLabel("AGENT INTERNET CONNECTIVITY");
        GridPane inetGrid = new GridPane();
        inetGrid.setHgap(36);
        inetGrid.setVgap(12);

        if (inet != null && !InternetConnectivityMetrics.STATUS_UNKNOWN.equals(inet.getStatus())) {
            addGridItem(inetGrid, 0, 0, "Internet Status", inet.getStatus());
            addGridItem(inetGrid, 1, 0, "Default Gateway", inet.getGatewayIp() + (inet.isGatewayReachable() ? " (REACHABLE)" : " (UNREACHABLE)"));
            addGridItem(inetGrid, 2, 0, "DNS Host & Latency", inet.getDnsTestHost() + " (" + inet.getDnsResolutionTimeMs() + "ms)");
            addGridItem(inetGrid, 3, 0, "External HTTPS Probe", "HTTP " + inet.getHttpStatusCode() + " (" + inet.getHttpsResponseTimeMs() + "ms)");
        } else {
            addGridItem(inetGrid, 0, 0, "Internet Status", "UNKNOWN / Offline");
            addGridItem(inetGrid, 1, 0, "Default Gateway", "none");
            addGridItem(inetGrid, 2, 0, "DNS Resolution", "N/A");
            addGridItem(inetGrid, 3, 0, "External HTTPS Probe", "N/A");
        }

        card.getChildren().addAll(inetTitle, inetGrid);
        contentArea.getChildren().add(card);
    }

    // ================= VIEW 4: TOPOLOGY GRAPH =================

    private void showTopology() {
        currentPage = "Topology";
        updateHeading("NETWORK TOPOLOGY", "CENTRAL AGGREGATOR HUB & DISTRIBUTED AGENT SOCKET CONNECTIONS");
        contentArea.getChildren().clear();

        VBox card = new VBox(14);
        card.setPadding(new Insets(20));
        card.setStyle(getPanelStyle());

        topologyPane = new Pane();
        topologyPane.setPrefHeight(420);
        topologyPane.setStyle("-fx-background-color:#051324;-fx-border-color:" + BORDER_COLOR + ";-fx-border-radius:9;-fx-background-radius:9;");

        drawTopology(topologyPane, new ArrayList<>(registry.getAllNodes()));

        card.getChildren().addAll(createHeadingLabel("⌘  LIVE TOPOLOGY GRAPH"), topologyPane);
        contentArea.getChildren().add(card);
    }

    private void drawTopology(Pane pane, List<NodeRecord> nodes) {
        if (pane == null) return;
        pane.getChildren().clear();

        double cx = 580;
        double cy = 90;

        // Central Server Node
        Rectangle serverBox = new Rectangle(cx - 75, cy - 24, 150, 48);
        serverBox.setArcWidth(10);
        serverBox.setArcHeight(10);
        serverBox.setFill(Color.web("#062C44"));
        serverBox.setStroke(Color.web(CYAN_ACCENT));
        serverBox.setStrokeWidth(2);

        Label serverLabel = new Label("PulseNet Server\nTCP: " + tcpPort + " | UDP: " + udpProbePort);
        serverLabel.setAlignment(Pos.CENTER);
        serverLabel.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:10px;-fx-font-weight:bold;");
        serverLabel.setLayoutX(cx - 70);
        serverLabel.setLayoutY(cy - 18);
        pane.getChildren().addAll(serverBox, serverLabel);

        if (nodes.isEmpty()) {
            Label noNodes = createMutedLabel("(No agent nodes connected)");
            noNodes.setLayoutX(cx - 65);
            noNodes.setLayoutY(cy + 60);
            pane.getChildren().add(noNodes);
            return;
        }

        // Agent Nodes Fan-out
        int count = Math.min(nodes.size(), 10);
        double gap = 110;
        double startX = cx - ((count - 1) * gap) / 2.0;
        double agentY = cy + 180;

        for (int i = 0; i < count; i++) {
            NodeRecord n = nodes.get(i);
            double x = startX + i * gap;
            boolean online = !"OFFLINE".equalsIgnoreCase(n.getStatus());

            // Connection Link Line
            Line link = new Line(cx, cy + 24, x, agentY - 14);
            link.setStroke(Color.web(online ? "#12617A" : "#65313C"));
            link.setStrokeWidth(1.8);

            Rectangle devBox = new Rectangle(x - 38, agentY - 14, 76, 42);
            devBox.setArcWidth(8);
            devBox.setArcHeight(8);
            devBox.setFill(Color.web(online ? "#063D36" : "#421E2A"));
            devBox.setStroke(Color.web(online ? GREEN_ACCENT : RED_ACCENT));
            devBox.setStyle("-fx-cursor:hand;");
            devBox.setOnMouseClicked(e -> showNodeDetails(n.getNodeId()));

            Circle statusDot = new Circle(x + 30, agentY - 8, 4, Color.web(online ? GREEN_ACCENT : RED_ACCENT));

            Label devLabel = new Label(shorten(n.getNodeId(), 12));
            devLabel.setStyle("-fx-text-fill:" + CYAN_ACCENT + ";-fx-font-size:9px;-fx-font-weight:bold;-fx-cursor:hand;");
            devLabel.setLayoutX(x - 34);
            devLabel.setLayoutY(agentY - 8);
            devLabel.setOnMouseClicked(e -> showNodeDetails(n.getNodeId()));

            String rtt = (n.getNetworkMetrics() != null && n.getNetworkMetrics().getCurrentRttMs() >= 0)
                    ? String.format(Locale.US, "%.0f ms", n.getNetworkMetrics().getCurrentRttMs())
                    : (online ? "OK" : "OFF");
            Label rttBadge = new Label(rtt);
            rttBadge.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:8px;");
            rttBadge.setLayoutX(x - 34);
            rttBadge.setLayoutY(agentY + 8);

            pane.getChildren().addAll(link, devBox, statusDot, devLabel, rttBadge);
        }
    }

    // ================= VIEW 5: ALERTS & LOGS =================

    private void showAlerts() {
        currentPage = "Alerts";
        updateHeading("ALERTS & WATCHDOG LOGS", "REAL-TIME NODE STATE TRANSITIONS, HEARTBEATS, AND THRESHOLD WARNINGS");
        contentArea.getChildren().clear();

        VBox card = new VBox(14);
        card.setPadding(new Insets(20));
        card.setStyle(getPanelStyle());

        List<String> events = registry.getRecentEvents(40);
        if (events.isEmpty()) {
            card.getChildren().add(createMutedLabel("No alerts or log records generated yet."));
        } else {
            for (String ev : events) {
                Label l = new Label(ev);
                String color = CYAN_ACCENT;
                if (ev.contains("[WARN]") || ev.contains("[LOST]")) color = RED_ACCENT;
                else if (ev.contains("[REC]") || ev.contains("[OK]")) color = GREEN_ACCENT;
                l.setStyle("-fx-text-fill:" + color + ";-fx-font-size:11px;-fx-font-family:monospace;");
                card.getChildren().add(l);
            }
        }
        contentArea.getChildren().add(card);
    }

    // ================= VIEW 6: SETTINGS & PORTS =================

    private void showSettings() {
        currentPage = "Settings";
        updateHeading("SETTINGS & LOCAL NETWORKS", "CONFIGURED PORTS, RUNTIME ADAPTERS, AND SOCKET METRICS");
        contentArea.getChildren().clear();

        VBox card = new VBox(18);
        card.setPadding(new Insets(24));
        card.setStyle(getPanelStyle());

        card.getChildren().add(createHeadingLabel("ACTIVE LISTENING INTERFACES"));

        GridPane g = new GridPane();
        g.setHgap(36);
        g.setVgap(12);

        addGridItem(g, 0, 0, "Telemetry Server Port", String.valueOf(tcpPort) + " (TCP)");
        addGridItem(g, 1, 0, "Discovery Broadcast Port", String.valueOf(udpDiscoveryPort) + " (UDP)");
        addGridItem(g, 2, 0, "Network Probe Port", String.valueOf(udpProbePort) + " (UDP)");
        addGridItem(g, 3, 0, "Server Identifier", serverId);

        card.getChildren().addAll(g, new Separator(), createHeadingLabel("DETECTED LAN IP ADDRESSES"));

        List<LanAddress> lans = NetworkUtils.listLanIPv4Addresses();
        if (lans.isEmpty()) {
            card.getChildren().add(createMutedLabel("No external LAN adapters detected (running loopback)."));
        } else {
            for (LanAddress lan : lans) {
                Label l = new Label("•  " + lan.address() + "  (" + lan.interfaceName() + ")");
                l.setStyle("-fx-text-fill:" + CYAN_ACCENT + ";-fx-font-size:12px;");
                card.getChildren().add(l);
            }
        }

        contentArea.getChildren().add(card);
    }

    // ================= HELPER WIDGETS & FORMATTING =================

    private Label createKpiCard(HBox parent, String title, String color, String icon) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(16, 18, 16, 18));
        card.setStyle(getPanelStyle());
        HBox.setHgrow(card, Priority.ALWAYS);

        HBox top = new HBox(8);
        Label ic = new Label(icon);
        ic.setStyle("-fx-text-fill:" + color + ";-fx-font-size:14px;-fx-font-weight:bold;");
        Label t = createMutedLabel(title);
        top.getChildren().addAll(ic, t);

        Label val = new Label("0");
        val.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:24px;-fx-font-weight:bold;-fx-font-family:'Consolas', monospace;");

        card.getChildren().addAll(top, val);
        parent.getChildren().add(card);
        return val;
    }

    private VBox createGaugeWidget(String title, String color, String icon) {
        VBox box = new VBox(8);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(8));
        HBox.setHgrow(box, Priority.ALWAYS);

        HBox top = new HBox(6);
        top.setAlignment(Pos.CENTER);
        Label ic = new Label(icon);
        ic.setStyle("-fx-text-fill:" + color + ";-fx-font-size:11px;");
        Label t = createMutedLabel(title);
        top.getChildren().addAll(ic, t);

        StackPane ring = new StackPane();
        Circle bg = new Circle(44, Color.TRANSPARENT);
        bg.setStroke(Color.web(PANEL_ALT));
        bg.setStrokeWidth(7);

        Circle arc = new Circle(44, Color.TRANSPARENT);
        arc.setStroke(Color.web(color));
        arc.setStrokeWidth(7);

        Label value = new Label("100%");
        value.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:14px;-fx-font-weight:bold;");

        ring.getChildren().addAll(bg, arc, value);
        box.getChildren().addAll(top, ring);
        return box;
    }

    private Label getGaugeLabel(VBox gauge) {
        StackPane ring = (StackPane) gauge.getChildren().get(1);
        return (Label) ring.getChildren().get(2);
    }

    private Circle getGaugeCircle(VBox gauge) {
        StackPane ring = (StackPane) gauge.getChildren().get(1);
        return (Circle) ring.getChildren().get(1);
    }

    private void setGauge(Circle circle, Label label, double percent) {
        double p = Math.max(0.0, Math.min(100.0, percent));
        label.setText(String.format(Locale.US, "%.0f%%", p));
        double circumference = 2 * Math.PI * 44;
        double strokeLen = (p / 100.0) * circumference;
        circle.getStrokeDashArray().setAll(strokeLen, circumference);
    }

    private LineChart<Number, Number> createLineChart(String title, String yAxisLabel) {
        NumberAxis x = new NumberAxis();
        x.setLabel("TIME");
        x.setTickLabelsVisible(false);
        x.setTickMarkVisible(false);

        NumberAxis y = new NumberAxis();
        y.setLabel(yAxisLabel);

        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setTitle(title);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(true);
        chart.setPrefHeight(230);
        chart.setStyle("-fx-background-color:transparent;-fx-text-fill:" + MUTED_TEXT + ";");
        return chart;
    }

    private XYChart.Series<Number, Number> addSeries(LineChart<Number, Number> chart, String name) {
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        s.setName(name);
        chart.getData().add(s);
        return s;
    }

    private void appendChartPoint(XYChart.Series<Number, Number> s, double value) {
        if (s == null) return;
        s.getData().add(new XYChart.Data<>(chartTick, value));
        if (s.getData().size() > HISTORY_POINTS) {
            s.getData().remove(0);
        }
    }

    private VBox createChartContainer(LineChart<Number, Number> chart) {
        VBox b = new VBox(chart);
        b.setPadding(new Insets(10));
        b.setStyle(getPanelStyle());
        HBox.setHgrow(b, Priority.ALWAYS);
        return b;
    }

    private void addGridItem(GridPane grid, int col, int row, String label, String value) {
        addGridItem(grid, col, row, label, value, WHITE_TEXT);
    }

    private void addGridItem(GridPane grid, int col, int row, String label, String value, String valueColor) {
        VBox b = new VBox(4);
        Label lbl = new Label(label);
        lbl.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:11px;-fx-font-weight:normal;");
        Label val = new Label(value);
        val.setStyle("-fx-text-fill:" + valueColor + ";-fx-font-size:14px;-fx-font-weight:bold;-fx-font-family:'Consolas', monospace;");
        b.getChildren().addAll(lbl, val);
        grid.add(b, col, row);
    }

    private Label createHeadingLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:13px;-fx-font-weight:bold;");
        return l;
    }

    private Label createMutedLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill:" + MUTED_TEXT + ";-fx-font-size:11px;");
        return l;
    }

    private Label createValueLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill:" + WHITE_TEXT + ";-fx-font-size:14px;-fx-font-weight:bold;");
        return l;
    }

    private Label createStatusBadge(String status) {
        String s = (status != null && !status.isBlank()) ? status.toUpperCase(Locale.ROOT) : "UNKNOWN";
        String color = GREEN_ACCENT;
        String bg = "#073023";

        if (s.contains("OFFLINE") || s.contains("CRITICAL")) {
            color = RED_ACCENT;
            bg = "#3A1116";
        } else if (s.contains("RECONNECT") || s.contains("LIMITED") || s.contains("WARN")) {
            color = YELLOW_ACCENT;
            bg = "#382E06";
        }

        Label l = new Label("●  " + s);
        l.setPadding(new Insets(6, 12, 6, 12));
        l.setStyle("-fx-text-fill:" + color + ";-fx-background-color:" + bg + ";-fx-background-radius:14;-fx-border-color:" + color + ";-fx-border-radius:14;-fx-font-size:10px;-fx-font-weight:bold;");
        return l;
    }

    private Button createSmallButton(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color:#0A2942;-fx-text-fill:" + CYAN_ACCENT + ";-fx-border-color:" + BORDER_COLOR + ";-fx-border-radius:5;-fx-background-radius:5;-fx-font-size:9px;-fx-font-weight:bold;-fx-cursor:hand;");
        return b;
    }

    private VBox createEmptyStateWidget(String text) {
        VBox b = new VBox(createMutedLabel(text));
        b.setAlignment(Pos.CENTER);
        b.setPadding(new Insets(36));
        b.setStyle(getPanelStyle());
        return b;
    }

    private String getQualityString(NetworkMetrics net) {
        if (net == null || net.getProbesSent() == 0 || net.getCurrentRttMs() < 0) {
            return "N/A";
        }
        double loss = net.getPacketLossPercent();
        double rtt = net.getCurrentRttMs();
        if (loss == 0.0 && rtt < 40.0) return "EXCELLENT";
        if (loss < 2.0 && rtt < 100.0) return "GOOD";
        if (loss < 5.0) return "FAIR";
        return "POOR";
    }

    private String getPanelStyle() {
        return "-fx-background-color:" + PANEL_BG + ";-fx-background-radius:9;-fx-border-color:" + BORDER_COLOR + ";-fx-border-radius:9;";
    }

    private String formatMs(double ms) {
        if (ms < 0.0) return "N/A";
        return String.format(Locale.US, "%.1f ms", ms);
    }

    private String formatDuration(long millis) {
        if (millis <= 0L) return "00m 00s";
        long totalSec = millis / 1000L;
        long hours = totalSec / 3600L;
        long minutes = (totalSec % 3600L) / 60L;
        long seconds = totalSec % 60L;
        if (hours > 0) return String.format(Locale.US, "%02dh %02dm %02ds", hours, minutes, seconds);
        return String.format(Locale.US, "%02dm %02ds", minutes, seconds);
    }

    private String shorten(String s, int maxLen) {
        if (s == null) return "unknown";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }

    private void refreshCurrentView() {
        if ("NOC Overview".equals(currentPage)) {
            refreshOverview();
        } else if ("Network Fleet".equals(currentPage)) {
            refreshNetworkFleet();
        } else if ("Topology".equals(currentPage) && topologyPane != null) {
            drawTopology(topologyPane, new ArrayList<>(registry.getAllNodes()));
        }
    }

    private void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return;
        if (refreshTimeline != null) refreshTimeline.stop();
        if (watchdog != null) watchdog.stop();
        if (probeServer != null) probeServer.stop();
        if (discoveryServer != null) discoveryServer.stop();
        if (server != null) server.stop();
        LOG.info("PulseNet NOC shutdown complete.");
    }

    @Override
    public void stop() {
        shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

