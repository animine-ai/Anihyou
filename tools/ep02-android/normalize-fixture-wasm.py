#!/usr/bin/env python3
"""Normalize the deterministic Rust fixture to the frozen AREX export surface."""

from __future__ import annotations

from pathlib import Path
import sys

MAGIC = b"\x00asm\x01\x00\x00\x00"
TOOLCHAIN_EXPORTS = {"__data_end", "__heap_base"}
EXPECTED_EXPORTS = {
    "memory",
    "arex_alloc",
    "arex_free",
    "plan_requests",
    "parse_responses",
    "plan_navigation",
    "parse_navigation",
}


def read_u32(data: bytes, offset: int, limit: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while offset < limit and shift <= 28:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if byte & 0x80 == 0:
            if value > 0xFFFF_FFFF or (shift == 28 and byte > 0x0F):
                raise ValueError("invalid u32 LEB128")
            return value, offset
        shift += 7
    raise ValueError("truncated or overflowing u32 LEB128")


def write_u32(value: int) -> bytes:
    if not 0 <= value <= 0xFFFF_FFFF:
        raise ValueError("u32 out of range")
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def normalize_exports(payload: bytes) -> bytes:
    count, cursor = read_u32(payload, 0, len(payload))
    entries: list[tuple[str, bytes]] = []
    names: list[str] = []

    for _ in range(count):
        entry_start = cursor
        name_size, cursor = read_u32(payload, cursor, len(payload))
        name_end = cursor + name_size
        if name_end > len(payload):
            raise ValueError("truncated export name")
        name_bytes = payload[cursor:name_end]
        cursor = name_end
        try:
            name = name_bytes.decode("utf-8")
        except UnicodeDecodeError as error:
            raise ValueError("invalid UTF-8 export name") from error
        if cursor >= len(payload):
            raise ValueError("truncated export kind")
        cursor += 1
        _, cursor = read_u32(payload, cursor, len(payload))
        entries.append((name, payload[entry_start:cursor]))
        names.append(name)

    if cursor != len(payload):
        raise ValueError("trailing bytes in export section")
    if len(names) != len(set(names)):
        raise ValueError("duplicate fixture export")

    actual = set(names)
    expected_before = EXPECTED_EXPORTS | TOOLCHAIN_EXPORTS
    if actual != expected_before:
        raise ValueError(
            "unexpected rust fixture export surface: "
            f"expected {sorted(expected_before)}, got {sorted(actual)}"
        )

    kept = [raw for name, raw in entries if name not in TOOLCHAIN_EXPORTS]
    return write_u32(len(kept)) + b"".join(kept)


def normalize_module(module: bytes) -> bytes:
    if not module.startswith(MAGIC):
        raise ValueError("fixture is not a wasm32 core module")

    output = bytearray(MAGIC)
    cursor = len(MAGIC)
    export_sections = 0

    while cursor < len(module):
        section_id = module[cursor]
        cursor += 1
        size, cursor = read_u32(module, cursor, len(module))
        end = cursor + size
        if end > len(module):
            raise ValueError("truncated wasm section")
        payload = module[cursor:end]
        cursor = end

        if section_id == 7:
            export_sections += 1
            payload = normalize_exports(payload)

        output.append(section_id)
        output.extend(write_u32(len(payload)))
        output.extend(payload)

    if export_sections != 1:
        raise ValueError(f"expected one export section, found {export_sections}")
    return bytes(output)


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {Path(sys.argv[0]).name} <module.wasm>", file=sys.stderr)
        return 2

    path = Path(sys.argv[1])
    original = path.read_bytes()
    normalized = normalize_module(original)
    if normalized == original:
        raise ValueError("fixture normalization made no change")

    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(normalized)
    temporary.replace(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
