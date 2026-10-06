#!/usr/bin/env python3
"""Ephemeral authoritative DNS for the standalone Android HTTPS proof only."""
import os
import socket
import sys


def reply(query):
    if len(query) < 17:
        return None
    offset = 12
    labels = []
    while offset < len(query):
        length = query[offset]
        offset += 1
        if length == 0:
            break
        if length > 63 or offset + length > len(query):
            return None
        labels.append(query[offset:offset + length].decode("ascii", "ignore").lower())
        offset += length
    if offset + 4 > len(query):
        return None
    name = ".".join(labels)
    question = query[12:offset + 4]
    qtype = int.from_bytes(query[offset:offset + 2], "big")
    addresses = {
        "example.org": "8.8.8.8",
        "aniworld.to": "8.8.8.8",  # TEST-ONLY real guest routes to the hermetic TLS fixture.
        "wrong.example.org": "8.8.8.8",
        "private.example.org": "127.0.0.1",
        # Android's own connectivity probes (API 24 to 27 read the name from the platform, API 28+ from settings) end
        # at the hermetic HTTP 204 handler on 8.8.8.8:80, so the emulated network validates without touching the Internet.
        "connectivitycheck.gstatic.com": "8.8.8.8",
        "connectivitycheck.android.com": "8.8.8.8",
        "clients3.google.com": "8.8.8.8",
        "www.google.com": "8.8.8.8",
    }
    address = addresses.get(name)
    answer = b""
    if address and qtype == 1:
        answer = b"\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x3c\x00\x04" + socket.inet_aton(address)
    flags = b"\x81\x80" if address else b"\x81\x83"
    header = query[:2] + flags + b"\x00\x01" + (b"\x00\x01" if answer else b"\x00\x00") + b"\x00\x00\x00\x00"
    return header + question + answer


with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server:
    server.bind(("127.0.0.1", 53))
    if len(sys.argv) > 1:
        with open(sys.argv[1], "w", encoding="ascii") as ready_file:
            ready_file.write(str(os.getpid()) + "\n")
    while True:
        query, peer = server.recvfrom(4096)
        response = reply(query)
        if response is not None:
            server.sendto(response, peer)
