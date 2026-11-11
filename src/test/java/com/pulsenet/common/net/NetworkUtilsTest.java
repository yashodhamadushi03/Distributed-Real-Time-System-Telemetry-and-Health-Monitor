package com.pulsenet.common.net;

import com.pulsenet.common.net.NetworkUtils.LanAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkUtilsTest {

    @Test
    @DisplayName("Enumerated LAN addresses never include loopback, link-local, or non-IPv4")
    void enumeratedAddressesExcludeLoopbackAndLinkLocal() throws Exception {
        List<LanAddress> addresses = NetworkUtils.listLanIPv4Addresses();
        for (LanAddress lan : addresses) {
            InetAddress addr = InetAddress.getByName(lan.address());
            assertTrue(addr instanceof Inet4Address, "Not IPv4: " + lan);
            assertFalse(addr.isLoopbackAddress(), "Loopback leaked: " + lan);
            assertFalse(lan.address().startsWith("169.254."), "Link-local leaked: " + lan);
            assertFalse(NetworkUtils.isVirtualInterface(lan.interfaceName(), lan.displayName()),
                    "Virtual adapter leaked: " + lan);
        }
    }

    @Test
    @DisplayName("Address filter accepts LAN IPv4 and rejects loopback, link-local, wildcard, multicast, IPv6")
    void addressFilter() throws Exception {
        assertTrue(NetworkUtils.isUsableLanAddress(InetAddress.getByName("192.168.137.1")));
        assertTrue(NetworkUtils.isUsableLanAddress(InetAddress.getByName("10.0.0.5")));
        assertFalse(NetworkUtils.isUsableLanAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(NetworkUtils.isUsableLanAddress(InetAddress.getByName("169.254.10.20")));
        assertFalse(NetworkUtils.isUsableLanAddress(InetAddress.getByName("0.0.0.0")));
        assertFalse(NetworkUtils.isUsableLanAddress(InetAddress.getByName("fe80::1")));
        assertFalse(NetworkUtils.isUsableLanAddress(InetAddress.getByName("224.0.0.1")));
    }

    @Test
    @DisplayName("Virtual adapter detection matches Hyper-V, VirtualBox, VMware, Docker, WSL")
    void virtualInterfaceDetection() {
        assertTrue(NetworkUtils.isVirtualInterface("eth5", "Hyper-V Virtual Ethernet Adapter"));
        assertTrue(NetworkUtils.isVirtualInterface("vEthernet (WSL)", ""));
        assertTrue(NetworkUtils.isVirtualInterface("eth2", "VirtualBox Host-Only Ethernet Adapter"));
        assertTrue(NetworkUtils.isVirtualInterface("vmnet8", "VMware Network Adapter VMnet8"));
        assertTrue(NetworkUtils.isVirtualInterface("docker0", "docker0"));
        assertFalse(NetworkUtils.isVirtualInterface("wlan0", "Intel(R) Wi-Fi 6 AX201 160MHz"));
        assertFalse(NetworkUtils.isVirtualInterface("en0", "en0"));
        assertFalse(NetworkUtils.isVirtualInterface("eth0", "Realtek PCIe GbE Family Controller"));
    }

    @Test
    @DisplayName("Wildcard detection, private ranges, and interface labels")
    void helpers() {
        assertTrue(NetworkUtils.isWildcard("0.0.0.0"));
        assertTrue(NetworkUtils.isWildcard("::"));
        assertFalse(NetworkUtils.isWildcard("192.168.1.10"));
        assertEquals("192.168.1.10", NetworkUtils.advertisedHost("192.168.1.10"));

        assertTrue(NetworkUtils.isPrivateIPv4("192.168.137.1"));
        assertTrue(NetworkUtils.isPrivateIPv4("172.20.1.1"));
        assertFalse(NetworkUtils.isPrivateIPv4("172.32.1.1"));
        assertFalse(NetworkUtils.isPrivateIPv4("8.8.8.8"));

        assertEquals("Wi-Fi", new LanAddress("192.168.1.2", "wlan0", "Intel(R) Wi-Fi 6").label());
        assertEquals("Ethernet", new LanAddress("192.168.1.3", "eth0", "Realtek PCIe GbE").label());
    }

    @Test
    @DisplayName("Private-range addresses are listed before public ones")
    void privateAddressesFirst() {
        List<LanAddress> addresses = NetworkUtils.listLanIPv4Addresses();
        boolean seenPublic = false;
        for (LanAddress lan : addresses) {
            boolean isPrivate = NetworkUtils.isPrivateIPv4(lan.address());
            assertFalse(isPrivate && seenPublic, "Private address after public one: " + addresses);
            seenPublic |= !isPrivate;
        }
    }
}
