package com.pulsenet.server.network;

import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.common.protocol.WireProtocol;
import com.pulsenet.server.registry.NodeRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Worker thread dedicated to servicing an individual client socket connection.
 * Continuously reads length-prefixed frames, deserializes telemetry, and updates the NodeRegistry.
 */
public class ClientHandler implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(ClientHandler.class.getName());

    private final Socket clientSocket;
    private final NodeRegistry registry;
    private final String remoteAddress;
    private volatile String associatedNodeId;

    public ClientHandler(Socket clientSocket, NodeRegistry registry) {
        this.clientSocket = clientSocket;
        this.registry = registry;
        this.remoteAddress = clientSocket.getRemoteSocketAddress() != null
                ? clientSocket.getRemoteSocketAddress().toString()
                : "unknown";
    }

    @Override
    public void run() {
        LOGGER.info("Accepted connection from " + remoteAddress);
        try (clientSocket;
             InputStream in = clientSocket.getInputStream()) {

            while (!clientSocket.isClosed() && !Thread.currentThread().isInterrupted()) {
                // Read next framed message from socket (I/O occurs without holding any registry locks)
                String jsonMessage = WireProtocol.receiveFramedMessage(in);
                if (jsonMessage == null) {
                    // EOF: Remote peer cleanly closed connection
                    LOGGER.info("Client " + remoteAddress + (associatedNodeId != null ? " [" + associatedNodeId + "]" : "")
                            + " disconnected (EOF).");
                    break;
                }

                try {
                    TelemetryPayload payload = TelemetryPayload.fromJson(jsonMessage);
                    this.associatedNodeId = payload.getNodeId();

                    // Fast thread-safe state update under NodeRegistry lock
                    registry.upsertNode(payload, remoteAddress);

                    // Threshold warning event logging
                    if (payload.getCpuPercent() > 85.0) {
                        registry.logEvent(String.format("[WARN] %s crossed CPU threshold: %.1f%%",
                                payload.getNodeId(), payload.getCpuPercent()));
                    }
                    if (payload.getMemoryPercent() > 90.0) {
                        registry.logEvent(String.format("[WARN] %s crossed Memory threshold: %.1f%%",
                                payload.getNodeId(), payload.getMemoryPercent()));
                    }

                } catch (Exception ex) {
                    LOGGER.log(Level.WARNING, "Failed to parse telemetry JSON from " + remoteAddress + ": " + ex.getMessage());
                }
            }

        } catch (IOException e) {
            LOGGER.info("Client " + remoteAddress + (associatedNodeId != null ? " [" + associatedNodeId + "]" : "")
                    + " connection dropped: " + e.getMessage());
        } finally {
            closeQuietly();
            if (associatedNodeId != null) {
                registry.logEvent(String.format("[SYS]  Client disconnected: %s (%s)", associatedNodeId, remoteAddress));
            }
        }
    }

    private void closeQuietly() {
        if (!clientSocket.isClosed()) {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public String getAssociatedNodeId() {
        return associatedNodeId;
    }
}
