package com.pulsenet.server.network;

import com.pulsenet.server.registry.NodeRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Multithreaded TCP server listening for incoming client agent telemetry connections.
 * Dispatches each accepted connection to a dedicated ClientHandler worker thread.
 */
public class TelemetryServer implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(TelemetryServer.class.getName());

    private final String host;
    private final int port;
    private final NodeRegistry registry;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread listenerThread;
    private final Set<Socket> activeClientSockets = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public TelemetryServer(int port) {
        this("0.0.0.0", port, new NodeRegistry());
    }

    public TelemetryServer(String host, int port, NodeRegistry registry) {
        this.host = host;
        this.port = port;
        this.registry = registry;
    }

    /**
     * Starts the TCP listener on a background thread.
     */
    public synchronized void start() throws IOException {
        if (running.compareAndSet(false, true)) {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(host, port));

            listenerThread = new Thread(this, "TelemetryServer-Listener-" + getPort());
            listenerThread.setDaemon(true);
            listenerThread.start();

            LOGGER.info(String.format("PulseNet TelemetryServer listening on %s:%d", host, getPort()));
        }
    }

    /**
     * Main accept loop dispatching worker threads.
     */
    @Override
    public void run() {
        while (running.get() && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                clientSocket.setKeepAlive(true);

                activeClientSockets.add(clientSocket);

                ClientHandler handler = new ClientHandler(clientSocket, registry);
                Thread worker = new Thread(() -> {
                    try {
                        handler.run();
                    } finally {
                        activeClientSockets.remove(clientSocket);
                    }
                }, "ClientHandler-" + clientSocket.getRemoteSocketAddress());
                worker.setDaemon(true);
                worker.start();

            } catch (SocketException e) {
                if (!running.get()) {
                    LOGGER.info("Server socket closed gracefully.");
                    break;
                }
                LOGGER.log(Level.WARNING, "Socket accept error: " + e.getMessage());
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.log(Level.WARNING, "I/O error in accept loop: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Gracefully stops the listener and closes all connected client sockets.
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            LOGGER.info("Stopping TelemetryServer on port " + getPort() + "...");
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }

            // Close all active client connections
            for (Socket client : activeClientSockets) {
                if (client != null && !client.isClosed()) {
                    try {
                        client.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            activeClientSockets.clear();

            if (listenerThread != null) {
                listenerThread.interrupt();
            }
            LOGGER.info("TelemetryServer stopped.");
        }
    }

    public int getPort() {
        if (serverSocket != null && serverSocket.isBound()) {
            return serverSocket.getLocalPort();
        }
        return port;
    }

    public NodeRegistry getRegistry() {
        return registry;
    }

    public boolean isRunning() {
        return running.get();
    }
}
