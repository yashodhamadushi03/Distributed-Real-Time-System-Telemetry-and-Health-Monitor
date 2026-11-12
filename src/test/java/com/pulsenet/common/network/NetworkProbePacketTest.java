package com.pulsenet.common.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class NetworkProbePacketTest {

    @Test
    @DisplayName("Request packet serialization and deserialization preserves all fields")
    void testRequestPacketRoundTrip() {
        long seq = 42L;
        long sendTime = 1700000000123L;
        String nodeId = "node-alpha-01";

        NetworkProbePacket req = NetworkProbePacket.createRequest(seq, sendTime, nodeId);
        assertTrue(req.isRequest());
        assertFalse(req.isResponse());
        assertEquals(NetworkProbePacket.TYPE_PROBE_REQUEST, req.getType());
        assertEquals(seq, req.getSequenceNumber());
        assertEquals(sendTime, req.getClientSendTimestamp());
        assertEquals(0L, req.getServerEchoTimestamp());
        assertEquals(nodeId, req.getNodeId());

        byte[] bytes = req.toBytes();
        assertNotNull(bytes);
        assertTrue(bytes.length >= NetworkProbePacket.HEADER_MIN_SIZE);

        NetworkProbePacket parsed = NetworkProbePacket.fromBytes(bytes, 0, bytes.length);
        assertNotNull(parsed);
        assertEquals(req, parsed);
        assertEquals(req.hashCode(), parsed.hashCode());
        assertTrue(parsed.isRequest());
        assertEquals(seq, parsed.getSequenceNumber());
        assertEquals(sendTime, parsed.getClientSendTimestamp());
        assertEquals(0L, parsed.getServerEchoTimestamp());
        assertEquals(nodeId, parsed.getNodeId());
    }

    @Test
    @DisplayName("Response packet serialization preserves echo timestamp")
    void testResponsePacketRoundTrip() {
        NetworkProbePacket req = NetworkProbePacket.createRequest(101L, 1700000000500L, "agent-laptop");
        long echoTime = 1700000000505L;

        NetworkProbePacket resp = NetworkProbePacket.createResponse(req, echoTime);
        assertTrue(resp.isResponse());
        assertFalse(resp.isRequest());
        assertEquals(101L, resp.getSequenceNumber());
        assertEquals(1700000000500L, resp.getClientSendTimestamp());
        assertEquals(echoTime, resp.getServerEchoTimestamp());
        assertEquals("agent-laptop", resp.getNodeId());

        byte[] bytes = resp.toBytes();
        NetworkProbePacket parsed = NetworkProbePacket.fromBytes(bytes, 0, bytes.length);
        assertNotNull(parsed);
        assertEquals(resp, parsed);
        assertTrue(parsed.isResponse());
        assertEquals(echoTime, parsed.getServerEchoTimestamp());
    }

    @Test
    @DisplayName("Malformed or truncated packets return null gracefully")
    void testMalformedPackets() {
        assertNull(NetworkProbePacket.fromBytes(null, 0, 0));
        assertNull(NetworkProbePacket.fromBytes(new byte[10], 0, 10)); // Truncated header

        // Corrupt magic byte
        byte[] validBytes = NetworkProbePacket.createRequest(1L, 1000L, "test").toBytes();
        validBytes[0] = 0x00;
        assertNull(NetworkProbePacket.fromBytes(validBytes, 0, validBytes.length));

        // Invalid type byte
        validBytes = NetworkProbePacket.createRequest(1L, 1000L, "test").toBytes();
        validBytes[2] = 99; // Unknown type
        assertNull(NetworkProbePacket.fromBytes(validBytes, 0, validBytes.length));
    }
}

