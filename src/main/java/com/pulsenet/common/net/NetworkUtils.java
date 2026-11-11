package com.pulsenet.common.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Network discovery helpers used to tell operators which address agents should connect to.
 */
public final class NetworkUtils {

    /**
     * Substrings (lower-case) in an interface name or display name that mark a virtual,
     * container, VPN, or tunnel adapter that other laptops on the LAN cannot reach.
     */
    private static final String[] VIRTUAL_MARKERS = {
            "vethernet", "virtual", "virtualbox", "vbox", "vmware", "vmnet", "hyper-v",
            "docker", "veth", "br-", "wsl", "tailscale", "zerotier", "tap-", "tun",
            "utun", "loopback", "pseudo", "bluetooth"
    };

    private NetworkUtils() {
    }

    /**
     * A reachable LAN IPv4 address together with the interface it belongs to.
     *
     * @param address       dotted-quad IPv4 address, e.g. {@code 192.168.137.1}
     * @param interfaceName OS interface name, e.g. {@code wlan0} or {@code en0}
     * @param displayName   human-readable adapter description
     */
    public record LanAddress(String address, String interfaceName, String displayName) {

        /**
         * Returns a short label for banners, e.g. {@code Wi-Fi} or {@code Ethernet}, falling back
         * to the adapter's display name.
         *
         * @return a human-readable interface label
         */
        public String label() {
            String name = interfaceName == null ? "" : interfaceName.toLowerCase(Locale.ROOT);
            String display = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
            if (name.startsWith("wlan") || name.startsWith("wl") || display.contains("wi-fi")
                    || display.contains("wifi") || display.contains("wireless") || display.contains("802.11")) {
                return "Wi-Fi";
            }
            if (name.startsWith("eth") || display.contains("ethernet")) {
                return "Ethernet";
            }
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
            return interfaceName;
        }
    }

    /**
     * Enumerates up, non-loopback, non-virtual network interfaces and returns their usable
     * IPv4 addresses (link-local {@code 169.254.*} excluded). Private-range addresses
     * ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}) are listed first.
     *
     * @return the candidate LAN addresses; empty if none were found or enumeration failed
     */
    public static List<LanAddress> listLanIPv4Addresses() {
        List<LanAddress> result = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) {
                        continue;
                    }
                } catch (SocketException e) {
                    continue;
                }
                if (isVirtualInterface(nif.getName(), nif.getDisplayName())) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (isUsableLanAddress(addr)) {
                        result.add(new LanAddress(addr.getHostAddress(), nif.getName(), nif.getDisplayName()));
                    }
                }
            }
        } catch (SocketException e) {
            return Collections.emptyList();
        }
        // Stable sort: private ranges first, then preserve OS enumeration order.
        result.sort(Comparator.comparingInt(a -> isPrivateIPv4(a.address()) ? 0 : 1));
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns the address to advertise for a server bound to {@code bindHost}: the first LAN IPv4
     * when bound to a wildcard address, otherwise {@code bindHost} unchanged.
     *
     * @param bindHost the configured bind address
     * @return the address agents should target
     */
    public static String advertisedHost(String bindHost) {
        if (isWildcard(bindHost)) {
            List<LanAddress> lan = listLanIPv4Addresses();
            if (!lan.isEmpty()) {
                return lan.get(0).address();
            }
        }
        return bindHost;
    }

    /**
     * @param host a bind host string
     * @return true if {@code host} is a wildcard (all-interfaces) address
     */
    public static boolean isWildcard(String host) {
        return host == null || host.isBlank() || "0.0.0.0".equals(host)
                || "::".equals(host) || "[::]".equals(host) || "0:0:0:0:0:0:0:0".equals(host);
    }

    /**
     * @param addr an interface address
     * @return true if it is a non-loopback, non-link-local, non-multicast IPv4 address
     */
    public static boolean isUsableLanAddress(InetAddress addr) {
        return addr instanceof Inet4Address
                && !addr.isLoopbackAddress()
                && !addr.isLinkLocalAddress()      // 169.254.0.0/16
                && !addr.isAnyLocalAddress()
                && !addr.isMulticastAddress();
    }

    /**
     * @param name        OS interface name
     * @param displayName adapter description
     * @return true if the interface looks like a virtual/container/VPN adapter
     */
    public static boolean isVirtualInterface(String name, String displayName) {
        String haystack = ((name == null ? "" : name) + " " + (displayName == null ? "" : displayName))
                .toLowerCase(Locale.ROOT);
        for (String marker : VIRTUAL_MARKERS) {
            if (haystack.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param dotted an IPv4 address in dotted-quad form
     * @return true if the address is in an RFC 1918 private range
     */
    public static boolean isPrivateIPv4(String dotted) {
        String[] parts = dotted.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
