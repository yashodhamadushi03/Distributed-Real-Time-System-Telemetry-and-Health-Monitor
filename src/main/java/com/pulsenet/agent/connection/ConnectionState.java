package com.pulsenet.agent.connection;

/**
 * Lifecycle states of an agent's TCP connection to the PulseNet Central Aggregator.
 */
public enum ConnectionState {
    /** Initial state before any connection attempt is made. */
    DISCONNECTED,

    /** Active socket connection handshake in progress. */
    CONNECTING,

    /** Socket successfully connected and ready for telemetry transmission. */
    CONNECTED,

    /** Established connection was unexpectedly severed (socket EOF, timeout, or IOException). */
    CONNECTION_LOST,

    /** Waiting for backoff delay or attempting reconnection to the server. */
    RECONNECTING,

    /** Graceful shutdown initiated (disallows future reconnection attempts). */
    STOPPING,

    /** Fully terminated and all network resources released. */
    STOPPED;

    /**
     * @return true if the state allows sending telemetry
     */
    public boolean isConnected() {
        return this == CONNECTED;
    }

    /**
     * @return true if the connection lifecycle has permanently ended
     */
    public boolean isTerminal() {
        return this == STOPPING || this == STOPPED;
    }
}

