#!/usr/bin/env python3
"""Verify German release-resource parity and Android placeholder signatures."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "core/resources/src/main/res/values/strings.xml"
GERMAN = ROOT / "core/resources/src/main/res/values-de-rDE/strings.xml"
PLACEHOLDER = re.compile(r"%(?:[0-9]+\$)?[a-zA-Z]")


def placeholder_signature(text: str) -> tuple[str, ...]:
    return tuple(sorted(PLACEHOLDER.findall(text)))


def release_resources(path: Path) -> dict[str, tuple[str, tuple[tuple[str, tuple[str, ...]], ...]]]:
    root = ET.parse(path).getroot()
    values: dict[str, tuple[str, tuple[tuple[str, tuple[str, ...]], ...]]] = {}
    for element in root:
        name = element.attrib.get("name", "")
        if not name.startswith("release_"):
            continue
        if element.tag == "plurals":
            entries = tuple(
                sorted(
                    (
                        item.attrib.get("quantity", ""),
                        placeholder_signature("".join(item.itertext()).strip()),
                    )
                    for item in element.findall("item")
                )
            )
        else:
            entries = (("", placeholder_signature("".join(element.itertext()).strip())),)
        values[name] = (element.tag, entries)
    return values


def main() -> int:
    base = release_resources(BASE)
    german = release_resources(GERMAN)
    missing = sorted(set(base) - set(german))
    extra = sorted(set(german) - set(base))
    mismatches = sorted(
        name for name in set(base) & set(german)
        if base[name] != german[name]
    )
    if missing or extra or mismatches:
        if missing:
            print("missing German release resources:", ", ".join(missing))
        if extra:
            print("extra German release resources:", ", ".join(extra))
        if mismatches:
            print("resource type/plural/placeholder mismatches:", ", ".join(mismatches))
        return 1
    print(f"release resource parity verified: {len(base)} keys")
    return 0


if __name__ == "__main__":
    sys.exit(main())
