#!/usr/bin/env bash
# Start a PulseNet telemetry agent that streams this machine's metrics to the server.
# Usage: scripts/start-agent.sh [SERVER_IP[:PORT]] [NODE_ID]
# Or:    scripts/start-agent.sh --server 172.20.10.2:9000 [NODE_ID]
# If SERVER_IP is omitted or is --discover, automatic UDP discovery is used.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    echo "Usage: $0 [SERVER_IP] [NODE_ID]"
    echo "  No arguments: automatic UDP discovery mode"
    echo "  SERVER_IP   : LAN IP of the laptop running start-server"
    echo "  NODE_ID     : optional node name (default: derived from hostname)"
    exit 1
fi

pulsenet_check_java
pulsenet_find_jar

if [ $# -eq 0 ] || [ "${1:-}" = "--discover" ]; then
    ARGS=(agent --discover)
    if [ $# -ge 2 ]; then
        ARGS+=(--node-id "$2")
    fi
else
    if [ "$1" = "--server" ] || [ "$1" = "--host" ]; then
        shift
        if [ $# -eq 0 ]; then
            echo "[ERROR] --server needs an address, e.g. --server 192.168.1.20:9000" >&2
            exit 1
        fi
    fi
    HOST="${1%%:*}"
    PORT=9000
    if [[ "$1" == *:* ]]; then
        PORT="${1##*:}"
    fi
    ARGS=(agent --host "$HOST" --port "$PORT")
    if [ $# -ge 2 ]; then
        ARGS+=(--node-id "$2")
    fi
fi

exec java -jar "$PULSENET_JAR" "${ARGS[@]}"
