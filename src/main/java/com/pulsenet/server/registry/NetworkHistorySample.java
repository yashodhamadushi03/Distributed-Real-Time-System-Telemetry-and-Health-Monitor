package com.pulsenet.server.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;

/**
 * Immutable historical sample snapshot for network latency, jitter, packet loss,
 * and connection health for a specific node.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NetworkHistorySample(
        long timestamp,
        String nodeId,
        double currentRttMs,
        double avgRttMs,
        double minRttMs,
        double maxRttMs,
        double currentJitterMs,
        double avgJitterMs,
        double packetLossPercent,
        String connectionStatus,
        int reconnectionCount
) implements Serializable {
}

