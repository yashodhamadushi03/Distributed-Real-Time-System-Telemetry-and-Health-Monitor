@echo off
REM Start a PulseNet telemetry agent that streams this machine's metrics to the server.
REM Usage: scripts\start-agent.bat <SERVER_IP> [NODE_ID]
setlocal

if "%~1"=="" goto :usage
if /i "%~1"=="-h" goto :usage
if /i "%~1"=="--help" goto :usage

call "%~dp0_check-java.bat"
if errorlevel 1 exit /b 1

if "%~2"=="" (
    java -jar "%PULSENET_JAR%" agent --host %1 --port 9000
) else (
    java -jar "%PULSENET_JAR%" agent --host %1 --port 9000 --node-id %2
)
exit /b %ERRORLEVEL%

:usage
echo Usage: scripts\start-agent.bat ^<SERVER_IP^> [NODE_ID]
echo   SERVER_IP  LAN IP of the laptop running start-server (shown in its banner)
echo   NODE_ID    optional node name (default: derived from this machine's hostname)
exit /b 1
