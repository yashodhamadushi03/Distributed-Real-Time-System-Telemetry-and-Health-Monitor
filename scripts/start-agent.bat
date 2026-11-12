@echo off
REM Start a PulseNet telemetry agent that streams this machine's metrics to the server.
REM Usage: scripts\start-agent.bat [SERVER_IP] [NODE_ID]
REM If SERVER_IP is omitted or is --discover, automatic UDP discovery is used.
setlocal

if /i "%~1"=="-h" goto :usage
if /i "%~1"=="--help" goto :usage

call "%~dp0_check-java.bat"
if errorlevel 1 exit /b 1

if "%~1"=="" goto :discover
if /i "%~1"=="--discover" goto :discover_with_id

if "%~2"=="" (
    java -jar "%PULSENET_JAR%" agent --host %1 --port 9000
) else (
    java -jar "%PULSENET_JAR%" agent --host %1 --port 9000 --node-id %2
)
exit /b %ERRORLEVEL%

:discover
java -jar "%PULSENET_JAR%" agent --discover
exit /b %ERRORLEVEL%

:discover_with_id
if "%~2"=="" (
    java -jar "%PULSENET_JAR%" agent --discover
) else (
    java -jar "%PULSENET_JAR%" agent --discover --node-id %2
)
exit /b %ERRORLEVEL%

:usage
echo Usage: scripts\start-agent.bat [SERVER_IP] [NODE_ID]
echo   No arguments : automatic UDP discovery mode
echo   SERVER_IP    : LAN IP of the laptop running start-server
echo   NODE_ID      : optional node name (default: derived from hostname)
exit /b 1
