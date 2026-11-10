# PulseNet: Distributed Real-Time Telemetry Monitor - PowerShell Demo Runner

Write-Host "=========================================================================" -ForegroundColor Cyan
Write-Host " PulseNet: Distributed Real-Time Telemetry Monitor - Demo Runner" -ForegroundColor Cyan
Write-Host "=========================================================================" -ForegroundColor Cyan

# Refresh environment PATH in current PowerShell session
$env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path", "User")

# Locate mvn
$mvnCommand = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvnCommand) {
    $chocoMaven = Get-ChildItem "C:\ProgramData\chocolatey\lib\maven" -Filter "mvn.cmd" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($chocoMaven) {
        $mvnDir = $chocoMaven.Directory.FullName
        $env:Path = "$mvnDir;$env:Path"
    }
}

Write-Host "`n[1/3] Compiling and packaging PulseNet..." -ForegroundColor Yellow
mvn package -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Maven build failed." -ForegroundColor Red
    exit $LASTEXITCODE
}

$jarPath = "target/pulsenet-1.0.0-SNAPSHOT.jar"
$cp = "$jarPath;target/lib/*"

Write-Host "`n[2/3] Spawning Central Aggregator Server on Port 9000..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoExit", "-Command", "Write-Host 'Starting PulseNet Aggregator Server...' -ForegroundColor Cyan; java -cp '$cp' com.pulsenet.server.ServerMain --port 9000"

Start-Sleep -Seconds 3

Write-Host "`n[3/3] Spawning Simulated Cluster with 5 Agent Nodes (Chaos Enabled)..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoExit", "-Command", "Write-Host 'Starting PulseNet MockCluster...' -ForegroundColor Yellow; java -cp '$cp' com.pulsenet.simulation.MockCluster --port 9000 --nodes 5"

Write-Host "`n=========================================================================" -ForegroundColor Green
Write-Host " PulseNet is now running!" -ForegroundColor Green
Write-Host " - Window 1: Live ANSI Terminal Dashboard (Aggregator Server)" -ForegroundColor White
Write-Host " - Window 2: MockCluster Simulator streaming 5 nodes with synthetic load" -ForegroundColor White
Write-Host "   (Watch: agent-03 will intentionally drop after 10s to demonstrate" -ForegroundColor Gray
Write-Host "    the Watchdog automatically detecting the drop and marking it OFFLINE!)" -ForegroundColor Gray
Write-Host "=========================================================================" -ForegroundColor Green
