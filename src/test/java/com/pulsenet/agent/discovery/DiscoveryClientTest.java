package com.pulsenet.agent.discovery;

import com.pulsenet.common.discovery.DiscoveredServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryClientTest {

    @Test
    @DisplayName("Server selection selects preferredServerId when present")
    void testSelectServerPreferred() throws Exception {
        InetAddress ip = InetAddress.getByName("192.168.1.10");
        DiscoveredServer s1 = new DiscoveredServer("srv-alpha", "192.168.1.10", 9000, ip);
        DiscoveredServer s2 = new DiscoveredServer("srv-beta", "192.168.1.11", 9000, ip);
        DiscoveredServer s3 = new DiscoveredServer("srv-gamma", "192.168.1.12", 9000, ip);

        List<DiscoveredServer> list = List.of(s1, s2, s3);

        DiscoveredServer selected = DiscoveryClient.selectServer(list, "srv-beta");
        assertNotNull(selected);
        assertEquals("srv-beta", selected.serverId());
    }

    @Test
    @DisplayName("Server selection uses deterministic sort when preferredServerId is null or unmatched")
    void testSelectServerDeterministic() throws Exception {
        InetAddress ip = InetAddress.getByName("192.168.1.10");
        DiscoveredServer s1 = new DiscoveredServer("srv-gamma", "192.168.1.12", 9000, ip);
        DiscoveredServer s2 = new DiscoveredServer("srv-alpha", "192.168.1.10", 9000, ip);
        DiscoveredServer s3 = new DiscoveredServer("srv-beta", "192.168.1.11", 9000, ip);

        List<DiscoveredServer> list = List.of(s1, s2, s3);

        // When preferred is null, selects lexicographically lowest ("srv-alpha")
        DiscoveredServer selected = DiscoveryClient.selectServer(list, null);
        assertNotNull(selected);
        assertEquals("srv-alpha", selected.serverId());

        // When preferred does not match any candidate, selects lexicographically lowest ("srv-alpha")
        DiscoveredServer selected2 = DiscoveryClient.selectServer(list, "srv-delta");
        assertNotNull(selected2);
        assertEquals("srv-alpha", selected2.serverId());
    }

    @Test
    @DisplayName("Server selection returns null on empty list")
    void testSelectServerEmpty() {
        assertNull(DiscoveryClient.selectServer(null, "preferred"));
        assertNull(DiscoveryClient.selectServer(List.of(), "preferred"));
    }

    @Test
    @DisplayName("Broadcast targets contain at least global broadcast and loopback")
    void testBroadcastTargets() {
        List<InetAddress> targets = DiscoveryClient.getBroadcastTargets();
        assertFalse(targets.isEmpty(), "Broadcast targets must not be empty");

        boolean hasLoopback = targets.stream().anyMatch(InetAddress::isLoopbackAddress);
        boolean hasGlobalBroadcast = targets.stream().anyMatch(a -> "255.255.255.255".equals(a.getHostAddress()));

        assertTrue(hasGlobalBroadcast, "Broadcast targets must include 255.255.255.255");
        assertTrue(hasLoopback, "Broadcast targets must include loopback address for local testing");
    }
}
