#!/usr/bin/env bash
# Start the PulseNet aggregator server + live dashboard on TCP port 9000.
# Usage: scripts/start-server.sh [extra server options]
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

pulsenet_check_java
pulsenet_find_jar

exec java -jar "$PULSENET_JAR" server --port 9000 "$@"
