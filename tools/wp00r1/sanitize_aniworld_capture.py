#!/usr/bin/env python3
import base64
import datetime as dt
import hashlib
import html
import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".wp00/evidence/aniworld")

SOURCES = {
    "recent": ("neue-episoden", "https://aniworld.to/neue-episoden", "recent_current"),
    "calendar": ("animekalender", "https://aniworld.to/animekalender", "future_calendar"),
    "support": ("support-deutsche-synchro", "https://aniworld.to/support/frage/deutsche-synchro-sortieren", "support_role_explanation"),
}

class Probe(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.text = []
        self.flags = []
        self._heading = None
        self.headings = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag in ("h1", "h2"):
            self._heading = [tag, []]
        classes = attrs.get("class", "").split()
        if tag == "img" and "flag" in classes:
            self.flags.append({k: attrs.get(k, "") for k in ("class", "title", "alt", "src")})

    def handle_endtag(self, tag):
        if self._heading and tag == self._heading[0]:
            value = " ".join(self._heading[1]).strip()
            if value:
                self.headings.append((tag, value))
            self._heading = None

    def handle_data(self, data):
        value = " ".join(data.split())
        if not value:
            return
        self.text.append(value)
        if self._heading:
            self._heading[1].append(value)


def read_page(key):
    stem, expected_url, role = SOURCES[key]
    raw = (ROOT / f"{stem}.html").read_bytes()
    transport = (ROOT / f"{stem}.transport").read_text(encoding="utf-8").strip().split("\t", 1)
    status = int(transport[0])
    final_url = transport[1] if len(transport) == 2 else expected_url
    parser = Probe()
    parser.feed(raw.decode("utf-8", errors="replace"))
    return {
        "key": key,
        "stem": stem,
        "source_url": expected_url,
        "source_role": role,
        "http_status": status,
        "final_url": final_url,
        "raw_size": len(raw),
        "raw_sha256": hashlib.sha256(raw).hexdigest(),
        "parser": parser,
    }


def flag_hay(flag):
    return " ".join(flag.values()).casefold()


def is_de_dub(flag):
    value = flag_hay(flag)
    return ("auf deutsch" in value or "deutsche flagge" in value or "german flag" in value) and "untertitel" not in value


def is_de_sub(flag):
    value = flag_hay(flag)
    return "untertitel" in value and ("deutsch" in value or "german" in value)


def image(flag):
    attrs = " ".join(f'{key}="{html.escape(value, quote=True)}"' for key, value in flag.items() if value)
    return f"<img {attrs}>"


def document(title, body):
    return ("<!doctype html>\n<html lang=\"de\">\n<head><meta charset=\"utf-8\"><title>"
            + html.escape(title)
            + "</title></head>\n<body>\n"
            + body.rstrip()
            + "\n</body>\n</html>\n")


def short_context(text, marker, radius=90):
    low = text.casefold()
    pos = low.find(marker.casefold())
    if pos < 0:
        return ""
    start = max(0, pos - radius)
    end = min(len(text), pos + len(marker) + radius)
    snippet = " ".join(text[start:end].split())
    words = snippet.split()
    return " ".join(words[:24])

pages = {key: read_page(key) for key in SOURCES}
for page in pages.values():
    if page["http_status"] != 200:
        raise SystemExit(f"capture failed: {page['key']} HTTP {page['http_status']}")

recent = pages["recent"]
calendar = pages["calendar"]
support = pages["support"]
recent_text = " ".join(recent["parser"].text)
calendar_text = " ".join(calendar["parser"].text)
support_text = " ".join(support["parser"].text)

if "neue episoden" not in recent_text.casefold():
    raise SystemExit("recent page anchor missing")
if "animekalender" not in calendar_text.casefold():
    raise SystemExit("calendar page anchor missing")
if "deutsche synchro" not in support_text.casefold():
    raise SystemExit("support page anchor missing")

recent_dub = next((f for f in recent["parser"].flags if is_de_dub(f)), None)
recent_sub = next((f for f in recent["parser"].flags if is_de_sub(f)), None)
calendar_dub = next((f for f in calendar["parser"].flags if is_de_dub(f)), None)
if not recent_dub or not recent_sub:
    raise SystemExit("recent DE_DUB or separate DE_SUB evidence missing")
if not calendar_dub:
    raise SystemExit("calendar DE_DUB forecast flag evidence missing")

forecast_snippet = short_context(calendar_text, "Uhr") or short_context(calendar_text, "~")
if not forecast_snippet:
    raise SystemExit("calendar forecast time marker missing")
recent_support = short_context(support_text, "letzte 7 tage")
future_support = short_context(support_text, "nächste 14 tage") or short_context(support_text, "zukünft")
if not recent_support or not future_support:
    raise SystemExit("support recent/future role explanation missing")

positive = document(
    "Neue Episoden",
    "<main>\n<h1>Neue Episoden</h1>\n"
    "<!-- Canonical sanitizer deliberately places DE_SUB before DE_DUB to prove selector semantics are not positional. -->\n"
    + image(recent_sub) + "\n" + image(recent_dub) + "\n</main>",
)
de_sub = document("Neue Episoden - DE_SUB evidence", "<main>\n<h1>Neue Episoden</h1>\n" + image(recent_sub) + "\n</main>")
calendar_fixture = document(
    "Animekalender",
    "<main>\n<h1>Animekalender</h1>\n<p>" + html.escape(forecast_snippet) + "</p>\n" + image(calendar_dub) + "\n</main>",
)
support_fixture = document(
    "Nach Deutscher synchro sortieren",
    "<main>\n<h1>Nach Deutscher synchro sortieren</h1>\n<p>" + html.escape(recent_support) + "</p>\n<p>" + html.escape(future_support) + "</p>\n</main>",
)
missing_anchor = positive.replace("<h1>Neue Episoden</h1>\n", "")
changed_anchor = re.sub(
    r'<img class="flag"[^>]*(?:auf Deutsch|Deutsche Flagge|German Flag)[^>]*>',
    '<img class="flag" title="Folge 1" alt="Flag" src="/public/img/unknown.svg">',
    positive,
    count=1,
    flags=re.IGNORECASE,
)
if changed_anchor == positive:
    raise SystemExit("unable to derive changed semantic-anchor fixture")

fixtures = {
    "recent-positive-de-dub.html": (positive, recent, "positive_de_dub", "canonical semantic extraction; DE_SUB placed before DE_DUB; keeps live flag class/title/alt/src and page anchor"),
    "recent-de-sub-separate.html": (de_sub, recent, "de_sub_observed_not_activated", "canonical semantic extraction of DE_SUB evidence only; no confirmation-policy activation"),
    "future-calendar-forecast.html": (calendar_fixture, calendar, "negative_forecast_only", "canonical extraction of calendar anchor, forecast time context and live DE_DUB flag attributes"),
    "support-page-roles.html": (support_fixture, support, "support_recent_vs_future", "canonical extraction of short recent/future role text windows and support page anchor"),
    "recent-missing-page-anchor.html": (missing_anchor, recent, "negative_fail_closed_missing_page_anchor", "derived from recent positive fixture by removing required h1 page anchor"),
    "recent-changed-semantic-anchor.html": (changed_anchor, recent, "negative_fail_closed_changed_flag_semantics", "derived from recent positive fixture by replacing DE_DUB semantic flag attributes with unknown values"),
}

captured_path = ROOT / "captured_utc"
captured_utc = captured_path.read_text(encoding="utf-8").strip() if captured_path.exists() else dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
manifest = {
    "schema": 1,
    "capture_utc": captured_utc,
    "activation_scope": "PAGE_ROLE_PLUS_DE_DUB_TRACK_SELECTOR_EVIDENCED",
    "de_sub_policy": "observed_separately_not_activated_for_confirmation",
    "account_or_private_data_present": False,
    "fixtures": [],
    "raw_capture_provenance": {
        key: {
            "source_url": page["source_url"],
            "source_role": page["source_role"],
            "http_status": page["http_status"],
            "final_url": page["final_url"],
            "raw_size": page["raw_size"],
            "raw_sha256": page["raw_sha256"],
        }
        for key, page in pages.items()
    },
}

for name, (content, page, classification, method) in fixtures.items():
    data = content.encode("utf-8")
    manifest["fixtures"].append({
        "path": f"fixtures/{name}",
        "source_url": page["source_url"],
        "source_role": page["source_role"],
        "capture_timestamp_utc": captured_utc,
        "http_status": page["http_status"],
        "final_url": page["final_url"],
        "byte_size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "raw_or_sanitized": "sanitized",
        "sanitization_method": method,
        "expected_classification": classification,
        "selector_anchor_annotations": "page-role h1 plus semantic flag title/alt/src attributes; never flag position",
        "account_or_private_data_present": False,
    })
    print("WP00_R1_FIXTURE_PAYLOAD\t" + name + "\t" + base64.b64encode(data).decode("ascii"))

manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")
print("WP00_R1_MANIFEST_PAYLOAD\t" + base64.b64encode(manifest_bytes).decode("ascii"))
print("WP00_R1_CAPTURE_SUMMARY\t" + json.dumps({
    "capture_utc": captured_utc,
    "fixture_count": len(fixtures),
    "raw": manifest["raw_capture_provenance"],
}, ensure_ascii=False, sort_keys=True))
