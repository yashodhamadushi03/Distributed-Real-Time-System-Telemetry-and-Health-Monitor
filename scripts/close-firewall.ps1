# Remove the inbound firewall rules created by open-firewall.ps1 (run as Administrator).
#   powershell -ExecutionPolicy Bypass -File .\scripts\close-firewall.ps1 [-Port 9000] [-DiscoveryPort 9001] [-ProbePort 9002]
param(
    [ValidateRange(1, 65535)][int]$Port = 9000,
    [ValidateRange(1, 65535)][int]$DiscoveryPort = 9001,
    [ValidateRange(1, 65535)][int]$ProbePort = 9002
)

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "[ERROR] This script must be run as Administrator." -ForegroundColor Red
    exit 1
}

foreach ($ruleName in "PulseNet $Port", "PulseNet UDP $DiscoveryPort", "PulseNet UDP $ProbePort") {
    $existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
    if ($existing) {
        $existing | Remove-NetFirewallRule
        Write-Host "[OK] Removed firewall rule '$ruleName'." -ForegroundColor Green
    } else {
        Write-Host "No firewall rule named '$ruleName' found; nothing to do."
    }
}
