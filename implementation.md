# PulseNet: Phased Implementation Plan for Antigravity Agents (Java Edition)

This document outlines the strict execution roadmap for building PulseNet in Java.

### Implementation Status: Phases 0–5 COMPLETED (17/17 Tests Passing) — Phases 6–10 (Multi-Laptop Demo) PENDING
* **Phase 0:** [x] Environment Setup & Project Skeleton — **VERIFIED** (`mvn clean compile`)
* **Phase 1:** [x] Wire Protocol & Framing Specification — **VERIFIED** (`7/7 unit tests passing`)
* **Phase 2:** [x] Host Metric Collector & Resilient Agent Client — **VERIFIED** (`3/3 unit tests passing`)
* **Phase 3:** [x] Thread-Safe State Registry & Multithreaded Server — **VERIFIED** (`3/3 concurrency tests passing`)
* **Phase 4:** [x] Watchdog Supervisor & Terminal Dashboard — **VERIFIED** (`2/2 dashboard tests + timed exec exit`)
* **Phase 5:** [x] Cluster Simulation & Full Integration Testing — **VERIFIED** (`2/2 full loopback integration tests passing`)
* **Phase 6:** [ ] Portable Distribution (Single Executable JAR + Launch Scripts)
* **Phase 7:** [ ] Network Discoverability, Stable Node Identity & Dashboard Readability
* **Phase 8:** [ ] Real-Network Connection Hygiene (Half-Open Sockets, Duplicate IDs, Event Noise)
* **Phase 9:** [ ] Demo Tooling (Connectivity Probe, CPU Stress Generator, Firewall Helper)
* **Phase 10:** [ ] Multi-Laptop Rehearsal & Documentation

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

---
---

# Part II: Three-Laptop Live Demo (1 Server + 2 Agent Nodes)

**Target topology:**
```
   [ Laptop A: SERVER ]  <-- TCP :9000 --  [ Laptop B: NODE (AgentMain) ]
   ServerMain + Dashboard <-- TCP :9000 --  [ Laptop C: NODE (AgentMain [+ optional MockCluster]) ]
        (all three on the same LAN / hotspot)
```

## Gap Analysis

### Already works across machines (no change needed)
* `ServerMain` binds `0.0.0.0` by default, so it accepts connections on every interface.
* `AgentMain` / `MockCluster` accept `--host <ip>`, so they can target a remote server.
* Agent reconnect with exponential backoff (1s → 10s cap) survives server restarts and network blips.
* Staleness is judged by the **server's** clock (`NodeRecord.lastSeenTimestamp` is set on receipt), so clock skew between laptops cannot cause false OFFLINE flags.
* Recovery path (`OFFLINE` → `[REC] Node recovered`) already exists in `NodeRegistry.upsertNode`.

### What is lacking
| # | Gap | Where | Why it hurts the 3-laptop demo |
|---|-----|-------|--------------------------------|
| G1 | No self-contained executable JAR; run requires `target/*.jar` + `target/lib/*` and a platform-specific classpath separator (`;` vs `:`). `target/` is git-ignored. | `pom.xml`, `run_demo.*` | Every node laptop would need Maven + a full build, or a hand-copied `lib/` folder. Fragile on demo day. |
| G2 | Launch scripts only run server + MockCluster on **localhost**. No "start server" / "start agent pointing at IP X" scripts. | `run_demo.bat/.ps1/.sh` | Presenters type long `java -cp ...` commands live on each laptop. |
| G3 | Server never tells you its LAN IP. Dashboard header shows `Server: 0.0.0.0:9000`. | `ServerMain`, `DashboardRenderer` | Node operators have to run `ipconfig` on the server laptop and guess which adapter is correct. |
| G4 | Default node ID is `hostname-<random4>`; the random suffix changes on every agent restart. | `AgentMain.generateDefaultNodeId()` | A restarted node shows up as a **new** row instead of `[REC] recovered`; the old row lingers OFFLINE. This ruins the recovery story. |
| G5 | Dashboard columns too narrow: NODE ID truncated at 13 chars, IP:PORT at 17. | `DashboardRenderer` | Real hostnames (`DESKTOP-7QK2M1L`) and LAN addresses (`192.168.137.105:53122` = 21 chars) are truncated with `~`. |
| G6 | `java.util.logging` INFO/WARNING lines go to the same console as the 2 Hz clear-screen dashboard. | `ClientHandler`, `TelemetryServer`, `WatchdogSupervisor` | Dashboard tears/flickers whenever a node connects, drops, or the watchdog fires — exactly the moments you want the audience to watch. |
| G7 | Server client sockets have no `SO_TIMEOUT`. If a laptop loses Wi-Fi or sleeps (no FIN/RST), its `ClientHandler` blocks in `read()` indefinitely (until OS keepalive, ~2 h). | `TelemetryServer` / `ClientHandler` | Leaked worker threads; when the stale handler finally dies it logs `Client disconnected: node-b` **after** node-b has already reconnected. Misleading event. |
| G8 | No handling of a second live connection claiming the same `nodeId`. | `NodeRegistry`, `ClientHandler` | After a Wi-Fi blip there are two handlers for one node; the old one's late disconnect event contradicts the new state. |
| G9 | CPU/Memory `[WARN]` event logged on **every** frame above threshold (1/s). | `ClientHandler` | During the CPU-stress scene, the 6-line event log is flooded with identical WARN lines and hides the `[LOST]`/`[REC]` events. |
| G10 | `MockCluster` IDs are fixed `agent-01..N`. | `MockCluster` | Running a simulated cluster from both node laptops makes IDs collide and overwrite each other. |
| G11 | No way to make a real laptop cross the WARNING threshold on cue. | n/a | Real laptops idle at 5–20% CPU, so the yellow WARNING state never shows during the demo. |
| G12 | No connectivity pre-flight tool and no firewall guidance. Windows Defender Firewall blocks inbound TCP 9000 by default (especially on a "Public" network profile); many campus Wi-Fi networks enable client/AP isolation. | n/a | The most likely demo-day failure: nodes show `connect timed out` with no clear diagnosis. |
| G13 | README/runbook only describes single-machine operation. | `README.md` | No rehearsable script for the presenters. |

---

## Phase 6: Portable Distribution (Single Executable JAR + Launch Scripts) [PENDING]

### Objective
Produce one file, `pulsenet.jar`, that runs on any laptop with Java 17+ via `java -jar`, without Maven or a `lib/` folder. Fixes **G1** and **G2**.

### Actions
1. `pom.xml`: add `maven-shade-plugin` (build-time plugin only, no new runtime dependency) bound to `package`, producing `target/pulsenet.jar` with Jackson bundled and `Main-Class: com.pulsenet.Launcher`. Keep the existing `copy-dependencies` execution so `run_demo.*` keep working.
2. Create `com.pulsenet.Launcher`, a thin subcommand dispatcher that **delegates** to the existing mains without modifying them (File Isolation rule):
   * `java -jar pulsenet.jar server  [ServerMain args]`
   * `java -jar pulsenet.jar agent   [AgentMain args]`
   * `java -jar pulsenet.jar cluster [MockCluster args]`
   * `java -jar pulsenet.jar --help` prints all subcommands.
   * (Phase 9 adds `probe` and `stress`.)
3. Add a `scripts/` folder with launch scripts for both Windows and macOS/Linux:
   * `scripts/start-server.bat` / `.ps1` / `.sh`: `java -jar pulsenet.jar server --port 9000`
   * `scripts/start-agent.bat` / `.ps1` / `.sh <SERVER_IP> [NODE_ID]`: `java -jar pulsenet.jar agent --host %1 --port 9000 [--node-id %2]`
   * Each script checks `java -version` is ≥17 and prints a clear error if it isn't.
4. Create `scripts/package-demo.(ps1|sh)`, which runs `mvn clean package` and copies `pulsenet.jar` and `scripts/` into a `dist/pulsenet-demo/` folder (add `dist/` to `.gitignore`). That folder is what goes on a USB stick or GitHub Release for the node laptops.

### Exit Verification Gate
```bash
mvn clean package
java -jar target/pulsenet.jar --help
java -jar target/pulsenet.jar server --port 9000 --timeout 3 --headless
mvn test
```
* Add `LauncherTest`: unknown subcommand prints usage and returns non-zero exit status (via a testable `run(String[]) -> int` method); `server --help` delegates correctly.

---

## Phase 7: Network Discoverability, Stable Node Identity & Dashboard Readability [PENDING]

### Objective
Make it obvious which IP the nodes should target, keep each laptop's identity stable across restarts, and keep the dashboard legible. Fixes **G3, G4, G5, G6, G10**.

### Actions
1. **LAN address discovery (G3):** new `com.pulsenet.common.net.NetworkUtils.listLanIPv4Addresses()` that enumerates `java.net.NetworkInterface` and returns up, non-loopback, non-virtual IPv4 addresses with the interface name (filter out `vEthernet`, `VirtualBox`, `VMware`, `docker`, and `169.254.*` link-local).
   * `ServerMain` prints a startup banner: `Agents should connect to: 192.168.137.1:9000 (Wi-Fi)`.
   * `DashboardRenderer` header shows the first LAN IP instead of `0.0.0.0` when bound to the wildcard address (constructor overload; existing constructors unchanged).
2. **Stable node ID (G4):** change `AgentMain.generateDefaultNodeId()` to return the lower-cased hostname only, with no random suffix, so a restarted agent reuses the same ID and triggers `[REC] Node recovered`. `--node-id` still overrides it.
3. **Column widths (G5):** widen NODE ID to 20 chars and IP:PORT to 21 chars (fits `255.255.255.255:65535`). Adjust the separator width to match. Update `DashboardRendererTest` expectations.
4. **Log isolation (G6):** when the dashboard is in ANSI (non-headless) mode, `ServerMain` reconfigures the root JUL logger: console handler threshold raised to `SEVERE`, and a `FileHandler` writes everything to `pulsenet-server.log`. The dashboard's event log already shows the important events. Headless mode keeps the current console logging.
5. **MockCluster prefix (G10):** add `--prefix <str>` (default `agent-`) so IDs become e.g. `nodeC-sim-01`. The chaos target becomes `<prefix>03`.

### Exit Verification Gate
```bash
mvn test
java -jar target/pulsenet.jar server --port 9000 --timeout 5
```
* Banner shows at least one real LAN IPv4; dashboard header no longer shows `0.0.0.0`; no log lines interleave with the dashboard.
* New tests: `NetworkUtilsTest` (no loopback/link-local in results), `AgentMain` default ID is identical across two calls, `MockCluster` respects `--prefix`, and the dashboard renders a 21-char address untruncated.

---

## Phase 8: Real-Network Connection Hygiene [PENDING]

### Objective
Behave correctly when a laptop drops off Wi-Fi, sleeps, or reconnects, which a loopback-only test never exercises. Fixes **G7, G8, G9**.

### Actions
1. **Read timeout (G7):** `TelemetryServer` sets `clientSocket.setSoTimeout(readTimeoutMillis)` on accept (default `max(3 × watchdogTimeout, 15s)`, configurable via `ServerMain --read-timeout <sec>`). `ClientHandler` catches `SocketTimeoutException`, logs `[SYS] Connection timed out: <node>`, closes the socket and exits the thread. All I/O stays outside the registry lock.
2. **Connection supersession (G8):** `NodeRegistry` tracks a per-node connection generation (an `AtomicLong` counter, or the `ClientHandler` identity) assigned in `upsertNode`. When a handler exits, it only logs "Client disconnected" (or marks anything) if it is still the **current** owner of that `nodeId`. If a newer connection has claimed the ID, it logs `[SYS] Superseded stale connection for <node>` instead. All state reads and writes happen under the existing `ReentrantLock`.
3. **Edge-triggered threshold events (G9):** emit `[WARN] crossed CPU threshold` only on the HEALTHY→WARNING transition, and a new `[OK] <node> back below thresholds` on WARNING→HEALTHY. Compute the transition inside `NodeRegistry.upsertNode` under the lock, comparing the previous and new payload, instead of in `ClientHandler`.
4. *(Optional / stretch)* **Agent-side dead-server detection:** today an agent only notices a vanished server when a TCP write eventually fails (can take 10–30 s on Wi-Fi loss). If faster detection is wanted, add a server→agent 1-frame ACK every N messages and an agent `SO_TIMEOUT`. This changes the wire contract, so it needs its own `WireProtocolTest` additions. **Skip unless rehearsal shows it is needed.**

### Exit Verification Gate
```bash
mvn test
```
* New `IntegrationTest` cases:
  * A raw client socket that connects, sends one frame, then goes silent **without closing**. Its handler thread terminates within `readTimeout + 1s`.
  * Two sequential connections with the same `nodeId` where the first is never closed. The registry holds one record, the status ends non-OFFLINE, and no "Client disconnected" event appears for the superseded connection.
  * 10 consecutive frames at 95% CPU produce exactly **one** `[WARN]` event.
* Re-run the `NodeRegistryTest` 20-thread concurrency test to confirm the new transition logic adds no races.

---

## Phase 9: Demo Tooling (Probe, Stress, Firewall) [PENDING]

### Objective
Give presenters a one-command diagnosis for network problems and a way to trigger the WARNING state on a real laptop on cue. Fixes **G11, G12**.

### Actions
1. **Connectivity probe:** `java -jar pulsenet.jar probe --host <ip> [--port 9000]`
   * Attempts a TCP connect with a 3 s timeout and reports one of:
     * `OK: server reachable` (connect succeeded; closes immediately without sending a frame, so no phantom node appears on the dashboard).
     * `REFUSED: host reachable but nothing listening on 9000 → start the server`.
     * `TIMEOUT: no response → firewall on server laptop, wrong IP, or Wi-Fi client isolation`.
     * `UNREACHABLE / UNKNOWN HOST: check IP / same network`.
2. **CPU stress generator:** `java -jar pulsenet.jar stress [--seconds 20] [--threads <cores>]`
   * Spins `--threads` daemon busy-loop threads for N seconds with a cooperative `AtomicBoolean` stop flag, then exits. Run it in a second terminal on a node laptop to push CPU above 85% → yellow WARNING on the server dashboard.
   * *(Alternative:* an `AgentMain --stress-after <s> --stress-seconds <n>` flag. The separate subcommand is preferred because it keeps the agent pure.)*
3. **Firewall helper (Windows server laptop):** `scripts/open-firewall.ps1` (run as Administrator):
   * Prints the current network profile (`Get-NetConnectionProfile`) and warns if it is `Public`.
   * `New-NetFirewallRule -DisplayName "PulseNet 9000" -Direction Inbound -Protocol TCP -LocalPort 9000 -Action Allow -Profile Any`
   * Companion `scripts/close-firewall.ps1` removes the rule after the demo.
   * macOS: document allowing Java in System Settings → Network → Firewall.

### Exit Verification Gate
```bash
mvn test
java -jar target/pulsenet.jar probe --host 127.0.0.1 --port 9000      # expect REFUSED (no server)
java -jar target/pulsenet.jar server --port 9000 --timeout 10 &
java -jar target/pulsenet.jar probe --host 127.0.0.1 --port 9000      # expect OK
java -jar target/pulsenet.jar stress --seconds 5                      # CPU visibly spikes, exits by itself
```
* New tests: `ProbeTest` (REFUSED against a closed ephemeral port, OK against a test `ServerSocket`, and no registry entry created by a probe), `StressTest` (threads stop within 1 s after duration).

---

## Phase 10: Multi-Laptop Rehearsal & Documentation [PENDING]

### Objective
Document and rehearse the exact demo on the real three laptops. Fixes **G13**. This phase's gate is a **manual, on-hardware** checklist.

### Actions
1. Add a "Multi-Laptop Demo" section to `README.md` (or a dedicated `DEMO.md`) containing the network setup, pre-flight checklist, and scene script below.
2. Run `scripts/package-demo` and copy `dist/pulsenet-demo/` to laptops B and C (USB / GitHub Release).
3. Do at least one full rehearsal on the actual venue network, or on the hotspot you'll use.

### Network Setup (do this first, it is the #1 risk)
* **Preferred:** a phone hotspot or a cheap travel router that all three laptops join. Campus or enterprise Wi-Fi (e.g. eduroam) often enables **client isolation**, which silently blocks laptop-to-laptop TCP. `probe` will report `TIMEOUT`.
* Alternative: Laptop A creates a Windows Mobile Hotspot (Settings → Network → Mobile hotspot). Its IP is then usually `192.168.137.1`.
* On Laptop A: set the network profile to **Private**, run `scripts/open-firewall.ps1` as Administrator, and accept the Java firewall prompt if Windows shows one ("Private networks").
* All laptops: disable sleep on lid close and Wi-Fi power saving for the duration of the demo.
* Java 17+ on all three (`java -version`).

### Pre-Flight Checklist (≈5 min before presenting)
1. **A:** `scripts/start-server` → note the IP in the startup banner (e.g. `192.168.137.1`). Write it on the board.
2. **B and C:** `java -jar pulsenet.jar probe --host <A_IP>` → must print `OK`.
3. **B and C:** `scripts/start-agent <A_IP>` → both hostnames appear HEALTHY on A's dashboard within 2 s.
4. Stop the agents. The dashboard marks them OFFLINE after 5 s, confirming the watchdog works on this network. Restart the agents and confirm `[REC]` events, which also confirms node IDs are stable.
5. Restart the server for a clean dashboard before the audience arrives.

### Demo Scene Script
| Scene | Action | What the audience sees on Laptop A |
|-------|--------|-------------------------------------|
| 1. Boot | A: `start-server` | Banner with LAN IP; empty dashboard "Waiting for agent telemetry..." |
| 2. Join | B, then C: `start-agent <A_IP>` | Two rows appear with **real** CPU/RAM/Disk/PIDs and LAN `IP:PORT`; `[SYS] Node connected` events |
| 3. Scale *(optional)* | C: `java -jar pulsenet.jar cluster --host <A_IP> --nodes 5 --prefix c-sim- --no-chaos` | 7 nodes, showing the thread-per-client server handling concurrent streams |
| 4. Load | B: `java -jar pulsenet.jar stress --seconds 20` | B's CPU bar fills and status turns yellow **WARNING**; one `[WARN]` event; returns to HEALTHY with `[OK]` afterwards |
| 5. Failure | C: turn Wi-Fi **off** (or Ctrl+C the agent) | Within ~5 s C turns gray **OFFLINE (Ns)**; red `[LOST] Heartbeat lost` event |
| 6. Recovery | C: turn Wi-Fi back **on** (or rerun `start-agent`) | Agent backoff reconnects within ≤10 s; green `[REC] Node recovered`, same row, no duplicate |
| 7. Server restart *(optional)* | A: Ctrl+C, then `start-server` again | Both nodes reappear automatically within ≤10 s, with no action on B/C (exponential backoff reconnect) |

### Fallback Plan
* If the venue network blocks peer traffic and no hotspot is available, run `run_demo.bat` on Laptop A (single-machine MockCluster with chaos) as the backup demo.
* Keep `pulsenet-server.log` open in a second window on A to explain events in detail if asked.

### Exit Verification Gate (manual, on real hardware)
* [ ] `probe` returns OK from B and C to A.
* [ ] Scenes 1–6 complete without touching any command other than those in the script.
* [ ] Scene 6 shows `[REC]` on the **same** row (no duplicate node row).
* [ ] No log lines tear the dashboard during any scene.
* [ ] `mvn test` still passes on the development machine.

---

### Recommended Execution Order & Priority
* **Must-have for a working 3-laptop demo:** Phase 6, Phase 7 (items 1–4), Phase 9 item 1 (probe) + item 3 (firewall), and the Phase 10 network setup.
* **Strongly recommended for a polished demo:** Phase 8 items 1–3, Phase 9 item 2 (stress), and Phase 7 item 5 (only needed if you run Scene 3).
* **Optional:** Phase 8 item 4 (agent-side ACK).
