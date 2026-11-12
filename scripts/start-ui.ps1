# Start the PulseNet graphical Network Operations Center (NOC) UI.
# Usage: .\scripts\start-ui.ps1 [extra server options]
. (Join-Path $PSScriptRoot "_common.ps1")

Test-PulseNetJava
$jar = Get-PulseNetJar

& java -jar $jar ui @args
exit $LASTEXITCODE

