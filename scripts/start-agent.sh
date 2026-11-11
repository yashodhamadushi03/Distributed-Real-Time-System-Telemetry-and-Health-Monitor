#!/usr/bin/env bash
# Start a PulseNet telemetry agent that streams this machine's metrics to the server.
# Usage: scripts/start-agent.sh <SERVER_IP> [NODE_ID]
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

if [ $# -lt 1 ] || [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    echo "Usage: $0 <SERVER_IP> [NODE_ID]"
    echo "  SERVER_IP  LAN IP of the laptop running start-server (shown in its banner)"
    echo "  NODE_ID    optional node name (default: derived from this machine's hostname)"
    exit 1
fi

pulsenet_check_java
pulsenet_find_jar

ARGS=(agent --host "$1" --port 9000)
if [ $# -ge 2 ]; then
    ARGS+=(--node-id "$2")
fi

exec java -jar "$PULSENET_JAR" "${ARGS[@]}"
