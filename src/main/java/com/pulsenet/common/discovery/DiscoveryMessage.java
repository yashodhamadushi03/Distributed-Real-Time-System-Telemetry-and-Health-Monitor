package com.pulsenet.common.discovery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Versioned application-layer message format for PulseNet UDP node discovery.
 *
 * <p>Supports both discovery requests broadcast by agent nodes and discovery responses
 * returned by the central aggregator server.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DiscoveryMessage implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static final String TYPE_REQUEST = "DISCOVERY_REQUEST";
    public static final String TYPE_RESPONSE = "DISCOVERY_RESPONSE";
    public static final int CURRENT_PROTOCOL_VERSION = 1;

    private String type;
    private int protocolVersion = CURRENT_PROTOCOL_VERSION;
    private String agentId;
    private String serverId;
    private String tcpHost;
    private int tcpPort;
    private int udpProbePort = 9002;
    private String preferredServerId;

    /**
     * Default no-arg constructor required for Jackson deserialization.
     */
    public DiscoveryMessage() {
    }

    /**
     * Factory method for creating a valid DISCOVERY_REQUEST message.
     *
     * @param agentId unique agent identifier
     * @return populated DiscoveryMessage
     */
    public static DiscoveryMessage createRequest(String agentId) {
        return createRequest(agentId, null);
    }

    /**
     * Factory method for creating a valid DISCOVERY_REQUEST message with optional preferred server.
     *
     * @param agentId           unique agent identifier
     * @param preferredServerId optional preferred server ID
     * @return populated DiscoveryMessage
     */
    public static DiscoveryMessage createRequest(String agentId, String preferredServerId) {
        DiscoveryMessage msg = new DiscoveryMessage();
        msg.setType(TYPE_REQUEST);
        msg.setProtocolVersion(CURRENT_PROTOCOL_VERSION);
        msg.setAgentId(agentId);
        msg.setPreferredServerId(preferredServerId);
        return msg;
    }

    /**
     * Factory method for creating a valid DISCOVERY_RESPONSE message with default UDP probe port (9002).
     *
     * @param serverId server identifier
     * @param tcpHost  advertised TCP hostname or IP
     * @param tcpPort  listening TCP port
     * @return populated DiscoveryMessage
     */
    public static DiscoveryMessage createResponse(String serverId, String tcpHost, int tcpPort) {
        return createResponse(serverId, tcpHost, tcpPort, 9002);
    }

    /**
     * Factory method for creating a valid DISCOVERY_RESPONSE message with explicit UDP probe port.
     *
     * @param serverId     server identifier
     * @param tcpHost      advertised TCP hostname or IP
     * @param tcpPort      listening TCP port
     * @param udpProbePort listening UDP probe port for latency/loss measurements
     * @return populated DiscoveryMessage
     */
    public static DiscoveryMessage createResponse(String serverId, String tcpHost, int tcpPort, int udpProbePort) {
        DiscoveryMessage msg = new DiscoveryMessage();
        msg.setType(TYPE_RESPONSE);
        msg.setProtocolVersion(CURRENT_PROTOCOL_VERSION);
        msg.setServerId(serverId);
        msg.setTcpHost(tcpHost);
        msg.setTcpPort(tcpPort);
        msg.setUdpProbePort(udpProbePort);
        return msg;
    }

    /**
     * Validates whether this message is a structurally sound discovery request.
     *
     * @return true if valid request
     */
    public boolean isValidRequest() {
        return TYPE_REQUEST.equals(type)
                && protocolVersion == CURRENT_PROTOCOL_VERSION
                && agentId != null
                && !agentId.isBlank();
    }

    /**
     * Validates whether this message is a structurally sound discovery response.
     *
     * @return true if valid response
     */
    public boolean isValidResponse() {
        return TYPE_RESPONSE.equals(type)
                && protocolVersion == CURRENT_PROTOCOL_VERSION
                && serverId != null
                && !serverId.isBlank()
                && tcpPort > 0 && tcpPort <= 65535;
    }

    /**
     * Serializes this message to UTF-8 JSON bytes.
     *
     * @return UTF-8 encoded byte array
     * @throws JsonProcessingException if serialization fails
     */
    public byte[] toBytes() throws JsonProcessingException {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Serializes this message to a JSON string.
     *
     * @return JSON string
     * @throws JsonProcessingException if serialization fails
     */
    public String toJson() throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(this);
    }

    /**
     * Deserializes a JSON string into a DiscoveryMessage instance.
     *
     * @param json JSON string
     * @return DiscoveryMessage instance
     * @throws IOException if deserialization fails
     */
    public static DiscoveryMessage fromJson(String json) throws IOException {
        return OBJECT_MAPPER.readValue(json, DiscoveryMessage.class);
    }

    /**
     * Deserializes a byte buffer segment into a DiscoveryMessage instance.
     *
     * @param bytes  buffer containing JSON
     * @param offset byte offset
     * @param length byte length
     * @return DiscoveryMessage instance
     * @throws IOException if deserialization fails
     */
    public static DiscoveryMessage fromBytes(byte[] bytes, int offset, int length) throws IOException {
        return OBJECT_MAPPER.readValue(bytes, offset, length, DiscoveryMessage.class);
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getTcpHost() {
        return tcpHost;
    }

    public void setTcpHost(String tcpHost) {
        this.tcpHost = tcpHost;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public void setTcpPort(int tcpPort) {
        this.tcpPort = tcpPort;
    }

    public int getUdpProbePort() {
        return udpProbePort;
    }

    public void setUdpProbePort(int udpProbePort) {
        this.udpProbePort = udpProbePort;
    }

    public String getPreferredServerId() {
        return preferredServerId;
    }

    public void setPreferredServerId(String preferredServerId) {
        this.preferredServerId = preferredServerId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DiscoveryMessage that)) return false;
        return protocolVersion == that.protocolVersion &&
                tcpPort == that.tcpPort &&
                udpProbePort == that.udpProbePort &&
                Objects.equals(type, that.type) &&
                Objects.equals(agentId, that.agentId) &&
                Objects.equals(serverId, that.serverId) &&
                Objects.equals(tcpHost, that.tcpHost) &&
                Objects.equals(preferredServerId, that.preferredServerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, protocolVersion, agentId, serverId, tcpHost, tcpPort, udpProbePort, preferredServerId);
    }

    @Override
    public String toString() {
        return "DiscoveryMessage{" +
                "type='" + type + '\'' +
                ", protocolVersion=" + protocolVersion +
                ", agentId='" + agentId + '\'' +
                ", serverId='" + serverId + '\'' +
                ", tcpHost='" + tcpHost + '\'' +
                ", tcpPort=" + tcpPort +
                ", udpProbePort=" + udpProbePort +
                ", preferredServerId='" + preferredServerId + '\'' +
                '}';
    }
}

