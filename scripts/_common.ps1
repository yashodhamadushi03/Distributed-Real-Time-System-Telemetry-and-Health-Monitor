# Shared helpers for PulseNet PowerShell launch scripts (dot-sourced, not executed).

$PulseNetMinJava = 25

function Test-PulseNetJava {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        Write-Host "[ERROR] 'java' was not found on PATH. Install Java $PulseNetMinJava+ and retry." -ForegroundColor Red
        exit 1
    }
    # 'java -version' writes to stderr; run it through cmd so PowerShell 5.1 does not wrap it as an error.
    $line = (cmd /c "java -version 2>&1" | Select-Object -First 1) -as [string]
    $raw = if ($line -match 'version "([^"]+)"') { $Matches[1] } else { "" }
    $parts = $raw.Split('.')
    $majorText = if ($parts[0] -eq '1' -and $parts.Length -gt 1) { $parts[1] } else { $parts[0] }
    $majorText = ($majorText -replace '[^0-9].*$', '')
    $major = 0
    if (-not [int]::TryParse($majorText, [ref]$major) -or $major -lt $PulseNetMinJava) {
        Write-Host "[ERROR] Java $PulseNetMinJava+ is required, but found version '$raw'." -ForegroundColor Red
        Write-Host "        Install a newer JDK/JRE or put it first on PATH." -ForegroundColor Red
        exit 1
    }
}

function Get-PulseNetJar {
    $candidates = @(
        (Join-Path $PSScriptRoot "..\pulsenet.jar"),
        (Join-Path $PSScriptRoot "..\target\pulsenet.jar")
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate -PathType Leaf) {
            return (Resolve-Path $candidate).Path
        }
    }
    Write-Host "[ERROR] pulsenet.jar not found. Run 'mvn clean package' (or scripts\package-demo.ps1) first." -ForegroundColor Red
    exit 1
}
