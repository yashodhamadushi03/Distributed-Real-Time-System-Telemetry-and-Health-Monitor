# Open the inbound ports of the PulseNet server on the Windows server laptop (Laptop A):
#   TCP 9000 telemetry, UDP 9001 automatic discovery, UDP 9002 latency/packet-loss probes.
# Without the UDP ports agents still connect by IP, but discovery fails and packet loss reads 100%.
# Run in an elevated PowerShell ("Run as Administrator"):
#   powershell -ExecutionPolicy Bypass -File .\scripts\open-firewall.ps1 [-Port 9000] [-DiscoveryPort 9001] [-ProbePort 9002]
# Remove the rule after the demo with .\scripts\close-firewall.ps1.
#
# macOS server laptop: System Settings -> Network -> Firewall -> Options, and allow incoming
# connections for "java" (or accept the "allow incoming connections" prompt on first start).
# Linux: e.g. `sudo ufw allow 9000/tcp && sudo ufw allow 9001:9002/udp`.
param(
    [ValidateRange(1, 65535)][int]$Port = 9000,
    [ValidateRange(1, 65535)][int]$DiscoveryPort = 9001,
    [ValidateRange(1, 65535)][int]$ProbePort = 9002
)

$rules = @(
    @{ Name = "PulseNet $Port"; Protocol = "TCP"; Port = $Port },
    @{ Name = "PulseNet UDP $DiscoveryPort"; Protocol = "UDP"; Port = $DiscoveryPort },
    @{ Name = "PulseNet UDP $ProbePort"; Protocol = "UDP"; Port = $ProbePort }
)

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "[ERROR] This script must be run as Administrator." -ForegroundColor Red
    Write-Host "        Right-click PowerShell -> 'Run as Administrator', then re-run it." -ForegroundColor Red
    exit 1
}

Write-Host "Current network profile(s):"
$profiles = @(Get-NetConnectionProfile)
foreach ($p in $profiles) {
    Write-Host ("  {0,-30} {1}" -f $p.Name, $p.NetworkCategory)
}
if ($profiles | Where-Object { $_.NetworkCategory -eq "Public" }) {
    Write-Host "[WARN] A network is set to 'Public'. Windows is strictest on Public networks." -ForegroundColor Yellow
    Write-Host "       Consider: Set-NetConnectionProfile -InterfaceAlias <name> -NetworkCategory Private" -ForegroundColor Yellow
}

foreach ($rule in $rules) {
    $existing = Get-NetFirewallRule -DisplayName $rule.Name -ErrorAction SilentlyContinue
    if ($existing) {
        Write-Host "Firewall rule '$($rule.Name)' already exists; replacing it."
        $existing | Remove-NetFirewallRule
    }
    New-NetFirewallRule -DisplayName $rule.Name -Direction Inbound -Protocol $rule.Protocol -LocalPort $rule.Port `
        -Action Allow -Profile Any | Out-Null
    Write-Host "[OK] Inbound $($rule.Protocol) $($rule.Port) allowed (rule '$($rule.Name)', all profiles)." -ForegroundColor Green
}
Write-Host "     Verify from a node laptop: java -jar pulsenet.jar probe --host <this laptop's IP> --port $Port"
