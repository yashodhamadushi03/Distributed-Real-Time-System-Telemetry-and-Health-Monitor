package com.pulsenet.ui;

import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.server.network.TelemetryServer;
import com.pulsenet.server.registry.NodeRecord;
import com.pulsenet.server.registry.NodeRegistry;
import com.pulsenet.server.watchdog.WatchdogSupervisor;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
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
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PulseNetApp extends Application {
    private static final Logger LOG = Logger.getLogger(PulseNetApp.class.getName());
    private static final String HOST = "0.0.0.0";
    private static final int PORT = 9100;
    private static final double WATCHDOG_TIMEOUT = 5.0;
    private static final int HISTORY = 40;

    // Screenshot-inspired NOC palette
    private static final String BG = "#030B18";
    private static final String SIDEBAR = "#061426";
    private static final String PANEL = "#07172A";
    private static final String PANEL_ALT = "#0A1D33";
    private static final String BORDER = "#18324B";
    private static final String CYAN = "#00C8F5";
    private static final String BLUE = "#087FF5";
    private static final String GREEN = "#00D69A";
    private static final String YELLOW = "#FFC247";
    private static final String RED = "#FF4D5A";
    private static final String PURPLE = "#D45BBD";
    private static final String WHITE = "#EAF4FF";
    private static final String MUTED = "#8197B1";

    private NodeRegistry registry;
    private TelemetryServer server;
    private WatchdogSupervisor watchdog;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private VBox content;
    private Label pageTitle, pageSubtitle, liveStatus;
    private Timeline refreshTimeline;
    private String page = "NOC Overview";
    private final List<Button> navButtons = new ArrayList<>();
    private String selectedNodeId = null;

    private Label upValue, downValue, degradedValue, alertsValue, criticalValue, incidentsValue;
    private Label networkValue, cpuValue, memoryValue, diskValue, forecast, deviceCount;
    private Circle networkArc, cpuArc, memoryArc, diskArc;
    private VBox inventory, events;
    private Pane topology;
    private LineChart<Number, Number> cpuChart, memoryChart, networkChart;
    private XYChart.Series<Number, Number> cpuSeries, memorySeries, downloadSeries, uploadSeries;
    private int tick;

    @Override
    public void start(Stage stage) {
        registry = new NodeRegistry();
        server = new TelemetryServer(HOST, PORT, registry);
        watchdog = new WatchdogSupervisor(registry, WATCHDOG_TIMEOUT);
        try {
            server.start();
            watchdog.start();
        } catch (IOException ex) {
            LOG.log(Level.SEVERE, "Could not start PulseNet server", ex);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("PulseNet startup error");
            alert.setHeaderText("Could not start telemetry server");
            alert.setContentText("Port " + PORT + " may already be in use.\n" + ex.getMessage());
            alert.showAndWait();
            shutdown();
            return;
        }

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:" + BG + ";");
        root.setLeft(sidebar());

        VBox main = new VBox(18);
        main.setPadding(new Insets(24, 26, 28, 26));
        main.setStyle("-fx-background-color:" + BG + ";");
        main.getChildren().add(header());
        content = new VBox(17);
        main.getChildren().add(content);

        ScrollPane scroll = new ScrollPane(main);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background:" + BG + ";-fx-background-color:" + BG + ";");
        root.setCenter(scroll);

        showOverview();
        Scene scene = new Scene(root, 1360, 780);

        stage.setTitle("PulseNet | Network Operations Center");
        stage.setWidth(1360);
        stage.setHeight(780);
        stage.setMinWidth(1050);
        stage.setMinHeight(650);

        stage.setMaximized(false);
        stage.setFullScreen(false);
        stage.setScene(scene);
        stage.centerOnScreen();
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        refreshTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> refresh()));
        refreshTimeline.setCycleCount(Timeline.INDEFINITE);
        refreshTimeline.play();
        refresh();
    }

    // SIDEBAR
    private VBox sidebar() {
        VBox box = new VBox(9);
        box.setPadding(new Insets(25, 14, 18, 14));
        box.setPrefWidth(218);
        box.setMinWidth(218);
        box.setStyle("-fx-background-color:" + SIDEBAR + ";-fx-border-color:transparent " + BORDER + " transparent transparent;");

        HBox brand = new HBox(10);
        brand.setAlignment(Pos.CENTER_LEFT);
        Label mark = new Label("N");
        mark.setStyle("-fx-text-fill:" + CYAN + ";-fx-font-size:30px;-fx-font-weight:bold;-fx-border-color:" + CYAN + ";-fx-border-radius:7;-fx-padding:1 9 1 9;");
        VBox brandText = new VBox(2);
        Label name = new Label("PULSENET");
        name.setStyle("-fx-text-fill:" + WHITE + ";-fx-font-size:16px;-fx-font-weight:bold;");
        Label sub = new Label("DISTRIBUTED VISIBILITY");
        sub.setStyle("-fx-text-fill:" + CYAN + ";-fx-font-size:8px;-fx-font-weight:bold;");
        brandText.getChildren().addAll(name, sub);
        brand.getChildren().addAll(mark, brandText);

        Label group = muted("NETWORK OPERATIONS");
        group.setPadding(new Insets(22, 0, 8, 8));
        box.getChildren().addAll(brand, group,
                nav("▣", "NOC Overview", this::showOverview),
                nav("⌘", "Topology", this::showTopology),
                nav("▤", "Server Fleet", this::showNodes),
                nav("⌁", "Network Fleet", this::showNetwork),
                nav("⚠", "Alerts", this::showAlerts),
                nav("≡", "Log Search", this::showAlerts),
                nav("◉", "Incidents", this::showAlerts),
                nav("▥", "Capacity", this::showCapacity),
                nav("▧", "Reports", this::showReports),
                nav("⚙", "Settings", this::showSettings));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Label system = new Label("●  SYSTEM ONLINE");
        system.setMaxWidth(Double.MAX_VALUE);
        system.setPadding(new Insets(11, 9, 11, 9));
        system.setStyle("-fx-background-color:#062C32;-fx-text-fill:" + GREEN + ";-fx-background-radius:7;-fx-font-size:10px;-fx-font-weight:bold;");
        box.getChildren().addAll(spacer, system, muted("PULSENET 2.0  •  JAVA"));
        return box;
    }

    private Button nav(String icon, String text, Runnable action) {
        Button b = new Button(icon + "   " + text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setPadding(new Insets(11, 10, 11, 10));
        b.setStyle(navStyle(false));
        b.setOnAction(e -> {
            navButtons.forEach(x -> x.setStyle(navStyle(x == b)));
            action.run();
        });
        navButtons.add(b);
        if (text.equals("NOC Overview")) b.setStyle(navStyle(true));
        return b;
    }

    private String navStyle(boolean active) {
        return "-fx-background-color:" + (active ? "#073451" : "transparent") +
                ";-fx-text-fill:" + (active ? CYAN : "#B7C7D9") +
                ";-fx-font-size:12px;-fx-font-weight:" + (active ? "bold" : "normal") +
                ";-fx-background-radius:7;-fx-border-color:" + (active ? CYAN : "transparent") +
                ";-fx-border-width:0 0 0 3;-fx-border-radius:7;-fx-cursor:hand;";
    }

        private HBox header() {
        VBox titles = new VBox(4);
        pageSubtitle = new Label();
        pageSubtitle.setStyle("-fx-text-fill:" + CYAN + ";-fx-font-size:9px;-fx-font-weight:bold;");
        pageTitle = new Label();
        pageTitle.setStyle("-fx-text-fill:" + WHITE + ";-fx-font-size:27px;-fx-font-weight:bold;");
        titles.getChildren().addAll(pageSubtitle, pageTitle);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button add = new Button("+  ADD DEVICE");
        add.setStyle("-fx-background-color:" + BLUE + ";-fx-text-fill:white;-fx-font-size:10px;-fx-font-weight:bold;-fx-background-radius:18;-fx-padding:10 18 10 18;");
        add.setOnAction(e -> showNodes());
        liveStatus = new Label("●  LIVE TELEMETRY");
        liveStatus.setPadding(new Insets(9, 13, 9, 13));
        liveStatus.setStyle("-fx-background-color:#06131F;-fx-text-fill:" + GREEN + ";-fx-border-color:" + BORDER + ";-fx-border-radius:18;-fx-background-radius:18;-fx-font-size:9px;-fx-font-weight:bold;");
        HBox h = new HBox(12, titles, spacer, add, liveStatus);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    // OVERVIEW
    private void showOverview() {
        page = "NOC Overview";
        heading("NOC OVERVIEW", "OPERATIONAL INTELLIGENCE ENGINE  /  REAL-TIME INFRASTRUCTURE VISIBILITY");
        content.getChildren().clear();

        HBox cards = new HBox(12);
        upValue = summary(cards, "DEVICES UP", GREEN, "✓");
        downValue = summary(cards, "DEVICES DOWN", RED, "×");
        degradedValue = summary(cards, "DEGRADED", YELLOW, "△");
        alertsValue = summary(cards, "ACTIVE ALERTS", YELLOW, "♧");
        criticalValue = summary(cards, "CRITICAL ALERTS", RED, "ϟ");
        incidentsValue = summary(cards, "OPEN INCIDENTS", CYAN, "⌁");

        HBox gauges = new HBox(14);
        gauges.setAlignment(Pos.CENTER);
        gauges.setPadding(new Insets(15));
        gauges.setStyle(panelStyle());
        HBox.setHgrow(gauges, Priority.ALWAYS);
        VBox g1 = gauge("NETWORK HEALTH", GREEN, "⌁");
        VBox g2 = gauge("CPU UTILIZATION", RED, "◉");
        VBox g3 = gauge("MEMORY USAGE", RED, "▤");
        VBox g4 = gauge("DISK HEALTH", RED, "◌");
        networkValue = gaugeText(g1); cpuValue = gaugeText(g2);
        memoryValue = gaugeText(g3); diskValue = gaugeText(g4);
        networkArc = gaugeCircle(g1); cpuArc = gaugeCircle(g2);
        memoryArc = gaugeCircle(g3); diskArc = gaugeCircle(g4);
        gauges.getChildren().addAll(g1, g2, g3, g4);

        VBox forecastBox = new VBox(10);
        forecastBox.setPadding(new Insets(17));
        forecastBox.setMinWidth(245);
        forecastBox.setPrefWidth(270);
        forecastBox.setStyle(panelStyle());
        forecastBox.getChildren().add(headingLabel("◈  SYSTEM FORECAST"));
        forecast = new Label("Waiting for telemetry from monitored devices.");
        forecast.setWrapText(true);
        forecast.setStyle("-fx-text-fill:" + MUTED + ";-fx-font-size:11px;");
        deviceCount = new Label("DEVICES                                      0");
        deviceCount.setStyle("-fx-text-fill:" + CYAN + ";-fx-font-size:10px;-fx-font-weight:bold;");
        forecastBox.getChildren().addAll(forecast, deviceCount);
        HBox gaugeRow = new HBox(14, gauges, forecastBox);

        HBox lower = new HBox(14);
        VBox inventoryBox = new VBox(11);
        inventoryBox.setPadding(new Insets(16));
        inventoryBox.setStyle(panelStyle());
        HBox invHead = new HBox(8);
        invHead.getChildren().add(headingLabel("♧  INFRASTRUCTURE INVENTORY"));
        Region push = new Region(); HBox.setHgrow(push, Priority.ALWAYS);
        Button addNode = smallButton("ADD NODE");
        addNode.setOnAction(e -> showNodes());
        invHead.getChildren().addAll(push, addNode);
        inventory = new VBox(8);
        ScrollPane invScroll = new ScrollPane(inventory);
        invScroll.setFitToWidth(true);
        invScroll.setPrefHeight(300);
        invScroll.setStyle("-fx-background:transparent;-fx-background-color:transparent;");
        inventoryBox.getChildren().addAll(invHead, muted("REGISTERED AGENTS  •  LIVE RESOURCE TELEMETRY"), invScroll);
        HBox.setHgrow(inventoryBox, Priority.ALWAYS);

        VBox topologyBox = new VBox(10);
        topologyBox.setPadding(new Insets(16));
        topologyBox.setStyle(panelStyle());
        HBox topoHead = new HBox(8, headingLabel("⌘  INFRASTRUCTURE TOPO-MAP"));
        Region topPush = new Region(); HBox.setHgrow(topPush, Priority.ALWAYS);
        Label live = new Label("LIVE MAPPING");
        live.setStyle("-fx-text-fill:" + CYAN + ";-fx-font-size:9px;-fx-border-color:" + BORDER + ";-fx-border-radius:5;-fx-padding:5 8 5 8;");
        topoHead.getChildren().addAll(topPush, live);
        topology = new Pane();
        topology.setMinHeight(250);
        topology.setPrefHeight(290);
        topologyBox.getChildren().addAll(topoHead, topology, muted("● SERVER    ● CONNECTED AGENT    ● OFFLINE"));
        topologyBox.setMinWidth(350);
        HBox.setHgrow(topologyBox, Priority.ALWAYS);
        lower.getChildren().addAll(inventoryBox, topologyBox);

        HBox charts = new HBox(14);
        cpuChart = chart("CPU UTILIZATION", "Usage (%)", true);
        memoryChart = chart("MEMORY UTILIZATION", "Usage (%)", true);
        networkChart = chart("NETWORK THROUGHPUT", "KB/s", false);
        cpuSeries = series(cpuChart, "CPU");
        memorySeries = series(memoryChart, "Memory");
        downloadSeries = series(networkChart, "Download");
        uploadSeries = series(networkChart, "Upload");
        charts.getChildren().addAll(chartPanel(cpuChart), chartPanel(memoryChart), chartPanel(networkChart));

        VBox eventPanel = new VBox(9);
        eventPanel.setPadding(new Insets(15));
        eventPanel.setStyle(panelStyle());
        eventPanel.getChildren().add(headingLabel("≡  RECENT ACTIVITY"));
        events = new VBox(8);
        eventPanel.getChildren().add(events);

        content.getChildren().addAll(cards, gaugeRow, lower, charts, eventPanel);
        refreshOverview();
    }

    private Label summary(HBox row, String title, String accent, String icon) {
        VBox card = new VBox(11);
        card.setPadding(new Insets(13));
        card.setMinHeight(86);
        card.setStyle(panelStyle());
        HBox.setHgrow(card, Priority.ALWAYS);
        HBox top = new HBox(muted(title));
        Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
        Label symbol = new Label(icon);
        symbol.setStyle("-fx-text-fill:" + accent + ";-fx-font-size:17px;");
        top.getChildren().addAll(gap, symbol);
        Label value = new Label("0");
        value.setStyle("-fx-text-fill:" + accent + ";-fx-font-size:25px;-fx-font-weight:bold;");
        card.getChildren().addAll(top, value);
        row.getChildren().add(card);
        return value;
    }

    private VBox gauge(String title, String accent, String icon) {
        VBox box = new VBox(8);
        box.setAlignment(Pos.CENTER);
        HBox.setHgrow(box, Priority.ALWAYS);
        Label heading = muted(title);
        Circle track = new Circle(39, Color.TRANSPARENT);
        track.setStroke(Color.web("#1C334A")); track.setStrokeWidth(7);
        Circle arc = new Circle(39, Color.TRANSPARENT);
        arc.setStroke(Color.web(accent)); arc.setStrokeWidth(7);
        arc.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND); arc.setRotate(-90);
        Label value = new Label("0.0%");
        value.setStyle("-fx-text-fill:" + WHITE + ";-fx-font-size:16px;-fx-font-weight:bold;");
        Label glyph = new Label(icon);
        glyph.setStyle("-fx-text-fill:" + accent + ";-fx-font-size:10px;");
        VBox center = new VBox(0, value, glyph); center.setAlignment(Pos.CENTER);
        StackPane dial = new StackPane(track, arc, center);
        dial.setMinSize(100,100); dial.setPrefSize(100,100); dial.setMaxSize(100,100);
        box.getChildren().addAll(heading, dial);
        setGauge(arc, value, 0);
        return box;
    }

    private Label gaugeText(VBox box) {
        StackPane dial = (StackPane) box.getChildren().get(1);
        return (Label) ((VBox) dial.getChildren().get(2)).getChildren().get(0);
    }

    private Circle gaugeCircle(VBox box) {
        return (Circle) ((StackPane) box.getChildren().get(1)).getChildren().get(1);
    }

    private void setGauge(Circle arc, Label value, double percent) {
        double p = Math.max(0, Math.min(100, Double.isFinite(percent) ? percent : 0));
        double circumference = 2 * Math.PI * 39;
        arc.getStrokeDashArray().setAll(circumference * p / 100, circumference);
        value.setText(String.format(Locale.US, "%.1f%%", p));
    }

        // TOPOLOGY
    private void drawTopology(Pane pane, List<NodeRecord> nodes) {
        pane.getChildren().clear();
        double w = Math.max(330, pane.getWidth() > 0 ? pane.getWidth() : 390);
        double cx = w / 2, serverY = 30, agentY = 128;
        Line stem = new Line(cx, serverY + 15, cx, agentY - 15);
        stem.setStroke(Color.web("#12617A")); pane.getChildren().add(stem);
        Circle serverDot = new Circle(cx, serverY, 14, Color.web("#073C57"));
        serverDot.setStroke(Color.web(CYAN)); serverDot.setStrokeWidth(2);
        Label serverName = new Label("PULSENET SERVER");
        serverName.setStyle("-fx-text-fill:" + CYAN + ";-fx-font-size:9px;-fx-font-weight:bold;");
        serverName.setLayoutX(cx - 54); serverName.setLayoutY(serverY + 18);
        pane.getChildren().addAll(serverDot, serverName);
        int count = Math.min(nodes.size(), 8);
        if (count == 0) {
            Label wait = muted("Waiting for agents to connect");
            wait.setLayoutX(cx - 78); wait.setLayoutY(agentY + 25); pane.getChildren().add(wait); return;
        }
        double gap = Math.min(65, (w - 42) / Math.max(1, count - 1));
        double start = cx - gap * (count - 1) / 2;
        for (int i = 0; i < count; i++) {
            NodeRecord n = nodes.get(i);
            double x = start + i * gap;
            boolean online = !"OFFLINE".equalsIgnoreCase(n.getStatus());
            Line link = new Line(cx, agentY - 18, x, agentY + 4);
            link.setStroke(Color.web(online ? "#12617A" : "#65313C"));
            Rectangle device = new Rectangle(x - 12, agentY - 8, 24, 24);
            device.setArcWidth(6); device.setArcHeight(6);
            device.setFill(Color.web(online ? "#063D36" : "#421E2A"));
            device.setStroke(Color.web(online ? GREEN : RED));
            Circle dot = new Circle(x + 10, agentY - 10, 3.5, Color.web(online ? GREEN : RED));
            Label label = new Label(shortName(n.getNodeId(), 10));
            label.setStyle("-fx-text-fill:" + MUTED + ";-fx-font-size:8px;");
            label.setLayoutX(x - 28); label.setLayoutY(agentY + 21);
            device.setOnMouseClicked(event -> {
            showNodeDetails(n.getNodeId());
            });

            label.setOnMouseClicked(event -> {
            showNodeDetails(n.getNodeId());
            });

            device.setStyle("-fx-cursor: hand;");
            label.setStyle(
                "-fx-text-fill:" + CYAN +
                ";-fx-font-size:8px;-fx-cursor:hand;"
            );

            pane.getChildren().addAll(link, device, dot, label);
        }
        if (nodes.size() > count) {
            Label more = muted("+" + (nodes.size() - count) + " more agents");
            more.setLayoutX(cx - 35); more.setLayoutY(agentY + 45); pane.getChildren().add(more);
        }
    }

    private void showNodeDetails(String nodeId) {

        selectedNodeId = nodeId;

        page = "Server Fleet";

        heading(
            "SERVER FLEET",
            "SELECTED DEVICE INFORMATION"
        );

        content.getChildren().clear();

        Button backButton = smallButton("← BACK TO ALL DEVICES");

        backButton.setOnAction(event -> {
            selectedNodeId = null;
            showNodes();
        });

        content.getChildren().add(backButton);

        NodeRecord selectedNode = registry.getAllNodes()
            .stream()
            .filter(node -> node.getNodeId().equals(nodeId))
            .findFirst()
            .orElse(null);

        if (selectedNode == null) {
            content.getChildren().add(
                empty("Device not found.")
            );
            return;
        }

        VBox card = new VBox(14);
        card.setPadding(new Insets(22));
        card.setStyle(panelStyle());

        HBox header = new HBox(12);

        Label name = headingLabel(selectedNode.getNodeId());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header.getChildren().addAll(
            name,
            spacer,
            status(selectedNode.getStatus())
        );

        card.getChildren().add(header);

        card.getChildren().add(
            muted("DEVICE INFORMATION AND LIVE TELEMETRY")
        );

        TelemetryPayload p = selectedNode.getLatestPayload();

        if (p != null) {

            GridPane grid = new GridPane();

            grid.setHgap(30);
            grid.setVgap(22);

            detail(grid, 0, 0, "CPU", pct(p.getCpuPercent()));
            detail(grid, 1, 0, "MEMORY", pct(p.getMemoryPercent()));
            detail(grid, 2, 0, "DISK", pct(p.getDiskPercent()));
            detail(grid, 3, 0, "CPU TEMPERATURE",
                    temp(p.getCpuTemperatureCelsius()));

            detail(grid, 0, 1, "BATTERY", battery(p));
            detail(grid, 1, 1, "DOWNLOAD",
                    speed(p.getDownloadBytesPerSecond()));
            detail(grid, 2, 1, "UPLOAD",
                    speed(p.getUploadBytesPerSecond()));
            detail(grid, 3, 1, "PROCESSES",
                    String.valueOf(p.getProcessCount()));

            detail(grid, 0, 2, "HOSTNAME",
                    safe(p.getHostName()));
            detail(grid, 1, 2, "IP ADDRESS",
                    safe(p.getIpAddress()));
            detail(grid, 2, 2, "OPERATING SYSTEM",
                    safe(p.getOperatingSystem()));
            detail(grid, 3, 2, "UPTIME",
                    duration(p.getUptimeSeconds()));

            card.getChildren().add(grid);

        } else {

            card.getChildren().add(
                muted("Waiting for first telemetry payload.")
            );
        }

        content.getChildren().add(card);
    }

    // REFRESH
    private void refreshOverview() {
        if (!"NOC Overview".equals(page) || registry == null) return;
        List<NodeRecord> nodes = new ArrayList<>(registry.getAllNodes());
        nodes.sort(Comparator.comparing(NodeRecord::getNodeId, String.CASE_INSENSITIVE_ORDER));
        int up = 0, down = 0, degraded = 0, warning = 0, critical = 0;
        double cpu = 0, mem = 0, disk = 0, rx = 0, tx = 0;
        int measured = 0;
        for (NodeRecord n : nodes) {
            String status = n.getStatus() == null ? "UNKNOWN" : n.getStatus();
            if ("OFFLINE".equalsIgnoreCase(status)) down++;
            else {
                up++;
                if (!"HEALTHY".equalsIgnoreCase(status)) degraded++;
            }
            if ("WARNING".equalsIgnoreCase(status)) warning++;
            if ("CRITICAL".equalsIgnoreCase(status)) critical++;
            TelemetryPayload p = n.getLatestPayload();
            if (p != null && !"OFFLINE".equalsIgnoreCase(status)) {
                cpu += p.getCpuPercent(); mem += p.getMemoryPercent(); disk += p.getDiskPercent();
                rx += p.getDownloadBytesPerSecond(); tx += p.getUploadBytesPerSecond(); measured++;
            }
        }
        upValue.setText(String.valueOf(up)); downValue.setText(String.valueOf(down));
        degradedValue.setText(String.valueOf(degraded)); alertsValue.setText(String.valueOf(warning + degraded));
        criticalValue.setText(String.valueOf(critical)); incidentsValue.setText(String.valueOf(down + critical));
        double c = measured == 0 ? 0 : cpu / measured;
        double m = measured == 0 ? 0 : mem / measured;
        double d = measured == 0 ? 0 : disk / measured;
        setGauge(networkArc, networkValue, nodes.isEmpty() ? 100 : up * 100.0 / nodes.size());
        setGauge(cpuArc, cpuValue, c); setGauge(memoryArc, memoryValue, m); setGauge(diskArc, diskValue, 100 - d);
        forecast.setText(nodes.isEmpty()
                ? "No infrastructure agents are registered yet. Start an agent to begin live monitoring."
                : down == 0 && degraded == 0
                ? "Infrastructure is operating normally. All connected agents are reporting telemetry."
                : down + " device(s) are offline and " + degraded + " device(s) require attention.");
        deviceCount.setText("REGISTERED DEVICES                         " + nodes.size());
        refreshInventory(nodes); refreshEvents(); drawTopology(topology, nodes);
        tick++;
        point(cpuSeries, c); point(memorySeries, m);
        point(downloadSeries, (measured == 0 ? 0 : rx / measured) / 1024.0);
        point(uploadSeries, (measured == 0 ? 0 : tx / measured) / 1024.0);
        liveStatus.setText("●  LIVE TELEMETRY  •  " + (server.isRunning() ? "SERVER ONLINE" : "SERVER OFFLINE"));
    }

    private void refreshInventory(List<NodeRecord> nodes) {
        inventory.getChildren().clear();
        if (nodes.isEmpty()) {
            Label empty = muted("No agents registered. Waiting for telemetry...");
            empty.setPadding(new Insets(24)); inventory.getChildren().add(empty); return;
        }
        for (NodeRecord n : nodes) {
            TelemetryPayload p = n.getLatestPayload();
            boolean online = !"OFFLINE".equalsIgnoreCase(n.getStatus());
            HBox row = new HBox(10); row.setAlignment(Pos.CENTER_LEFT); row.setPadding(new Insets(9));
            row.setStyle("-fx-background-color:" + PANEL_ALT + ";-fx-border-color:" + BORDER + ";-fx-border-radius:7;-fx-background-radius:7;");
            Label glyph = new Label("▣");
            glyph.setStyle("-fx-text-fill:" + (online ? GREEN : RED) + ";-fx-font-size:17px;");
            VBox identity = new VBox(3);
            Label nodeName = new Label(n.getNodeId());
            nodeName.setStyle("-fx-text-fill:" + WHITE + ";-fx-font-size:10px;-fx-font-weight:bold;");
            identity.getChildren().addAll(nodeName, muted(n.getRemoteAddress() == null ? "Address unavailable" : n.getRemoteAddress()));
            Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
            VBox meters = new VBox(4);
            if (p != null) meters.getChildren().addAll(miniMeter("CPU", p.getCpuPercent(), CYAN), miniMeter("RAM", p.getMemoryPercent(), BLUE));
            else meters.getChildren().add(muted("Awaiting metrics"));
            row.getChildren().addAll(glyph, identity, gap, meters, status(n.getStatus()));
            inventory.getChildren().add(row);
        }
    }

    private HBox miniMeter(String title, double value, String color) {
        HBox row = new HBox(5); row.setAlignment(Pos.CENTER_RIGHT);
        Label t = new Label(title); t.setStyle("-fx-text-fill:" + MUTED + ";-fx-font-size:8px;");
        Region track = new Region(); track.setPrefSize(35,4); track.setMinSize(35,4);
        track.setStyle("-fx-background-color:#20364C;-fx-background-radius:4;");
        Region fill = new Region(); fill.setPrefSize(Math.max(1, 35 * Math.max(0, Math.min(100, value)) / 100),4);
        fill.setMinHeight(4); fill.setStyle("-fx-background-color:" + color + ";-fx-background-radius:4;");
        StackPane bar = new StackPane(track, fill); StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        Label pct = new Label(String.format(Locale.US, "%.0f%%", value));
        pct.setStyle("-fx-text-fill:" + WHITE + ";-fx-font-size:8px;");
        row.getChildren().addAll(t, bar, pct); return row;
    }

    private void refreshEvents() {
        events.getChildren().clear();
        List<String> recent = registry.getRecentEvents(5);
        if (recent.isEmpty()) { events.getChildren().add(muted("No events recorded. Waiting for agents...")); return; }
        for (int i = recent.size() - 1; i >= 0; i--) {
            Label e = new Label("●  " + recent.get(i)); e.setWrapText(true);
            e.setStyle("-fx-text-fill:" + eventColor(recent.get(i)) + ";-fx-font-size:10px;");
            events.getChildren().add(e);
        }
    }

        // PAGES
    private void showTopology() {
        page = "Topology"; heading("NETWORK TOPOLOGY", "LIVE AGENT-TO-SERVER COMMUNICATION MAP");
        content.getChildren().clear();
        VBox panel = new VBox(14); panel.setPadding(new Insets(20)); panel.setStyle(panelStyle());
        panel.getChildren().add(headingLabel("⌘  REGISTERED INFRASTRUCTURE"));
        topology = new Pane(); topology.setPrefHeight(520); panel.getChildren().add(topology);
        content.getChildren().add(panel); drawTopology(topology, registry.getAllNodes());
        topology.widthProperty().addListener((o, a, b) -> drawTopology(topology, registry.getAllNodes()));
    }

    private void showNodes() {
        selectedNodeId = null;

        page = "Server Fleet";

        heading(
            "SERVER FLEET",
            "INDIVIDUAL AGENT HEALTH AND HARDWARE TELEMETRY"
        );

        refreshNodes();
    }

    private void refreshNodes() {
        if (!"Server Fleet".equals(page)) return;
        content.getChildren().clear();
        List<NodeRecord> nodes = registry.getAllNodes();
        content.getChildren().add(headingLabel("REGISTERED COMPUTERS  •  " + nodes.size()));
        if (nodes.isEmpty()) { content.getChildren().add(empty("No agents connected yet.")); return; }
        for (NodeRecord n : nodes) {
            VBox card = new VBox(10); card.setPadding(new Insets(17)); card.setStyle(panelStyle());
            HBox head = new HBox(10, headingLabel(n.getNodeId()));
            Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
            head.getChildren().addAll(gap, status(n.getStatus()));
            card.getChildren().addAll(head, muted("REMOTE ADDRESS  •  " + safe(n.getRemoteAddress())));
            TelemetryPayload p = n.getLatestPayload();
            if (p != null) {
                GridPane grid = new GridPane(); grid.setHgap(24); grid.setVgap(13);
                detail(grid,0,0,"CPU",pct(p.getCpuPercent())); detail(grid,1,0,"MEMORY",pct(p.getMemoryPercent()));
                detail(grid,2,0,"DISK",pct(p.getDiskPercent())); detail(grid,3,0,"CPU TEMPERATURE",temp(p.getCpuTemperatureCelsius()));
                detail(grid,0,1,"BATTERY",battery(p)); detail(grid,1,1,"DOWNLOAD",speed(p.getDownloadBytesPerSecond()));
                detail(grid,2,1,"UPLOAD",speed(p.getUploadBytesPerSecond())); detail(grid,3,1,"PROCESSES",String.valueOf(p.getProcessCount()));
                detail(grid,0,2,"HOSTNAME",safe(p.getHostName())); detail(grid,1,2,"IP ADDRESS",safe(p.getIpAddress()));
                detail(grid,2,2,"OPERATING SYSTEM",safe(p.getOperatingSystem())); detail(grid,3,2,"UPTIME",duration(p.getUptimeSeconds()));
                card.getChildren().add(grid);
            } else card.getChildren().add(muted("Waiting for first telemetry payload."));
            content.getChildren().add(card);
        }
    }

    private void showNetwork() {
        page = "Network Fleet"; heading("NETWORK FLEET", "NETWORK TRAFFIC REPORTED BY CONNECTED AGENTS"); refreshNetwork();
    }

    private void refreshNetwork() {
        if (!"Network Fleet".equals(page)) return;
        content.getChildren().clear(); content.getChildren().add(headingLabel("NETWORK TELEMETRY"));
        int count = 0;
        for (NodeRecord n : registry.getAllNodes()) {
            TelemetryPayload p = n.getLatestPayload(); if (p == null) continue; count++;
            VBox card = new VBox(8); card.setPadding(new Insets(16)); card.setStyle(panelStyle());
            card.getChildren().addAll(headingLabel(n.getNodeId()), muted("IP ADDRESS  •  " + safe(p.getIpAddress())),
                    detailLine("DOWNLOAD", speed(p.getDownloadBytesPerSecond())), detailLine("UPLOAD", speed(p.getUploadBytesPerSecond())),
                    detailLine("TOTAL RECEIVED", bytes(p.getTotalBytesReceived())), detailLine("TOTAL SENT", bytes(p.getTotalBytesSent())));
            content.getChildren().add(card);
        }
        if (count == 0) content.getChildren().add(empty("No network telemetry available."));
    }

    private void showAlerts() {
        page = "Alerts"; heading("ALERTS & EVENT LOG", "WATCHDOG EVENTS AND NODE HEALTH CHANGES"); refreshAlerts();
    }

    private void refreshAlerts() {
        if (!"Alerts".equals(page)) return;
        content.getChildren().clear();
        List<String> list = registry.getRecentEvents(100);
        long count = list.stream().filter(x -> x.contains("[WARN]") || x.contains("[LOST]")).count();
        content.getChildren().add(wideCard("RECENT ALERTS", String.valueOf(count), "Warnings and lost heartbeats", YELLOW));
        VBox panel = new VBox(10); panel.setPadding(new Insets(18)); panel.setStyle(panelStyle());
        panel.getChildren().add(headingLabel("EVENT HISTORY"));
        if (list.isEmpty()) panel.getChildren().add(muted("No events recorded yet."));
        for (int i = list.size()-1; i >= 0; i--) {
            Label e = new Label("●  " + list.get(i)); e.setWrapText(true);
            e.setStyle("-fx-text-fill:" + eventColor(list.get(i)) + ";-fx-font-size:11px;"); panel.getChildren().add(e);
        }
        content.getChildren().add(panel);
    }

    private void showCapacity() {
        page = "Capacity"; heading("CAPACITY", "AGGREGATED RESOURCE UTILIZATION ACROSS CONNECTED NODES");
        content.getChildren().clear(); double cpu=0,mem=0,disk=0; int count=0;
        for (NodeRecord n : registry.getAllNodes()) {
            TelemetryPayload p=n.getLatestPayload();
            if (p!=null && !"OFFLINE".equalsIgnoreCase(n.getStatus())) {cpu+=p.getCpuPercent();mem+=p.getMemoryPercent();disk+=p.getDiskPercent();count++;}
        }
        content.getChildren().addAll(wideCard("AVERAGE CPU",pct(count==0?0:cpu/count),"Across online nodes",CYAN),
                wideCard("AVERAGE MEMORY",pct(count==0?0:mem/count),"Across online nodes",BLUE),
                wideCard("AVERAGE DISK",pct(count==0?0:disk/count),"Across online nodes",PURPLE));
    }

    private void showReports() {
        page="Reports"; heading("REPORTS","CURRENT INFRASTRUCTURE SNAPSHOT"); content.getChildren().clear();
        List<NodeRecord> nodes=registry.getAllNodes();
        content.getChildren().addAll(wideCard("REGISTERED DEVICES",String.valueOf(nodes.size()),"Current node inventory",CYAN),
                wideCard("ONLINE DEVICES",String.valueOf(nodes.stream().filter(n->!"OFFLINE".equalsIgnoreCase(n.getStatus())).count()),"Currently reporting agents",GREEN),
                wideCard("OFFLINE DEVICES",String.valueOf(nodes.stream().filter(n->"OFFLINE".equalsIgnoreCase(n.getStatus())).count()),"No recent telemetry",RED),
                muted("Live snapshot only. Persistent historical reporting is not enabled in the current backend."));
    }

    private void showSettings() {
        page="Settings"; heading("SETTINGS","PULSENET SERVER CONFIGURATION"); content.getChildren().clear();
        VBox panel=new VBox(14); panel.setPadding(new Insets(20)); panel.setStyle(panelStyle());
        panel.getChildren().addAll(headingLabel("SERVER CONFIGURATION"),detailLine("BIND HOST",HOST),detailLine("SERVER PORT",String.valueOf(PORT)),
                detailLine("WATCHDOG TIMEOUT",WATCHDOG_TIMEOUT+" seconds"),detailLine("CLIENT READ TIMEOUT",server.getReadTimeoutMillis()+" ms"),
                detailLine("DASHBOARD REFRESH","1 second"),detailLine("TELEMETRY LIBRARY","OSHI"),
                detailLine("COMMUNICATION","TCP + JSON"),detailLine("HISTORY STORAGE","In-memory only"));
        content.getChildren().add(panel);
    }

    private void heading(String title,String subtitle) { pageTitle.setText(title); pageSubtitle.setText(subtitle); }

    private void refresh() {
        if (registry == null) {
            return;
        }
        switch (page) {

            case "NOC Overview" -> refreshOverview();

            case "Server Fleet" -> {
                if (selectedNodeId == null) {
                    refreshNodes();
                } else {
                    showNodeDetails(selectedNodeId);
                }
            }

            case "Network Fleet" -> refreshNetwork();

            case "Alerts" -> refreshAlerts();

            case "Topology" -> {
                if (topology != null) {
                    drawTopology(topology, registry.getAllNodes());
                }
            }

            default -> {
                // No automatic refresh required for this page.
            }
        }
    }

    // CHARTS AND COMPONENTS
    private LineChart<Number,Number> chart(String title,String axis,boolean percent) {
        NumberAxis x=new NumberAxis(); NumberAxis y=percent?new NumberAxis(0,100,20):new NumberAxis();
        x.setLabel("TIME"); y.setLabel(axis); x.setTickLabelsVisible(false); x.setTickMarkVisible(false);
        LineChart<Number,Number> c=new LineChart<>(x,y); c.setTitle(title); c.setAnimated(false);
        c.setCreateSymbols(false); c.setLegendVisible(true); c.setPrefHeight(235); c.setMinWidth(250);
        c.setStyle("-fx-background-color:transparent;-fx-text-fill:"+MUTED+";"); return c;
    }

    private XYChart.Series<Number,Number> series(LineChart<Number,Number> chart,String name) {
        XYChart.Series<Number,Number> s=new XYChart.Series<>();s.setName(name);chart.getData().add(s);return s;
    }

    private void point(XYChart.Series<Number,Number> s,double value) {
        if(s==null)return;s.getData().add(new XYChart.Data<>(tick,value));if(s.getData().size()>HISTORY)s.getData().remove(0);
    }

    private VBox chartPanel(LineChart<Number,Number> chart) {
        VBox b=new VBox(chart);b.setPadding(new Insets(9));b.setStyle(panelStyle());HBox.setHgrow(b,Priority.ALWAYS);return b;
    }

    private VBox wideCard(String title,String value,String description,String color) {
        VBox b=new VBox(8);b.setPadding(new Insets(17));b.setStyle(panelStyle());
        Label v=new Label(value);v.setStyle("-fx-text-fill:"+color+";-fx-font-size:25px;-fx-font-weight:bold;");
        b.getChildren().addAll(muted(title),v,muted(description));return b;
    }

    private VBox empty(String text) {
        VBox b=new VBox(muted(text));b.setAlignment(Pos.CENTER);b.setPadding(new Insets(35));b.setStyle(panelStyle());return b;
    }

    private Button smallButton(String text) {
        Button b=new Button(text);b.setStyle("-fx-background-color:#0A2942;-fx-text-fill:"+CYAN+";-fx-border-color:"+BORDER+";-fx-border-radius:5;-fx-background-radius:5;-fx-font-size:9px;-fx-font-weight:bold;");return b;
    }

    private Label headingLabel(String text) {
        Label l=new Label(text);l.setStyle("-fx-text-fill:"+WHITE+";-fx-font-size:12px;-fx-font-weight:bold;");return l;
    }

    private Label muted(String text) {
        Label l=new Label(text);l.setStyle("-fx-text-fill:"+MUTED+";-fx-font-size:9px;");return l;
    }

    private Label status(String value) {
        String s=value==null?"UNKNOWN":value.toUpperCase(Locale.ROOT);
        String color="HEALTHY".equals(s)?GREEN:"OFFLINE".equals(s)?RED:YELLOW;
        String bg="HEALTHY".equals(s)?"#06362F":"OFFLINE".equals(s)?"#401F2B":"#3C321C";
        Label l=new Label("●  "+s);l.setPadding(new Insets(6,9,6,9));
        l.setStyle("-fx-text-fill:"+color+";-fx-background-color:"+bg+";-fx-background-radius:12;-fx-font-size:8px;-fx-font-weight:bold;");return l;
    }

    private Label detailLine(String title,String value) {
        Label l=new Label(title+"     "+value);l.setStyle("-fx-text-fill:"+WHITE+";-fx-font-size:11px;-fx-padding:7 0 7 0;-fx-border-color:transparent transparent "+BORDER+" transparent;");return l;
    }

    private void detail(GridPane g,int col,int row,String title,String value) {
        VBox b=new VBox(4,muted(title),headingLabel(value));g.add(b,col,row);
    }

    private String panelStyle() {return "-fx-background-color:"+PANEL+";-fx-background-radius:9;-fx-border-color:"+BORDER+";-fx-border-radius:9;";}
    private String eventColor(String s) {return s.contains("[WARN]")||s.contains("[LOST]")?RED:s.contains("[REC]")||s.contains("[OK]")?GREEN:CYAN;}
    private String shortName(String s,int n) {return s==null?"node":s.length()<=n?s:s.substring(0,n-1)+"…";}
    private String safe(String s) {return s==null||s.isBlank()?"Unavailable":s;}
    private String pct(double n) {return String.format(Locale.US,"%.1f%%",n);}
    private String temp(double n) {return n<=0||!Double.isFinite(n)?"Unavailable":String.format(Locale.US,"%.1f °C",n);}
    private String battery(TelemetryPayload p) {return !p.isBatteryAvailable()||p.getBatteryPercent()<0?"Unavailable":String.format(Locale.US,"%.1f%% (%s)",p.getBatteryPercent(),p.isBatteryCharging()?"Charging":"Not charging");}
    private String speed(double n) {return n<1024?String.format(Locale.US,"%.1f B/s",n):n<1048576?String.format(Locale.US,"%.2f KB/s",n/1024):String.format(Locale.US,"%.2f MB/s",n/1048576);}

    private String bytes(long n) {
        if(n<0)return "N/A";double v=n;String[] u={"B","KB","MB","GB","TB"};int i=0;
        while(v>=1024&&i<u.length-1){v/=1024;i++;}return String.format(Locale.US,"%.2f %s",v,u[i]);
    }

    private String duration(long s) {if(s<0)return "N/A";return (s/86400)+"d "+((s%86400)/3600)+"h "+((s%3600)/60)+"m";}

    private void shutdown() {
        if(!shuttingDown.compareAndSet(false,true))return;
        if(refreshTimeline!=null)refreshTimeline.stop();
        if(watchdog!=null)watchdog.stop();
        if(server!=null)server.stop();
        LOG.info("PulseNet shutdown complete.");
    }

    @Override public void stop(){shutdown();}
    public static void main(String[] args){launch(args);}
}