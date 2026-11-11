# PulseNet Multi-Laptop Demo Runbook

A rehearsable script for the live demo: **1 server laptop + 2 agent laptops** on the same network.

```
   [ Laptop A: SERVER ]  <-- TCP :9000 --  [ Laptop B: NODE (agent) ]
   server + dashboard    <-- TCP :9000 --  [ Laptop C: NODE (agent [+ optional cluster]) ]
               (all three on the same hotspot / LAN)
```

All commands below are run from the `pulsenet-demo/` folder (or the repo root on the build machine).
Each script ships in three flavours. Pick the one for your shell:

| Windows (cmd) | Windows (PowerShell) | macOS / Linux |
|---------------|----------------------|---------------|
| `scripts\start-server.bat` | `.\scripts\start-server.ps1` | `scripts/start-server.sh` |
| `scripts\start-agent.bat <A_IP> [NODE_ID]` | `.\scripts\start-agent.ps1 <A_IP> [NODE_ID]` | `scripts/start-agent.sh <A_IP> [NODE_ID]` |
| `scripts\probe.bat <A_IP> [PORT]` | `.\scripts\probe.ps1 <A_IP> [PORT]` | `scripts/probe.sh <A_IP> [PORT]` |

Anything else is a direct `java -jar pulsenet.jar <subcommand>` call, which works identically on every OS.
(`java -jar pulsenet.jar --help` lists the subcommands: `server`, `agent`, `cluster`, `probe`, `stress`.)

---

## 1. Prerequisites

* **Java 25 or newer** on all three laptops: `java -version`. The scripts refuse to start on older Java.
* Maven is needed **only** on the build machine.

## 2. Packaging (build machine, once)

```bash
# Windows PowerShell
.\scripts\package-demo.ps1
# macOS / Linux
scripts/package-demo.sh
```

This runs `mvn clean package` (all tests) and assembles `dist/pulsenet-demo/`:

```
pulsenet-demo/
  pulsenet.jar        (self-contained, ~2.3 MB)
  DEMO.md             (this runbook)
  scripts/            (start-server, start-agent, probe, open/close-firewall, helpers)
```

Copy `dist/pulsenet-demo/` to Laptops B and C (USB stick, shared drive, or a GitHub Release).
Laptop A can run straight from the repo or from the same folder.

## 3. Network Setup (do this first: it is the #1 risk)

* **Preferred: a phone hotspot or travel router** that all three laptops join.
  Campus or enterprise Wi-Fi (e.g. eduroam) often enables **client isolation**, which silently
  blocks laptop-to-laptop TCP. `probe` then reports `TIMEOUT`.
* **Alternative:** Laptop A creates a Windows Mobile Hotspot (Settings → Network & internet →
  Mobile hotspot). Its IP is then usually `192.168.137.1`.
* **Laptop A (Windows server):**
  1. Set the network profile to **Private** (Settings → Network → the Wi-Fi network → Network profile type).
  2. Open TCP 9000 in an **Administrator** PowerShell:
     ```powershell
     powershell -ExecutionPolicy Bypass -File .\scripts\open-firewall.ps1        # optional: -Port 9000
     ```
     It prints the current network profile, warns if it is `Public`, and creates (or replaces) the
     inbound rule `PulseNet 9000`.
  3. If Windows shows a "Windows Defender Firewall has blocked some features of Java" prompt, allow it on **Private networks**.
  4. After the demo, remove the rule: `powershell -ExecutionPolicy Bypass -File .\scripts\close-firewall.ps1`.
* **Laptop A on macOS instead:** System Settings → Network → Firewall → Options → allow incoming
  connections for `java` (or click *Allow* on the first-start prompt). Linux: `sudo ufw allow 9000/tcp`.
* **All laptops:** disable sleep on lid close and Wi-Fi power saving for the duration of the demo,
  and plug in the chargers.

## 4. Pre-Flight Checklist (≈5 min before presenting)

1. **A:** `start-server` → note the IP in the startup banner, e.g.
   `Agents should connect to: 192.168.137.1:9000 (Wi-Fi)`. Write it on the board.
   (The banner stays up for 3 s, then the dashboard takes over. Full logs go to `pulsenet-server.log`.)
2. **B and C:** `probe <A_IP>` → must print `OK: server reachable at <A_IP>:9000`.
   A probe never registers a node, so the dashboard stays clean.
3. **B and C:** `start-agent <A_IP>` → both hostnames appear **HEALTHY** on A's dashboard within ~2 s.
   The node ID defaults to the lower-cased hostname, so it is stable across restarts.
4. Stop the agents (Ctrl+C). The dashboard marks them **OFFLINE** after 5 s, confirming the watchdog
   works on this network. Restart them and confirm `[REC]  Node recovered` on the **same rows**.
5. Ctrl+C the server and start it again, so the audience sees a clean dashboard.

## 5. Demo Scene Script

| Scene | Action | What the audience sees on Laptop A |
|-------|--------|-------------------------------------|
| 1. Boot | A: `start-server` | Banner with the LAN IP, then an empty dashboard waiting for agents |
| 2. Join | B, then C: `start-agent <A_IP>` | Two rows with **real** CPU/RAM/Disk/PIDs and LAN `IP:PORT`; `[SYS]  Node connected` events |
| 3. Scale *(optional)* | C: `java -jar pulsenet.jar cluster --host <A_IP> --nodes 5 --prefix c-sim- --no-chaos` | 7 nodes (`c-sim-01..05` + the 2 laptops): the thread-per-client server handles concurrent streams |
| 4. Load | B, second terminal: `java -jar pulsenet.jar stress --seconds 20` | B's CPU bar fills and turns yellow **WARNING**; exactly **one** `[WARN] <B> crossed CPU threshold` event; after the stress ends, one `[OK]   <B> back below thresholds` and HEALTHY again |
| 5. Failure | C: turn Wi-Fi **off** (or Ctrl+C the agent) | Within ~5 s C turns gray **OFFLINE (Ns)** with a red `[LOST] Heartbeat lost` event |
| 6. Recovery | C: turn Wi-Fi back **on** (or rerun `start-agent <A_IP>`) | The agent's backoff (1 s → 10 s max) reconnects; green `[REC]  Node recovered` on the **same row**, no duplicate |
| 7. Server restart *(optional)* | A: Ctrl+C, then `start-server` again | Both nodes reappear automatically within ≤10 s with no action on B/C |

**Notes for scenes 5–6 (Wi-Fi off):**
* When Wi-Fi drops, no FIN/RST reaches the server, so C's old socket on A stays open but silent.
  The server's **read timeout** (default 15 s; `start-server --read-timeout <sec>` to change)
  reaps it with `[SYS]  Connection timed out: <C>`.
* If C reconnects **before** the old socket has timed out, the new connection takes over the node ID
  (`[SYS]  Node reconnected on new connection` while still online, or `[REC]` if already OFFLINE).
  The old socket then logs `[SYS]  Superseded stale connection for <C>` instead of a misleading
  "Client disconnected". Either way there is one row for C.
* CPU stress is edge-triggered: a sustained 95% CPU produces one `[WARN]`, not one per second, so the
  `[LOST]`/`[REC]` events stay visible in the 6-line event log.

## 6. Troubleshooting (`probe <A_IP>` on B or C)

| Probe output | Meaning | Fix |
|--------------|---------|-----|
| `OK: server reachable at …` | TCP works | If the agent still doesn't appear, check `start-agent` used the same IP |
| `REFUSED: host reachable but nothing listening on 9000 …` | A is up, server isn't running (or wrong port) | Start the server on A; check the port matches |
| `TIMEOUT: no response from …` | Packets are dropped | Firewall on A (`open-firewall.ps1`, Private profile), wrong IP, or Wi-Fi **client isolation** → switch to a hotspot |
| `UNREACHABLE: …` | No route to that address | B/C not on the same network as A; re-check the Wi-Fi/hotspot and IP |
| `UNKNOWN HOST: cannot resolve …` | Name typo / DNS | Use the numeric IP from A's banner |

Other symptoms:
* **No LAN IP in the banner** (`WARNING: no LAN IPv4 address found`): A isn't connected to Wi-Fi/Ethernet.
* **Wrong IP in the banner** (e.g. a VPN): the banner lists every candidate under `also reachable at:`. Probe each one.
* **Two rows for one laptop:** the laptops have identical hostnames. Pass an explicit ID: `start-agent <A_IP> node-b`.

## 7. Fallback Plan

* If the venue network blocks peer traffic and no hotspot is available, run the single-machine
  demo on Laptop A: `run_demo.bat` / `.\run_demo.ps1` / `./run_demo.sh` (server + MockCluster with chaos injection).
* Keep `pulsenet-server.log` open in a second window on A to explain events in detail if asked.

## 8. Exit Verification Gate (manual, on real hardware)

* [ ] `probe` returns OK from B and C to A.
* [ ] Scenes 1–6 complete using only the commands in this script.
* [ ] Scene 6 shows `[REC]` on the **same** row (no duplicate node row).
* [ ] No log lines tear the dashboard during any scene.
* [ ] `mvn test` still passes on the development machine.
