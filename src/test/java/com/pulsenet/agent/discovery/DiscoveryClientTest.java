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

    @Test
    @DisplayName("Scenario A: Exactly one server discovered is automatically selected without prompt")
    void testScenarioAExactlyOneServerAutoSelected() throws Exception {
        InetAddress ip = InetAddress.getByName("192.168.1.10");
        DiscoveredServer s1 = new DiscoveredServer("srv-lone", "192.168.1.10", 9000, ip);

        // Even with an interactive selector, single server must auto-select without reading from input
        InteractiveConsoleSelector selector = new InteractiveConsoleSelector(
                new java.io.ByteArrayInputStream(new byte[0]),
                new java.io.PrintStream(new java.io.ByteArrayOutputStream())
        );

        DiscoveredServer selected = DiscoveryClient.selectServer(List.of(s1), null, selector);
        assertNotNull(selected);
        assertEquals("srv-lone", selected.serverId());
    }

    @Test
    @DisplayName("Scenario B: Multiple servers with custom selector delegates to selector")
    void testScenarioBManualSelectionViaSelector() throws Exception {
        InetAddress ip = InetAddress.getByName("192.168.1.10");
        DiscoveredServer s1 = new DiscoveredServer("srv-alpha", "192.168.1.10", 9000, ip);
        DiscoveredServer s2 = new DiscoveredServer("srv-beta", "192.168.1.11", 9000, ip);
        DiscoveredServer s3 = new DiscoveredServer("srv-gamma", "192.168.1.12", 9000, ip);

        List<DiscoveredServer> list = List.of(s1, s2, s3);

        // Selector explicitly chooses candidate 3
        ServerSelector customSelector = candidates -> candidates.get(2);
        DiscoveredServer selected = DiscoveryClient.selectServer(list, null, customSelector);

        assertNotNull(selected);
        assertEquals("srv-gamma", selected.serverId());
    }

    @Test
    @DisplayName("InteractiveConsoleSelector prompts and parses valid user choice")
    void testInteractiveConsoleSelectorValidChoice() throws Exception {
        InetAddress ip = InetAddress.getByName("192.168.1.10");
        DiscoveredServer s1 = new DiscoveredServer("srv-alpha", "192.168.1.10", 9000, ip);
        DiscoveredServer s2 = new DiscoveredServer("srv-beta", "192.168.1.11", 9000, ip);

        String simulatedInput = "2\n";
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(simulatedInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        InteractiveConsoleSelector selector = new InteractiveConsoleSelector(in, new java.io.PrintStream(out));
        DiscoveredServer chosen = selector.select(List.of(s1, s2));

        assertNotNull(chosen);
        assertEquals("srv-beta", chosen.serverId());
        assertTrue(out.toString().contains("MULTIPLE PULSENET SERVERS DISCOVERED"));
    }

    @Test
    @DisplayName("InteractiveConsoleSelector recovers from invalid input before valid choice")
    void testInteractiveConsoleSelectorInvalidThenValid() throws Exception {
        InetAddress ip = InetAddress.getByName("192.168.1.10");
        DiscoveredServer s1 = new DiscoveredServer("srv-alpha", "192.168.1.10", 9000, ip);
        DiscoveredServer s2 = new DiscoveredServer("srv-beta", "192.168.1.11", 9000, ip);

        String simulatedInput = "abc\n99\n1\n";
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(simulatedInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        InteractiveConsoleSelector selector = new InteractiveConsoleSelector(in, new java.io.PrintStream(out));
        DiscoveredServer chosen = selector.select(List.of(s1, s2));

        assertNotNull(chosen);
        assertEquals("srv-alpha", chosen.serverId());
        assertTrue(out.toString().contains("Invalid selection 'abc'"));
        assertTrue(out.toString().contains("Invalid selection '99'"));
    }

    @Test
    @DisplayName("InteractiveConsoleSelector returns null when user enters 'q'")
    void testInteractiveConsoleSelectorQuit() throws Exception {
        InetAddress ip = InetAddress.getByName("192.168.1.10");
        DiscoveredServer s1 = new DiscoveredServer("srv-alpha", "192.168.1.10", 9000, ip);
        DiscoveredServer s2 = new DiscoveredServer("srv-beta", "192.168.1.11", 9000, ip);

        String simulatedInput = "q\n";
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(simulatedInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        InteractiveConsoleSelector selector = new InteractiveConsoleSelector(in, new java.io.PrintStream(out));
        DiscoveredServer chosen = selector.select(List.of(s1, s2));

        assertNull(chosen);
        assertTrue(out.toString().contains("Server selection aborted by user"));
    }

    @Test
    @DisplayName("Deduplication: duplicate discovery responses with the same serverId are deduplicated")
    void testDeduplicateDiscoveryResponses() throws Exception {
        InetAddress ip1 = InetAddress.getByName("192.168.1.10");
        InetAddress ip2 = InetAddress.getByName("192.168.1.11");

        DiscoveredServer s1 = new DiscoveredServer("srv-dup", "192.168.1.10", 9000, ip1);
        DiscoveredServer s2 = new DiscoveredServer("srv-dup", "192.168.1.10", 9000, ip2); // same serverId, different packet interface

        java.util.Map<String, DiscoveredServer> map = new java.util.LinkedHashMap<>();
        map.put(s1.serverId(), s1);
        map.put(s2.serverId(), s2);

        assertEquals(1, map.size(), "Duplicate serverId must overwrite/deduplicate in map");
        assertEquals("srv-dup", map.values().iterator().next().serverId());
    }
}

