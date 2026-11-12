package com.pulsenet.common.network;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Compact binary datagram packet format for PulseNet UDP latency, jitter, and packet loss probing.
 *
 * <p>Wire Layout:
 * <pre>
 * +---------------+---------------+------------------+-----------------------+-----------------------+---------------+-----------------+
 * | Magic (2 B)   | Type (1 B)    | Sequence (8 B)   | Client Tx Time (8 B)  | Server Rx Time (8 B)  | NodeLen (2 B) | Node ID (UTF-8) |
 * | 0x504E ("PN") | 1=REQ, 2=RESP | signed long      | millisecond timestamp | millisecond timestamp | short         | variable string |
 * +---------------+---------------+------------------+-----------------------+-----------------------+---------------+-----------------+
 * </pre>
 */
public class NetworkProbePacket {

    public static final short MAGIC = 0x504E; // 'P' 'N'
    public static final byte TYPE_PROBE_REQUEST = 1;
    public static final byte TYPE_PROBE_RESPONSE = 2;
    public static final int HEADER_MIN_SIZE = 2 + 1 + 8 + 8 + 8 + 2; // 29 bytes

    private final byte type;
    private final long sequenceNumber;
    private final long clientSendTimestamp;
    private final long serverEchoTimestamp;
    private final String nodeId;

    public NetworkProbePacket(byte type, long sequenceNumber, long clientSendTimestamp,
                              long serverEchoTimestamp, String nodeId) {
        this.type = type;
        this.sequenceNumber = sequenceNumber;
        this.clientSendTimestamp = clientSendTimestamp;
        this.serverEchoTimestamp = serverEchoTimestamp;
        this.nodeId = nodeId != null ? nodeId : "";
    }

    /**
     * Creates a probe request packet sent by an agent node.
     *
     * @param sequenceNumber sequential probe index
     * @param clientSendTimestamp client-side dispatch timestamp in milliseconds
     * @param nodeId originating agent node ID
     * @return populated NetworkProbePacket
     */
    public static NetworkProbePacket createRequest(long sequenceNumber, long clientSendTimestamp, String nodeId) {
        return new NetworkProbePacket(TYPE_PROBE_REQUEST, sequenceNumber, clientSendTimestamp, 0L, nodeId);
    }

    /**
     * Creates a probe response packet returned by the aggregator server.
     *
     * @param request original probe request
     * @param serverEchoTimestamp server receipt timestamp in milliseconds
     * @return populated NetworkProbePacket response
     */
    public static NetworkProbePacket createResponse(NetworkProbePacket request, long serverEchoTimestamp) {
        return new NetworkProbePacket(TYPE_PROBE_RESPONSE, request.getSequenceNumber(),
                request.getClientSendTimestamp(), serverEchoTimestamp, request.getNodeId());
    }

    /**
     * Serializes this packet into standard network byte order (Big-Endian) bytes.
     *
     * @return binary byte array
     */
    public byte[] toBytes() {
        byte[] idBytes = nodeId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(HEADER_MIN_SIZE + idBytes.length).order(ByteOrder.BIG_ENDIAN);
        buf.putShort(MAGIC);
        buf.put(type);
        buf.putLong(sequenceNumber);
        buf.putLong(clientSendTimestamp);
        buf.putLong(serverEchoTimestamp);
        buf.putShort((short) idBytes.length);
        buf.put(idBytes);
        return buf.array();
    }

    /**
     * Deserializes a binary datagram into a NetworkProbePacket.
     *
     * @param data   raw byte array
     * @param offset start offset
     * @param length number of valid bytes
     * @return deserialized NetworkProbePacket, or {@code null} if malformed
     */
    public static NetworkProbePacket fromBytes(byte[] data, int offset, int length) {
        if (data == null || length < HEADER_MIN_SIZE) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, offset, length).order(ByteOrder.BIG_ENDIAN);
        short magic = buf.getShort();
        if (magic != MAGIC) {
            return null;
        }
        byte type = buf.get();
        if (type != TYPE_PROBE_REQUEST && type != TYPE_PROBE_RESPONSE) {
            return null;
        }
        long seq = buf.getLong();
        long clientTx = buf.getLong();
        long serverEcho = buf.getLong();
        int idLen = buf.getShort() & 0xFFFF;
        if (buf.remaining() < idLen) {
            return null;
        }
        byte[] idBytes = new byte[idLen];
        buf.get(idBytes);
        String nodeId = new String(idBytes, StandardCharsets.UTF_8);

        return new NetworkProbePacket(type, seq, clientTx, serverEcho, nodeId);
    }

    public byte getType() {
        return type;
    }

    public boolean isRequest() {
        return type == TYPE_PROBE_REQUEST;
    }

    public boolean isResponse() {
        return type == TYPE_PROBE_RESPONSE;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public long getClientSendTimestamp() {
        return clientSendTimestamp;
    }

    public long getServerEchoTimestamp() {
        return serverEchoTimestamp;
    }

    public String getNodeId() {
        return nodeId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NetworkProbePacket that)) return false;
        return type == that.type &&
                sequenceNumber == that.sequenceNumber &&
                clientSendTimestamp == that.clientSendTimestamp &&
                serverEchoTimestamp == that.serverEchoTimestamp &&
                Objects.equals(nodeId, that.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, sequenceNumber, clientSendTimestamp, serverEchoTimestamp, nodeId);
    }

    @Override
    public String toString() {
        return "NetworkProbePacket{" +
                "type=" + (type == TYPE_PROBE_REQUEST ? "REQ" : "RESP") +
                ", seq=" + sequenceNumber +
                ", clientTx=" + clientSendTimestamp +
                ", serverEcho=" + serverEchoTimestamp +
                ", nodeId='" + nodeId + '\'' +
                '}';
    }
}

