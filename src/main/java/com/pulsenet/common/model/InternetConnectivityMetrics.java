package com.pulsenet.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.util.Objects;

/**
 * Data transfer object encapsulating agent-side Internet connectivity diagnostics:
 * Gateway IP & reachability, DNS resolution speed & status, external HTTPS connectivity,
 * response times, and overall Internet status classification (ONLINE, LIMITED, OFFLINE, UNKNOWN).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InternetConnectivityMetrics implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STATUS_ONLINE = "ONLINE";
    public static final String STATUS_LIMITED = "LIMITED";
    public static final String STATUS_OFFLINE = "OFFLINE";
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    private String status = STATUS_UNKNOWN;

    // Gateway diagnostics
    private String gatewayIp;
    private boolean gatewayReachable = false;
    private long gatewayCheckDurationMs = -1L;

    // DNS diagnostics
    private String dnsTestHost = "dns.google";
    private boolean dnsSuccess = false;
    private long dnsResolutionTimeMs = -1L;

    // External HTTPS diagnostics
    private String httpsTestEndpoint = "https://www.google.com/generate_204";
    private boolean httpsSuccess = false;
    private int httpStatusCode = -1;
    private long httpsResponseTimeMs = -1L;

    // Timestamps
    private long lastCheckTimestamp = 0L;
    private long lastSuccessfulCheckTimestamp = 0L;

    public InternetConnectivityMetrics() {
    }

    public InternetConnectivityMetrics(InternetConnectivityMetrics other) {
        if (other == null) return;
        this.status = other.status;
        this.gatewayIp = other.gatewayIp;
        this.gatewayReachable = other.gatewayReachable;
        this.gatewayCheckDurationMs = other.gatewayCheckDurationMs;
        this.dnsTestHost = other.dnsTestHost;
        this.dnsSuccess = other.dnsSuccess;
        this.dnsResolutionTimeMs = other.dnsResolutionTimeMs;
        this.httpsTestEndpoint = other.httpsTestEndpoint;
        this.httpsSuccess = other.httpsSuccess;
        this.httpStatusCode = other.httpStatusCode;
        this.httpsResponseTimeMs = other.httpsResponseTimeMs;
        this.lastCheckTimestamp = other.lastCheckTimestamp;
        this.lastSuccessfulCheckTimestamp = other.lastSuccessfulCheckTimestamp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getGatewayIp() {
        return gatewayIp;
    }

    public void setGatewayIp(String gatewayIp) {
        this.gatewayIp = gatewayIp;
    }

    public boolean isGatewayReachable() {
        return gatewayReachable;
    }

    public void setGatewayReachable(boolean gatewayReachable) {
        this.gatewayReachable = gatewayReachable;
    }

    public long getGatewayCheckDurationMs() {
        return gatewayCheckDurationMs;
    }

    public void setGatewayCheckDurationMs(long gatewayCheckDurationMs) {
        this.gatewayCheckDurationMs = gatewayCheckDurationMs;
    }

    public String getDnsTestHost() {
        return dnsTestHost;
    }

    public void setDnsTestHost(String dnsTestHost) {
        this.dnsTestHost = dnsTestHost;
    }

    public boolean isDnsSuccess() {
        return dnsSuccess;
    }

    public void setDnsSuccess(boolean dnsSuccess) {
        this.dnsSuccess = dnsSuccess;
    }

    public long getDnsResolutionTimeMs() {
        return dnsResolutionTimeMs;
    }

    public void setDnsResolutionTimeMs(long dnsResolutionTimeMs) {
        this.dnsResolutionTimeMs = dnsResolutionTimeMs;
    }

    public String getHttpsTestEndpoint() {
        return httpsTestEndpoint;
    }

    public void setHttpsTestEndpoint(String httpsTestEndpoint) {
        this.httpsTestEndpoint = httpsTestEndpoint;
    }

    public boolean isHttpsSuccess() {
        return httpsSuccess;
    }

    public void setHttpsSuccess(boolean httpsSuccess) {
        this.httpsSuccess = httpsSuccess;
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public void setHttpStatusCode(int httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
    }

    public long getHttpsResponseTimeMs() {
        return httpsResponseTimeMs;
    }

    public void setHttpsResponseTimeMs(long httpsResponseTimeMs) {
        this.httpsResponseTimeMs = httpsResponseTimeMs;
    }

    public long getLastCheckTimestamp() {
        return lastCheckTimestamp;
    }

    public void setLastCheckTimestamp(long lastCheckTimestamp) {
        this.lastCheckTimestamp = lastCheckTimestamp;
    }

    public long getLastSuccessfulCheckTimestamp() {
        return lastSuccessfulCheckTimestamp;
    }

    public void setLastSuccessfulCheckTimestamp(long lastSuccessfulCheckTimestamp) {
        this.lastSuccessfulCheckTimestamp = lastSuccessfulCheckTimestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InternetConnectivityMetrics that)) return false;
        return gatewayReachable == that.gatewayReachable &&
                gatewayCheckDurationMs == that.gatewayCheckDurationMs &&
                dnsSuccess == that.dnsSuccess &&
                dnsResolutionTimeMs == that.dnsResolutionTimeMs &&
                httpsSuccess == that.httpsSuccess &&
                httpStatusCode == that.httpStatusCode &&
                httpsResponseTimeMs == that.httpsResponseTimeMs &&
                lastCheckTimestamp == that.lastCheckTimestamp &&
                lastSuccessfulCheckTimestamp == that.lastSuccessfulCheckTimestamp &&
                Objects.equals(status, that.status) &&
                Objects.equals(gatewayIp, that.gatewayIp) &&
                Objects.equals(dnsTestHost, that.dnsTestHost) &&
                Objects.equals(httpsTestEndpoint, that.httpsTestEndpoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, gatewayIp, gatewayReachable, gatewayCheckDurationMs,
                dnsTestHost, dnsSuccess, dnsResolutionTimeMs, httpsTestEndpoint, httpsSuccess,
                httpStatusCode, httpsResponseTimeMs, lastCheckTimestamp, lastSuccessfulCheckTimestamp);
    }

    @Override
    public String toString() {
        return "InternetConnectivityMetrics{" +
                "status='" + status + '\'' +
                ", gatewayIp='" + gatewayIp + '\'' +
                ", gatewayReachable=" + gatewayReachable +
                ", dnsSuccess=" + dnsSuccess +
                ", dnsTimeMs=" + dnsResolutionTimeMs +
                ", httpsSuccess=" + httpsSuccess +
                ", httpCode=" + httpStatusCode +
                ", httpsTimeMs=" + httpsResponseTimeMs +
                '}';
    }
}

