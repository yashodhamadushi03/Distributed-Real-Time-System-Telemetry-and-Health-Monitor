# Build pulsenet.jar and assemble a portable dist\pulsenet-demo\ folder for the node laptops.
# Usage: .\scripts\package-demo.ps1 [-SkipTests]
param([switch]$SkipTests)

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Push-Location $repoRoot
try {
    # Locate mvn (same fallback as run_demo.ps1)
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        $chocoMaven = Get-ChildItem "C:\ProgramData\chocolatey\lib\maven" -Filter "mvn.cmd" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($chocoMaven) { $env:Path = "$($chocoMaven.Directory.FullName);$env:Path" }
    }

    $mvnArgs = @("clean", "package")
    if ($SkipTests) { $mvnArgs += "-DskipTests" }

    Write-Host "[1/2] Building pulsenet.jar (mvn $($mvnArgs -join ' '))..." -ForegroundColor Yellow
    & mvn @mvnArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] Maven build failed." -ForegroundColor Red
        exit $LASTEXITCODE
    }

    $dist = Join-Path $repoRoot "dist\pulsenet-demo"
    Write-Host "[2/2] Assembling $dist ..." -ForegroundColor Yellow
    if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
    New-Item -ItemType Directory -Force (Join-Path $dist "scripts") | Out-Null

    Copy-Item "target\pulsenet.jar" $dist
    Copy-Item "DEMO.md" $dist
    Get-ChildItem "scripts" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -in ".sh", ".ps1", ".bat" -and $_.BaseName -ne "package-demo" } |
        Copy-Item -Destination (Join-Path $dist "scripts") -ErrorAction SilentlyContinue

    Write-Host ""
    Write-Host "Done. Copy this folder to each laptop (USB stick / GitHub Release):" -ForegroundColor Green
    Write-Host "  $dist"
    Write-Host "Server laptop:  scripts\start-server.bat   (or .ps1 / .sh)"
    Write-Host "Node laptops:   scripts\start-agent.bat    (auto-discovers host, or pass <SERVER_IP>)"
}
finally {
    Pop-Location
}
