#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER="$DIR/server/bin/zenith-server"
CLIENT="$DIR/test/test_client"

if [ ! -f "$SERVER" ]; then
    make -C "$DIR/server"
fi

if [ ! -f "$CLIENT" ]; then
    g++ -std=c++20 -O3 "$DIR/test/test_client.cpp" -I"$DIR/protocol" $(pkg-config --cflags --libs opus) -o "$CLIENT"
fi

echo "=========================================================="
echo "         ZENITH AUDIO ENGINE LATENCY BENCHMARK           "
echo "=========================================================="
echo "[*] Launching server on UDP port 8088..."
"$SERVER" > /tmp/zenith_bench_server.log 2>&1 &
SERVER_PID=$!
sleep 1

echo "[*] Running client benchmark test..."
"$CLIENT" 127.0.0.1 8088 > /tmp/zenith_bench_client.log 2>&1

sleep 1
kill $SERVER_PID 2>/dev/null || true

echo ""
echo "=== BENCHMARK RESULTS ==="
cat /tmp/zenith_bench_client.log | grep -E "Decoded|Summary|Total"
echo "========================="
echo ""
echo "[*] Server telemetry:"
tail -n 6 /tmp/zenith_bench_server.log
echo "=========================================================="
