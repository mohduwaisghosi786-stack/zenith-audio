#!/usr/bin/env python3
"""
Zenith Audio Network Impairment Simulator Proxy
Injects packet loss, random jitter, and delay to test protocol robustness.
"""

import socket
import select
import random
import time
import argparse
import sys

def main():
    parser = argparse.ArgumentParser(description="Zenith Audio Network Loss Proxy")
    parser.add_argument("--listen-port", type=int, default=8089, help="Proxy listening port")
    parser.add_argument("--target-host", type=str, default="127.0.0.1", help="Target server host")
    parser.add_argument("--target-port", type=int, default=8088, help="Target server port")
    parser.add_argument("--loss", type=float, default=0.0, help="Packet drop rate in percent (e.g. 3.0 for 3 percent)")
    parser.add_argument("--jitter-ms", type=float, default=0.0, help="Random jitter in milliseconds")
    parser.add_argument("--delay-ms", type=float, default=0.0, help="Fixed propagation delay in milliseconds")
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", args.listen_port))
    sock.setblocking(False)

    print(f"=== Zenith Audio Network Impairment Proxy ===")
    print(f"Listening on:     0.0.0.0:{args.listen_port}")
    print(f"Forwarding to:    {args.target_host}:{args.target_port}")
    print(f"Packet loss:      {args.loss:.1f}%")
    print(f"Delay / Jitter:   {args.delay_ms:.1f}ms + [0..{args.jitter_ms:.1f}]ms")
    print("=============================================")

    client_addr = None
    target_addr = (args.target_host, args.target_port)

    total_fwd = 0
    total_dropped = 0

    try:
        while True:
            r, _, _ = select.select([sock], [], [], 0.1)
            if not r:
                continue

            data, addr = sock.recvfrom(4096)

            # Route determination
            if addr[1] == args.target_port:
                # Coming from server -> send to client
                if client_addr is None:
                    continue
                dest = client_addr
            else:
                # Coming from client -> send to server
                client_addr = addr
                dest = (args.target_host, args.target_port)

            # Simulate packet loss
            if args.loss > 0.0 and random.uniform(0.0, 100.0) < args.loss:
                total_dropped += 1
                if total_dropped % 10 == 0:
                    print(f"[Proxy] Dropped packet ({total_dropped} dropped so far)")
                continue

            # Simulate delay and jitter
            delay = (args.delay_ms + random.uniform(0.0, args.jitter_ms)) / 1000.0
            if delay > 0.001:
                time.sleep(delay)

            sock.sendto(data, dest)
            total_fwd += 1

    except KeyboardInterrupt:
        print(f"\nProxy stopped. Forwarded: {total_fwd}, Dropped: {total_dropped}")
    finally:
        sock.close()

if __name__ == "__main__":
    main()
