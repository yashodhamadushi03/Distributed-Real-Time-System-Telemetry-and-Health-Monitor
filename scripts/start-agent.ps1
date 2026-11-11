# Start a PulseNet telemetry agent that streams this machine's metrics to the server.
# Usage: .\scripts\start-agent.ps1 <SERVER_IP> [NODE_ID]
param(
    [Parameter(Position = 0)][string]$ServerIp,
    [Parameter(Position = 1)][string]$NodeId
)

if (-not $ServerIp -or $ServerIp -eq "-h" -or $ServerIp -eq "--help") {
    Write-Host "Usage: .\scripts\start-agent.ps1 <SERVER_IP> [NODE_ID]"
    Write-Host "  SERVER_IP  LAN IP of the laptop running start-server (shown in its banner)"
    Write-Host "  NODE_ID    optional node name (default: derived from this machine's hostname)"
    exit 1
}

. (Join-Path $PSScriptRoot "_common.ps1")

Test-PulseNetJava
$jar = Get-PulseNetJar

$javaArgs = @("-jar", $jar, "agent", "--host", $ServerIp, "--port", "9000")
if ($NodeId) {
    $javaArgs += @("--node-id", $NodeId)
}

& java @javaArgs
exit $LASTEXITCODE
