# Start a PulseNet telemetry agent that streams this machine's metrics to the server.
# Usage: .\scripts\start-agent.ps1 [SERVER_IP] [NODE_ID]
# If SERVER_IP is omitted or is "-Discover" / "--discover", automatic UDP discovery is used.
param(
    [Parameter(Position = 0)][string]$ServerIp,
    [Parameter(Position = 1)][string]$NodeId,
    [switch]$Discover
)

if ($ServerIp -eq "-h" -or $ServerIp -eq "--help") {
    Write-Host "Usage: .\scripts\start-agent.ps1 [SERVER_IP] [NODE_ID] [-Discover]"
    Write-Host "  No arguments: automatic UDP discovery mode"
    Write-Host "  SERVER_IP   : LAN IP of the laptop running start-server"
    Write-Host "  NODE_ID     : optional node name (default: derived from hostname)"
    exit 1
}

. (Join-Path $PSScriptRoot "_common.ps1")

Test-PulseNetJava
$jar = Get-PulseNetJar

if (-not $ServerIp -or $ServerIp -eq "--discover" -or $ServerIp -eq "-Discover" -or $Discover) {
    $javaArgs = @("-jar", $jar, "agent", "--discover")
    if ($NodeId -and $NodeId -ne "--discover") {
        $javaArgs += @("--node-id", $NodeId)
    }
} else {
    $javaArgs = @("-jar", $jar, "agent", "--host", $ServerIp, "--port", "9000")
    if ($NodeId) {
        $javaArgs += @("--node-id", $NodeId)
    }
}

& java @javaArgs
exit $LASTEXITCODE
