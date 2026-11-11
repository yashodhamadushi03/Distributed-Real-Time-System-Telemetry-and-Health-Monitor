package com.pulsenet.server.network;

import com.pulsenet.common.model.TelemetryPayload;
import com.pulsenet.common.protocol.WireProtocol;
import com.pulsenet.server.registry.NodeRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
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
        boolean timedOut = false;
        try (clientSocket;
             InputStream in = clientSocket.getInputStream()) {

            while (!clientSocket.isClosed() && !Thread.currentThread().isInterrupted()) {
                // Read next framed message from socket (I/O occurs without holding any registry locks)
                String jsonMessage = WireProtocol.receiveFramedMessage(in);
                if (jsonMessage == null) {
                    // EOF: Remote peer cleanly closed connection
                    LOGGER.info("Client " + describe() + " disconnected (EOF).");
                    break;
                }

                try {
                    TelemetryPayload payload = TelemetryPayload.fromJson(jsonMessage);
                    this.associatedNodeId = payload.getNodeId();

                    // Fast thread-safe state update under NodeRegistry lock. Passing this handler as
                    // the connection owner lets the registry detect superseded connections; threshold
                    // events are edge-triggered inside the registry.
                    registry.upsertNode(payload, remoteAddress, this);

                } catch (Exception ex) {
                    LOGGER.log(Level.WARNING, "Failed to parse telemetry JSON from " + remoteAddress + ": " + ex.getMessage());
                }
            }

        } catch (SocketTimeoutException e) {
            // Half-open socket (peer slept / lost Wi-Fi without FIN/RST): no frame within SO_TIMEOUT.
            timedOut = true;
            LOGGER.info("Client " + describe() + " read timed out: " + e.getMessage());
        } catch (IOException e) {
            LOGGER.info("Client " + describe() + " connection dropped: " + e.getMessage());
        } finally {
            closeQuietly();
            String nodeId = associatedNodeId;
            if (nodeId != null) {
                if (registry.releaseConnection(nodeId, this)) {
                    if (timedOut) {
                        registry.logEvent(String.format("[SYS]  Connection timed out: %s (%s)", nodeId, remoteAddress));
                    } else {
                        registry.logEvent(String.format("[SYS]  Client disconnected: %s (%s)", nodeId, remoteAddress));
                    }
                } else {
                    registry.logEvent(String.format("[SYS]  Superseded stale connection for %s (%s)", nodeId, remoteAddress));
                }
            }
        }
    }

    private String describe() {
        String nodeId = associatedNodeId;
        return remoteAddress + (nodeId != null ? " [" + nodeId + "]" : "");
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
