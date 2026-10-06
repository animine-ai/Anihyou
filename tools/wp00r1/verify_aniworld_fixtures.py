#!/usr/bin/env python3
import hashlib
import json
import sys
from html.parser import HTMLParser
from pathlib import Path

BASE = Path(sys.argv[1] if len(sys.argv) > 1 else "private/evidence/wp00-r1/aniworld")
MANIFEST = BASE / "manifest.json"

class Probe(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.text = []
        self.flags = []
        self._h1 = False
        self.h1 = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "h1":
            self._h1 = True
        classes = attrs.get("class", "").split()
        if tag == "img" and "flag" in classes:
            self.flags.append({k: attrs.get(k, "") for k in ("class", "title", "alt", "src")})

    def handle_endtag(self, tag):
        if tag == "h1":
            self._h1 = False

    def handle_data(self, data):
        value = " ".join(data.split())
        if value:
            self.text.append(value)
            if self._h1:
                self.h1.append(value)


def parse(path):
    parser = Probe()
    parser.feed(path.read_text(encoding="utf-8"))
    return parser


def flag_hay(flag):
    return " ".join(flag.values()).casefold()


def is_de_dub(flag):
    value = flag_hay(flag)
    return ("auf deutsch" in value or "deutsche flagge" in value or "german flag" in value) and "untertitel" not in value


def is_de_sub(flag):
    value = flag_hay(flag)
    return "untertitel" in value and ("deutsch" in value or "german" in value)


def recent_confirmation(parser):
    page_anchor = "neue episoden" in " ".join(parser.h1).casefold()
    return page_anchor and any(is_de_dub(flag) for flag in parser.flags)


def calendar_forecast_only(parser):
    page_anchor = "animekalender" in " ".join(parser.h1).casefold()
    text = " ".join(parser.text).casefold()
    forecast_marker = "uhr" in text or "~" in text
    return page_anchor and forecast_marker

manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
assert manifest["activation_scope"] == "PAGE_ROLE_PLUS_DE_DUB_TRACK_SELECTOR_EVIDENCED"
assert manifest["de_sub_policy"] == "observed_separately_not_activated_for_confirmation"
assert manifest["account_or_private_data_present"] is False

entries = {Path(entry["path"]).name: entry for entry in manifest["fixtures"]}
expected_names = {
    "recent-positive-de-dub.html",
    "recent-de-sub-separate.html",
    "future-calendar-forecast.html",
    "support-page-roles.html",
    "recent-missing-page-anchor.html",
    "recent-changed-semantic-anchor.html",
}
assert set(entries) == expected_names

for name, entry in entries.items():
    path = BASE / entry["path"]
    data = path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    assert digest == entry["sha256"], (name, digest, entry["sha256"])
    assert len(data) == entry["byte_size"], (name, len(data), entry["byte_size"])
    assert entry["raw_or_sanitized"] == "sanitized"
    assert entry["account_or_private_data_present"] is False
    print(f"fixture_integrity={name}\tbytes={len(data)}\tsha256={digest}")

positive = parse(BASE / "fixtures/recent-positive-de-dub.html")
assert recent_confirmation(positive)
assert len(positive.flags) >= 2
assert is_de_sub(positive.flags[0]), "first flag is deliberately DE_SUB"
assert any(is_de_dub(flag) for flag in positive.flags[1:]), "DE_DUB must be selected semantically, not by position"
print("positive_de_dub_replay=PASS")
print("semantic_selector_not_position=PASS")

sub_only = parse(BASE / "fixtures/recent-de-sub-separate.html")
assert any(is_de_sub(flag) for flag in sub_only.flags)
assert not recent_confirmation(sub_only), "DE_SUB must not become DE_DUB confirmation"
print("de_sub_separate_not_activated=PASS")

calendar = parse(BASE / "fixtures/future-calendar-forecast.html")
assert calendar_forecast_only(calendar)
assert not recent_confirmation(calendar), "future calendar must never satisfy recent-list confirmation"
print("future_calendar_forecast_only=PASS")

support = parse(BASE / "fixtures/support-page-roles.html")
support_text = " ".join(support.text).casefold()
assert "letzte 7 tage" in support_text
assert "nächste 14 tage" in support_text or "zukünft" in support_text
print("support_recent_vs_future_roles=PASS")

missing = parse(BASE / "fixtures/recent-missing-page-anchor.html")
assert not recent_confirmation(missing)
print("missing_required_page_anchor_fail_closed=PASS")

changed = parse(BASE / "fixtures/recent-changed-semantic-anchor.html")
assert "neue episoden" in " ".join(changed.h1).casefold()
assert not recent_confirmation(changed)
print("changed_required_flag_semantics_fail_closed=PASS")

print("WP00_R1_FIXTURE_TESTS=PASS")
