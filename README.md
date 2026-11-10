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
* **Socket Lifecycle Management:** Explicit configuration of `SO_REUSEADDR` (`socket.setReuseAddress(true)`), `TCP_NODELAY` (`socket.setTcpNoDelay(true)`), graceful teardown using socket shutdowns (`shutdownInput()`, `shutdownOutput()`, `close()`), and configurable read timeouts (`setSoTimeout()`).

### Multithreading & Synchronization
* **Concurrent Ingestion:** The server maintains an accept loop on the main thread, dispatching incoming client connections to dedicated worker threads (`ClientHandler`).
* **Shared State Concurrency:** All worker threads write updates to a centralized `NodeRegistry`. Thread collisions and race conditions are mitigated using a mutual exclusion lock (`java.util.concurrent.locks.ReentrantLock` or `synchronized`).
* **Watchdog Supervision:** A decoupled daemon thread (`thread.setDaemon(true)`) audits heartbeat intervals ($T_{\text{last\_seen}}$) every second. If $T_{\text{current}} - T_{\text{last\_seen}} > \text{TIMEOUT}$, the node status transitions to `WARNING` or `OFFLINE`.

---

## 4. Repository File Structure

```
pulsenet/
├── pom.xml                   # Maven project dependencies & build lifecycle
├── README.md                 # Project specifications & academic documentation
├── implementation.md         # Phased execution checklist for agent workflows
├── agents.md                 # Operational prompt engineering and system directives
├── run_demo.sh               # Shell script executing server + multiple local agents
├── run_demo.bat              # Batch script executing server + multiple local agents (Windows)
│
└── src/
    ├── main/
    │   └── java/
    │       └── com/
    │           └── pulsenet/
    │               ├── common/
    │               │   ├── model/
    │               │   │   ├── TelemetryPayload.java # Telemetry POJO / JSON representation
    │               │   │   └── NodeRecord.java       # Server-side registry node representation
    │               │   └── protocol/
    │               │       └── WireProtocol.java     # Length-prefixed binary framing parser
    │               │
    │               ├── agent/
    │               │   ├── collector/
    │               │   │   └── MetricCollector.java  # Host metric sampler (CPU, RAM, Disk, PIDs)
    │               │   ├── client/
    │               │   │   └── TelemetryAgent.java   # Resilient TCP socket client with auto-reconnect
    │               │   └── AgentMain.java            # CLI entry point for telemetry agent
    │               │
    │               ├── server/
    │               │   ├── registry/
    │               │   │   └── NodeRegistry.java     # Thread-safe in-memory metrics storage & mutex
    │               │   ├── network/
    │               │   │   ├── TelemetryServer.java  # Multithreaded TCP ServerSocket listener
    │               │   │   └── ClientHandler.java    # Per-client socket reader worker thread
    │               │   ├── watchdog/
    │               │   │   └── WatchdogSupervisor.java # Heartbeat supervisor checking for node timeouts
    │               │   ├── dashboard/
    │               │   │   └── DashboardRenderer.java  # Terminal UI renderer (ANSI live display)
    │               │   └── ServerMain.java           # CLI entry point for aggregator server
    │               │
    │               └── simulation/
    │                   └── MockCluster.java          # Spawns N simulated agent nodes with varying loads
    │
    └── test/
        └── java/
            └── com/
                └── pulsenet/
                    ├── common/
                    │   └── protocol/
                    │       └── WireProtocolTest.java # Protocol framing & chunking unit tests
                    ├── server/
                    │   └── registry/
                    │       └── NodeRegistryTest.java # Thread-safety and race condition unit tests
                    └── integration/
                        └── IntegrationTest.java      # Full network socket loopback integration tests
```

---

## 5. End Result & Deliverables

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
3. **Synchronization:** Provably safe updates to shared metrics map without deadlocks or dirty reads under high client concurrency using explicit locks (`ReentrantLock` / `synchronized`).
4. **Fault Tolerance:** Server handles sudden client drops without crashing; clients automatically attempt exponential backoff reconnection.
