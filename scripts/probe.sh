#!/usr/bin/env bash
# Check that this laptop can reach the PulseNet server (no node is registered).
# Usage: scripts/probe.sh <SERVER_IP> [PORT]
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

if [ $# -lt 1 ] || [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    echo "Usage: $0 <SERVER_IP> [PORT]"
    exit 1
fi

pulsenet_check_java
pulsenet_find_jar

exec java -jar "$PULSENET_JAR" probe --host "$1" --port "${2:-9000}"
