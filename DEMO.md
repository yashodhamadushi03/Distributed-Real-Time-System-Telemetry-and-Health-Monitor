# PulseNet Demo Runbook (Windows, Graphical UI)

A rehearsable script for the live demo: **1 server laptop running the PulseNet window + 1 or 2 agent laptops**, all Windows, on the same network.

```
   [ Laptop A: SERVER + UI window ]  <-- TCP :9000 / UDP :9001 / UDP :9002 --  [ Laptop B: AGENT ]
                                     <-- TCP :9000 / UDP :9001 / UDP :9002 --  [ Laptop C: AGENT (+ optional simulated nodes) ]
                              (all on the same hotspot or LAN)
```

All commands are run from the `pulsenet-demo\` folder (or the repo root on the build machine). Use **Command Prompt** or **PowerShell**; each script has a `.bat` and a `.ps1` version.

| Purpose | Command Prompt | PowerShell |
|---------|----------------|------------|
| Open the UI (Laptop A) | `scripts\start-ui.bat` | `.\scripts\start-ui.ps1` |
| Start an agent (Laptops B, C) | `scripts\start-agent.bat [SERVER_IP] [NODE_ID]` | `.\scripts\start-agent.ps1 [SERVER_IP] [NODE_ID]` |
| Check the server is reachable | `scripts\probe.bat <SERVER_IP> [PORT]` | `.\scripts\probe.ps1 <SERVER_IP> [PORT]` |

> [!TIP]
> If you leave out `SERVER_IP`, the agent finds the server by itself: it broadcasts on UDP port 9001 and connects to TCP 9000. No IP typing needed.

Other commands (`java -jar pulsenet.jar ...`):

* `agent --host <ip> --port 9000 [--node-id <id>]`: connect to a specific server
* `cluster --host <ip> --nodes 5`: simulate extra nodes
* `stress --seconds 20`: load the CPU to trigger an alert

The UI **is** the server. Do not run `start-server` at the same time; both would want port 9000.

---

## 1. Prerequisites

* **Java 25 or newer** on every laptop (`java -version`). The scripts refuse to start on older Java.
* Maven is needed **only** on the build machine.

## 2. Packaging (build machine, once)

```powershell
.\scripts\package-demo.ps1
```

This runs `mvn clean package` (all 113 tests) and builds `dist\pulsenet-demo\`:

```
pulsenet-demo\
  pulsenet.jar     (self-contained, about 16 MB, includes JavaFX)
  DEMO.md          (this runbook)
  scripts\         (start-ui, start-agent, probe, firewall helpers)
```

Copy `dist\pulsenet-demo\` to Laptops B and C (USB stick or shared folder). Laptop A can run from the repository or the same folder.

---

## 3. Network & Firewall Setup

* **Best:** a phone hotspot or travel router that every laptop joins. Campus Wi-Fi (e.g. eduroam) often blocks laptop-to-laptop traffic, so avoid it.
* **Alternative:** Laptop A shares a Windows Mobile Hotspot (Settings → Network & internet → Mobile hotspot). Its IP is usually `192.168.137.1`.
* **Laptop A:**
  1. Set the network profile to **Private** (Settings → Network & internet → Wi-Fi → your network → Network profile type).
  2. Open the ports by running `scripts\open-firewall.ps1` as Administrator, or manually in an Administrator PowerShell:
     ```powershell
     New-NetFirewallRule -DisplayName "PulseNet TCP 9000" -Direction Inbound -Protocol TCP -LocalPort 9000 -Action Allow
     New-NetFirewallRule -DisplayName "PulseNet UDP 9001" -Direction Inbound -Protocol UDP -LocalPort 9001 -Action Allow
     New-NetFirewallRule -DisplayName "PulseNet UDP 9002" -Direction Inbound -Protocol UDP -LocalPort 9002 -Action Allow
     ```
  3. If Windows asks whether to let Java through the firewall, allow it on **Private networks**.
* **All laptops:** turn off sleep on lid close and Wi-Fi power saving during the presentation.

After the demo, `scripts\close-firewall.ps1` removes the rules.

---

## 4. Pre-Flight Checklist (about 5 minutes before)

1. **Laptop A:** run `scripts\start-ui.bat`. The PulseNet window opens on the **Overview** page, and the bottom of the sidebar shows the ports it is listening on. Find Laptop A's IP with `ipconfig` (the IPv4 address of the Wi-Fi adapter).
2. **Laptop B:** run `scripts\probe.bat <A_IP> 9000`. It must print `OK: server reachable`.
3. **Laptops B and C:** run `scripts\start-agent.bat` (no arguments). Both machines should appear in the **Nodes** page as healthy within 1 to 2 seconds.
4. **Reconnect test:** press `Ctrl+C` in B's agent window. After about 5 seconds B shows as **Offline** and a "heartbeat lost" line appears on the **Events** page. Start the agent again and check B comes back on the **same row** with a "recovered" event.
5. Close the UI window and reopen it for a clean start.

---

## 5. Demo Script

The window has four pages in the left sidebar:

| Page | Shows |
|------|-------|
| **Overview** | Online and offline counts, average CPU, memory and latency, plus two live charts |
| **Nodes** | One row per machine: CPU, memory, disk, battery, uptime. Click a row for that node's details and its own chart |
| **Network** | Latency, jitter, packet loss, internet status and reconnect count per node |
| **Events** | Timeline of connects, drops, recoveries and threshold alerts |

| Scene | Action | What the audience sees |
|-------|--------|------------------------|
| 1. Start | A: `scripts\start-ui.bat` | The window opens with no nodes. The sidebar shows the ports it is listening on (TCP 9000, UDP 9001 and 9002) |
| 2. Auto-discovery join | B, then C: `scripts\start-agent.bat` | Each agent finds the server and connects. Rows appear on **Nodes**, the Online count goes up, and the **Events** page logs `Node connected` |
| 3. Scale *(optional)* | C: `java -jar pulsenet.jar cluster --host <A_IP> --nodes 5 --prefix c-sim- --no-chaos` | Five more rows appear. Each node has its own server thread, so they all update at once |
| 4. CPU alert | B: `java -jar pulsenet.jar stress --seconds 20` | B's CPU turns amber above 85%. **Events** shows one `[WARN]` line, then `[OK]` when the load ends |
| 5. Connection loss | C: turn Wi-Fi **off** | After about 5 seconds C goes **Offline** and **Events** shows `Heartbeat lost` |
| 6. Recovery | C: turn Wi-Fi **on** | The agent retries (1 s, 2 s, 4 s...), reconnects, and C returns on the **same row** with a `Node recovered` event |
| 7. Server restart | A: close the window, wait 5 s, run `scripts\start-ui.bat` again | The agents log `Connection lost` and retry. When the window is back, they reconnect without being touched |

For the **Network** page, point out latency, jitter and packet loss per node. For a closer look at one machine, click its row on **Nodes**.

Reference screenshots (1 agent plus 4 simulated nodes) are in [docs/screenshots/](docs/screenshots/).

---

## 6. Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `No PulseNet server found after 3 attempts` | Firewall blocking UDP 9001, or the laptops are on different networks | Open the ports (section 3) and join the same hotspot |
| `probe` prints `TIMEOUT` | Firewall on Laptop A, or wrong IP | Check the Private profile and firewall rules, and confirm the IP |
| `probe` prints `REFUSED` | The UI is not running on Laptop A | Run `scripts\start-ui.bat` |
| UI shows "Could not open the network ports" | Another PulseNet server or UI is still running | Close it (check Task Manager for `java.exe`) and start again |
| Two rows for one laptop | Both laptops have the same hostname | Give each agent a name: `scripts\start-agent.bat <A_IP> node-b` |

---

## 7. Final Checks (physical hardware)

* [ ] Agent on Laptop B finds Laptop A without typing an IP.
* [ ] Live metrics show on **Nodes** within 2 seconds of the agent starting.
* [ ] Turning Wi-Fi off and on brings Laptop B back automatically.
* [ ] Restarting the UI reconnects all agents without touching them.
* [ ] All 113 automated tests pass (`mvn test`).
