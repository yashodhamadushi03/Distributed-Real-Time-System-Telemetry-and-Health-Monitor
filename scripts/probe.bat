@echo off
REM Check that this laptop can reach the PulseNet server (no node is registered).
REM Usage: scripts\probe.bat <SERVER_IP> [PORT]
setlocal

if "%~1"=="" goto :usage
if /i "%~1"=="-h" goto :usage
if /i "%~1"=="--help" goto :usage

call "%~dp0_check-java.bat"
if errorlevel 1 exit /b 1

set "PROBE_PORT=%~2"
if "%PROBE_PORT%"=="" set "PROBE_PORT=9000"

java -jar "%PULSENET_JAR%" probe --host %1 --port %PROBE_PORT%
exit /b %ERRORLEVEL%

:usage
echo Usage: scripts\probe.bat ^<SERVER_IP^> [PORT]
exit /b 1
