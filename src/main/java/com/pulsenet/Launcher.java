package com.pulsenet;

import com.pulsenet.agent.AgentMain;
import com.pulsenet.server.ServerMain;
import com.pulsenet.simulation.MockCluster;
import com.pulsenet.tools.Probe;
import com.pulsenet.tools.Stress;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Single entry point for the self-contained {@code pulsenet.jar}.
 *
 * <p>Dispatches a subcommand to the existing CLI entry points without modifying them:
 * <pre>
 *   java -jar pulsenet.jar server  [ServerMain args]
 *   java -jar pulsenet.jar agent   [AgentMain args]
 *   java -jar pulsenet.jar cluster [MockCluster args]
 *   java -jar pulsenet.jar --help
 * </pre>
 */
public final class Launcher {

    /** Exit status for a successful dispatch or help output. */
    public static final int EXIT_OK = 0;

    /** Exit status for a missing or unknown subcommand. */
    public static final int EXIT_USAGE = 2;

    private static final Map<String, Subcommand> SUBCOMMANDS = buildSubcommands();

    private Launcher() {
    }

    /** A named subcommand that delegates to an existing {@code main(String[])}. */
    private record Subcommand(String description, Consumer<String[]> entryPoint) {
    }

    private static Map<String, Subcommand> buildSubcommands() {
        Map<String, Subcommand> map = new LinkedHashMap<>();
        map.put("server", new Subcommand("Run the aggregator server + live dashboard", ServerMain::main));
        map.put("agent", new Subcommand("Run a telemetry agent streaming this machine's metrics", AgentMain::main));
        map.put("cluster", new Subcommand("Run a simulated multi-node cluster (MockCluster)", MockCluster::main));
        map.put("probe", new Subcommand("Check TCP connectivity to a server (no node is registered)", Probe::main));
        map.put("stress", new Subcommand("Burn CPU for N seconds to trigger the WARNING state", Stress::main));
        return Collections.unmodifiableMap(map);
    }

    /**
     * JVM entry point. Exits with a non-zero status only when dispatch fails; on success the
     * delegated main controls the JVM lifetime exactly as it does when invoked directly.
     *
     * @param args the subcommand followed by its arguments
     */
    public static void main(String[] args) {
        int status = run(args);
        if (status != EXIT_OK) {
            System.exit(status);
        }
    }

    /**
     * Testable dispatcher using {@link System#out} and {@link System#err}.
     *
     * @param args the subcommand followed by its arguments
     * @return {@link #EXIT_OK} on success or help, {@link #EXIT_USAGE} on a missing/unknown subcommand
     */
    public static int run(String[] args) {
        return run(args, System.out, System.err);
    }

    /**
     * Testable dispatcher with explicit output streams for launcher-level messages.
     *
     * @param args the subcommand followed by its arguments
     * @param out  stream for help output
     * @param err  stream for error output
     * @return {@link #EXIT_OK} on success or help, {@link #EXIT_USAGE} on a missing/unknown subcommand
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args == null || args.length == 0) {
            err.println("Error: no subcommand given.");
            printUsage(err);
            return EXIT_USAGE;
        }

        String command = args[0];
        if (command.equals("--help") || command.equals("-h") || command.equals("help")) {
            printUsage(out);
            return EXIT_OK;
        }

        Subcommand subcommand = SUBCOMMANDS.get(command);
        if (subcommand == null) {
            err.println("Error: unknown subcommand '" + command + "'.");
            printUsage(err);
            return EXIT_USAGE;
        }

        subcommand.entryPoint().accept(Arrays.copyOfRange(args, 1, args.length));
        return EXIT_OK;
    }

    /**
     * Prints the list of subcommands.
     *
     * @param stream destination stream
     */
    public static void printUsage(PrintStream stream) {
        stream.println("PulseNet - Distributed Real-Time Telemetry Monitor");
        stream.println("Usage: java -jar pulsenet.jar <subcommand> [options]");
        stream.println();
        stream.println("Subcommands:");
        for (Map.Entry<String, Subcommand> entry : SUBCOMMANDS.entrySet()) {
            stream.printf("  %-9s %s%n", entry.getKey(), entry.getValue().description());
        }
        stream.println();
        stream.println("Run 'java -jar pulsenet.jar <subcommand> --help' for subcommand options.");
        stream.println();
        stream.println("Examples:");
        stream.println("  java -jar pulsenet.jar server --port 9000");
        stream.println("  java -jar pulsenet.jar agent --discover");
        stream.println("  java -jar pulsenet.jar agent --host 192.168.137.1 --port 9000");
        stream.println("  java -jar pulsenet.jar cluster --host 192.168.137.1 --nodes 5");
        stream.println("  java -jar pulsenet.jar probe --host 192.168.137.1");
        stream.println("  java -jar pulsenet.jar stress --seconds 20");
    }
}
