package com.pulsenet.common.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.pulsenet.common.model.TelemetryPayload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic length-prefixed binary wire protocol for framing TCP stream payloads.
 * Format: [4-Byte Big-Endian Payload Length (int)] + [UTF-8 JSON Payload String]
 */
public class WireProtocol {

    /**
     * Defensive sanity boundary to prevent OOM attacks or corrupted headers (10 MB).
     */
    public static final int MAX_FRAME_LENGTH = 10 * 1024 * 1024;

    /**
     * Serializes a TelemetryPayload and packs it into a length-prefixed byte array.
     *
     * @param payload Telemetry data object.
     * @return 4-byte length prefix + UTF-8 JSON bytes.
     * @throws JsonProcessingException if serialization fails.
     */
    public static byte[] packMessage(TelemetryPayload payload) throws JsonProcessingException {
        return packMessage(payload.toJson());
    }

    /**
     * Packs a UTF-8 string into a length-prefixed byte array.
     *
     * @param jsonString Payload string.
     * @return 4-byte length prefix + UTF-8 payload bytes.
     */
    public static byte[] packMessage(String jsonString) {
        byte[] payloadBytes = jsonString.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[4 + payloadBytes.length];

        ByteBuffer.wrap(frame)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(payloadBytes.length)
                .put(payloadBytes);

        return frame;
    }

    /**
     * Packs and sends a framed TelemetryPayload over an OutputStream.
     *
     * @param out Output stream of the target socket.
     * @param payload Telemetry payload to send.
     * @throws IOException on socket write failure.
     */
    public static void sendFramedMessage(OutputStream out, TelemetryPayload payload) throws IOException {
        byte[] frame = packMessage(payload);
        out.write(frame);
        out.flush();
    }

    /**
     * Reads exactly numBytes from an InputStream, handling partial TCP chunking.
     *
     * @param in InputStream to read from.
     * @param numBytes Exact number of bytes required.
     * @return Byte array of size numBytes, or null if the stream closed before reading all bytes.
     * @throws IOException on socket read failure.
     */
    public static byte[] readExact(InputStream in, int numBytes) throws IOException {
        byte[] buffer = new byte[numBytes];
        int totalRead = 0;
        while (totalRead < numBytes) {
            int read = in.read(buffer, totalRead, numBytes - totalRead);
            if (read == -1) {
                // Stream closed prematurely (EOF)
                return null;
            }
            totalRead += read;
        }
        return buffer;
    }

    /**
     * Reads a 4-byte length-prefixed frame and returns the UTF-8 payload string.
     * Returns null cleanly on connection close / EOF.
     *
     * @param in InputStream to read from.
     * @return Decoded UTF-8 payload string, or null on clean connection close.
     * @throws IOException if frame length is invalid or stream is corrupted.
     */
    public static String receiveFramedMessage(InputStream in) throws IOException {
        byte[] rawLength = readExact(in, 4);
        if (rawLength == null) {
            return null;
        }
        int length = ByteBuffer.wrap(rawLength).order(ByteOrder.BIG_ENDIAN).getInt();
        if (length < 0 || length > MAX_FRAME_LENGTH) {
            throw new IOException("Frame length out of allowable bounds: " + length);
        }
        byte[] rawPayload = readExact(in, length);
        if (rawPayload == null) {
            return null;
        }
        return new String(rawPayload, StandardCharsets.UTF_8);
    }
}
