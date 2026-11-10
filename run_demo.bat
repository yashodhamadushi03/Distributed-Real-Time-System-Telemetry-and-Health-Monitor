@echo off
setlocal EnableDelayedExpansion

echo =========================================================================
echo  PulseNet: Distributed Real-Time Telemetry Monitor - Demo Runner
echo =========================================================================

REM Detect Maven if not in current shell session PATH
where mvn >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    for /d %%D in (C:\ProgramData\chocolatey\lib\maven\apache-maven-*) do (
        if exist "%%D\bin\mvn.cmd" (
            set "PATH=%%D\bin;!PATH!"
        )
    )
)

echo [1/3] Compiling and packaging PulseNet...
call mvn package -DskipTests
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Maven build failed.
    exit /b %ERRORLEVEL%
)

echo [2/3] Spawning Central Aggregator Server on Port 9000...
start "PulseNet Aggregator Server" cmd /k "java -cp "target/pulsenet-1.0.0-SNAPSHOT.jar;target/lib/*" com.pulsenet.server.ServerMain --port 9000"

REM Wait 3 seconds for server socket bind
timeout /t 3 /nobreak >nul

echo [3/3] Spawning Simulated Cluster with 5 Agent Nodes...
start "PulseNet Mock Cluster" cmd /k "java -cp "target/pulsenet-1.0.0-SNAPSHOT.jar;target/lib/*" com.pulsenet.simulation.MockCluster --port 9000 --nodes 5"

echo.
echo =========================================================================
echo  PulseNet is now running!
echo  - Window 1: Live ANSI Terminal Dashboard (Aggregator Server)
echo  - Window 2: MockCluster Simulator streaming 5 nodes with synthetic load
echo    (Notice: agent-03 will intentionally drop after 10s to demonstrate
echo     the Watchdog flagging it OFFLINE on the dashboard!)
echo =========================================================================
