#!/usr/bin/env bash
# Start the PulseNet graphical Network Operations Center (NOC) UI.
# Usage: ./scripts/start-ui.sh [extra server options]
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_common.sh
source "$SCRIPT_DIR/_common.sh"

check_java
JAR="$(find_jar)"

exec java -jar "$JAR" ui "$@"

