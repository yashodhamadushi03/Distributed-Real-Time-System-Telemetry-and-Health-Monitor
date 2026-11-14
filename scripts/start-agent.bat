@echo off
REM Start a PulseNet telemetry agent that streams this machine's metrics to the server.
REM Usage: scripts\start-agent.bat [SERVER_IP] [NODE_ID]
REM Or:    scripts\start-agent.bat --server 172.20.10.2:9000 [NODE_ID]
setlocal

if /i "%~1"=="-h" goto :usage
if /i "%~1"=="--help" goto :usage

call "%~dp0_check-java.bat"
if errorlevel 1 exit /b 1

set "SERVER_ARG=%~1"
set "NODE_ARG=%~2"
set "PORT_ARG=9000"

if /i "%SERVER_ARG%"=="--server" (
    set "SERVER_ARG=%~2"
    set "NODE_ARG=%~3"
)
if /i "%SERVER_ARG%"=="--host" (
    set "SERVER_ARG=%~2"
    set "NODE_ARG=%~3"
)

if "%SERVER_ARG%"=="" goto :discover
if /i "%SERVER_ARG%"=="--discover" goto :discover_with_id

for /f "tokens=1,2 delims=:" %%a in ("%SERVER_ARG%") do (
    set "SERVER_IP=%%a"
    if not "%%b"=="" set "PORT_ARG=%%b"
)

if "%NODE_ARG%"=="" (
    java -jar "%PULSENET_JAR%" agent --host %SERVER_IP% --port %PORT_ARG%
) else (
    java -jar "%PULSENET_JAR%" agent --host %SERVER_IP% --port %PORT_ARG% --node-id %NODE_ARG%
)
exit /b %ERRORLEVEL%

:discover
java -jar "%PULSENET_JAR%" agent --discover
exit /b %ERRORLEVEL%

:discover_with_id
if "%NODE_ARG%"=="" (
    java -jar "%PULSENET_JAR%" agent --discover
) else (
    java -jar "%PULSENET_JAR%" agent --discover --node-id %NODE_ARG%
)
exit /b %ERRORLEVEL%

:usage
echo Usage: scripts\start-agent.bat [SERVER_IP] [NODE_ID]
echo   No arguments : automatic UDP discovery mode
echo   SERVER_IP    : LAN IP of the laptop running start-server
echo   NODE_ID      : optional node name (default: derived from hostname)
exit /b 1
