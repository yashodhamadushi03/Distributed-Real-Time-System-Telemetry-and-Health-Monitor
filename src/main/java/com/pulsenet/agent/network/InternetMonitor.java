package com.pulsenet.agent.network;

import com.pulsenet.common.model.InternetConnectivityMetrics;
import com.pulsenet.common.net.NetworkUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.util.*;
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
 *   <li><b>Gateway Reachability:</b> Discovers the active default network gateway using OS route tables,
 *       interface binding, and reachability probes without assuming a hardcoded address.</li>
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
    private volatile InetAddress activeLocalAddress;

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

    private record RouteEntry(String destination, String netmask, String gateway, String iface, int metric) {}

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
     * Updates the active local network address used by the TCP connection to the PulseNet server.
     * This allows exact network interface and subnet gateway resolution.
     */
    public void setActiveLocalAddress(InetAddress address) {
        if (address != null && !address.isLoopbackAddress() && !address.isAnyLocalAddress()) {
            this.activeLocalAddress = address;
        }
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

    /**
     * Triggers an immediate asynchronous execution of the Internet connectivity check,
     * without waiting for the scheduled interval timer.
     */
    public void triggerImmediateCheck() {
        if (running.get() && scheduler != null && !scheduler.isShutdown()) {
            scheduler.execute(() -> {
                try {
                    executeCheck();
                } catch (Throwable t) {
                    LOGGER.log(Level.FINE, "[" + nodeId + "] Error during immediate Internet check: " + t.getMessage());
                }
            });
        }
    }

    /**
     * Immediately transitions the current metrics to OFFLINE and resets reachable status.
     * Called when the agent detects network interface loss or connection failure.
     */
    public void markOffline() {
        metricsLock.lock();
        try {
            currentMetrics.setStatus(InternetConnectivityMetrics.STATUS_OFFLINE);
            currentMetrics.setGatewayReachable(false);
            currentMetrics.setDnsSuccess(false);
            currentMetrics.setHttpsSuccess(false);
            currentMetrics.setHttpStatusCode(-1);
            currentMetrics.setLastCheckTimestamp(System.currentTimeMillis());
        } finally {
            metricsLock.unlock();
        }
    }

    @Override
    public void run() {
        if (!running.get()) {
            return;
        }

        try {
            executeCheck();
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[" + nodeId + "] Unexpected error during Internet check: " + t.getMessage(), t);
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

        LOGGER.fine(String.format("[%s] Internet check: Status=%s, Gateway=%s (%s), DNS=%b (%dms), HTTPS=%b (code=%d, %dms)",
                nodeId, status, gw.gatewayIp(), gw.reachable() ? "OK" : "FAIL",
                dns.success(), dns.durationMs(), https.success(), https.statusCode(), https.durationMs()));
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
     * Determines whether any non-loopback, non-point-to-point network interface is currently UP
     * and assigned a valid IPv4 address.
     *
     * @return true if an active IPv4 interface is present; false if all physical interfaces are down.
     */
    public static boolean hasAnyActiveNetworkInterface() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return false;
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp() || iface.isPointToPoint()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Default Gateway reachability check.
     */
    private GatewayResult defaultGatewayProbe(int timeoutMs) {
        long start = System.currentTimeMillis();
        if (!hasAnyActiveNetworkInterface()) {
            return new GatewayResult("none", false, 0L);
        }
        String gatewayIp = resolveDefaultGatewayIp();
        if (gatewayIp == null || gatewayIp.isBlank() || "unknown".equalsIgnoreCase(gatewayIp)) {
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
        if (!hasAnyActiveNetworkInterface()) {
            return new DnsResult(false, -1L);
        }
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
        if (!hasAnyActiveNetworkInterface()) {
            return new HttpsResult(false, -1, -1L);
        }
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
                    // "Connection refused" indicates the host is active on the network and actively refused the port
                    return true;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Resolves the real default gateway IP using OS routing tables, active socket interface mapping,
     * and reachability verification without hardcoding a .1 address.
     */
    public String resolveDefaultGatewayIp() {
        String targetInterfaceIp = (activeLocalAddress != null) ? activeLocalAddress.getHostAddress() : null;

        // If not explicitly set from TCP socket, find primary candidate LAN IP
        if (targetInterfaceIp == null) {
            List<NetworkUtils.LanAddress> lans = NetworkUtils.listLanIPv4Addresses();
            if (!lans.isEmpty()) {
                targetInterfaceIp = lans.get(0).address();
            }
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String gateway = null;
        String discoveryMethod = "none";

        if (os.contains("win")) {
            gateway = discoverWindowsGateway(targetInterfaceIp);
            if (gateway != null) {
                discoveryMethod = "Windows Route Table";
            } else {
                gateway = discoverWindowsNetshGateway(targetInterfaceIp);
                if (gateway != null) {
                    discoveryMethod = "Windows Netsh Config";
                }
            }
        } else if (os.contains("mac") || os.contains("darwin")) {
            gateway = discoverMacGateway();
            if (gateway != null) {
                discoveryMethod = "macOS Route";
            }
        } else {
            gateway = discoverLinuxGateway(targetInterfaceIp);
            if (gateway != null) {
                discoveryMethod = "Linux IP Route";
            }
        }

        // Subnet verification fallback only if OS routing commands failed to yield a gateway
        if (gateway == null && targetInterfaceIp != null) {
            int lastDot = targetInterfaceIp.lastIndexOf('.');
            if (lastDot > 0) {
                String candidate = targetInterfaceIp.substring(0, lastDot) + ".1";
                if (checkIpReachable(candidate, 800)) {
                    gateway = candidate;
                    discoveryMethod = "Subnet Probed Responsive Router (.1)";
                }
            }
        }

        String finalGateway = (gateway != null && !gateway.isBlank() && !"0.0.0.0".equals(gateway)) ? gateway : "unknown";
        LOGGER.info(String.format("[%s] Gateway Discovery: InterfaceIP=%s, Gateway=%s, Method=%s",
                nodeId, targetInterfaceIp != null ? targetInterfaceIp : "unknown", finalGateway, discoveryMethod));

        return finalGateway;
    }

    private String discoverWindowsGateway(String targetInterfaceIp) {
        Process process = null;
        try {
            process = new ProcessBuilder("route", "print", "0.0.0.0").start();
            List<RouteEntry> routes = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("0.0.0.0")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 5 && "0.0.0.0".equals(parts[0]) && "0.0.0.0".equals(parts[1])) {
                            String gw = parts[2];
                            String iface = parts[3];
                            int metric = 99999;
                            try {
                                metric = Integer.parseInt(parts[4]);
                            } catch (NumberFormatException ignored) {
                            }
                            if (isValidIpv4(gw) && !"0.0.0.0".equals(gw)) {
                                routes.add(new RouteEntry(parts[0], parts[1], gw, iface, metric));
                            }
                        }
                    }
                }
            }
            process.waitFor(1500, TimeUnit.MILLISECONDS);

            // 1. Match route whose interface matches targetInterfaceIp
            if (targetInterfaceIp != null) {
                for (RouteEntry r : routes) {
                    if (targetInterfaceIp.equals(r.iface())) {
                        return r.gateway();
                    }
                }
            }

            // 2. Select lowest metric default route
            routes.sort(Comparator.comparingInt(RouteEntry::metric));
            if (!routes.isEmpty()) {
                return routes.get(0).gateway();
            }
        } catch (Exception e) {
            LOGGER.fine(String.format("[%s] Failed to parse Windows route table: %s", nodeId, e.getMessage()));
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
        return null;
    }

    private String discoverWindowsNetshGateway(String targetInterfaceIp) {
        Process process = null;
        try {
            process = new ProcessBuilder("netsh", "interface", "ipv4", "show", "config").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                boolean inTargetInterface = (targetInterfaceIp == null);
                String lastDetectedGateway = null;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("Configuration for interface")) {
                        inTargetInterface = (targetInterfaceIp == null);
                    }
                    if (targetInterfaceIp != null && trimmed.contains(targetInterfaceIp)) {
                        inTargetInterface = true;
                    }
                    if (inTargetInterface && trimmed.startsWith("Default Gateway:")) {
                        String[] parts = trimmed.split(":", 2);
                        if (parts.length > 1) {
                            String gw = parts[1].trim();
                            if (isValidIpv4(gw) && !"0.0.0.0".equals(gw)) {
                                return gw;
                            }
                        }
                    } else if (trimmed.startsWith("Default Gateway:")) {
                        String[] parts = trimmed.split(":", 2);
                        if (parts.length > 1) {
                            String gw = parts[1].trim();
                            if (isValidIpv4(gw) && !"0.0.0.0".equals(gw)) {
                                lastDetectedGateway = gw;
                            }
                        }
                    }
                }
                if (lastDetectedGateway != null) {
                    return lastDetectedGateway;
                }
            }
            process.waitFor(1500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOGGER.fine(String.format("[%s] Failed to parse netsh config: %s", nodeId, e.getMessage()));
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
        return null;
    }

    private String discoverLinuxGateway(String targetInterfaceIp) {
        Process process = null;
        try {
            process = new ProcessBuilder("ip", "route", "show", "default").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+");
                    for (int i = 0; i < parts.length - 1; i++) {
                        if ("via".equalsIgnoreCase(parts[i]) && isValidIpv4(parts[i + 1])) {
                            return parts[i + 1];
                        }
                    }
                }
            }
            process.waitFor(1500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOGGER.fine(String.format("[%s] Failed to parse Linux ip route: %s", nodeId, e.getMessage()));
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
        return null;
    }

    private String discoverMacGateway() {
        Process process = null;
        try {
            process = new ProcessBuilder("route", "-n", "get", "default").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("gateway:")) {
                        String[] parts = trimmed.split(":", 2);
                        if (parts.length > 1 && isValidIpv4(parts[1].trim())) {
                            return parts[1].trim();
                        }
                    }
                }
            }
            process.waitFor(1500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOGGER.fine(String.format("[%s] Failed to parse macOS route: %s", nodeId, e.getMessage()));
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
        return null;
    }

    private static boolean isValidIpv4(String ip) {
        if (ip == null || ip.isBlank()) return false;
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) return false;
        try {
            for (String p : parts) {
                int n = Integer.parseInt(p);
                if (n < 0 || n > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
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
