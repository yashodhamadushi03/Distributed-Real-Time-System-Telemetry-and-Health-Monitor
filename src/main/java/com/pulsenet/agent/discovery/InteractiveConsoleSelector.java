package com.pulsenet.agent.discovery;

import com.pulsenet.common.discovery.DiscoveredServer;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Scanner;

/**
 * Interactive terminal menu for allowing a user to choose a target server when
 * multiple PulseNet aggregator servers are discovered on the local network.
 */
public class InteractiveConsoleSelector implements ServerSelector {

    private final InputStream in;
    private final PrintStream out;

    public InteractiveConsoleSelector() {
        this(System.in, System.out);
    }

    public InteractiveConsoleSelector(InputStream in, PrintStream out) {
        this.in = in != null ? in : System.in;
        this.out = out != null ? out : System.out;
    }

    @Override
    public DiscoveredServer select(List<DiscoveredServer> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        // Scenario A: Exactly one server discovered -> auto-select without asking
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        // Scenario B: Multiple servers discovered -> display terminal menu
        out.println();
        out.println("================================================================================");
        out.println("                   MULTIPLE PULSENET SERVERS DISCOVERED                         ");
        out.println("================================================================================");
        for (int i = 0; i < candidates.size(); i++) {
            DiscoveredServer s = candidates.get(i);
            out.printf("  [%d] Server ID : %-25s | Endpoint: %s:%d%n",
                    (i + 1), s.serverId(), s.resolveConnectHost(), s.tcpPort());
        }
        out.println("================================================================================");

        Scanner scanner = new Scanner(in);
        while (true) {
            out.printf("Select server [1-%d] (or 'q' to quit): ", candidates.size());
            out.flush();
            if (!scanner.hasNextLine()) {
                // Non-interactive stdin: fallback to first server
                return candidates.get(0);
            }
            String line = scanner.nextLine().trim();
            if ("q".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) {
                out.println("[DISCOVERY] Server selection aborted by user.");
                return null;
            }
            try {
                int choice = Integer.parseInt(line);
                if (choice >= 1 && choice <= candidates.size()) {
                    DiscoveredServer chosen = candidates.get(choice - 1);
                    out.printf("[DISCOVERY] Selected server '%s' (%s:%d)%n",
                            chosen.serverId(), chosen.resolveConnectHost(), chosen.tcpPort());
                    return chosen;
                }
            } catch (NumberFormatException ignored) {
            }
            out.printf("Invalid selection '%s'. Please enter a number between 1 and %d.%n", line, candidates.size());
        }
    }
}

