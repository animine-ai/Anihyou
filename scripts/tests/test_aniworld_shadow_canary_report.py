import hashlib
import json
import sqlite3
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import importlib.util

MODULE_PATH = Path(__file__).resolve().parents[1] / "aniworld-shadow-canary-report.py"
SPEC = importlib.util.spec_from_file_location("shadow_report", MODULE_PATH)
shadow_report = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(shadow_report)


def pack(fields):
    return "".join(f"{len(value)}:{value}" for value in fields)


def make_manifest(generation_id, started_at):
    sources = [
        ["aw:list:calendar:v1", "ANIWORLD_CALENDAR", "aniworld:list:calendar", "",
         "https://aniworld.to/animekalender", "true", "false", ""],
        ["aw:list:recent:v1", "ANIWORLD_RECENT", "aniworld:list:recent", "",
         "https://aniworld.to/neue-episoden", "true", "false", ""],
        ["aw:list:postponement:v1", "ANIWORLD_POSTPONEMENT", "aniworld:list:postponement", "",
         "https://aniworld.to/support/frage/anime-verschiebungen", "false", "false", ""],
    ]
    source_fields = [item for source in sources for item in source]
    digest = hashlib.sha256(pack(source_fields).encode()).hexdigest()
    candidate_digest = "b" * 64
    fields = [
        "aw-shadow-manifest-v1", generation_id, "owner-random", "process-random", "1",
        started_at, "2026-09-27T12:04:00Z", digest, candidate_digest, str(len(sources)),
        *source_fields,
    ]
    return digest, pack(fields)


def metric_payload():
    return {
        "v": 1,
        "planned": 3, "attempted": 3, "succeeded": 2, "partial": 0, "failed": 0, "skipped": 1,
        "directEligible": 4, "directSelected": 1, "reserved": 3, "redirects": 0,
        "postponementUnbound": 0, "postponementAmbiguous": 0, "postponementRejected": 0,
        "comparable": 2, "r2Only": 0, "v3Only": 0, "disagreements": 0, "stale": 0,
        "uncomparable": 0, "elapsedMillis": 4500, "completedWireCalls": 2,
        "uncompletedReservedCalls": 1, "same": 2,
        "sourceMetrics": [{
            "instanceId": "aw:list:calendar:v1",
            "type": "ANIWORLD_CALENDAR",
            "outcome": "SUCCESS",
            "elapsedMillis": 1500,
            "failureKind": None,
        }],
        "postponementReasons": {},
    }


def make_connection(generation_count=1, payload_version=1, payload=None):
    connection = sqlite3.connect(":memory:")
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA user_version=13")
    connection.executescript("""
        CREATE TABLE v3_poll_generation (
            generationId TEXT, startedAt TEXT, completedAt TEXT, state TEXT, outcome TEXT,
            reason TEXT, manifestVersion INTEGER, manifestPayload TEXT, manifestDigest TEXT
        );
        CREATE TABLE v3_http_attempt (
            generationId TEXT, ordinal INTEGER, rootUrl TEXT, requestUrl TEXT, role TEXT,
            reservedAt TEXT, completedAt TEXT, outcome TEXT, retryAfterSeconds INTEGER
        );
        CREATE TABLE v3_request_state (
            scopeKey TEXT, lastAttemptAt TEXT, lastSuccessAt TEXT, failureCount INTEGER, nextEligibleAt TEXT
        );
        CREATE TABLE v3_shadow_metric (
            generationId TEXT, recordedAt TEXT, payloadVersion INTEGER, payload TEXT
        );
        CREATE TABLE private_profile (accessToken TEXT, htmlBody TEXT);
    """)
    connection.execute(
        "INSERT INTO private_profile VALUES (?,?)",
        ("ANI_LIST_SECRET_DO_NOT_EXPORT", "<html>PRIVATE_BODY_DO_NOT_EXPORT</html>"),
    )
    for index in range(generation_count):
        generation_id = f"aw-shadow-v1:test-{index:03d}"
        started_at = f"2026-09-27T12:{index % 60:02d}:00Z"
        digest, manifest = make_manifest(generation_id, started_at)
        connection.execute(
            "INSERT INTO v3_poll_generation VALUES (?,?,?,?,?,?,?,?,?)",
            (generation_id, started_at, f"2026-09-27T12:{index % 60:02d}:05Z",
             "COMMITTED", "COMMITTED", None, 1, manifest, digest),
        )
        connection.execute(
            "INSERT INTO v3_http_attempt VALUES (?,?,?,?,?,?,?,?,?)",
            (generation_id, 0, "https://aniworld.to/anime/private-title",
             "https://aniworld.to/anime/private-title/episode-1", "DIRECT",
             started_at, None, "HTTP_429", 300),
        )
    connection.execute(
        "INSERT INTO v3_request_state VALUES (?,?,?,?,?)",
        ("scope:aniworld-shadow-v1", "2026-09-27T12:00:00Z", None, 1, "2026-09-27T12:30:00Z"),
    )
    connection.execute(
        "INSERT INTO v3_request_state VALUES (?,?,?,?,?)",
        ("host:aniworld", "2026-09-27T12:00:00Z", None, 1, "2026-09-27T12:30:00Z"),
    )
    generation_id = "aw-shadow-v1:test-000"
    if payload is None:
        payload = json.dumps(metric_payload(), separators=(",", ":"))
    connection.execute(
        "INSERT INTO v3_shadow_metric VALUES (?,?,?,?)",
        (generation_id, "2026-09-27T12:00:05Z", payload_version, payload),
    )
    return connection


class ShadowReportTest(unittest.TestCase):
    def report(self, connection, limit=10):
        return shadow_report.build_report(
            connection,
            "app.kiyori.debug",
            {"versionName": "test-debug", "versionCode": 1},
            generated_at="2026-09-27T12:10:00Z",
            limit=limit,
        )

    def test_valid_v1_payload_and_manifest_are_normalized(self):
        report = self.report(make_connection())
        generation = report["generations"][0]
        self.assertEqual("valid", generation["manifest"]["status"])
        self.assertEqual(3, generation["manifest"]["count"])
        self.assertEqual("valid", generation["shadowMetric"]["status"])
        self.assertEqual(3, generation["shadowMetric"]["normalized"]["planned"])
        self.assertEqual("SUCCESS", generation["shadowMetric"]["perSourceOutcomes"][0]["outcome"])
        self.assertEqual(1, generation["requests"]["http429Count"])
        self.assertEqual(300, generation["requests"]["retryAfterSecondsHint"]["maximum"])

    def test_unknown_payload_version_fails_closed(self):
        report = self.report(make_connection(payload_version=2))
        metric = report["generations"][0]["shadowMetric"]
        self.assertEqual("unsupported-version", metric["status"])
        self.assertIsNone(metric["normalized"])
        self.assertIsNone(metric["rawPayload"])

    def test_malformed_metric_json_is_marked_and_not_returned(self):
        report = self.report(make_connection(payload="{not-json"))
        metric = report["generations"][0]["shadowMetric"]
        self.assertEqual("malformed", metric["status"])
        self.assertIsNone(metric["normalized"])
        self.assertIsNone(metric["rawPayload"])

    def test_unknown_metric_fields_fail_closed(self):
        payload = metric_payload()
        payload["htmlBody"] = "<html>SHOULD_NOT_ESCAPE</html>"
        report = self.report(make_connection(payload=json.dumps(payload)))
        metric = report["generations"][0]["shadowMetric"]
        self.assertEqual("malformed", metric["status"])
        self.assertIsNone(metric["rawPayload"])

    def test_report_limits_generation_count(self):
        report = self.report(make_connection(generation_count=5), limit=2)
        self.assertEqual(2, report["latestGenerationCount"])
        self.assertEqual(2, len(report["generations"]))

    def test_wait_logic_does_not_mistake_an_older_generation_for_a_new_one(self):
        report = self.report(make_connection(generation_count=3))
        before_id = report["latestGenerationId"]
        self.assertIsNone(shadow_report._new_generation(report, before_id))

    def test_report_does_not_export_urls_profiles_or_secret_fields(self):
        report = self.report(make_connection())
        serialized = json.dumps(report)
        for secret in (
            "private-title", "episode-1", "ANI_LIST_SECRET_DO_NOT_EXPORT",
            "PRIVATE_BODY_DO_NOT_EXPORT", "accessToken", "htmlBody",
        ):
            self.assertNotIn(secret, serialized)
        forbidden_key_fragments = ("token", "authorization", "cookie", "html", "body", "profile")
        def visit(value):
            if isinstance(value, dict):
                for key, child in value.items():
                    self.assertFalse(any(part in key.lower() for part in forbidden_key_fragments), key)
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)
        visit(report)

    def test_unknown_room_schema_fails_closed(self):
        connection = make_connection()
        connection.execute("PRAGMA user_version=14")
        with self.assertRaisesRegex(shadow_report.ExportError, "Unsupported Room database version"):
            self.report(connection)


if __name__ == "__main__":
    unittest.main()
