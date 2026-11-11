# Open inbound TCP for the PulseNet server on the Windows server laptop (Laptop A).
# Run in an elevated PowerShell ("Run as Administrator"):
#   powershell -ExecutionPolicy Bypass -File .\scripts\open-firewall.ps1 [-Port 9000]
# Remove the rule after the demo with .\scripts\close-firewall.ps1.
#
# macOS server laptop: System Settings -> Network -> Firewall -> Options, and allow incoming
# connections for "java" (or accept the "allow incoming connections" prompt on first start).
# Linux: e.g. `sudo ufw allow 9000/tcp`.
param(
    [int]$Port = 9000
)

$ruleName = "PulseNet $Port"

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

$existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Firewall rule '$ruleName' already exists; replacing it."
    $existing | Remove-NetFirewallRule
}

New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP -LocalPort $Port `
    -Action Allow -Profile Any | Out-Null
Write-Host "[OK] Inbound TCP $Port allowed (rule '$ruleName', all profiles)." -ForegroundColor Green
Write-Host "     Verify from a node laptop: java -jar pulsenet.jar probe --host <this laptop's IP> --port $Port"
