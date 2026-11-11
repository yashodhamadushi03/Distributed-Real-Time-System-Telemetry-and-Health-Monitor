@echo off
REM Shared helper: verifies Java 25+ is on PATH and sets PULSENET_JAR. Called by start-*.bat.
REM Exits with ERRORLEVEL 1 (and a message) on failure.

set "PULSENET_MIN_JAVA=25"

where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 'java' was not found on PATH. Install Java %PULSENET_MIN_JAVA%+ and retry.
    exit /b 1
)

set "PULSENET_JAVA_RAW="
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    if not defined PULSENET_JAVA_RAW set "PULSENET_JAVA_RAW=%%~v"
)
set "PULSENET_JAVA_MAJOR="
set "PULSENET_JAVA_MINOR="
for /f "tokens=1,2 delims=.-+_" %%a in ("%PULSENET_JAVA_RAW%") do (
    set "PULSENET_JAVA_MAJOR=%%a"
    set "PULSENET_JAVA_MINOR=%%b"
)
if "%PULSENET_JAVA_MAJOR%"=="1" set "PULSENET_JAVA_MAJOR=%PULSENET_JAVA_MINOR%"
if not defined PULSENET_JAVA_MAJOR set "PULSENET_JAVA_MAJOR=0"

set /a PULSENET_JAVA_NUM=%PULSENET_JAVA_MAJOR% 2>nul
if %PULSENET_JAVA_NUM% LSS %PULSENET_MIN_JAVA% (
    echo [ERROR] Java %PULSENET_MIN_JAVA%+ is required, but found version '%PULSENET_JAVA_RAW%'.
    echo         Install a newer JDK/JRE or put it first on PATH.
    exit /b 1
)

set "PULSENET_JAR="
if exist "%~dp0..\pulsenet.jar" (
    set "PULSENET_JAR=%~dp0..\pulsenet.jar"
) else if exist "%~dp0..\target\pulsenet.jar" (
    set "PULSENET_JAR=%~dp0..\target\pulsenet.jar"
) else (
    echo [ERROR] pulsenet.jar not found. Run 'mvn clean package' ^(or scripts\package-demo.ps1^) first.
    exit /b 1
)
exit /b 0
