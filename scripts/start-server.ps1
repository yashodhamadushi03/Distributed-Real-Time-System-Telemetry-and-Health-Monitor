# Start the PulseNet aggregator server + live dashboard on TCP port 9000.
# Usage: .\scripts\start-server.ps1 [extra server options]
. (Join-Path $PSScriptRoot "_common.ps1")

Test-PulseNetJava
$jar = Get-PulseNetJar

& java -jar $jar server --port 9000 @args
exit $LASTEXITCODE
