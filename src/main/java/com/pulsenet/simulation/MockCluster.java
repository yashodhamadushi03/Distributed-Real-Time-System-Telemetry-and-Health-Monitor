package com.pulsenet.simulation;

import com.pulsenet.agent.MetricCollector;
import com.pulsenet.agent.TelemetryAgent;
import com.pulsenet.common.model.TelemetryPayload;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Multi-node cluster simulator for PulseNet.
 * Spawns N simulated client agents streaming synthetic loads, with optional chaotic
 * node drop injection to demonstrate watchdog timeout detection.
 */
public class MockCluster {

    private static final Logger LOGGER = Logger.getLogger(MockCluster.class.getName());

    private final String host;
    private final int port;
    private final int nodeCount;
    private final long intervalMillis;
    private final boolean chaosEnabled;

    private final List<TelemetryAgent> activeAgents = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public MockCluster(String host, int port, int nodeCount, long intervalMillis, boolean chaosEnabled) {
        this.host = host;
        this.port = port;
        this.nodeCount = nodeCount;
        this.intervalMillis = intervalMillis;
        this.chaosEnabled = chaosEnabled;
    }

    /**
     * Starts all simulated agents and schedules chaos events if enabled.
     */
    public synchronized void start() {
        LOGGER.info(String.format("Spawning mock cluster with %d nodes targeting %s:%d...",
                nodeCount, host, port));

        for (int i = 1; i <= nodeCount; i++) {
            String nodeId = String.format("agent-%02d", i);
            SyntheticMetricCollector collector = new SyntheticMetricCollector(nodeId, i);
            TelemetryAgent agent = new TelemetryAgent(host, port, nodeId, intervalMillis, collector);
            agent.setInitialBackoffMillis(500);
            agent.setMaxBackoffMillis(3000);
            activeAgents.add(agent);
            agent.start();
        }

        LOGGER.info(String.format("Successfully spawned %d telemetry agents.", activeAgents.size()));

        if (chaosEnabled && nodeCount >= 3) {
            // Chaos scenario: Stop agent-03 after 10 seconds to showcase Watchdog timeout detection
            LOGGER.info("Chaos Mode ENABLED: Scheduled abrupt shutdown of agent-03 in 10 seconds.");
            scheduler.schedule(() -> {
                for (TelemetryAgent agent : activeAgents) {
                    if ("agent-03".equals(agent.getNodeId())) {
                        LOGGER.warning(">>> CHAOS INJECTION: Abruptly terminating agent-03 socket! <<<");
                        agent.stop();
                        break;
                    }
                }
            }, 10, TimeUnit.SECONDS);
        }
    }

    /**
     * Stops all running simulated agents.
     */
    public synchronized void stop() {
        LOGGER.info("Stopping mock cluster...");
        scheduler.shutdownNow();
        for (TelemetryAgent agent : activeAgents) {
            agent.stop();
        }
        activeAgents.clear();
        LOGGER.info("Mock cluster stopped.");
    }

    public List<TelemetryAgent> getActiveAgents() {
        return Collections.unmodifiableList(activeAgents);
    }

    /**
     * Synthetic metric generator providing varying, realistic load fluctuations.
     */
    public static class SyntheticMetricCollector extends MetricCollector {
        private final String nodeId;
        private final int seedIndex;
        private final Random random = new Random();
        private long tick = 0;

        public SyntheticMetricCollector(String nodeId, int seedIndex) {
            this.nodeId = nodeId;
            this.seedIndex = seedIndex;
        }

        @Override
        public TelemetryPayload collect(String id) {
            tick++;
            double baseCpu = 25.0 + (seedIndex * 12.0) % 60.0;
            // Introduce occasional spike for node 2 to demonstrate WARNING
            if (seedIndex == 2 && tick % 4 == 0) {
                baseCpu = 88.5;
            }

            double cpuPercent = Math.min(99.0, Math.max(5.0, baseCpu + (random.nextDouble() * 8.0 - 4.0)));
            double memPercent = Math.min(95.0, Math.max(20.0, 45.0 + (seedIndex * 9.0) % 45.0 + (random.nextDouble() * 4.0 - 2.0)));
            double diskPercent = 35.0 + (seedIndex * 7.0) % 45.0;
            int processCount = 120 + seedIndex * 25 + (int) (random.nextDouble() * 10);

            String status = determineStatus(cpuPercent, memPercent, diskPercent);

            return new TelemetryPayload(
                    nodeId,
                    System.currentTimeMillis(),
                    Math.round(cpuPercent * 10.0) / 10.0,
                    Math.round(memPercent * 10.0) / 10.0,
                    Math.round(diskPercent * 10.0) / 10.0,
                    processCount,
                    status
            );
        }
    }

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 9000;
        int nodes = 5;
        long intervalMillis = 1000L;
        boolean chaos = true;
        int durationSeconds = -1;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                    if (i + 1 < args.length) host = args[++i];
                    break;
                case "--port":
                    if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
                    break;
                case "--nodes":
                    if (i + 1 < args.length) nodes = Integer.parseInt(args[++i]);
                    break;
                case "--interval":
                    if (i + 1 < args.length) intervalMillis = Long.parseLong(args[++i]);
                    break;
                case "--duration":
                    if (i + 1 < args.length) durationSeconds = Integer.parseInt(args[++i]);
                    break;
                case "--no-chaos":
                    chaos = false;
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    return;
                default:
                    LOGGER.warning("Unknown argument: " + args[i]);
            }
        }

        MockCluster cluster = new MockCluster(host, port, nodes, intervalMillis, chaos);
        CountDownLatch shutdownLatch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutdown hook received. Stopping MockCluster...");
            cluster.stop();
            shutdownLatch.countDown();
        }));

        cluster.start();

        try {
            if (durationSeconds > 0) {
                boolean finished = shutdownLatch.await(durationSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    cluster.stop();
                }
            } else {
                shutdownLatch.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printUsage() {
        System.out.println("PulseNet MockCluster Simulator");
        System.out.println("Usage: java -cp <jar> com.pulsenet.simulation.MockCluster [options]");
        System.out.println("Options:");
        System.out.println("  --host <ip>          Aggregator host (default: 127.0.0.1)");
        System.out.println("  --port <port>        Aggregator port (default: 9000)");
        System.out.println("  --nodes <N>          Number of simulated agent nodes (default: 5)");
        System.out.println("  --interval <ms>      Telemetry stream interval in ms (default: 1000)");
        System.out.println("  --duration <sec>     Total run time before automatic exit");
        System.out.println("  --no-chaos           Disable automatic failure injection for agent-03");
    }
}
