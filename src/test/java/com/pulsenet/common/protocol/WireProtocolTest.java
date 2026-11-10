package com.pulsenet.common.protocol;

import com.pulsenet.common.model.TelemetryPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class WireProtocolTest {

    @Test
    @DisplayName("Verify packing and unpacking symmetry of TelemetryPayload")
    void testPackAndReceiveSymmetry() throws Exception {
        TelemetryPayload original = new TelemetryPayload(
                "node-alpha",
                1712000000000L,
                45.5,
                68.2,
                30.1,
                150,
                "HEALTHY"
        );

        byte[] frame = WireProtocol.packMessage(original);
        assertNotNull(frame);
        assertTrue(frame.length > 4);

        ByteArrayInputStream in = new ByteArrayInputStream(frame);
        String receivedJson = WireProtocol.receiveFramedMessage(in);
        assertNotNull(receivedJson);

        TelemetryPayload decoded = TelemetryPayload.fromJson(receivedJson);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("Verify handling of sequential multiple framed messages in a single stream")
    void testSequentialMessagesInStream() throws Exception {
        TelemetryPayload p1 = new TelemetryPayload("node-1", 1000L, 10.0, 20.0, 30.0, 50, "HEALTHY");
        TelemetryPayload p2 = new TelemetryPayload("node-2", 2000L, 85.0, 90.0, 40.0, 220, "WARNING");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WireProtocol.sendFramedMessage(out, p1);
        WireProtocol.sendFramedMessage(out, p2);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());

        String json1 = WireProtocol.receiveFramedMessage(in);
        assertNotNull(json1);
        assertEquals(p1, TelemetryPayload.fromJson(json1));

        String json2 = WireProtocol.receiveFramedMessage(in);
        assertNotNull(json2);
        assertEquals(p2, TelemetryPayload.fromJson(json2));

        // Next read should detect EOF and return null cleanly
        assertNull(WireProtocol.receiveFramedMessage(in));
    }

    @Test
    @DisplayName("Verify simulated TCP fragmentation (reading in tiny byte chunks)")
    void testSimulatedTcpFragmentation() throws Exception {
        TelemetryPayload payload = new TelemetryPayload("frag-node", 99999L, 50.0, 60.0, 70.0, 100, "HEALTHY");
        byte[] frame = WireProtocol.packMessage(payload);

        // InputStream that delivers at most 3 bytes per read() call
        InputStream fragmentedStream = new InputStream() {
            private int index = 0;

            @Override
            public int read() {
                if (index < frame.length) {
                    return frame[index++] & 0xFF;
                }
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (index >= frame.length) {
                    return -1;
                }
                int chunkSize = Math.min(len, Math.min(3, frame.length - index));
                System.arraycopy(frame, index, b, off, chunkSize);
                index += chunkSize;
                return chunkSize;
            }
        };

        String received = WireProtocol.receiveFramedMessage(fragmentedStream);
        assertNotNull(received);
        TelemetryPayload decoded = TelemetryPayload.fromJson(received);
        assertEquals(payload, decoded);
    }

    @Test
    @DisplayName("Verify clean null return on empty stream (EOF on connection open)")
    void testCleanEofOnEmptyStream() throws IOException {
        ByteArrayInputStream emptyStream = new ByteArrayInputStream(new byte[0]);
        assertNull(WireProtocol.receiveFramedMessage(emptyStream));
    }

    @Test
    @DisplayName("Verify clean null return on premature stream termination mid-payload")
    void testPrematureStreamClosure() throws IOException {
        byte[] frame = WireProtocol.packMessage("{\"nodeId\":\"test\"}");
        // Truncate frame to simulate abrupt disconnection halfway through payload
        byte[] truncatedFrame = new byte[frame.length - 5];
        System.arraycopy(frame, 0, truncatedFrame, 0, truncatedFrame.length);

        ByteArrayInputStream in = new ByteArrayInputStream(truncatedFrame);
        assertNull(WireProtocol.receiveFramedMessage(in));
    }

    @Test
    @DisplayName("Verify rejection of negative frame length")
    void testNegativeFrameLength() {
        byte[] badHeader = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(-10).array();
        ByteArrayInputStream in = new ByteArrayInputStream(badHeader);
        assertThrows(IOException.class, () -> WireProtocol.receiveFramedMessage(in));
    }

    @Test
    @DisplayName("Verify rejection of oversized frame length beyond 10MB")
    void testOversizedFrameLength() {
        byte[] giantHeader = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(20 * 1024 * 1024).array();
        ByteArrayInputStream in = new ByteArrayInputStream(giantHeader);
        assertThrows(IOException.class, () -> WireProtocol.receiveFramedMessage(in));
    }
}
