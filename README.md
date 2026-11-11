# PulseNet: Distributed Real-Time System Telemetry & Health Monitor

---

## 1. Project Overview

**PulseNet** is a distributed client-server telemetry monitor designed to track the health and resource utilization of multiple networked computing nodes in real time. 

In enterprise infrastructures and distributed compute clusters, systems administrators must aggregate hardware health metrics (CPU utilization, memory saturation, disk I/O, process count, and node status) continuously across hundreds of host nodes. PulseNet implements this in **Java** using low-level socket programming and multithreaded server architecture, avoiding high-level abstractions or third-party web frameworks to demonstrate core operating systems and networking fundamentals.

### Key Objectives
* Establish persistent, reliable TCP socket streams between distributed client agents and an aggregation server using standard Java sockets (`java.net.Socket`, `java.net.ServerSocket`).
* Handle variable packet fragmentation with a custom binary-framed wire protocol (4-byte length prefix + UTF-8 JSON payload).
* Manage multiple concurrent reporting agents using thread-per-client concurrency.
* Guarantee race-condition-free data aggregation using thread synchronization primitives (`java.util.concurrent.locks.ReentrantLock` / `synchronized`).
* Detect silent agent failures using an automated background watchdog daemon thread with heartbeat timeouts.
* Present live node telemetry in an interactive, ANSI terminal-based dashboard.

---

## 2. System Architecture

```
+-----------------------------------------------------------------------------------+
|                                AGENT NODES                                        |
|                                                                                   |
|  [ Host 1 (Agent) ]       [ Host 2 (Agent) ]       [ Host N (Agent) ]             |
|   - Metric Collector       - Metric Collector       - Metric Collector            |
|   - Telemetry Packager     - Telemetry Packager     - Telemetry Packager          |
|   - TCP Client Socket      - TCP Client Socket      - TCP Client Socket           |
+----------+------------------------+------------------------+----------------------+
           |                        |                        |
           | (Raw TCP Stream)       | (Raw TCP Stream)       | (Raw TCP Stream)
           | Length-Prefixed Wire   | Length-Prefixed Wire   | Length-Prefixed Wire
           v                        v                        v
+-----------------------------------------------------------------------------------+
|                            CENTRAL AGGREGATOR SERVER                              |
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  | Master Listener Thread (ServerSocket bind, listen, accept on 0.0.0.0:9000)  |  |
|  +-------------------------------------+---------------------------------------+  |
|                                        | Spawns Worker Threads                    |
|         +------------------------------+-----------------------------+            |
|         v                                                            v            |
|  [ ClientHandler Thread 1 ]                               [ ClientHandler Thread N ]
|   - Socket Framing Parser                                  - Socket Framing Parser|
|   - JSON Deserializer                                      - JSON Deserializer    |
|         |                                                            |            |
|         +--------------------+              +------------------------+            |
|                              |              |                                     |
|                              v              v                                     |
|                    +----------------------------------+                           |
|                    | Mutex Lock (`ReentrantLock`)     |                           |
|                    +-----------------+----------------+                           |
|                                      |                                            |
|                                      v                                            |
|                    +----------------------------------+                           |
|                    | In-Memory Node State Registry    |                           |
|                    | Map<String, NodeRecord>          |                           |
|                    +-----------------+----------------+                           |
|                                      |                                            |
|                +---------------------+---------------------+                      |
|                v                                           v                      |
|  +---------------------------+               +-------------------------------+    |
|  | Watchdog Reaper Thread    |               | Real-Time Dashboard Thread    |    |
|  | - Sweeps timestamps       |               | - Reads shared registry       |    |
|  | - Marks dead nodes OFFLINE|               | - Refreshes CLI terminal UI   |    |
|  +---------------------------+               +-------------------------------+    |
+-----------------------------------------------------------------------------------+
```

---

## 3. Core Operating System & Networking Concepts

### Sockets & Wire Protocol
* **Stream-Oriented TCP Handling:** TCP is a byte-stream protocol, not a message-oriented protocol. A single `InputStream.read()` call may return partial frames or multiple concatenated packets. PulseNet implements a strict **Length-Prefixed Framing Protocol**:
  $$\text{Frame} = [\text{4-Byte Big-Endian Integer (Payload Length)}] + [\text{UTF-8 JSON Payload}]$$
* **Socket Lifecycle Management:** Explicit configuration of `SO_REUSEADDR` (`socket.setReuseAddress(true)`), `TCP_NODELAY` (`socket.setTcpNoDelay(true)`), graceful teardown using socket shutdowns (`shutdownInput()`, `shutdownOutput()`, `close()`), and configurable timeouts.

### Multithreading & Synchronization
* **Concurrent Ingestion:** The server maintains an accept loop on the main thread, dispatching incoming client connections to dedicated worker threads (`ClientHandler`).
* **Shared State Concurrency:** All worker threads write updates to a centralized `NodeRegistry`. Thread collisions and race conditions are mitigated using a mutual exclusion lock (`java.util.concurrent.locks.ReentrantLock`).
* **Watchdog Supervision:** A decoupled daemon thread (`thread.setDaemon(true)`) audits heartbeat intervals ($T_{\text{last\_seen}}$) every second. If $T_{\text{current}} - T_{\text{last\_seen}} > \text{TIMEOUT}$, the node status transitions to `WARNING` or `OFFLINE`.

---

## 4. Repository File Structure

```
pulsenet/
├── pom.xml                   # Maven dependencies, packaging & build lifecycle
├── README.md                 # Project architecture & user guide
├── implementation.md         # Phased execution checklist & test gates
├── agents.md                 # Antigravity agent system directives
├── run_demo.ps1              # One-click PowerShell demo launcher (Windows)
├── run_demo.bat              # One-click Batch demo launcher (Windows)
├── run_demo.sh               # One-click Shell demo launcher (Linux/macOS)
│
└── src/
    ├── main/
    │   └── java/
    │       └── com/
    │           └── pulsenet/
    │               ├── common/
    │               │   ├── model/
    │               │   │   └── TelemetryPayload.java   # Telemetry POJO / JSON DTO
    │               │   └── protocol/
    │               │       └── WireProtocol.java       # Length-prefixed framing engine
    │               │
    │               ├── agent/
    │               │   ├── MetricCollector.java        # System metric sampler (CPU, RAM, Disk, PIDs)
    │               │   ├── TelemetryAgent.java         # Resilient TCP socket client with auto-reconnect
    │               │   └── AgentMain.java              # CLI entry point for telemetry agent
    │               │
    │               ├── server/
    │               │   ├── registry/
    │               │   │   ├── NodeRecord.java         # Server-side registry node representation
    │               │   │   └── NodeRegistry.java       # Thread-safe in-memory store (ReentrantLock)
    │               │   ├── network/
    │               │   │   ├── TelemetryServer.java    # Multithreaded TCP ServerSocket listener
    │               │   │   └── ClientHandler.java      # Per-client socket reader worker thread
    │               │   ├── watchdog/
    │               │   │   └── WatchdogSupervisor.java # Heartbeat watchdog daemon auditing timeouts
    │               │   ├── dashboard/
    │               │   │   └── DashboardRenderer.java  # ANSI live terminal UI renderer
    │               │   └── ServerMain.java             # CLI entry point for aggregator server
    │               │
    │               └── simulation/
    │                   └── MockCluster.java            # Spawns N simulated agents with chaos injection
    │
    └── test/
        └── java/
            └── com/
                └── pulsenet/
                    ├── common/
                    │   └── protocol/
                    │       └── WireProtocolTest.java       # Protocol framing & chunking unit tests (7 tests)
                    ├── agent/
                    │   └── TelemetryAgentTest.java         # Socket streaming & auto-reconnect tests (3 tests)
                    ├── server/
                    │   ├── registry/
                    │   │   └── NodeRegistryTest.java       # Concurrency (20 threads) & lock tests (3 tests)
                    │   └── dashboard/
                    │       └── DashboardRendererTest.java  # Progress bar & UI generation tests (2 tests)
                    └── integration/
                        └── IntegrationTest.java            # Full socket loopback & watchdog tests (2 tests)
```

---

## 5. Quick Start & Execution Guide

### Prerequisites
* **Java Development Kit (JDK):** Version 25 LTS or higher.
* **Apache Maven:** Version 3.9+ (`mvn`).

---

### A. Run Automated Unit & Integration Tests (17 Tests)
To run the complete automated regression test suite:
```bash
mvn test
```
*Tests cover length-prefixed protocol fragmentation, client reconnect loops, 20-thread concurrency without race conditions, watchdog timeouts, and full loopback integration.*

---

### B. One-Click Demo Execution

#### Windows PowerShell:
```powershell
.\run_demo.ps1
```

#### Windows Command Prompt:
```cmd
run_demo.bat
```

#### Linux / macOS:
```bash
chmod +x run_demo.sh
./run_demo.sh
```

*The demo script compiles the project, launches the Central Aggregator Server in Window 1, and spawns the 5-node Mock Cluster in Window 2. After 10 seconds, `agent-03` is intentionally disconnected to demonstrate real-time watchdog failure detection.*

---

### C. Manual Component Execution

#### 1. Start Aggregator Server:
```bash
java -cp "target/pulsenet-1.0.0-SNAPSHOT.jar;target/lib/*" com.pulsenet.server.ServerMain --port 9000
```
*Options:*
* `--host <ip>` (default: `0.0.0.0`)
* `--port <port>` (default: `9000`)
* `--watchdog-timeout <seconds>` (default: `5.0`)
* `--headless` (disables ANSI screen clearing)

#### 2. Start Mock Cluster Simulator:
```bash
java -cp "target/pulsenet-1.0.0-SNAPSHOT.jar;target/lib/*" com.pulsenet.simulation.MockCluster --port 9000 --nodes 5
```
*Options:*
* `--nodes <N>` (default: `5`)
* `--interval <ms>` (default: `1000`)
* `--no-chaos` (disables intentional drop of `agent-03`)

#### 3. Start a Real Local Host Telemetry Agent:
```bash
java -cp "target/pulsenet-1.0.0-SNAPSHOT.jar;target/lib/*" com.pulsenet.agent.AgentMain --host 127.0.0.1 --port 9000 --node-id host-primary
```

---

## 6. End Result & Deliverables

### Live Terminal Dashboard Output
When the aggregator server runs, it displays a live-refreshing terminal interface:

```
========================================================================================
 PulseNet: Distributed Telemetry Aggregator | Server: 0.0.0.0:9000 | Active Nodes: 3
========================================================================================
 NODE ID       IP:PORT           CPU LOAD    MEM USAGE   DISK USAGE   ACTIVE PIDS  STATUS
----------------------------------------------------------------------------------------
 node-alpha    192.168.1.10:5012 [|||||   ] 42% [||||||  ] 58% [||||    ] 38%   184     HEALTHY
 node-beta     192.168.1.11:4822 [||||||||] 89% [||||||||] 91% [||||||  ] 64%   241     WARNING
 node-gamma    192.168.1.15:5204 [          ] 0% [          ] 0% [          ] 0%    0       OFFLINE (5s)
========================================================================================
 [EVENT LOG]
 10:14:02 [SYS]  Client connected: node-beta (192.168.1.11:4822)
 10:14:05 [WARN] node-beta crossed CPU threshold: 89.2%
 10:14:08 [LOST] Heartbeat lost for node-gamma. Marked OFFLINE.
========================================================================================
```

### Verification Criteria for Course Rubric
1. **Socket Programming:** Pure standard Java socket implementation (`java.net.Socket`, `java.net.ServerSocket`, raw `InputStream` / `OutputStream`) with framed packet boundary resolution.
2. **Multithreading:** Distinct threads for connection accepting (`ServerSocket.accept()`), per-client socket reading (`ClientHandler`), periodic watchdog auditing (`WatchdogSupervisor`), and UI rendering (`DashboardRenderer`).
3. **Synchronization:** Provably safe updates to shared metrics map without deadlocks or dirty reads under high client concurrency using explicit locks (`ReentrantLock`).
4. **Fault Tolerance:** Server handles sudden client drops without crashing; clients automatically attempt exponential backoff reconnection.

---

## 7. Multi-Laptop Demo

To run PulseNet across three laptops (1 server + 2 agents), build the portable bundle with `scripts/package-demo` and follow **[DEMO.md](DEMO.md)**. It covers network setup, the firewall, a pre-flight checklist, the scene script, and troubleshooting.

Quick start (Windows `.bat` shown; `.ps1` and `.sh` variants take the same arguments):
```bash
scripts\start-server.bat              # Laptop A: note the IP in the startup banner
scripts\probe.bat <A_IP>              # Laptops B and C: must print OK
scripts\start-agent.bat <A_IP>        # Laptops B and C: node appears on A's dashboard
```
