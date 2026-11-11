#!/usr/bin/env bash
# Shared helpers for PulseNet launch scripts (sourced, not executed).

PULSENET_MIN_JAVA=25
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Verify `java` is on PATH and its major version is >= PULSENET_MIN_JAVA.
pulsenet_check_java() {
    if ! command -v java >/dev/null 2>&1; then
        echo "[ERROR] 'java' was not found on PATH. Install Java ${PULSENET_MIN_JAVA}+ and retry." >&2
        exit 1
    fi
    local raw major
    raw="$(java -version 2>&1 | head -n 1 | sed -E 's/.*version "([^"]+)".*/\1/')"
    major="${raw%%.*}"
    if [ "$major" = "1" ]; then
        major="$(echo "$raw" | cut -d. -f2)"   # legacy "1.8.0_xxx" scheme
    fi
    major="${major%%[^0-9]*}"
    if [ -z "$major" ] || [ "$major" -lt "$PULSENET_MIN_JAVA" ]; then
        echo "[ERROR] Java ${PULSENET_MIN_JAVA}+ is required, but found version '${raw}'." >&2
        echo "        Install a newer JDK/JRE or put it first on PATH." >&2
        exit 1
    fi
}

# Locate pulsenet.jar: dist layout (next to scripts/) first, then the Maven target/ folder.
pulsenet_find_jar() {
    local candidate
    for candidate in "$SCRIPT_DIR/../pulsenet.jar" "$SCRIPT_DIR/../target/pulsenet.jar"; do
        if [ -f "$candidate" ]; then
            PULSENET_JAR="$(cd "$(dirname "$candidate")" && pwd)/pulsenet.jar"
            return 0
        fi
    done
    echo "[ERROR] pulsenet.jar not found. Run 'mvn clean package' (or scripts/package-demo.sh) first." >&2
    exit 1
}
