package com.pulsenet.common.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryMessageTest {

    @Test
    @DisplayName("Request message serialization and deserialization symmetry")
    void testRequestSymmetry() throws Exception {
        DiscoveryMessage req = DiscoveryMessage.createRequest("test-agent-01", "preferred-srv");
        byte[] bytes = req.toBytes();
        DiscoveryMessage parsed = DiscoveryMessage.fromBytes(bytes, 0, bytes.length);

        assertEquals(DiscoveryMessage.TYPE_REQUEST, parsed.getType());
        assertEquals(1, parsed.getProtocolVersion());
        assertEquals("test-agent-01", parsed.getAgentId());
        assertEquals("preferred-srv", parsed.getPreferredServerId());
        assertTrue(parsed.isValidRequest());
        assertFalse(parsed.isValidResponse());
    }

    @Test
    @DisplayName("Response message serialization and deserialization symmetry")
    void testResponseSymmetry() throws Exception {
        DiscoveryMessage resp = DiscoveryMessage.createResponse("pulsenet-central-server", "192.168.1.50", 9000);
        byte[] bytes = resp.toBytes();
        DiscoveryMessage parsed = DiscoveryMessage.fromBytes(bytes, 0, bytes.length);

        assertEquals(DiscoveryMessage.TYPE_RESPONSE, parsed.getType());
        assertEquals(1, parsed.getProtocolVersion());
        assertEquals("pulsenet-central-server", parsed.getServerId());
        assertEquals("192.168.1.50", parsed.getTcpHost());
        assertEquals(9000, parsed.getTcpPort());
        assertTrue(parsed.isValidResponse());
        assertFalse(parsed.isValidRequest());
    }

    @Test
    @DisplayName("Validation fails for invalid request fields")
    void testInvalidRequests() {
        DiscoveryMessage msg = new DiscoveryMessage();
        msg.setType(DiscoveryMessage.TYPE_REQUEST);
        msg.setProtocolVersion(1);
        msg.setAgentId(null);
        assertFalse(msg.isValidRequest(), "Null agentId is invalid");

        msg.setAgentId("   ");
        assertFalse(msg.isValidRequest(), "Blank agentId is invalid");

        msg.setAgentId("valid-id");
        msg.setProtocolVersion(999);
        assertFalse(msg.isValidRequest(), "Unsupported protocolVersion is invalid");

        msg.setProtocolVersion(1);
        msg.setType("UNKNOWN_TYPE");
        assertFalse(msg.isValidRequest(), "Unknown message type is invalid");
    }

    @Test
    @DisplayName("Validation fails for invalid response fields")
    void testInvalidResponses() {
        DiscoveryMessage msg = new DiscoveryMessage();
        msg.setType(DiscoveryMessage.TYPE_RESPONSE);
        msg.setProtocolVersion(1);
        msg.setServerId(null);
        msg.setTcpPort(9000);
        assertFalse(msg.isValidResponse(), "Null serverId is invalid");

        msg.setServerId("srv-01");
        msg.setTcpPort(0);
        assertFalse(msg.isValidResponse(), "Port 0 is invalid");

        msg.setTcpPort(-1);
        assertFalse(msg.isValidResponse(), "Negative port is invalid");

        msg.setTcpPort(70000);
        assertFalse(msg.isValidResponse(), "Port > 65535 is invalid");

        msg.setTcpPort(9000);
        msg.setProtocolVersion(2);
        assertFalse(msg.isValidResponse(), "Unsupported version is invalid");
    }

    @Test
    @DisplayName("DiscoveredServer resolves connect host correctly")
    void testDiscoveredServerResolveHost() throws Exception {
        InetAddress remoteIp = InetAddress.getByName("192.168.1.100");
        InetAddress loopbackIp = InetAddress.getByName("127.0.0.1");

        // Case 1: Server advertised specific LAN IP and UDP packet came from it
        DiscoveredServer s1 = new DiscoveredServer("srv-1", "192.168.1.100", 9000, remoteIp);
        assertEquals("192.168.1.100", s1.resolveConnectHost());

        // Case 2: Server advertised 0.0.0.0 (wildcard) -> uses UDP source address
        DiscoveredServer s2 = new DiscoveredServer("srv-2", "0.0.0.0", 9000, remoteIp);
        assertEquals("192.168.1.100", s2.resolveConnectHost());

        // Case 3: Server mistakenly advertised 127.0.0.1 but response came from remote IP
        DiscoveredServer s3 = new DiscoveredServer("srv-3", "127.0.0.1", 9000, remoteIp);
        assertEquals("192.168.1.100", s3.resolveConnectHost(), "Remote source IP should be preferred over loopback");

        // Case 4: Local test on loopback
        DiscoveredServer s4 = new DiscoveredServer("srv-4", "127.0.0.1", 9000, loopbackIp);
        assertEquals("127.0.0.1", s4.resolveConnectHost());
    }
}

