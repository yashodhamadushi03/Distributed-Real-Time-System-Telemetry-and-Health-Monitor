package com.pulsenet.agent.connection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionManagerTest {

    @Test
    @DisplayName("State transitions fire listener callbacks and record history")
    void testStateTransitions() {
        ConnectionManager manager = new ConnectionManager("test-node", "127.0.0.1", 9000);
        List<ConnectionState> history = new ArrayList<>();

        manager.addListener((oldState, newState) -> history.add(newState));

        assertEquals(ConnectionState.DISCONNECTED, manager.getState());

        assertTrue(manager.transitionTo(ConnectionState.CONNECTING));
        assertTrue(manager.transitionTo(ConnectionState.CONNECTED));
        assertTrue(manager.transitionTo(ConnectionState.CONNECTION_LOST));
        assertTrue(manager.transitionTo(ConnectionState.RECONNECTING));
        assertTrue(manager.transitionTo(ConnectionState.STOPPING));
        assertTrue(manager.transitionTo(ConnectionState.STOPPED));

        // Terminal state should reject transitioning back to active state
        assertFalse(manager.transitionTo(ConnectionState.CONNECTED), "STOPPED state must reject transition to CONNECTED");

        assertEquals(List.of(
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED,
                ConnectionState.CONNECTION_LOST,
                ConnectionState.RECONNECTING,
                ConnectionState.STOPPING,
                ConnectionState.STOPPED
        ), history);
    }

    @Test
    @DisplayName("Stop cleanly halts reconnection and sets terminal STOPPED state")
    void testStopGraceful() {
        ConnectionManager manager = new ConnectionManager("test-node", "127.0.0.1", 9000);
        manager.transitionTo(ConnectionState.RECONNECTING);

        manager.stop();
        assertEquals(ConnectionState.STOPPED, manager.getState());
        assertTrue(manager.isTerminal());
        assertFalse(manager.isConnected());
    }

    @Test
    @DisplayName("Duplicate reconnection attempts are prevented concurrently")
    void testPreventDuplicateReconnections() throws Exception {
        ConnectionManager manager = new ConnectionManager("test-node", "127.0.0.1", 9000);
        manager.transitionTo(ConnectionState.CONNECTED);

        int threads = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    manager.notifyConnectionLost(new RuntimeException("Simulated drop"));
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

        // State must be RECONNECTING and retry count should have incremented exactly once for this cycle
        assertEquals(ConnectionState.RECONNECTING, manager.getState());
        assertEquals(1, manager.getRetryCount(), "Only one reconnection should be scheduled for the loss event");

        manager.stop();
    }
}

