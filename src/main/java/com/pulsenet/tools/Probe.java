package com.pulsenet.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Connectivity pre-flight check: attempts a raw TCP connect to the aggregator server and
 * classifies the outcome so presenters can diagnose network problems in one command.
 *
 * <p>The probe closes the socket immediately after connecting and never sends a frame, so the
 * server never registers a phantom node.
 *
 * <pre>
 *   java -jar pulsenet.jar probe --host 192.168.137.1 [--port 9000] [--timeout 3000]
 * </pre>
 */
public final class Probe {

    /** Default connect timeout in milliseconds. */
    public static final int DEFAULT_TIMEOUT_MILLIS = 3000;

    /** Default aggregator port. */
    public static final int DEFAULT_PORT = 9000;

    /** Exit status when the server is reachable. */
    public static final int EXIT_OK = 0;

    /** Exit status when the server is not reachable. */
    public static final int EXIT_UNREACHABLE = 1;

    /** Exit status for invalid command-line usage. */
    public static final int EXIT_USAGE = 2;

    private Probe() {
    }

    /** Classified probe outcome. */
    public enum Status {
        /** TCP connect succeeded. */
        OK,
        /** Host answered with RST: nothing is listening on the port. */
        REFUSED,
        /** No answer within the timeout: firewall, wrong IP, or client isolation. */
        TIMEOUT,
        /** No route to host or other network error. */
        UNREACHABLE,
        /** Host name could not be resolved. */
        UNKNOWN_HOST
    }

    /**
     * Result of a single probe.
     *
     * @param status  classified outcome
     * @param message human-readable diagnosis line
     */
    public record Result(Status status, String message) {
        /** @return true if the server accepted the TCP connection */
        public boolean isOk() {
            return status == Status.OK;
        }
    }

    /**
     * Attempts a TCP connect to {@code host:port} and classifies the outcome.
     *
     * @param host          server IP or hostname
     * @param port          server TCP port
     * @param timeoutMillis connect timeout in milliseconds
     * @return the classified result; never throws
     */
    public static Result probe(String host, int port, int timeoutMillis) {
        String target = host + ":" + port;
        InetSocketAddress address = new InetSocketAddress(host, port);
        if (address.isUnresolved()) {
            return new Result(Status.UNKNOWN_HOST,
                    "UNKNOWN HOST: cannot resolve '" + host + "' -> check the IP / same network");
        }
        try (Socket socket = new Socket()) {
            socket.connect(address, timeoutMillis);
            return new Result(Status.OK, "OK: server reachable at " + target);
        } catch (ConnectException e) {
            return new Result(Status.REFUSED, "REFUSED: host reachable but nothing listening on "
                    + port + " -> start the server");
        } catch (SocketTimeoutException e) {
            return new Result(Status.TIMEOUT, "TIMEOUT: no response from " + target
                    + " -> firewall on server laptop, wrong IP, or Wi-Fi client isolation");
        } catch (UnknownHostException e) {
            return new Result(Status.UNKNOWN_HOST,
                    "UNKNOWN HOST: cannot resolve '" + host + "' -> check the IP / same network");
        } catch (NoRouteToHostException e) {
            return new Result(Status.UNREACHABLE, "UNREACHABLE: no route to " + target
                    + " -> check the IP / same network");
        } catch (IOException | IllegalArgumentException e) {
            return new Result(Status.UNREACHABLE, "UNREACHABLE: " + target + " (" + e.getMessage()
                    + ") -> check the IP / same network");
        }
    }

    /**
     * JVM entry point. Exits with a non-zero status unless the server is reachable.
     *
     * @param args CLI arguments
     */
    public static void main(String[] args) {
        int status = run(args, System.out);
        if (status != EXIT_OK) {
            System.exit(status);
        }
    }

    /**
     * Testable CLI implementation.
     *
     * @param args CLI arguments
     * @param out  output stream
     * @return {@link #EXIT_OK}, {@link #EXIT_UNREACHABLE}, or {@link #EXIT_USAGE}
     */
    public static int run(String[] args, PrintStream out) {
        String host = null;
        int port = DEFAULT_PORT;
        int timeout = DEFAULT_TIMEOUT_MILLIS;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = requireValue(args, ++i);
                    case "--port" -> port = Integer.parseInt(requireValue(args, ++i));
                    case "--timeout" -> timeout = Integer.parseInt(requireValue(args, ++i));
                    case "--help", "-h" -> {
                        printUsage(out);
                        return EXIT_OK;
                    }
                    default -> {
                        out.println("Unknown argument: " + args[i]);
                        printUsage(out);
                        return EXIT_USAGE;
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            out.println("Invalid arguments: " + e.getMessage());
            printUsage(out);
            return EXIT_USAGE;
        }
        if (host == null || host.isBlank()) {
            out.println("Missing required --host <ip>");
            printUsage(out);
            return EXIT_USAGE;
        }

        out.println("Probing " + host + ":" + port + " (timeout " + timeout + " ms)...");
        Result result = probe(host, port, timeout);
        out.println(result.message());
        return result.isOk() ? EXIT_OK : EXIT_UNREACHABLE;
    }

    private static String requireValue(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException("missing value for " + args[index - 1]);
        }
        return args[index];
    }

    private static void printUsage(PrintStream out) {
        out.println("PulseNet Connectivity Probe");
        out.println("Usage: java -jar pulsenet.jar probe --host <ip> [options]");
        out.println("Options:");
        out.println("  --host <ip/hostname>   Aggregator server host (required)");
        out.println("  --port <port>          Aggregator server port (default: 9000)");
        out.println("  --timeout <ms>         Connect timeout in milliseconds (default: 3000)");
    }
}
