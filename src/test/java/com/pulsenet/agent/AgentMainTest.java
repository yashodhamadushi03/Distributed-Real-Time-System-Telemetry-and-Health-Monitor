package com.pulsenet.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMainTest {

    @Test
    @DisplayName("Default node ID is stable across calls (no random suffix)")
    void defaultNodeIdIsStable() {
        String first = AgentMain.generateDefaultNodeId();
        String second = AgentMain.generateDefaultNodeId();
        assertEquals(first, second, "A restarted agent must reuse the same node ID");
        assertFalse(first.isBlank());
        assertEquals(first.toLowerCase(Locale.ROOT), first, "Default node ID must be lower-case");
        assertFalse(first.contains("."), "Domain suffix must be stripped");
    }

    @Test
    @DisplayName("Hostname normalization lower-cases and strips the domain suffix")
    void normalizeHostName() {
        assertEquals("desktop-7qk2m1l", AgentMain.normalizeHostName("DESKTOP-7QK2M1L"));
        assertEquals("didulas-mbp", AgentMain.normalizeHostName("Didulas-MBP.local"));
        assertEquals("node-b", AgentMain.normalizeHostName("  node-b.lab.example.com "));
    }

    @Test
    @DisplayName("IP literals, localhost, and blanks are not usable hostnames")
    void usableHostName() {
        assertTrue(AgentMain.isUsableHostName("DESKTOP-7QK2M1L"));
        assertFalse(AgentMain.isUsableHostName("192.168.1.20"));
        assertFalse(AgentMain.isUsableHostName("fe80::1"));
        assertFalse(AgentMain.isUsableHostName("localhost"));
        assertFalse(AgentMain.isUsableHostName(" "));
        assertFalse(AgentMain.isUsableHostName(null));
    }
}
