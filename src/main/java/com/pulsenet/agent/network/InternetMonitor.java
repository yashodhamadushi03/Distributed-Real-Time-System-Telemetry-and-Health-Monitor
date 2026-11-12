package com.pulsenet.agent.network;

import com.pulsenet.common.model.InternetConnectivityMetrics;
import com.pulsenet.common.net.NetworkUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Autonomous background agent monitor testing Internet connectivity independently of
 * the PulseNet TCP Telemetry Server session.
 *
 * <p>Diagnostic stages:
 * <ol>
 *   <li><b>Gateway Reachability:</b> Resolves active LAN default gateway and verifies L2/L3 reachability.</li>
 *   <li><b>DNS Resolution:</b> Verifies DNS lookup speed against a test hostname (e.g. {@code dns.google}).</li>
 *   <li><b>External HTTPS Verification:</b> Executes a lightweight HTTPS HEAD/GET probe against a known endpoint
 *       (e.g. {@code https://www.google.com/generate_204}) to confirm actual global Internet transit.</li>
 * </ol>
 *
 * <p>Status Classification:
 * <ul>
 *   <li><b>ONLINE:</b> External HTTPS probe succeeded.</li>
 *   <li><b>LIMITED:</b> Local gateway or DNS succeeded, but external HTTPS failed (e.g. captive portal or ISP drop).</li>
 *   <li><b>OFFLINE:</b> Gateway, DNS, and external HTTPS all failed.</li>
 *   <li><b>UNKNOWN:</b> Check has not yet completed.</li>
 * </ul>
 */
public class InternetMonitor implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(InternetMonitor.class.getName());

    public static final String DEFAULT_DNS_TEST_HOST = "dns.google";
    public static final String DEFAULT_HTTPS_ENDPOINT = "https://www.google.com/generate_204";
    public static final int DEFAULT_CHECK_INTERVAL_SECONDS = 5;
    public static final int DEFAULT_CHECK_TIMEOUT_MILLIS = 2500;

    private final String nodeId;
    private final int checkIntervalSeconds;
    private final int checkTimeoutMillis;
    private volatile String dnsTestHost;
    private volatile String httpsTestEndpoint;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ReentrantLock metricsLock = new ReentrantLock();
    private final InternetConnectivityMetrics currentMetrics = new InternetConnectivityMetrics();

    private ScheduledExecutorService scheduler;

    // Testable probe delegates
    private GatewayProbe gatewayProbe = this::defaultGatewayProbe;
    private DnsProbe dnsProbe = this::defaultDnsProbe;
    private HttpsProbe httpsProbe = this::defaultHttpsProbe;

    public interface GatewayProbe {
        GatewayResult check(int timeoutMillis);
    }

    public interface DnsProbe {
        DnsResult check(String host, int timeoutMillis);
    }

    public interface HttpsProbe {
        HttpsResult check(String endpoint, int timeoutMillis);
    }

    public record GatewayResult(String gatewayIp, boolean reachable, long durationMs) {}
    public record DnsResult(boolean success, long durationMs) {}
    public record HttpsResult(boolean success, int statusCode, long durationMs) {}

    public InternetMonitor(String nodeId) {
        this(nodeId, DEFAULT_CHECK_INTERVAL_SECONDS, DEFAULT_CHECK_TIMEOUT_MILLIS,
                DEFAULT_DNS_TEST_HOST, DEFAULT_HTTPS_ENDPOINT);
    }

    public InternetMonitor(String nodeId, int checkIntervalSeconds, int checkTimeoutMillis,
                           String dnsTestHost, String httpsTestEndpoint) {
        this.nodeId = nodeId != null ? nodeId : "agent";
        this.checkIntervalSeconds = Math.max(2, checkIntervalSeconds);
        this.checkTimeoutMillis = Math.max(500, checkTimeoutMillis);
        this.dnsTestHost = (dnsTestHost != null && !dnsTestHost.isBlank()) ? dnsTestHost : DEFAULT_DNS_TEST_HOST;
        this.httpsTestEndpoint = (httpsTestEndpoint != null && !httpsTestEndpoint.isBlank()) ? httpsTestEndpoint : DEFAULT_HTTPS_ENDPOINT;

        currentMetrics.setDnsTestHost(this.dnsTestHost);
        currentMetrics.setHttpsTestEndpoint(this.httpsTestEndpoint);
    }

    /**
     * Starts the background Internet connectivity monitoring daemon.
     */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "InternetMonitor-" + nodeId);
                t.setDaemon(true);
                return t;
            });

            // Initial check with short delay, then periodic
            scheduler.scheduleWithFixedDelay(this, 100, (long) checkIntervalSeconds * 1000L, TimeUnit.MILLISECONDS);
            LOGGER.info(String.format("[%s] InternetMonitor started (interval=%ds, timeout=%dms)",
                    nodeId, checkIntervalSeconds, checkTimeoutMillis));
        }
    }

    @Override
    public void run() {
        if (!running.get()) {
            return;
        }

        try {
            executeCheck();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "[" + nodeId + "] Error during Internet check: " + e.getMessage());
        }
    }

    /**
     * Executes a full diagnostic pass (Gateway, DNS, HTTPS) and updates currentMetrics.
     */
    public void executeCheck() {
        long now = System.currentTimeMillis();

        GatewayResult gw = gatewayProbe.check(checkTimeoutMillis);
        DnsResult dns = dnsProbe.check(dnsTestHost, checkTimeoutMillis);
        HttpsResult https = httpsProbe.check(httpsTestEndpoint, checkTimeoutMillis);

        String status = classify(gw.reachable(), dns.success(), https.success());

        metricsLock.lock();
        try {
            currentMetrics.setStatus(status);
            currentMetrics.setGatewayIp(gw.gatewayIp());
            currentMetrics.setGatewayReachable(gw.reachable());
            currentMetrics.setGatewayCheckDurationMs(gw.durationMs());

            currentMetrics.setDnsTestHost(dnsTestHost);
            currentMetrics.setDnsSuccess(dns.success());
            currentMetrics.setDnsResolutionTimeMs(dns.durationMs());

            currentMetrics.setHttpsTestEndpoint(httpsTestEndpoint);
            currentMetrics.setHttpsSuccess(https.success());
            currentMetrics.setHttpStatusCode(https.statusCode());
            currentMetrics.setHttpsResponseTimeMs(https.durationMs());

            currentMetrics.setLastCheckTimestamp(now);
            if (InternetConnectivityMetrics.STATUS_ONLINE.equals(status)) {
                currentMetrics.setLastSuccessfulCheckTimestamp(now);
            }
        } finally {
            metricsLock.unlock();
        }
    }

    /**
     * Classifies the overall status based on diagnostic results.
     */
    public static String classify(boolean gatewayReachable, boolean dnsSuccess, boolean httpsSuccess) {
        if (httpsSuccess) {
            // Only verified external HTTPS connectivity yields ONLINE status
            return InternetConnectivityMetrics.STATUS_ONLINE;
        }
        if (gatewayReachable || dnsSuccess) {
            // Local network or DNS is reachable, but external HTTPS is failing
            return InternetConnectivityMetrics.STATUS_LIMITED;
        }
        return InternetConnectivityMetrics.STATUS_OFFLINE;
    }

    /**
     * Default Gateway reachability check.
     */
    private GatewayResult defaultGatewayProbe(int timeoutMs) {
        long start = System.currentTimeMillis();
        String gatewayIp = resolveDefaultGatewayIp();
        if (gatewayIp == null || gatewayIp.isBlank()) {
            return new GatewayResult("unknown", false, -1L);
        }

        boolean reachable = checkIpReachable(gatewayIp, timeoutMs);
        long elapsed = System.currentTimeMillis() - start;
        return new GatewayResult(gatewayIp, reachable, elapsed);
    }

    /**
     * Default DNS resolution probe.
     */
    private DnsResult defaultDnsProbe(String host, int timeoutMs) {
        long start = System.currentTimeMillis();
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            long elapsed = System.currentTimeMillis() - start;
            boolean ok = addrs != null && addrs.length > 0;
            return new DnsResult(ok, elapsed);
        } catch (Exception e) {
            return new DnsResult(false, -1L);
        }
    }

    /**
     * Default lightweight external HTTPS check.
     */
    private HttpsResult defaultHttpsProbe(String endpoint, int timeoutMs) {
        long start = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            URL url = new URI(endpoint).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "PulseNet-Probe/1.0");

            int code = conn.getResponseCode();
            long elapsed = System.currentTimeMillis() - start;
            boolean ok = (code >= 200 && code < 400);
            return new HttpsResult(ok, code, elapsed);
        } catch (Exception e) {
            return new HttpsResult(false, -1, -1L);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Determines whether an IP address is reachable via ICMP or TCP probe.
     */
    private boolean checkIpReachable(String ip, int timeoutMs) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            if (addr.isReachable(timeoutMs)) {
                return true;
            }
            // Fallback for non-privileged OS environments: test standard router ports (53, 80, 443)
            int[] ports = {53, 80, 443};
            for (int p : ports) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(addr, p), Math.min(timeoutMs, 500));
                    return true;
                } catch (SocketTimeoutException ignored) {
                } catch (ConnectException e) {
                    // "Connection refused" indicates the IP is active on the network and actively refused the port
                    return true;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Resolves the primary active default gateway IP using OS route tables or subnet conventions.
     */
    public String resolveDefaultGatewayIp() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String gateway = null;

        if (os.contains("win")) {
            gateway = parseWindowsGateway();
        } else {
            gateway = parseUnixGateway();
        }

        if (gateway == null || gateway.isBlank() || "0.0.0.0".equals(gateway)) {
            // Fallback: Infer standard .1 gateway from primary LAN IPv4
            List<NetworkUtils.LanAddress> lans = NetworkUtils.listLanIPv4Addresses();
            if (!lans.isEmpty()) {
                String lanIp = lans.get(0).address();
                int lastDot = lanIp.lastIndexOf('.');
                if (lastDot > 0) {
                    gateway = lanIp.substring(0, lastDot) + ".1";
                }
            }
        }

        return gateway != null ? gateway : "unknown";
    }

    private String parseWindowsGateway() {
        try {
            Process process = new ProcessBuilder("route", "print", "0.0.0.0").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("0.0.0.0")) {
                        String[] parts = line.split("\\s+");
                        // Format: Network Destination | Netmask | Gateway | Interface | Metric
                        if (parts.length >= 3 && !"0.0.0.0".equals(parts[2]) && parts[2].matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                            return parts[2];
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String parseUnixGateway() {
        try {
            Process process = new ProcessBuilder("ip", "route", "show", "default").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null) {
                    // Format: default via 192.168.1.1 dev ...
                    String[] parts = line.trim().split("\\s+");
                    for (int i = 0; i < parts.length - 1; i++) {
                        if ("via".equalsIgnoreCase(parts[i]) && parts[i + 1].matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                            return parts[i + 1];
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Retrieves a defensive copy of the latest Internet connectivity metrics.
     */
    public InternetConnectivityMetrics getLatestMetrics() {
        metricsLock.lock();
        try {
            return new InternetConnectivityMetrics(currentMetrics);
        } finally {
            metricsLock.unlock();
        }
    }

    /**
     * Stops the background monitor.
     */
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            LOGGER.info("[" + nodeId + "] InternetMonitor stopped.");
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public String getDnsTestHost() {
        return dnsTestHost;
    }

    public void setDnsTestHost(String dnsTestHost) {
        if (dnsTestHost != null && !dnsTestHost.isBlank()) {
            this.dnsTestHost = dnsTestHost.trim();
            metricsLock.lock();
            try {
                currentMetrics.setDnsTestHost(this.dnsTestHost);
            } finally {
                metricsLock.unlock();
            }
        }
    }

    public String getHttpsTestEndpoint() {
        return httpsTestEndpoint;
    }

    public void setHttpsTestEndpoint(String httpsTestEndpoint) {
        if (httpsTestEndpoint != null && !httpsTestEndpoint.isBlank()) {
            this.httpsTestEndpoint = httpsTestEndpoint.trim();
            metricsLock.lock();
            try {
                currentMetrics.setHttpsTestEndpoint(this.httpsTestEndpoint);
            } finally {
                metricsLock.unlock();
            }
        }
    }

    // Setters for test mocking / customization
    public void setGatewayProbe(GatewayProbe gatewayProbe) {
        this.gatewayProbe = gatewayProbe != null ? gatewayProbe : this::defaultGatewayProbe;
    }

    public void setDnsProbe(DnsProbe dnsProbe) {
        this.dnsProbe = dnsProbe != null ? dnsProbe : this::defaultDnsProbe;
    }

    public void setHttpsProbe(HttpsProbe httpsProbe) {
        this.httpsProbe = httpsProbe != null ? httpsProbe : this::defaultHttpsProbe;
    }
}
