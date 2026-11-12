package com.pulsenet.agent.connection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionStateTest {

    @Test
    @DisplayName("Verify ConnectionState query helper methods")
    void testStateQueries() {
        assertTrue(ConnectionState.CONNECTED.isConnected());
        assertFalse(ConnectionState.CONNECTING.isConnected());
        assertFalse(ConnectionState.CONNECTION_LOST.isConnected());
        assertFalse(ConnectionState.RECONNECTING.isConnected());
        assertFalse(ConnectionState.DISCONNECTED.isConnected());
        assertFalse(ConnectionState.STOPPING.isConnected());
        assertFalse(ConnectionState.STOPPED.isConnected());

        assertTrue(ConnectionState.STOPPING.isTerminal());
        assertTrue(ConnectionState.STOPPED.isTerminal());
        assertFalse(ConnectionState.CONNECTED.isTerminal());
        assertFalse(ConnectionState.RECONNECTING.isTerminal());
        assertFalse(ConnectionState.DISCONNECTED.isTerminal());
    }
}
