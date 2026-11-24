package com.pulsenet.ui;

import com.pulsenet.common.cli.CliArgs;
import com.pulsenet.server.ServerMain;
import com.pulsenet.server.discovery.DiscoveryServer;
import com.pulsenet.server.network.NetworkProbeServer;

import java.util.logging.Logger;

/**
 * Command-line options of the graphical server.
 *
 * @param host            TCP bind address
 * @param tcpPort         telemetry port
 * @param discoveryPort   UDP discovery port
 * @param probePort       UDP probe port
 * @param serverId        identifier announced to agents
 * @param watchdogTimeout seconds of silence before a node is marked OFFLINE
 */
record UiOptions(String host, int tcpPort, int discoveryPort, int probePort, String serverId,
                 double watchdogTimeout) {

    private static final Logger LOGGER = Logger.getLogger(UiOptions.class.getName());

    static final int DEFAULT_TCP_PORT = 9000;
    static final double DEFAULT_WATCHDOG_TIMEOUT = 5.0;

    /**
     * @throws CliArgs.UsageException if an option value is missing or invalid
     */
    static UiOptions parse(String[] args) {
        String host = "0.0.0.0";
        int tcpPort = DEFAULT_TCP_PORT;
        int discoveryPort = DiscoveryServer.DEFAULT_UDP_PORT;
        int probePort = NetworkProbeServer.DEFAULT_PROBE_PORT;
        String serverId = null;
        double watchdogTimeout = DEFAULT_WATCHDOG_TIMEOUT;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = CliArgs.text(args, i++);
                case "--port" -> tcpPort = CliArgs.port(args, i++);
                case "--udp-port" -> discoveryPort = CliArgs.port(args, i++);
                case "--probe-port", "--udp-probe-port" -> probePort = CliArgs.port(args, i++);
                case "--server-id" -> serverId = CliArgs.text(args, i++);
                case "--watchdog-timeout" -> watchdogTimeout = CliArgs.decimal(args, i++, 0.5, 86_400);
                default -> LOGGER.warning("Unknown argument: " + args[i]);
            }
        }
        if (discoveryPort == probePort) {
            throw new CliArgs.UsageException("--udp-port and --probe-port must be different");
        }
        return new UiOptions(host, tcpPort, discoveryPort, probePort,
                serverId != null ? serverId : ServerMain.generateDefaultServerId(tcpPort), watchdogTimeout);
    }

    static boolean wantsHelp(String[] args) {
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    static void printUsage() {
        System.out.println("PulseNet Network Operations Center (graphical server)");
        System.out.println("Usage: java -jar pulsenet.jar ui [options]");
        System.out.println("Options:");
        System.out.println("  --host <ip>              TCP bind address (default: 0.0.0.0)");
        System.out.println("  --port <port>            Telemetry TCP port (default: 9000)");
        System.out.println("  --udp-port <port>        UDP discovery port (default: 9001)");
        System.out.println("  --probe-port <port>      UDP network probe port (default: 9002)");
        System.out.println("  --server-id <id>         Server identifier (default: derived from hostname)");
        System.out.println("  --watchdog-timeout <sec> Seconds of silence before marking a node OFFLINE (default: 5.0)");
    }
}
