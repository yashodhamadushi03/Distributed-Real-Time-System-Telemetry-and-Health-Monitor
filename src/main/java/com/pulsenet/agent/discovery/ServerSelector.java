package com.pulsenet.agent.discovery;

import com.pulsenet.common.discovery.DiscoveredServer;

import java.util.List;

/**
 * Strategy interface for selecting one server from multiple discovered PulseNet servers.
 */
@FunctionalInterface
public interface ServerSelector {

    /**
     * Selects one target server from the candidate list.
     *
     * @param candidates list of discovered servers (guaranteed non-empty)
     * @return selected DiscoveredServer, or null if aborted or no selection made
     */
    DiscoveredServer select(List<DiscoveredServer> candidates);
}

