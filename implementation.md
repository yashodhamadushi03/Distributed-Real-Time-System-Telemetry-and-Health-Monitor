# PulseNet: Phased Implementation Plan for Antigravity Agents

This document outlines the strict execution roadmap for building PulseNet. 

### Context-Boundary Execution Rules
To prevent AI agent hallucination, degradation, or context loss across long sessions:
1. **Never skip phases.** Each phase must pass its **Exit Verification Gate** before moving to the next.
2. **File Isolation:** Do not rewrite existing modules when creating new ones. Extend or import.
3. **Deterministic Verification:** Every phase concludes with a terminal command that can be executed to verify functionality.

---

## Phase 0: Environment Setup & Project Skeleton

### Objective
Create the root directory structure, initial configuration, virtual environment, and dependencies.

### Actions
1. Create directory structure matching `README.md`.
2. Generate `requirements.txt` with minimal dependencies:
   ```txt
   psutil>=5.9.0
   rich>=13.0.0
   pytest>=7.0.0
   ```
3. Initialize empty `__init__.py` files across all packages.

### Exit Verification Gate
```bash
python3 -c "import socket, threading, struct, json; print('Standard libraries verified')"
pip install -r requirements.txt
```

---

## Phase 1: Wire Protocol & Framing Specification

### Objective
Build deterministic length-prefixed packet serialization and deserialization to handle TCP stream boundaries and prevent fragmentation bugs.

### Key Components
* `pulsenet/common/models.py`:
  * Dataclass `TelemetryPayload`: `node_id` (str), `timestamp` (float), `cpu_percent` (float), `memory_percent` (float), `disk_percent` (float), `process_count` (int), `status` (str).
  * Method `to_json() -> str` and `@classmethod from_json(raw_str) -> TelemetryPayload`.
* `pulsenet/common/protocol.py`:
  * `pack_message(payload: TelemetryPayload) -> bytes`: Encodes payload to UTF-8 JSON, calculates length $L$, and prepends a 4-byte unsigned integer in big-endian network byte order (`!I`).
  * `receive_framed_message(sock: socket.socket) -> Optional[TelemetryPayload]`: Reads exactly 4 bytes to determine payload length $L$, then loops `sock.recv()` until all $L$ bytes are accumulated. Returns `None` cleanly on EOF or disconnection.

### Exit Verification Gate
Create `tests/test_protocol.py` verifying:
* Message packing and unpacking symmetry.
* Simulated fragmentation (passing partial byte streams into the parser).
```bash
pytest tests/test_protocol.py -v
```

---

## Phase 2: Host Metric Collector & Resilient Agent Client

### Objective
Implement the client-side telemetry sampler and socket streamer with automatic reconnect logic.

### Key Components
* `pulsenet/agent/collector.py`:
  * Uses `psutil` (with fallback to standard `/proc` or `os` modules if running in restricted environments) to sample system metrics.
  * Emits populated `TelemetryPayload` instances.
* `pulsenet/agent/client.py`:
  * Class `TelemetryAgent`:
    * Accepts `server_host`, `server_port`, `node_id`, and `interval_seconds`.
    * Contains a connection loop: connects socket, streams periodic payloads, handles `BrokenPipeError` or `ConnectionResetError`, waits with exponential backoff, and reconnects without crashing.

### Exit Verification Gate
Run a temporary local listener and stream 5 messages from the agent:
```bash
python3 -c "
import socket
s = socket.socket()
s.bind(('127.0.0.1', 9999))
s.listen(1)
print('Listening...')
conn, _ = s.accept()
data = conn.recv(1024)
print('Received raw bytes:', len(data))
conn.close()
s.close()
" &
AGENT_PID=$!
python3 -c "
from pulsenet.agent.client import TelemetryAgent
import time
agent = TelemetryAgent(server_host='127.0.0.1', server_port=9999, node_id='test-node', interval_seconds=1)
# Trigger single send test
agent.send_single_burst()
"
```

---

## Phase 3: Thread-Safe State Registry & Multithreaded Server

### Objective
Build the server concurrency engine: connection listener, per-client worker threads, and mutex-protected in-memory storage.

### Key Components
* `pulsenet/server/registry.py`:
  * Class `NodeRegistry`:
    * Internal dictionary: `_nodes: Dict[str, NodeRecord]`.
    * Concurrency control: `self._lock = threading.Lock()`.
    * `upsert_node(payload: TelemetryPayload, addr: Tuple[str, int]) -> None`: Acquires lock, updates record with current local timestamp, releases lock.
    * `get_all_nodes() -> List[NodeRecord]`: Acquires lock, returns shallow copy, releases lock.
    * `audit_stale_nodes(timeout_seconds: float) -> List[str]`: Acquires lock, checks timestamps, flags nodes with no heartbeat as `OFFLINE`.
* `pulsenet/server/listener.py`:
  * Class `TelemetryServer`:
    * Main thread executes socket `bind()` and `listen()`.
    * Worker dispatch: Spawns `threading.Thread(target=self._handle_client, args=(client_sock, addr), daemon=True)` for each accepted connection.
    * `_handle_client`: Loops calling `receive_framed_message(client_sock)`, updates `NodeRegistry`, breaks cleanly on socket close.

### Exit Verification Gate
Create `tests/test_registry.py` testing concurrent updates from 20 simulated threads simultaneously to verify zero race conditions or data corruption:
```bash
pytest tests/test_registry.py -v
```

---

## Phase 4: Watchdog Supervisor & Terminal Dashboard

### Objective
Implement background health auditing and real-time ANSI/Rich UI presentation.

### Key Components
* `pulsenet/server/watchdog.py`:
  * Class `WatchdogSupervisor(threading.Thread)`:
    * Runs continuously with a 1-second sleep interval.
    * Invokes `registry.audit_stale_nodes(timeout_seconds=5.0)`.
    * Logs node status transitions (`HEALTHY -> WARNING -> OFFLINE`).
* `pulsenet/server/dashboard.py`:
  * Class `DashboardRenderer`:
    * Query `NodeRegistry` at a fixed rate (e.g., 2 Hz).
    * Render structured table using the `rich.live.Live` module or ANSI escape sequences (for terminal portability).
    * Visual alert triggers: Highlight CPU > 85% or Memory > 90% in red/yellow; highlight OFFLINE nodes in gray.

### Exit Verification Gate
Run server in standalone mode for 5 seconds and terminate gracefully:
```bash
python3 -m pulsenet.server.main --port 9000 --timeout 5 &
SERVER_PID=$!
sleep 2
kill $SERVER_PID
```

---

## Phase 5: Cluster Simulation & Full Integration Testing

### Objective
Demonstrate multi-node concurrent telemetry exchange under simulated heavy load and network drop conditions.

### Key Components
* `scripts/mock_cluster.py`:
  * CLI tool taking `--nodes N` (e.g., $N=5$).
  * Spawns $N$ background threads/processes, each running a `TelemetryAgent` with unique IDs (`agent-01`, `agent-02`, etc.) and synthetic load variations.
  * Simulates a chaotic node drop: deliberately kills `agent-03` after 10 seconds to showcase the Watchdog detecting node failure in real time.
* `tests/test_integration.py`:
  * Automated end-to-end socket test: launches server on random test port, connects two clients, verifies server registry contains both nodes within 2 seconds.

### Exit Verification Gate
```bash
pytest tests/test_integration.py -v
```