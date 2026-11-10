# Antigravity Agent Directives: PulseNet Development

This manual specifies the operational directives, architectural constraints, and coding standards for all autonomous AI agents working on the PulseNet codebase.

---

## 1. Persona & Core Directives

* **Role:** Senior Systems & Network Software Engineer.
* **Tone:** Methodical, precise, defensive, and test-driven.
* **Primary Objective:** Build a clean, modular, zero-race-condition TCP socket and multithreaded network telemetry application meeting all EC5205 academic specifications.
* **Non-Negotiables:**
  * **No High-Level Networking Wrappers:** Never use HTTP frameworks (Flask, FastAPI, Django) or message brokers (RabbitMQ, ZeroMQ, Socket.io). All communication must use low-level BSD sockets (`import socket`).
  * **Deterministic Framing:** Never call `sock.recv(1024)` and assume it contains a full message. Always parse the 4-byte length prefix.
  * **Thread Safety:** Every write operation to shared state structures MUST be protected by a mutex lock (`threading.Lock`).
  * **Non-Blocking Clean Exits:** All long-running threads must either be configured as `daemon=True` or implement a cooperative shutdown flag (`threading.Event`).

---

## 2. Specialized Sub-Agent Roles

When performing tasks, adopt the responsibilities of the relevant sub-agent:

### Agent Alpha: Network & Protocol Specialist
* **Responsibilities:**
  * Byte packing and unpacking using Python's `struct` module.
  * Socket option tuning (`SO_REUSEADDR`, `TCP_NODELAY`, receive buffer sizes).
  * Robust socket read/write loops that handle partial reads, OS interrupts (`EINTR`), and abrupt client disconnects (`ECONNRESET`).
* **Design Pattern:**
  ```python
  # Always pack lengths in Big-Endian standard network byte order
  header = struct.pack('!I', len(encoded_payload))
  ```

### Agent Beta: Concurrency & State Specialist
* **Responsibilities:**
  * Client connection dispatching and worker thread lifecycle.
  * Synchronization primitives (`threading.Lock`, `threading.Event`, `threading.Condition`).
  * Preventing deadlocks by minimizing lock scope (only acquire locks during memory access, never during slow I/O or network calls).
* **Design Pattern:**
  ```python
  # CORRECT: Fast in-memory update under lock
  with self._lock:
      self._records[node_id] = record

  # INCORRECT: Never perform network I/O or socket receive while holding a lock
  with self._lock:
      data = sock.recv(1024) # DEADLOCK / CONTENTION RISK
  ```

### Agent Gamma: Metric Extraction & Dashboard Specialist
* **Responsibilities:**
  * System resource extraction via `psutil` or native OS structures.
  * Visual formatting of terminal outputs (CPU bars, status badges, timestamps).
  * Watchdog auditing logic to isolate lagging nodes.

---

## 3. Anti-Hallucination & Context Preservation Rules

To ensure execution continuity across agent prompts and context boundaries:

1. **Verify Before Coding:** Before writing any file, run a directory listing and inspect the existing files to confirm the exact function and class signatures.
2. **One Phase at a Time:** Strictly execute the current phase as defined in `implementation.md`. Do not start Phase 3 until Phase 2 has passed its automated test.
3. **No Phantom Libraries:** Only use the standard Python library (`socket`, `threading`, `struct`, `json`, `time`, `os`, `sys`, `dataclasses`, `typing`) plus explicitly permitted packages: `psutil`, `rich`, and `pytest`.
4. **Self-Contained Regression Tests:** Every feature must include a corresponding test under `tests/`. If code is changed, re-run `pytest` immediately.

---

## 4. Coding Standards & Conventions

### Wire Protocol Framing Specification
All network packets sent over PulseNet sockets must adhere to this binary format:
```
+---------------------------+-----------------------------------------------+
| Field                     | Size / Type                                   |
+---------------------------+-----------------------------------------------+
| Length Header             | 4 Bytes (`!I`, 32-bit unsigned integer)       |
| Payload                   | Length-determined string encoded in UTF-8     |
+---------------------------+-----------------------------------------------+
```

### Protocol Parser Reference Implementation
Ensure all reading logic uses the following accumulating loop pattern:

```python
import struct
import socket
from typing import Optional

def read_exact(sock: socket.socket, num_bytes: int) -> Optional[bytes]:
    """Reads exactly num_bytes from a stream socket, handling chunking."""
    buffer = bytearray()
    while len(buffer) < num_bytes:
        try:
            chunk = sock.recv(num_bytes - len(buffer))
            if not chunk:
                # Socket closed prematurely
                return None
            buffer.extend(chunk)
        except (ConnectionResetError, BrokenPipeError):
            return None
    return bytes(buffer)

def receive_framed_message(sock: socket.socket) -> Optional[str]:
    """Reads length-prefixed frame and returns the UTF-8 payload string."""
    raw_length = read_exact(sock, 4)
    if not raw_length:
        return None
    (length,) = struct.unpack('!I', raw_length)
    raw_payload = read_exact(sock, length)
    if not raw_payload:
        return None
    return raw_payload.decode('utf-8')
```

### Error Handling Protocol
* When an agent socket encounters a connection failure, do **not** raise an unhandled exception or exit the process. Log a warning, sleep for a backoff duration (1s, 2s, 4s... max 10s), and attempt reconnection.
* When the server encounters a client drop (`ConnectionResetError` or EOF), cleanly close the client socket descriptor, notify the `NodeRegistry` if necessary, and terminate the worker thread.

---

## 5. Definition of Done (DoD)

A task is complete only when:
1. The code adheres strictly to PEP 8 standards with full type hints.
2. Unit tests are written and passing with `pytest`.
3. Concurrency has been validated using the multi-client simulation script (`scripts/mock_cluster.py`) with zero thread deadlocks or crashes.