#!/usr/bin/env bash
# Start the PulseNet graphical Network Operations Center (NOC) UI.
# Usage: ./scripts/start-ui.sh [extra server options]
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

pulsenet_check_java
pulsenet_find_jar

exec java -jar "$PULSENET_JAR" ui "$@"

