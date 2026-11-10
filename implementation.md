# PulseNet: Phased Implementation Plan for Antigravity Agents (Java Edition)

This document outlines the strict execution roadmap for building PulseNet in Java.

### Context-Boundary Execution Rules
To prevent AI agent hallucination, degradation, or context loss across long sessions:
1. **Never skip phases.** Each phase must pass its **Exit Verification Gate** before moving to the next.
2. **File Isolation:** Do not rewrite existing modules when creating new ones. Extend or import.
3. **Deterministic Verification:** Every phase concludes with a terminal command that can be executed to verify functionality.

---

## Phase 0: Environment Setup & Project Skeleton

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
   src/test/java/com/pulsenet/server/registry/
   src/test/java/com/pulsenet/integration/
   ```
2. Generate `pom.xml` configured for Java 17+ with minimal dependencies:
   * Jackson Databind (`com.fasterxml.jackson.core:jackson-databind:2.17.0`) for fast, clean JSON serialization.
   * JUnit 5 Jupiter (`org.junit.jupiter:junit-jupiter:5.10.2`) for testing.
   * Maven Compiler & Surefire plugins.

### Exit Verification Gate
```bash
mvn clean compile
```

---

## Phase 1: Wire Protocol & Framing Specification

### Objective
Build deterministic length-prefixed packet serialization and deserialization to handle TCP stream boundaries and prevent fragmentation bugs.

### Key Components
* `com.pulsenet.common.model.TelemetryPayload`:
  * Fields: `nodeId` (String), `timestamp` (long), `cpuPercent` (double), `memoryPercent` (double), `diskPercent` (double), `processCount` (int), `status` (String).
  * Methods `toJson() -> String` and static `fromJson(String json) -> TelemetryPayload`.
* `com.pulsenet.common.protocol.WireProtocol`:
  * `packMessage(TelemetryPayload payload) -> byte[]`: Encodes payload to UTF-8 JSON, determines length $L$, and returns a byte array consisting of a 4-byte big-endian integer followed by the UTF-8 payload bytes.
  * `sendFramedMessage(OutputStream out, TelemetryPayload payload) throws IOException`: Writes the packed frame directly to the socket output stream and calls `flush()`.
  * `receiveFramedMessage(InputStream in) throws IOException`: Reads exactly 4 bytes to determine payload length $L$, then accumulates chunks until all $L$ bytes are received. Returns `null` cleanly on EOF or socket disconnect.

### Exit Verification Gate
Create `src/test/java/com/pulsenet/common/protocol/WireProtocolTest.java` verifying:
* Message packing and unpacking symmetry.
* Simulated fragmentation (passing partial byte streams into the parser using `ByteArrayInputStream` chunk simulation).
```bash
mvn test -Dtest=WireProtocolTest
```

---

## Phase 2: Host Metric Collector & Resilient Agent Client

### Objective
Implement the client-side telemetry sampler and socket streamer with automatic reconnect logic.

### Key Components
* `com.pulsenet.agent.MetricCollector`:
  * Uses standard Java runtime facilities (`java.lang.management.ManagementFactory`, `com.sun.management.OperatingSystemMXBean`) for CPU and memory usage.
  * Uses `java.io.File` (`File.getTotalSpace()` / `File.getUsableSpace()`) for disk metric collection.
  * Uses `ProcessHandle.allProcesses().count()` for active process count.
  * Emits populated `TelemetryPayload` instances.
* `com.pulsenet.agent.TelemetryAgent`:
  * Accepts `serverHost`, `serverPort`, `nodeId`, and `intervalMillis`.
  * Contains a resilient connection loop:
    * Connects `java.net.Socket`.
    * Configures socket options: `setTcpNoDelay(true)`, `setKeepAlive(true)`.
    * Streams periodic framed payloads.
    * On `SocketException` or `IOException`, logs warning, waits with exponential backoff (1s, 2s, 4s... max 10s), and reconnects without crashing.

### Exit Verification Gate
Spin up a temporary local `ServerSocket` in a test and stream 5 messages from `TelemetryAgent`:
```bash
mvn test -Dtest=TelemetryAgentTest
```

---

## Phase 3: Thread-Safe State Registry & Multithreaded Server

### Objective
Build the server concurrency engine: connection listener, per-client worker threads, and mutex-protected in-memory storage.

### Key Components
* `com.pulsenet.server.registry.NodeRecord`:
  * Stores `TelemetryPayload`, remote client socket address, and local timestamp of last received heartbeat.
* `com.pulsenet.server.registry.NodeRegistry`:
  * Internal storage: `Map<String, NodeRecord> nodes = new HashMap<>()`.
  * Concurrency control: `ReentrantLock lock = new ReentrantLock()`.
  * `upsertNode(TelemetryPayload payload, String remoteAddress) -> void`: Acquires lock, updates record with local system timestamp, releases lock in `finally`.
  * `getAllNodes() -> List<NodeRecord>`: Acquires lock, returns snapshot (defensive copy), releases lock in `finally`.
  * `auditStaleNodes(double timeoutSeconds) -> List<String>`: Acquires lock, checks timestamps, flags nodes with no heartbeat as `OFFLINE`.
* `com.pulsenet.server.network.TelemetryServer`:
  * Main thread executes `ServerSocket.bind(new InetSocketAddress(host, port))` with `setReuseAddress(true)`.
  * Worker dispatch: Spawns a dedicated worker thread `ClientHandler` (with `setDaemon(true)`) for each accepted client socket.
  * `ClientHandler (Runnable)`: Loops calling `WireProtocol.receiveFramedMessage(in)`, updates `NodeRegistry`, and breaks cleanly on socket close.

### Exit Verification Gate
Create `src/test/java/com/pulsenet/server/registry/NodeRegistryTest.java` testing concurrent updates from 20 simulated threads simultaneously to verify zero race conditions or data corruption:
```bash
mvn test -Dtest=NodeRegistryTest
```

---

## Phase 4: Watchdog Supervisor & Terminal Dashboard

### Objective
Implement background health auditing and real-time ANSI terminal UI presentation.

### Key Components
* `com.pulsenet.server.watchdog.WatchdogSupervisor`:
  * Runs continuously as a background daemon thread (`setDaemon(true)`) with a 1-second sleep interval.
  * Invokes `registry.auditStaleNodes(5.0)`.
  * Logs node status transitions (`HEALTHY -> WARNING -> OFFLINE`).
* `com.pulsenet.server.dashboard.DashboardRenderer`:
  * Queries `NodeRegistry` at a fixed rate (e.g., 2 Hz).
  * Renders a structured ANSI terminal table:
    * ASCII/Unicode progress bars for CPU, RAM, and Disk: `[|||||   ] 42%`.
    * Visual alert triggers: Highlight CPU > 85% or Memory > 90% in yellow/red; highlight OFFLINE nodes in gray.
    * Rolling event log displaying connection and heartbeat events.

### Exit Verification Gate
Run server in standalone mode for 5 seconds and terminate gracefully:
```bash
mvn exec:java -Dexec.mainClass="com.pulsenet.server.ServerMain" -Dexec.args="--port 9000 --timeout 5"
```

---

## Phase 5: Cluster Simulation & Full Integration Testing

### Objective
Demonstrate multi-node concurrent telemetry exchange under simulated heavy load and network drop conditions.

### Key Components
* `com.pulsenet.simulation.MockCluster`:
  * CLI tool taking `--nodes N` (e.g., $N=5$).
  * Spawns $N$ background threads, each running a `TelemetryAgent` with unique IDs (`agent-01`, `agent-02`, etc.) and synthetic load variations.
  * Simulates a chaotic node drop: deliberately shuts down `agent-03` after 10 seconds to showcase the Watchdog detecting node failure in real time.
* `src/test/java/com/pulsenet/integration/IntegrationTest.java`:
  * Automated end-to-end socket test: launches server on dynamic port, connects two clients, verifies server registry contains both nodes within 2 seconds.

### Exit Verification Gate
```bash
mvn test -Dtest=IntegrationTest
```
