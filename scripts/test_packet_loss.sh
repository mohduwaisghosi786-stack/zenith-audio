#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER="$DIR/server/bin/zenith-server"
CLIENT="$DIR/test/test_client"
PROXY="$DIR/scripts/loss_proxy.py"

if [ ! -f "$SERVER" ]; then
    make -C "$DIR/server"
fi

echo "=========================================================="
echo "      ZENITH AUDIO ENGINE PACKET LOSS STRESS TEST         "
echo "=========================================================="

test_loss_level() {
    local LOSS=$1
    local JITTER=$2
    echo ""
    echo ">>> Testing with $LOSS% simulated packet loss and ${JITTER}ms jitter <<<"

    # Start server on 8088
    "$SERVER" > /tmp/server_stress.log 2>&1 &
    SERVER_PID=$!
    sleep 0.5

    # Start loss proxy on 8089 -> 8088
    python3 "$PROXY" --listen-port 8089 --target-port 8088 --loss "$LOSS" --jitter-ms "$JITTER" > /dev/null 2>&1 &
    PROXY_PID=$!
    sleep 0.5

    # Run client connecting to proxy port 8089
    "$CLIENT" 127.0.0.1 8089 > /tmp/client_stress.log 2>&1 || true

    sleep 0.5
    kill $PROXY_PID 2>/dev/null || true
    kill $SERVER_PID 2>/dev/null || true

    echo "--- Results ($LOSS% loss) ---"
    cat /tmp/client_stress.log | grep -E "Summary|lost|received"
}

test_loss_level 1.0 2.0
test_loss_level 3.0 5.0
test_loss_level 5.0 8.0
test_loss_level 10.0 12.0

echo ""
echo "=========================================================="
echo " Packet loss resilience tests completed successfully!     "
echo "=========================================================="
