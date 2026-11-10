# PulseNet: Distributed Real-Time System Telemetry & Health Monitor

**Course Module:** EC5205 — Operating Systems & Network Programming  
**Team Size:** 3 Members  
**Core Technologies:** Sockets (TCP/IP), Multithreading, Concurrency Primitives (Mutex Locks), OS Metric Sampling  

---

## 1. Project Overview

**PulseNet** is a production-grade, distributed client-server telemetry monitor designed to track the health and resource utilization of multiple networked computing nodes in real time. 

In enterprise infrastructures and distributed compute clusters, systems administrators must aggregate hardware health metrics (CPU utilization, memory saturation, disk I/O, process count, and node status) continuously across hundreds of host nodes. PulseNet implements this using low-level socket programming and multithreaded server architecture, avoiding high-level abstractions or third-party web frameworks to demonstrate core operating systems and networking fundamentals.

### Key Objectives
* Establish persistent, reliable TCP socket streams between distributed client agents and an aggregation server.
* Handle variable packet fragmentation with a custom binary-framed wire protocol.
* Manage multiple concurrent reporting agents using thread-per-client or thread-pool concurrency models.
* Guarantee race-condition-free data aggregation using thread synchronization primitives (mutual exclusion locks).
* Detect silent agent failures using an automated background watchdog thread with heartbeat timeouts.
* Present live node telemetry in an interactive, terminal-based dashboard.

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
|  | Master Listener Thread (bind, listen, accept on 0.0.0.0:9000)               |  |
|  +-------------------------------------+---------------------------------------+  |
|                                        | Spawns Worker Threads                    |
|         +------------------------------+-----------------------------+            |
|         v                                                            v            |
|  [ Worker Thread 1 ]                                          [ Worker Thread N ] |
|   - Socket Framing Parser                                      - Socket Framing   |
|   - JSON Deserializer                                          - JSON Deserializer|
|         |                                                            |            |
|         +--------------------+              +------------------------+            |
|                              |              |                                     |
|                              v              v                                     |
|                    +----------------------------------+                           |
|                    | Mutex Lock (`threading.Lock`)    |                           |
|                    +-----------------+----------------+                           |
|                                      |                                            |
|                                      v                                            |
|                    +----------------------------------+                           |
|                    | In-Memory Node State Registry    |                           |
|                    | { node_id: TelemetryRecord, ...} |                           |
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
* **Stream-Oriented TCP Handling:** TCP is a byte-stream protocol, not a message-oriented protocol. A single `recv()` call may return partial frames or multiple concatenated packets. PulseNet implements a strict **Length-Prefixed Framing Protocol**:
  $$\text{Frame} = [\text{4-Byte Big-Endian Integer (Payload Length)}] + [\text{UTF-8 JSON Payload}]$$
* **Socket Lifecycle Management:** Explicit configuration of `SO_REUSEADDR`, graceful teardown using socket shutdowns (`SHUT_RDWR`), and configurable read timeouts.

### Multithreading & Synchronization
* **Concurrent Ingestion:** The server maintains an accept loop on the main thread, dispatching incoming client connections to dedicated worker threads (`ClientHandlerThread`).
* **Shared State Concurrency:** All worker threads write updates to a centralized `NodeRegistry`. Thread collisions and race conditions are mitigated using a mutual exclusion lock (`threading.Lock`).
* **Watchdog Supervision:** A decoupled daemon thread audits heartbeat intervals ($T_{\text{last\_seen}}$) every second. If $T_{\text{current}} - T_{\text{last\_seen}} > \text{TIMEOUT}$, the node status transitions to `CRITICAL` or `OFFLINE`.

---

## 4. Repository File Structure

```
pulsenet/
├── README.md                 # Project specifications & academic documentation
├── implementation.md         # Phased execution checklist for agent workflows
├── agents.md                 # Operational prompt engineering and system directives
├── requirements.txt          # Python dependencies (psutil, rich, pytest)
├── run_demo.sh               # Shell script executing server + multiple local agents
│
├── pulsenet/                 # Core package
│   ├── __init__.py
│   ├── common/
│   │   ├── __init__.py
│   │   ├── protocol.py       # Wire protocol framing (pack/unpack length prefix)
│   │   └── models.py         # Telemetry dataclasses and validation models
│   │
│   ├── agent/
│   │   ├── __init__.py
│   │   ├── collector.py      # Host metric sampler (CPU, RAM, Disk, PIDs)
│   │   └── client.py         # Resilient TCP socket client with auto-reconnect
│   │
│   └── server/
│       ├── __init__.py
│       ├── listener.py       # Multithreaded TCP socket server
│       ├── registry.py       # Thread-safe in-memory metrics storage & mutex
│       ├── watchdog.py       # Heartbeat supervisor checking for node timeouts
│       └── dashboard.py      # Terminal UI renderer (ANSI / Rich live display)
│
├── tests/                    # Automated verification tests
│   ├── __init__.py
│   ├── test_protocol.py      # Protocol serialization and chunking unit tests
│   ├── test_registry.py      # Thread-safety and race condition unit tests
│   └── test_integration.py   # Full network socket loopback integration tests
│
└── scripts/
    └── mock_cluster.py       # Spawns N simulated agent nodes with varying loads
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
1. **Socket Programming:** Pure socket implementation (`socket.socket`, `AF_INET`, `SOCK_STREAM`) with framed packet boundary resolution.
2. **Multithreading:** Distinct threads for connection accepting, per-client socket reading, periodic watchdog auditing, and UI rendering.
3. **Synchronization:** Provably safe updates to shared metrics dictionary without deadlocks or dirty reads under high client concurrency.
4. **Fault Tolerance:** Server handles sudden client drops without crashing; clients automatically attempt reconnection.