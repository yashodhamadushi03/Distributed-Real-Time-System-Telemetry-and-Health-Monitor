# Check that this laptop can reach the PulseNet server (no node is registered).
# Usage: .\scripts\probe.ps1 <SERVER_IP> [PORT]
param(
    [Parameter(Position = 0)][string]$ServerIp,
    [Parameter(Position = 1)][int]$Port = 9000
)

if (-not $ServerIp -or $ServerIp -eq "-h" -or $ServerIp -eq "--help") {
    Write-Host "Usage: .\scripts\probe.ps1 <SERVER_IP> [PORT]"
    exit 1
}

. (Join-Path $PSScriptRoot "_common.ps1")

Test-PulseNetJava
$jar = Get-PulseNetJar

& java -jar $jar probe --host $ServerIp --port $Port
exit $LASTEXITCODE
