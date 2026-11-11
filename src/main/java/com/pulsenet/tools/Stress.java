package com.pulsenet.tools;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CPU stress generator used on cue during the demo to push a node laptop above the 85% CPU
 * threshold, so the server dashboard shows the yellow WARNING state.
 *
 * <p>Spins daemon busy-loop threads guarded by a cooperative {@link AtomicBoolean} stop flag and
 * stops them automatically after the configured duration.
 *
 * <pre>
 *   java -jar pulsenet.jar stress [--seconds 20] [--threads &lt;cores&gt;]
 * </pre>
 */
public final class Stress {

    /** Default stress duration in seconds. */
    public static final int DEFAULT_SECONDS = 20;

    private final long durationMillis;
    private final int threadCount;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong sink = new AtomicLong();
    private final List<Thread> workers = new ArrayList<>();
    private Thread timer;

    /**
     * @param durationMillis how long to keep the CPU busy
     * @param threadCount    number of busy-loop threads (at least 1)
     */
    public Stress(long durationMillis, int threadCount) {
        this.durationMillis = Math.max(0, durationMillis);
        this.threadCount = Math.max(1, threadCount);
    }

    /** Starts the busy-loop threads and the auto-stop timer. Calling it twice is a no-op. */
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        for (int i = 0; i < threadCount; i++) {
            Thread worker = new Thread(this::burn, "PulseNet-Stress-" + (i + 1));
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
        timer = new Thread(() -> {
            try {
                Thread.sleep(durationMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.set(false);
        }, "PulseNet-Stress-Timer");
        timer.setDaemon(true);
        timer.start();
    }

    /** Busy loop doing non-trivial arithmetic the JIT cannot eliminate. */
    private void burn() {
        long x = System.nanoTime() | 1L;
        while (running.get()) {
            for (int i = 0; i < 10_000; i++) {
                x ^= x << 13;
                x ^= x >>> 7;
                x ^= x << 17;
            }
        }
        sink.addAndGet(x);
    }

    /** Requests all threads to stop immediately. */
    public void stop() {
        running.set(false);
        Thread t;
        synchronized (this) {
            t = timer;
        }
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * Waits until every worker thread has exited.
     *
     * @param timeoutMillis maximum total wait
     * @return true if all workers exited within the timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitCompletion(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        List<Thread> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(workers);
        }
        for (Thread worker : snapshot) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return !anyAlive(snapshot);
            }
            worker.join(remaining);
        }
        return !anyAlive(snapshot);
    }

    private static boolean anyAlive(List<Thread> threads) {
        for (Thread t : threads) {
            if (t.isAlive()) {
                return true;
            }
        }
        return false;
    }

    /** @return true while the stop flag is not set */
    public boolean isRunning() {
        return running.get();
    }

    /** @return true if any worker thread is still alive */
    public synchronized boolean hasLiveWorkers() {
        return anyAlive(workers);
    }

    /** @return configured worker thread count */
    public int getThreadCount() {
        return threadCount;
    }

    /**
     * JVM entry point.
     *
     * @param args CLI arguments
     */
    public static void main(String[] args) {
        int status = run(args, System.out);
        if (status != 0) {
            System.exit(status);
        }
    }

    /**
     * Testable CLI implementation: runs the stress for the requested duration and returns.
     *
     * @param args CLI arguments
     * @param out  output stream
     * @return 0 on success, 2 on invalid usage
     */
    public static int run(String[] args, PrintStream out) {
        int seconds = DEFAULT_SECONDS;
        int threads = Runtime.getRuntime().availableProcessors();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--seconds" -> seconds = Integer.parseInt(args[++i]);
                    case "--threads" -> threads = Integer.parseInt(args[++i]);
                    case "--help", "-h" -> {
                        printUsage(out);
                        return 0;
                    }
                    default -> {
                        out.println("Unknown argument: " + args[i]);
                        printUsage(out);
                        return 2;
                    }
                }
            }
        } catch (RuntimeException e) {
            out.println("Invalid arguments: " + e.getMessage());
            printUsage(out);
            return 2;
        }

        Stress stress = new Stress(seconds * 1000L, threads);
        out.printf("Stressing CPU with %d thread(s) for %d s...%n", stress.getThreadCount(), seconds);
        stress.start();
        try {
            stress.awaitCompletion(seconds * 1000L + 5000L);
        } catch (InterruptedException e) {
            stress.stop();
            Thread.currentThread().interrupt();
        }
        out.println("Stress finished.");
        return 0;
    }

    private static void printUsage(PrintStream out) {
        out.println("PulseNet CPU Stress Generator");
        out.println("Usage: java -jar pulsenet.jar stress [options]");
        out.println("Options:");
        out.println("  --seconds <n>   Duration in seconds (default: 20)");
        out.println("  --threads <n>   Busy-loop threads (default: number of CPU cores)");
    }
}
