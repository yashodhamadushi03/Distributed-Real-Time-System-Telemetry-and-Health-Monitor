# Remove the inbound firewall rule created by open-firewall.ps1 (run as Administrator).
#   powershell -ExecutionPolicy Bypass -File .\scripts\close-firewall.ps1 [-Port 9000]
param(
    [int]$Port = 9000
)

$ruleName = "PulseNet $Port"

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "[ERROR] This script must be run as Administrator." -ForegroundColor Red
    exit 1
}

$existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if ($existing) {
    $existing | Remove-NetFirewallRule
    Write-Host "[OK] Removed firewall rule '$ruleName'." -ForegroundColor Green
} else {
    Write-Host "No firewall rule named '$ruleName' found; nothing to do."
}
