package com.pulsenet.agent.connection;

/**
 * Observer interface notified upon agent connection state transitions.
 */
@FunctionalInterface
public interface ConnectionStateListener {

    /**
     * Invoked when connection state changes.
     *
     * @param oldState previous state
     * @param newState new state
     */
    void onStateChanged(ConnectionState oldState, ConnectionState newState);
}

