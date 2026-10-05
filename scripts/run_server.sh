#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$DIR/server/bin/zenith-server"

if [ ! -f "$BIN" ]; then
    echo "[!] Zenith Server binary not found. Building now..."
    make -C "$DIR/server"
fi

echo "[*] Starting Zenith Audio Server..."
exec "$BIN" "$@"
