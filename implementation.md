# PulseNet: Phased Implementation Plan for Antigravity Agents (Java Edition)

This document outlines the strict execution roadmap for building PulseNet in Java.

### Implementation Status: ALL PHASES COMPLETED (17/17 Tests Passing)
* **Phase 0:** [x] Environment Setup & Project Skeleton — **VERIFIED** (`mvn clean compile`)
* **Phase 1:** [x] Wire Protocol & Framing Specification — **VERIFIED** (`7/7 unit tests passing`)
* **Phase 2:** [x] Host Metric Collector & Resilient Agent Client — **VERIFIED** (`3/3 unit tests passing`)
* **Phase 3:** [x] Thread-Safe State Registry & Multithreaded Server — **VERIFIED** (`3/3 concurrency tests passing`)
* **Phase 4:** [x] Watchdog Supervisor & Terminal Dashboard — **VERIFIED** (`2/2 dashboard tests + timed exec exit`)
* **Phase 5:** [x] Cluster Simulation & Full Integration Testing — **VERIFIED** (`2/2 full loopback integration tests passing`)

---

### Context-Boundary Execution Rules
To prevent AI agent hallucination, degradation, or context loss across long sessions:
1. **Never skip phases.** Each phase must pass its **Exit Verification Gate** before moving to the next.
2. **File Isolation:** Do not rewrite existing modules when creating new ones. Extend or import.
3. **Deterministic Verification:** Every phase concludes with a terminal command that can be executed to verify functionality.

---

## Phase 0: Environment Setup & Project Skeleton [COMPLETED]

### Objective
Create the Maven standard directory layout, `pom.xml`, and baseline project structure.

### Actions
1. Create directory structure matching the Maven standard layout:
   ```
   src/main/java/com/pulsenet/common/model/
   src/main/java/com/pulsenet/common/protocol/
   src/main/java/com/pulsenet/agent/
   src/main/java/com/pulsenet/server/registry/
   src/main/java/com/pulsenet/server/network/
   src/main/java/com/pulsenet/server/watchdog/
   src/main/java/com/pulsenet/server/dashboard/
   src/main/java/com/pulsenet/simulation/
   src/test/java/com/pulsenet/common/protocol/
   src/test/java/com/pulsenet/agent/
   src/test/java/com/pulsenet/server/registry/
   src/test/java/com/pulsenet/server/dashboard/
   src/test/java/com/pulsenet/integration/
   ```
2. Generate `pom.xml` configured for Java 17+ with minimal dependencies:
   * Jackson Databind (`com.fasterxml.jackson.core:jackson-databind:2.17.0`) for fast, clean JSON serialization.
   * JUnit 5 Jupiter (`org.junit.jupiter:junit-jupiter:5.10.2`) for testing.
   * Maven Compiler, Surefire, Exec, and Dependency plugins.

### Exit Verification Gate
```bash
mvn clean compile
```
* **Status:** Passed (`BUILD SUCCESS`).

---

## Phase 1: Wire Protocol & Framing Specification [COMPLETED]

### Objective
Build deterministic length-prefixed packet serialization and deserialization to handle TCP stream boundaries and prevent fragmentation bugs.

### Key Components
* `com.pulsenet.common.model.TelemetryPayload`:
  * Fields: `nodeId` (String), `timestamp` (long), `cpuPercent` (double), `memoryPercent` (double), `diskPercent` (double), `processCount` (int), `status` (String).
  * Methods `toJson() -> String` and static `fromJson(String json) -> TelemetryPayload`.
* `com.pulsenet.common.protocol.WireProtocol`:
  * `packMessage(TelemetryPayload payload) -> byte[]`: Encodes payload to UTF-8 JSON, determines length $L$, and returns a byte array consisting of a 4-byte big-endian integer followed by the UTF-8 payload bytes.
  * `sendFramedMessage(OutputStream out, TelemetryPayload payload) throws IOException`: Writes the packed frame directly to the socket output stream and calls `flush()`.
  * `receiveFramedMessage(InputStream in) throws IOException`: Reads exactly 4 bytes to determine payload length $L$, then accumulates chunks until all $L$ bytes are received. Returns `null` cleanly on EOF or socket disconnect. Handles 10MB bounds limit.

### Exit Verification Gate
Create `src/test/java/com/pulsenet/common/protocol/WireProtocolTest.java` verifying:
* Message packing and unpacking symmetry.
* Sequential multiple framed messages over a single TCP stream.
* Simulated TCP fragmentation (passing partial 3-byte chunks into the parser).
* Clean null return on empty stream and premature disconnection.
* Rejection of negative and oversized frames ($> 10\text{ MB}$).

```bash
mvn test -Dtest=WireProtocolTest
```
* **Status:** Passed (7/7 tests passed, 0 failures, 0 errors).

---

## Phase 2: Host Metric Collector & Resilient Agent Client [COMPLETED]

### Objective
Implement the client-side telemetry sampler and socket streamer with automatic reconnect logic.

### Key Components
* `com.pulsenet.agent.MetricCollector`:
  * Uses standard Java runtime facilities (`java.lang.management.ManagementFactory`, `com.sun.management.OperatingSystemMXBean`) for CPU and memory usage.
  * Uses `java.io.File` (`File.getTotalSpace()` / `File.getUsableSpace()`) for disk metric collection.
  * Uses `ProcessHandle.allProcesses().count()` for active process count.
  * Evaluates health status (`WARNING` if CPU > 85%, Mem > 90%, or Disk > 95%; else `HEALTHY`).
* `com.pulsenet.agent.TelemetryAgent`:
  * Accepts `serverHost`, `serverPort`, `nodeId`, and `intervalMillis`.
  * Contains a resilient connection loop:
    * Connects `java.net.Socket` with `setTcpNoDelay(true)` and `setKeepAlive(true)`.
    * Streams periodic framed payloads.
    * On `IOException` / `SocketException`, logs warning, waits with exponential backoff (1s, 2s, 4s... max 10s), and reconnects without crashing.
* `com.pulsenet.agent.AgentMain`:
  * CLI entry point supporting `--host`, `--port`, `--node-id`, `--interval`, and graceful JVM shutdown hooks.

### Exit Verification Gate
Spin up a temporary local `ServerSocket` in a test and stream 5 messages from `TelemetryAgent`:
```bash
mvn test -Dtest=TelemetryAgentTest
```
* **Status:** Passed (3/3 tests passed, verifying 5-message streaming, socket drop reconnect resilience, and OS metrics sampling).

---

## Phase 3: Thread-Safe State Registry & Multithreaded Server [COMPLETED]

### Objective
Build the server concurrency engine: connection listener, per-client worker threads, and mutex-protected in-memory storage.

### Key Components
* `com.pulsenet.server.registry.NodeRecord`:
  * Stores `TelemetryPayload`, remote client socket address, local timestamp of last received heartbeat, and status. Provides defensive copying.
* `com.pulsenet.server.registry.NodeRegistry`:
  * Internal storage: `Map<String, NodeRecord> nodes = new HashMap<>()`.
  * Concurrency control: `ReentrantLock lock = new ReentrantLock()`.
  * `upsertNode(TelemetryPayload payload, String remoteAddress) -> void`: Acquires lock, updates record with local system timestamp, releases lock in `finally`.
  * `getAllNodes() -> List<NodeRecord>`: Acquires lock, returns snapshot (defensive copy), releases lock in `finally`.
  * `auditStaleNodes(double timeoutSeconds) -> List<String>`: Acquires lock, checks timestamps, flags nodes with no heartbeat as `OFFLINE`.
  * Maintains bounded rolling event log (`getRecentEvents(int limit)`).
* `com.pulsenet.server.network.TelemetryServer`:
  * Main thread executes `ServerSocket.bind(new InetSocketAddress(host, port))` with `setReuseAddress(true)`.
  * Worker dispatch: Spawns dedicated daemon worker thread `ClientHandler` for each accepted client socket.
* `com.pulsenet.server.network.ClientHandler (Runnable)`:
  * Reads length-prefixed frames via `WireProtocol.receiveFramedMessage(in)`.
  * Performs network I/O strictly outside lock scope.
  * Updates `NodeRegistry` under fast mutex protection.
  * Breaks cleanly on socket disconnect, closes socket, and terminates worker thread.

### Exit Verification Gate
Create `src/test/java/com/pulsenet/server/registry/NodeRegistryTest.java` testing concurrent updates from 20 simulated threads simultaneously to verify zero race conditions or data corruption:
```bash
mvn test -Dtest=NodeRegistryTest
```
* **Status:** Passed (3/3 tests passed, verifying 20 concurrent threads with 2,000 rapid updates, stale node timeouts, and snapshot isolation).

---

## Phase 4: Watchdog Supervisor & Terminal Dashboard [COMPLETED]

### Objective
Implement background health auditing and real-time ANSI terminal UI presentation.

### Key Components
* `com.pulsenet.server.watchdog.WatchdogSupervisor`:
  * Runs continuously as a background daemon thread (`setDaemon(true)`) with a 1-second sleep interval.
  * Invokes `registry.auditStaleNodes(timeoutSeconds)` (default 5.0 seconds).
  * Logs node status transitions (`HEALTHY -> WARNING -> OFFLINE`).
* `com.pulsenet.server.dashboard.DashboardRenderer`:
  * Queries `NodeRegistry` at a fixed rate (2 Hz / 500ms).
  * Renders a structured ANSI terminal table:
    * ASCII/Unicode progress bars for CPU, RAM, and Disk: `[|||||   ] 42%`.
    * Visual alert triggers: Highlight CPU > 85% or Memory > 90% in yellow/red; highlight OFFLINE nodes in gray.
    * Rolling event log displaying connection, threshold, and heartbeat drop events.
    * Headless mode support for automated environments.
* `com.pulsenet.server.ServerMain`:
  * CLI entry point supporting `--host`, `--port`, `--watchdog-timeout`, `--headless`, and `--timeout`.

### Exit Verification Gate
Run server in standalone mode for 5 seconds and terminate gracefully:
```bash
mvn exec:java -Dexec.mainClass="com.pulsenet.server.ServerMain" -Dexec.args="--port 9000 --timeout 5"
```
* **Status:** Passed (server started, bound port 9000, rendered live dashboard, executed watchdog sweeps, and shut down cleanly upon reaching timeout).

---

## Phase 5: Cluster Simulation & Full Integration Testing [COMPLETED]

### Objective
Demonstrate multi-node concurrent telemetry exchange under simulated heavy load and network drop conditions.

### Key Components
* `com.pulsenet.simulation.MockCluster`:
  * CLI tool taking `--nodes N` (default 5), `--host`, `--port`, `--interval`, and `--chaos`.
  * Spawns $N$ background threads, each running a `TelemetryAgent` with unique IDs (`agent-01`, `agent-02`, etc.) and realistic synthetic load variations.
  * **Chaos Injection:** Deliberately shuts down `agent-03` after 10 seconds to showcase the Watchdog detecting silent node failure in real time.
* `src/test/java/com/pulsenet/integration/IntegrationTest.java`:
  * Automated end-to-end socket test: launches server on dynamic ephemeral port, connects multiple clients, verifies server registry contains all nodes within 2 seconds.
  * Verifies abrupt socket drop detection and Watchdog marking silent nodes `OFFLINE`.
  * Verifies surviving nodes stay active and healthy.

### Exit Verification Gate
```bash
mvn test -Dtest=IntegrationTest
```
* **Status:** Passed (2/2 tests passed).

---

### Overall Project Verification
Execute complete regression test suite:
```bash
mvn test
```
* **Status:** **BUILD SUCCESS** — 17/17 Tests Passing across all modules (0 failures, 0 errors, 0 skipped).
