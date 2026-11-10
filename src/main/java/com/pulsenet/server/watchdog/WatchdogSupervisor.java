package com.pulsenet.server.watchdog;

import com.pulsenet.server.registry.NodeRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Background watchdog daemon supervising active node heartbeats.
 * Sweeps the registry every second, identifying lagging nodes and marking them OFFLINE.
 */
public class WatchdogSupervisor implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(WatchdogSupervisor.class.getName());

    private final NodeRegistry registry;
    private final double timeoutSeconds;
    private final long sweepIntervalMillis;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread supervisorThread;

    public WatchdogSupervisor(NodeRegistry registry) {
        this(registry, 5.0, 1000L);
    }

    public WatchdogSupervisor(NodeRegistry registry, double timeoutSeconds) {
        this(registry, timeoutSeconds, 1000L);
    }

    public WatchdogSupervisor(NodeRegistry registry, double timeoutSeconds, long sweepIntervalMillis) {
        this.registry = registry;
        this.timeoutSeconds = timeoutSeconds;
        this.sweepIntervalMillis = sweepIntervalMillis;
    }

    /**
     * Starts the supervisor in a dedicated background daemon thread.
     */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            supervisorThread = new Thread(this, "WatchdogSupervisor");
            supervisorThread.setDaemon(true);
            supervisorThread.start();
            LOGGER.info(String.format("WatchdogSupervisor started (timeout=%.1fs, sweep=%dms)",
                    timeoutSeconds, sweepIntervalMillis));
        }
    }

    @Override
    public void run() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(sweepIntervalMillis);
                List<String> offlineNodes = registry.auditStaleNodes(timeoutSeconds);
                if (!offlineNodes.isEmpty()) {
                    LOGGER.warning(String.format("Watchdog audit: %d node(s) marked OFFLINE: %s",
                            offlineNodes.size(), offlineNodes));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.warning("Unexpected error during watchdog audit sweep: " + e.getMessage());
            }
        }
        LOGGER.info("WatchdogSupervisor stopped.");
    }

    /**
     * Stops the watchdog daemon.
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            if (supervisorThread != null) {
                supervisorThread.interrupt();
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
