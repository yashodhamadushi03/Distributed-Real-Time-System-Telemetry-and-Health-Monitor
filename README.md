# PulseNet: Distributed Real-Time System Telemetry & Health Monitor

---

## 1. Project Overview

**PulseNet** is a distributed real-time telemetry and health monitoring system designed to monitor computing nodes across local networks in real time.

PulseNet aggregates core operating system metrics—including CPU utilization, memory saturation, disk I/O, active processes, and node status—across distributed hosts. Built in **Java 25**, the system utilizes low-level socket programming (`java.net.Socket`, `java.net.ServerSocket`, `java.net.DatagramSocket`) and multithreaded concurrency rather than third-party web frameworks, demonstrating fundamental operating systems and distributed networking principles.

### Key Capabilities
* **Advanced Real-Time Network Monitoring:** Measures accurate Round-Trip Time (RTT latency: Current, Avg, Min, Max), RFC 3550 network jitter variation, and sequence-numbered UDP packet loss over a sliding measurement window.
* **UDP-Based Automatic Node Discovery:** Agents broadcast discovery datagrams on the local network (`UDP :9001`) to automatically locate the PulseNet central server without manual IP configuration.
* **Resilient Automatic TCP Reconnection:** Agents detect network severed events, socket closure, and host restarts, recovering seamlessly using exponential backoff with randomized jitter while preserving node identity.
* **Connection Uptime & Reconnection Tracking:** Accurately tracks connection establishment timestamps, active uptime durations, total successful reconnection counts, and disconnection causes.
* **Network Topology & Time-Series History:** Server tracks node origin IPs, source ports, connected server coordinates, and retains bounded historical samples per node for future frontend visualization.
* **Deterministic Wire Protocol Framing:** Custom length-prefixed binary framing (4-byte big-endian header + UTF-8 JSON payload) that prevents TCP stream fragmentation and chunking issues.
* **Thread-Safe Shared State Engine:** Thread-per-client concurrency synchronized by mutual exclusion locks (`java.util.concurrent.locks.ReentrantLock`).
* **Automated Heartbeat Watchdog:** Background supervisor daemon auditing node silence thresholds to mark dead or lagging nodes `OFFLINE`.
* **Live ANSI Terminal Dashboard:** High-contrast terminal dashboard presenting real-time hardware gauges, dedicated Network Performance cards per node, status badges, and an edge-triggered system event log.

---

## 2. System Architecture

```
+---------------------------------------------------------------------------------------------------------+
|                                           AGENT NODES                                                   |
|                                                                                                         |
|  [ Host 1 (Agent) ]                       [ Host 2 (Agent) ]                 [ Host N (Agent) ]         |
|   - DiscoveryClient (UDP :9001 Broadcast)  - DiscoveryClient                  - DiscoveryClient         |
|   - MetricCollector (OS / Hardware)        - MetricCollector                  - MetricCollector         |
|   - ConnectionManager (State & Backoff)    - ConnectionManager                - ConnectionManager       |
|   - TelemetryAgent (TCP Streamer)          - TelemetryAgent                   - TelemetryAgent          |
+---------------------+------------------------------+----------------------------------+-----------------+
                      |                              |                                  |
   (UDP Discovery)    | UDP Broadcast                | UDP Broadcast                    | UDP Broadcast
   Port 9001          v                              v                                  v
+---------------------------------------------------------------------------------------------------------+
|                                    CENTRAL AGGREGATOR SERVER (HOST)                                     |
|                                                                                                         |
|  +---------------------------------------------------------------------------------------------------+  |
|  | DiscoveryServer Daemon (DatagramSocket bind 0.0.0.0:9001)                                         |  |
|  | - Listens for DISCOVERY_REQUEST broadcasts from agents on LAN                                     |  |
|  | - Responds with DISCOVERY_RESPONSE containing server identity and reachable TCP address/port      |  |
|  +---------------------------------------------------------------------------------------------------+  |
|                                                                                                         |
|  +---------------------------------------------------------------------------------------------------+  |
|  | TelemetryServer Master Listener (ServerSocket bind 0.0.0.0:9000, TCP_NODELAY, SO_TIMEOUT)         |  |
|  +-----------------------------------------+---------------------------------------------------------+  |
|                                            | Dispatches Per-Client Worker Threads                       |
|             +------------------------------+-----------------------------+                              |
|             v                                                            v                              |
|      [ ClientHandler 1 ]                                          [ ClientHandler N ]                   |
|       - Length-Prefixed Frame Parser                               - Length-Prefixed Frame Parser       |
|       - Half-Open Socket Detection                                 - Half-Open Socket Detection         |
|             |                                                            |                              |
|             +------------------------+          +------------------------+                              |
|                                      v          v                                                       |
|                                +----------------------------------+                                     |
|                                | Mutex Mutex (`ReentrantLock`)    |                                     |
|                                +----------------+-----------------+                                     |
|                                                 |                                                       |
|                                                 v                                                       |
|                                +----------------------------------+                                     |
|                                | In-Memory Node Registry Store    |                                     |
|                                | Map<String, NodeRecord>          |                                     |
|                                +----------------+-----------------+                                     |
|                                                 |                                                       |
|                         +-----------------------+-----------------------+                               |
|                         v                                               v                               |
|          +-------------------------------+               +-------------------------------+              |
|          | Watchdog Supervisor Daemon    |               | Real-Time Dashboard Renderer  |              |
|          | - Sweeps timestamps (1 Hz)    |               | - 2 Hz ANSI terminal update   |              |
|          | - Flags silent nodes OFFLINE  |               | - Edge-triggered event log    |              |
|          +-------------------------------+               +-------------------------------+              |
+---------------------------------------------------------------------------------------------------------+
```

---

## 3. Core Networking & Concurrency Features

### A. UDP Automatic Node Discovery

Agents do not require hardcoded or manually entered IP addresses. On startup with `--discover`, an agent locates the Aggregator Host automatically via UDP broadcast on port `9001`.

#### Discovery Message Protocol (Version 1)

1. **Discovery Request (Agent $\to$ Broadcast):**
```json
{
  "type": "DISCOVERY_REQUEST",
  "protocolVersion": 1,
  "agentId": "yashodha-laptop",
  "preferredServerId": null
}
```
The agent transmits this request to the global broadcast address (`255.255.255.255`), local loopback (`127.0.0.1`), and each active network interface broadcast address.

2. **Discovery Response (Server $\to$ Agent):**
```json
{
  "type": "DISCOVERY_RESPONSE",
  "protocolVersion": 1,
  "serverId": "pulsenet-central-server",
  "tcpHost": "192.168.137.1",
  "tcpPort": 9000
}
```
The server validates the version and message type, resolves its reachable LAN IPv4 address (filtering out virtual adapters like Hyper-V, WSL, Docker, and VirtualBox), and replies directly to the client's socket address.

3. **Deterministic Server Selection:**
If multiple PulseNet servers exist on the network:
* If a preferred server ID (`--server-id <id>`) is specified, the matching server is chosen.
* Otherwise, the server is selected deterministically by lexicographical order of `serverId`.
* The client prefers the physical source IP of the UDP response if the advertised host is a wildcard or unreachable.

---

### B. Automatic TCP Reconnection & Resilient State Engine

TCP connections are prone to temporary drops during Wi-Fi roaming, host reboots, or cable disconnections. PulseNet implements a formal connection state machine managed by `ConnectionManager`.

#### Connection Lifecycle State Machine
```
   [ DISCONNECTED ]
          |
          v
   [ CONNECTING ]  <----------------------------+
          |                                     |
          | (Connected successfully)            |
          v                                     |
   [ CONNECTED ]                                |
          |                                     |
          | (EOF, SocketException, Timeout)     |
          v                                     |
   [ CONNECTION_LOST ]                          |
          |                                     |
          v                                     | (Retry attempt n)
   [ RECONNECTING ] ----------------------------+
          |
          | (Graceful Stop / Ctrl+C)
          v
   [ STOPPING ] ---> [ STOPPED ]
```

#### Exponential Backoff with Jitter
Reconnection attempts follow truncated exponential backoff to avoid reconnection storms:
$$D(n) = \min(D_{\max}, D_0 \cdot 2^{n-1})$$
$$D_{\text{jittered}} = \text{round}(D(n) \cdot (0.8 + 0.2 \cdot r)), \quad r \in [0.0, 1.0)$$

* Initial delay ($D_0$): `1000 ms`
* Maximum delay ($D_{\max}$): `10000 ms`
* Sequence without jitter: 1s $\to$ 2s $\to$ 4s $\to$ 8s $\to$ 10s $\to$ 10s...
* Scheduled via Java `ScheduledExecutorService` without blocking worker threads.
* When re-establishing connection, if direct retries fail repeatedly, the agent re-executes UDP discovery to detect changed server IPs.

#### Node Identity & Recovery Preservation
* When reconnecting, the agent reuses its original `nodeId`.
* The server's `NodeRegistry` detects the existing node record and logs `[REC] Node recovered: <nodeId>`, updating the existing row on the dashboard rather than creating duplicate entries.
* Immediate EOF detection: a dedicated daemon monitor reads the socket stream, catching remote termination immediately rather than waiting for write timeouts.

---

### C. Binary Length-Prefixed Wire Protocol
TCP is stream-oriented. PulseNet uses a length-prefixed framing format:
```
+------------------------------------+---------------------------------------+
| Field                              | Size / Type                           |
+------------------------------------+---------------------------------------+
| Payload Length Header              | 4 Bytes (32-bit signed big-endian int)|
| JSON Payload String                | Variable (UTF-8 encoded JSON)         |
+------------------------------------+---------------------------------------+
```
`WireProtocol.receiveFramedMessage()` reads exactly 4 bytes to determine payload length, verifies boundaries ($0 < \text{len} \le 10\text{ MB}$), and loops to read exact frame bytes, handling partial TCP chunks.

---

## 4. Repository File Structure

```
pulsenet/
├── pom.xml                                      # Maven dependencies, shade plugin & build lifecycle
├── README.md                                    # Comprehensive system documentation
├── DEMO.md                                      # Two-laptop demonstration runbook
├── agents.md                                    # Architectural constraints & directives
├── implementation.md                            # Phased implementation plan & history
├── scripts/
│   ├── start-server.bat / .ps1 / .sh            # Launches aggregator server with live dashboard
│   ├── start-agent.bat / .ps1 / .sh             # Launches agent (auto-discovery or manual host)
│   ├── probe.bat / .ps1 / .sh                   # Pre-flight TCP connectivity probe
│   ├── package-demo.ps1 / .sh                   # Assembles portable distribution bundle
│   └── _common.ps1 / _common.sh / _check-java.bat # Shared environment and Java 25 checkers
│
└── src/
    ├── main/java/com/pulsenet/
    │   ├── Launcher.java                        # Single entry point for shaded pulsenet.jar
    │   ├── common/
    │   │   ├── discovery/
    │   │   │   ├── DiscoveryMessage.java        # UDP discovery JSON protocol DTO
    │   │   │   └── DiscoveredServer.java        # Discovered server network coordinates
    │   │   ├── model/
    │   │   │   └── TelemetryPayload.java        # Telemetry POJO / JSON DTO
    │   │   ├── net/
    │   │   │   └── NetworkUtils.java            # Network interface enumeration & LAN IP resolution
    │   │   └── protocol/
    │   │       └── WireProtocol.java            # 4-byte big-endian framing engine
    │   ├── agent/
    │   │   ├── AgentMain.java                   # Agent CLI entry point (--discover or --host)
    │   │   ├── MetricCollector.java             # System metric sampler (CPU, RAM, Disk, PIDs)
    │   │   ├── TelemetryAgent.java              # Resilient streaming agent client
    │   │   ├── connection/
    │   │   │   ├── ConnectionState.java         # Connection lifecycle enum
    │   │   │   ├── ConnectionStateListener.java # State transition observer interface
    │   │   │   ├── BackoffCalculator.java       # Exponential backoff + jitter calculator
    │   │   │   └── ConnectionManager.java       # Reconnection lifecycle & resource manager
    │   │   └── discovery/
    │   │       └── DiscoveryClient.java         # UDP broadcast sender & server selector
    │   ├── server/
    │   │   ├── ServerMain.java                  # Aggregator server CLI entry point
    │   │   ├── discovery/
    │   │   │   └── DiscoveryServer.java         # UDP discovery listener daemon
    │   │   ├── network/
    │   │   │   ├── TelemetryServer.java         # TCP ServerSocket listener
    │   │   │   └── ClientHandler.java           # Per-client TCP worker thread
    │   │   ├── registry/
    │   │   │   ├── NodeRecord.java              # Node state representation
    │   │   │   └── NodeRegistry.java            # Thread-safe in-memory store (ReentrantLock)
    │   │   ├── watchdog/
    │   │   │   └── WatchdogSupervisor.java      # Heartbeat watchdog daemon auditing timeouts
    │   │   └── dashboard/
    │   │       └── DashboardRenderer.java       # Real-time ANSI terminal UI
    │   ├── simulation/
    │   │   └── MockCluster.java                 # Multi-node synthetic cluster simulator
    │   └── tools/
    │       ├── Probe.java                       # Pre-flight TCP connectivity probe
    │       └── Stress.java                      # CPU stress load generator for demos
    │
    └── test/java/com/pulsenet/
        ├── agent/
        │   ├── AgentMainTest.java
        │   ├── TelemetryAgentTest.java
        │   ├── connection/
        │   │   ├── BackoffCalculatorTest.java
        │   │   ├── ConnectionManagerTest.java
        │   │   └── ConnectionStateTest.java
        │   └── discovery/
        │       └── DiscoveryClientTest.java
        ├── common/
        │   ├── discovery/
        │   │   └── DiscoveryMessageTest.java
        │   ├── net/
        │   │   └── NetworkUtilsTest.java
        │   └── protocol/
        │       └── WireProtocolTest.java
        ├── integration/
        │   ├── IntegrationTest.java
        │   └── DiscoveryAndReconnectionIntegrationTest.java
        ├── server/
        │   ├── dashboard/
        │   │   └── DashboardRendererTest.java
        │   └── registry/
        │       └── NodeRegistryTest.java
        ├── simulation/
        │   └── MockClusterTest.java
        └── tools/
            ├── ProbeTest.java
            └── StressTest.java
```

---

## 5. Execution Guide

### Prerequisites
* **Java Development Kit (JDK):** Version 25 LTS or higher.
* **Apache Maven:** Version 3.9+ (`mvn`).

---

### A. Run Automated Regression Test Suite (71 Tests)
Execute all unit and integration tests:
```bash
mvn clean test
```
*Tests cover UDP discovery serialization, server response validation, deterministic selection, exponential backoff jitter bounds, connection state transitions, duplicate reconnection prevention, full loopback discovery and streaming, and automatic recovery.*

---

### B. Build Portable Distribution Package
```powershell
.\scripts\package-demo.ps1     # Windows PowerShell
```
or
```bash
./scripts/package-demo.sh      # Linux / macOS
```
This builds `target/pulsenet.jar` and assembles `dist/pulsenet-demo/` with all launch scripts and documentation.

---

### C. Running the Central Aggregator Server (Host)

#### Using the Portable JAR:
```bash
java -jar target/pulsenet.jar server --port 9000 --udp-port 9001
```

#### Using Classpath:
```bash
java -cp "target/pulsenet-1.0.0-SNAPSHOT.jar;target/lib/*" com.pulsenet.server.ServerMain --port 9000 --udp-port 9001
```

#### Using Convenience Script:
```powershell
.\scripts\start-server.ps1
```

*Server CLI Options:*
* `--host <ip>`: Bind address (default: `0.0.0.0`)
* `--port <port>`: TCP listening port (default: `9000`)
* `--udp-port <port>`: UDP discovery port (default: `9001`)
* `--server-id <id>`: Server identifier (default: `pulsenet-central-server`)
* `--disable-discovery`: Disables the UDP discovery responder
* `--watchdog-timeout <sec>`: Silence duration before marking node OFFLINE (default: `5.0`)
* `--read-timeout <sec>`: Half-open socket read timeout (default: `15.0`)
* `--headless`: Disables ANSI terminal clearing (useful for CI/log capture)

---

### D. Running an Agent Node

#### 1. Automatic Discovery Mode (No Host IP Required!):
```bash
java -jar target/pulsenet.jar agent --discover --node-id yashodha-laptop
```
or using classpath:
```bash
java -cp "target/pulsenet-1.0.0-SNAPSHOT.jar;target/lib/*" com.pulsenet.agent.AgentMain --discover --node-id yashodha-laptop
```
or using convenience script (running with no arguments defaults to discovery):
```powershell
.\scripts\start-agent.ps1
```

#### 2. Manual Connection Mode (Backward Compatible):
```bash
java -jar target/pulsenet.jar agent --host 192.168.137.1 --port 9000 --node-id yashodha-laptop
```
or using convenience script:
```powershell
.\scripts\start-agent.ps1 192.168.137.1 yashodha-laptop
```

*Agent CLI Options:*
* `--discover`: Enables automatic UDP server discovery on LAN
* `--udp-port <port>`: UDP port to broadcast discovery probes (default: `9001`)
* `--server-id <id>`: Preferred server ID if multiple servers exist
* `--host <ip>`: Target Host IP for manual connection mode (default: `127.0.0.1`)
* `--port <port>`: Target Host TCP port for manual connection mode (default: `9000`)
* `--node-id <id>`: Stable node identifier (default: normalized hostname)
* `--interval <ms>`: Telemetry sampling interval in milliseconds (default: `1000`)

---

## 6. Two-Laptop Demonstration & Testing Runbook

This test proves both **UDP Automatic Node Discovery** and **Automatic TCP Reconnection** across physical machines on the same local network.

```
   [ Laptop A: HOST / SERVER ]                   [ Laptop B: AGENT NODE ]
   - TCP Server on Port 9000       <-----------   - Telemetry Streaming (TCP 9000)
   - UDP Discovery on Port 9001    <== = = = =   - Automatic Discovery (UDP 9001)
   - Live Terminal Dashboard                      - Resilient Reconnection
```

### Network & Firewall Setup
1. Connect both laptops to the same Wi-Fi network, mobile hotspot, or Ethernet switch.
2. **Laptop A (Host):**
   * Ensure network category is **Private**.
   * Allow incoming connections on **TCP port 9000** (telemetry), **UDP port 9001** (discovery), and **UDP port 9002** (network probes):
     ```powershell
     New-NetFirewallRule -DisplayName "PulseNet TCP" -Direction Inbound -Protocol TCP -LocalPort 9000 -Action Allow
     New-NetFirewallRule -DisplayName "PulseNet UDP Discovery" -Direction Inbound -Protocol UDP -LocalPort 9001 -Action Allow
     New-NetFirewallRule -DisplayName "PulseNet UDP Probe" -Direction Inbound -Protocol UDP -LocalPort 9002 -Action Allow
     ```
   * Or allow Java when prompted by Windows Defender Firewall.

---

### Step-by-Step Verification Procedure

#### Step 1: Start Central Server on Laptop A
```powershell
java -jar pulsenet.jar server --port 9000 --udp-port 9001
```
*Laptop A displays the startup banner advertising reachable LAN IP addresses, then launches the ANSI dashboard waiting for nodes.*

#### Step 2: Start Agent on Laptop B in Automatic Discovery Mode
```powershell
java -jar pulsenet.jar agent --discover
```
*Observe console output on Laptop B:*
```
[DISCOVERY] Starting automatic server discovery...
[DISCOVERY] Sending UDP request on port 9001 (attempt 1/3)...
[DISCOVERY] Waiting for server responses...
[DISCOVERY] PulseNet server discovered!
[DISCOVERY] Server ID: pulsenet-central-server
[DISCOVERY] Host IP: 192.168.137.1
[DISCOVERY] TCP Port: 9000
[DISCOVERY] Discovery completed successfully.
[CONNECTION] Establishing TCP connection to 192.168.137.1:9000...
[CONNECTION] Connected successfully to 192.168.137.1:9000 [Node: laptop-b]
```
*Within 1 second, Laptop B appears as `HEALTHY` on Laptop A's dashboard.*

#### Step 3: Test TCP Connection Loss (Server Drop)
On **Laptop A**, press `Ctrl+C` to stop the server.

*Observe Laptop B console:*
```
[CONNECTION] Connection lost (Remote peer closed connection (EOF)). Preparing to reconnect...
[RECONNECT] Attempt 1: Reconnecting in 950 ms (target: 192.168.137.1:9000)...
[CONNECTION] Establishing TCP connection to 192.168.137.1:9000...
[RECONNECT] Attempt 2: Reconnecting in 1840 ms (target: 192.168.137.1:9000)...
```
*The agent automatically transitions to `CONNECTION_LOST` $\to$ `RECONNECTING`, applying exponential backoff without crashing.*

#### Step 4: Test Automatic Recovery (Server Restart)
On **Laptop A**, restart the server:
```powershell
java -jar pulsenet.jar server --port 9000 --udp-port 9001
```

*Observe Laptop B console:*
```
[CONNECTION] Establishing TCP connection to 192.168.137.1:9000...
[CONNECTION] Connected successfully to 192.168.137.1:9000 [Node: laptop-b]
[RECONNECT] Connection successfully restored to 192.168.137.1:9000! [Node ID: laptop-b]
```
*Observe Laptop A Dashboard:*
```
[EVENT LOG]
16:15:20 [SYS] Node connected: laptop-b (192.168.137.25:52134)
...
16:15:35 [REC] Node recovered: laptop-b (192.168.137.25:52140)
```
*Laptop B is recognized as the same logical node, its status returns to `HEALTHY`, and telemetry streaming resumes without duplicating dashboard rows.*

---

## 7. Troubleshooting & Limitations

| Symptom | Cause | Solution |
|---|---|---|
| Discovery times out: `No PulseNet server found` | Public network profile or firewall dropping UDP 9001 | Check firewall rules on Laptop A. Allow UDP port 9001. Ensure network is set to "Private". |
| `probe` or TCP connect reports `TIMEOUT` | Enterprise Wi-Fi **client isolation** | Many university/corporate Wi-Fi networks prevent device-to-device communication. Switch to a mobile hotspot. |
| Discovery finds wrong IP address | Server machine has multiple virtual adapters (WSL, Hyper-V, VPN) | PulseNet automatically filters virtual adapters. If an active VPN interferes, pass `--host` explicitly or disconnect the VPN. |
| Agent repeatedly reconnects | Network latency exceeds socket read timeout | Increase server read timeout with `--read-timeout 30`. |

### Limitations of UDP Broadcast
* **Subnet Boundary:** UDP broadcast datagrams (`255.255.255.255`) do not cross Layer 3 routers. The agent and server must reside on the same broadcast domain (subnet). For cross-subnet deployments, use manual `--host <ip>` mode.
* **Client Isolation:** Guest Wi-Fi networks block peer-to-peer broadcasts. Use a mobile hotspot or direct router connection for physical demos.
