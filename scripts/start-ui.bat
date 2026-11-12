@echo off
REM Start the PulseNet graphical Network Operations Center (NOC) UI.
REM Usage: scripts\start-ui.bat [extra server options]
setlocal
call "%~dp0_check-java.bat"
if errorlevel 1 exit /b 1

java -jar "%PULSENET_JAR%" ui %*
exit /b %ERRORLEVEL%

