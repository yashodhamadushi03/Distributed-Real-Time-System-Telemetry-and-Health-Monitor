@echo off
REM Start the PulseNet aggregator server + live dashboard on TCP port 9000.
REM Usage: scripts\start-server.bat [extra server options]
setlocal
call "%~dp0_check-java.bat"
if errorlevel 1 exit /b 1

java -jar "%PULSENET_JAR%" server --port 9000 %*
exit /b %ERRORLEVEL%
