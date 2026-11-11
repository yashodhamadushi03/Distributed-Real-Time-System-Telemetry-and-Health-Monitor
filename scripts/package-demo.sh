#!/usr/bin/env bash
# Build pulsenet.jar and assemble a portable dist/pulsenet-demo/ folder for the node laptops.
# Usage: scripts/package-demo.sh [--skip-tests]
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

MVN_ARGS=(clean package)
if [ "${1:-}" = "--skip-tests" ]; then
    MVN_ARGS+=(-DskipTests)
fi

echo "[1/2] Building pulsenet.jar (mvn ${MVN_ARGS[*]})..."
mvn "${MVN_ARGS[@]}"

DIST="$REPO_ROOT/dist/pulsenet-demo"
echo "[2/2] Assembling $DIST ..."
rm -rf "$DIST"
mkdir -p "$DIST/scripts"
cp target/pulsenet.jar "$DIST/"
cp DEMO.md "$DIST/"
cp scripts/*.sh scripts/*.ps1 scripts/*.bat "$DIST/scripts/" 2>/dev/null || true
rm -f "$DIST/scripts/package-demo.sh" "$DIST/scripts/package-demo.ps1"
chmod +x "$DIST"/scripts/*.sh

echo
echo "Done. Copy this folder to each laptop (USB stick / GitHub Release):"
echo "  $DIST"
echo "Server laptop:  scripts/start-server.sh"
echo "Node laptops:   scripts/start-agent.sh <SERVER_IP>"
