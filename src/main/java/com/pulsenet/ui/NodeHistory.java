package com.pulsenet.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;

/**
 * Recent one-per-second samples behind the trend graphs of one node (or of the whole fleet).
 * Seconds without a measurement are stored as {@link Double#NaN} so that time keeps its scale.
 *
 * <p>Not thread-safe: used from the JavaFX application thread only.
 */
final class NodeHistory {

    /** Number of samples kept, i.e. seconds shown by a graph. */
    static final int CAPACITY = 60;

    enum Metric { CPU, MEMORY, DOWNLOAD, UPLOAD, RTT, JITTER }

    private final Map<Metric, Deque<Double>> samples = new EnumMap<>(Metric.class);
    private long ticks;
    private long onlineTicks;

    void add(Metric metric, double value) {
        Deque<Double> values = samples.computeIfAbsent(metric, m -> new ArrayDeque<>());
        values.addLast(value);
        if (values.size() > CAPACITY) {
            values.removeFirst();
        }
    }

    /** Records whether the node was online during this second. */
    void addAvailability(boolean online) {
        ticks++;
        if (online) {
            onlineTicks++;
        }
    }

    /**
     * @return samples from oldest to newest; unmeasured seconds are {@link Double#NaN}
     */
    double[] values(Metric metric) {
        Deque<Double> values = samples.get(metric);
        return values == null ? new double[0] : values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * @return percent of observed seconds the node was online, or {@link Double#NaN} if never observed
     */
    double availabilityPercent() {
        return ticks > 0 ? onlineTicks * 100.0 / ticks : Double.NaN;
    }
}
