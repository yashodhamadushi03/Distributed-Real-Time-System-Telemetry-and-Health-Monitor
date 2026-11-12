# PulseNet Multi-Laptop Demo Runbook

A rehearsable script for the live demo: **1 server laptop + 1 or 2 agent laptops** on the same network.

```
   [ Laptop A: HOST / SERVER ]   <-- TCP :9000 / UDP :9001 --  [ Laptop B: AGENT NODE ]
   ServerMain + Live Dashboard   <-- TCP :9000 / UDP :9001 --  [ Laptop C: AGENT NODE (+ optional MockCluster) ]
                               (all on the same hotspot or LAN)
```

All commands below are run from the `pulsenet-demo/` folder (or the repo root on the build machine).
Each script ships in three flavours:

| Windows (cmd) | Windows (PowerShell) | macOS / Linux |
|---------------|----------------------|---------------|
| `scripts\start-server.bat` | `.\scripts\start-server.ps1` | `scripts/start-server.sh` |
| `scripts\start-agent.bat [SERVER_IP] [NODE_ID]` | `.\scripts\start-agent.ps1 [SERVER_IP] [NODE_ID]` | `scripts/start-agent.sh [SERVER_IP] [NODE_ID]` |
| `scripts\probe.bat <SERVER_IP> [PORT]` | `.\scripts\probe.ps1 <SERVER_IP> [PORT]` | `scripts/probe.sh <SERVER_IP> [PORT]` |

> [!TIP]
> When `SERVER_IP` is omitted, the agent starts in **UDP Automatic Discovery Mode**, broadcasts on port 9001, locates the server on Laptop A, and connects to TCP 9000 automatically without typing any IP address!

Anything else is a direct `java -jar pulsenet.jar <subcommand>` call:
* `java -jar pulsenet.jar server [--port 9000] [--udp-port 9001] [--probe-port 9002]`
* `java -jar pulsenet.jar agent --discover [--node-id <id>]`
* `java -jar pulsenet.jar agent --host <ip> --port 9000 [--node-id <id>]`
* `java -jar pulsenet.jar cluster --host <ip> --nodes 5`
* `java -jar pulsenet.jar probe --host <ip>`
* `java -jar pulsenet.jar stress --seconds 20`

---

## 1. Prerequisites

* **Java 25 or newer** on all laptops: `java -version`. The scripts refuse to start on older Java.
* Maven is needed **only** on the build machine.

## 2. Packaging (build machine, once)

```bash
# Windows PowerShell
.\scripts\package-demo.ps1
# macOS / Linux
scripts/package-demo.sh
```

This runs `mvn clean package` (all 71 unit and integration tests) and assembles `dist/pulsenet-demo/`:

```
pulsenet-demo/
  pulsenet.jar        (self-contained shaded JAR, ~2.3 MB)
  DEMO.md             (this runbook)
  scripts/            (start-server, start-agent, probe, helpers)
```

Copy `dist/pulsenet-demo/` to Laptops B and C (via USB stick, shared folder, or GitHub Release).
Laptop A can run straight from the repository or from the same folder.

---

## 3. Network & Firewall Setup

* **Preferred:** A phone hotspot or travel router that all laptops join.
  * *Notice:* Public or campus Wi-Fi (such as eduroam) often enables **client isolation**, which drops peer-to-peer TCP and UDP traffic.
* **Alternative:** Laptop A creates a Windows Mobile Hotspot (Settings $\to$ Network & internet $\to$ Mobile hotspot). Its IP is then typically `192.168.137.1`.
* **Laptop A (Windows server):**
  1. Set network profile to **Private** (Settings $\to$ Network $\to$ Wi-Fi $\to$ Network profile type).
  2. Open inbound TCP 9000, UDP 9001, and UDP 9002:
     ```powershell
     New-NetFirewallRule -DisplayName "PulseNet TCP 9000" -Direction Inbound -Protocol TCP -LocalPort 9000 -Action Allow
     New-NetFirewallRule -DisplayName "PulseNet UDP 9001" -Direction Inbound -Protocol UDP -LocalPort 9001 -Action Allow
     New-NetFirewallRule -DisplayName "PulseNet UDP 9002" -Direction Inbound -Protocol UDP -LocalPort 9002 -Action Allow
     ```
  3. If Windows displays a "Windows Defender Firewall has blocked some features of Java" prompt, allow access on **Private networks**.
* **Laptop A on macOS:** System Settings $\to$ Network $\to$ Firewall $\to$ Options $\to$ allow incoming connections for `java`. Linux: `sudo ufw allow 9000/tcp && sudo ufw allow 9001/udp && sudo ufw allow 9002/udp`.
* **All laptops:** Disable sleep on lid close and power-saving on Wi-Fi adapters during the presentation.

---

## 4. Pre-Flight Checklist (≈5 min before presenting)

1. **A (Server):** Run `scripts\start-server.bat`. Confirm startup banner:
   ```
   ==============================================================================================================
    PulseNet Aggregator Server listening on 0.0.0.0:9000 (TCP)
    Automatic Node Discovery active on UDP port 9001
   ==============================================================================================================
    Agents can auto-discover or connect to: 192.168.137.1:9000 (Wi-Fi)
   ```
2. **B (Agent):** Run `scripts\probe.bat 192.168.137.1 9000`. Must print `OK: server reachable`.
3. **B and C (Agents):** Run `scripts\start-agent.bat` (no arguments $\to$ automatic discovery). Confirm both hostnames appear as **HEALTHY** on Laptop A's live dashboard within 1–2 seconds.
4. **Failure & Reconnect test:** Stop agent on B (`Ctrl+C`). Dashboard marks node **OFFLINE** after 5 s. Start agent again; confirm green `[REC] Node recovered` on the **same row**.
5. Stop server (`Ctrl+C`) and start again for a clean presentation dashboard.

---

## 5. Demo Scene Script

| Scene | Action | What the audience sees on Laptop A |
|-------|--------|-------------------------------------|
| 1. Boot | A: `scripts\start-server.bat` | Banner displaying LAN IP and UDP discovery port 9001; live ANSI dashboard initializes |
| 2. Auto-Discovery Join | B, then C: `scripts\start-agent.bat` (no IP typed!) | Agents broadcast UDP requests on port 9001, discover Laptop A, and connect. Two rows appear on Laptop A with live CPU/RAM/Disk gauges; `[SYS] Node connected` |
| 3. Scale *(optional)* | C: `java -jar pulsenet.jar cluster --host <A_IP> --nodes 5 --prefix c-sim- --no-chaos` | 7 active nodes streaming concurrently; shows multithreaded thread-per-client scalability |
| 4. CPU Threshold Alert | B: `java -jar pulsenet.jar stress --seconds 20` | B's CPU bar turns yellow **WARNING** (>85%); exactly one edge-triggered `[WARN] <B> crossed CPU threshold` event; returns to HEALTHY with `[OK]` after stress |
| 5. Connection Loss | C: Turn Wi-Fi **off** (or pull cable) | Within ~5 s, C turns gray **OFFLINE (5s)** with red event `[LOST] Heartbeat lost for <C>` |
| 6. Automatic Recovery | C: Turn Wi-Fi back **on** | Agent detects network return, executes exponential backoff reconnect, re-registers with same logical ID; green `[REC] Node recovered` on the **same row** (no duplicate) |
| 7. Host Crash & Recovery | A: Press `Ctrl+C` on server, wait 5s, restart server | Both agent nodes log `Connection lost. Preparing to reconnect...`, retry with exponential backoff (1s $\to$ 2s $\to$ 4s), reconnect automatically upon server reboot, and restore dashboard |

---

## 6. Troubleshooting

| Symptom | Cause | Solution |
|---------|-------|----------|
| `No PulseNet server found after 3 attempts` | Firewall on Laptop A blocked UDP 9001, or client isolation | Ensure Laptop A has allowed UDP 9001. Ensure both laptops are on the same hotspot. |
| `probe` reports `TIMEOUT` | Firewall on Laptop A or wrong IP | Run `probe.bat <A_IP>` to test TCP port 9000. Check Windows Defender Firewall profile is Private. |
| `REFUSED: host reachable but nothing listening` | Server is not currently running | Run `start-server` on Laptop A. |
| Agent connects but displays duplicate row | Hostnames identical on both laptops | Run agent with explicit ID: `start-agent.bat --discover node-b` |

---

## 7. Exit Verification Gate (Physical Hardware)

* [ ] UDP Discovery: Agent on Laptop B discovers Laptop A without typing an IP.
* [ ] TCP Telemetry: Live metrics appear on Laptop A within 2 seconds of agent discovery.
* [ ] Reconnection: Turning Wi-Fi off and on reconnects Laptop B automatically with `[REC]`.
* [ ] Host Reboot: Restarting the server reconnects all agents without manual agent intervention.
* [ ] Test Suite: All 71 automated tests pass (`mvn test`).
