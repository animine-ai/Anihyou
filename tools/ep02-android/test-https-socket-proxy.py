#!/usr/bin/env python3
"""Test-only byte relay from runner 8.8.8.8:443 to adb-forwarded app TLS server."""
import os
import select
import socket
import sys
import threading


def relay(client):
    try:
        with client, socket.create_connection(("127.0.0.1", 8443), timeout=10) as target:
            peers = {client: target, target: client}
            while peers:
                ready, _, _ = select.select(list(peers), [], [], 15)
                if not ready:
                    return
                for source in ready:
                    data = source.recv(8192)
                    if not data:
                        return
                    peers[source].sendall(data)
    except (OSError, TimeoutError):
        return


with socket.create_server(("8.8.8.8", 443), backlog=16) as server:
    with open(sys.argv[1], "w", encoding="ascii") as pid_file:
        pid_file.write(str(os.getpid()) + "\n")
    while True:
        client, _ = server.accept()
        threading.Thread(target=relay, args=(client,), daemon=True).start()
