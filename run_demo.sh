#!/usr/bin/env bash
set -e

echo "========================================================================="
echo " PulseNet: Distributed Real-Time Telemetry Monitor - Demo Runner"
echo "========================================================================="

echo "[1/3] Compiling and packaging PulseNet..."
mvn clean package -DskipTests

echo "[2/3] Starting Central Aggregator Server (Port 9000)..."
mvn exec:java -Dexec.mainClass="com.pulsenet.server.ServerMain" -Dexec.args="--port 9000" &
SERVER_PID=$!

trap "echo 'Terminating PulseNet demo...'; kill $SERVER_PID 2>/dev/null || true; exit 0" SIGINT SIGTERM EXIT

sleep 2

echo "[3/3] Starting Simulated Cluster (5 Agent Nodes with Chaos Injection)..."
mvn exec:java -Dexec.mainClass="com.pulsenet.simulation.MockCluster" -Dexec.args="--port 9000 --nodes 5"
