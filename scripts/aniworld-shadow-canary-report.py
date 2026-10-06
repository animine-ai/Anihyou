#!/usr/bin/env python3
"""Export a privacy-minimized report from the local debug Room-v13 shadow tables."""

from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import hashlib
import io
import json
import os
import re
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import time
from pathlib import Path
from typing import Any, Iterator

DB_NAME = "release-data.db"
DB_SCHEMA_VERSION = 13
MAX_GENERATIONS = 100
MAX_PAYLOAD_CHARS = 16_384
MAX_MANIFEST_CHARS = 65_536
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
PACKAGE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")
SOURCE_TYPES = {
    "ANIWORLD_CALENDAR",
    "ANIWORLD_RECENT",
    "ANIWORLD_POSTPONEMENT",
    "ANIWORLD_DIRECT_PAGE",
}
SOURCE_OUTCOMES = {"SUCCESS", "PARTIAL_SUCCESS", "FAILURE", "INCOMPLETE"}
METRIC_REQUIRED_KEYS = {
    "v", "planned", "attempted", "succeeded", "partial", "failed", "skipped",
    "directEligible", "directSelected", "reserved", "redirects", "postponementUnbound",
    "postponementAmbiguous", "postponementRejected", "comparable", "r2Only", "v3Only",
    "disagreements", "stale", "uncomparable", "elapsedMillis", "completedWireCalls",
    "uncompletedReservedCalls", "same", "sourceMetrics", "postponementReasons",
}
METRIC_OPTIONAL_KEYS = {"postponementSnapshotHash", "postponementParserVersion"}
METRIC_INT_KEYS = METRIC_REQUIRED_KEYS - {"v", "sourceMetrics", "postponementReasons"}
MANIFEST_FIELDS_PER_SOURCE = 8
SAFE_SOURCE_IDS = {
    "aw:list:calendar:v1",
    "aw:list:recent:v1",
    "aw:list:postponement:v1",
}
DIRECT_SOURCE_ID_RE = re.compile(r"^aw:direct-target:v1:[0-9a-f]{64}$")
SAFE_REASON_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
SAFE_ENUM_RE = re.compile(r"^[A-Z][A-Z0-9_]{0,63}$")
SAFE_VERSION_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

GENERATION_COLUMNS = {
    "generationId", "startedAt", "completedAt", "state", "outcome", "reason",
    "manifestVersion", "manifestPayload", "manifestDigest",
}
ATTEMPT_COLUMNS = {
    "generationId", "ordinal", "rootUrl", "requestUrl", "role", "reservedAt",
    "completedAt", "outcome", "retryAfterSeconds",
}
REQUEST_STATE_COLUMNS = {
    "scopeKey", "lastAttemptAt", "lastSuccessAt", "failureCount", "nextEligibleAt",
}
METRIC_COLUMNS = {"generationId", "recordedAt", "payloadVersion", "payload"}


class ExportError(RuntimeError):
    """A clear, fail-closed exporter error."""


def _utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _safe_timestamp(value: Any) -> str | None:
    if not isinstance(value, str) or len(value) > 40:
        return None
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return value


def _safe_reason(value: Any) -> str | None:
    return value if isinstance(value, str) and SAFE_REASON_RE.fullmatch(value) else None


def _parse_length_prefixed(payload: str) -> list[str] | None:
    if not isinstance(payload, str) or len(payload) > MAX_MANIFEST_CHARS:
        return None
    fields: list[str] = []
    offset = 0
    while offset < len(payload):
        colon = payload.find(":", offset)
        if colon <= offset or colon - offset > 6:
            return None
        length_text = payload[offset:colon]
        if not length_text.isascii() or not length_text.isdigit():
            return None
        length = int(length_text)
        end = colon + 1 + length
        if length > MAX_MANIFEST_CHARS or end > len(payload):
            return None
        fields.append(payload[colon + 1:end])
        if len(fields) > 10 + 11 * MANIFEST_FIELDS_PER_SOURCE:
            return None
        offset = end
    return fields


def _pack_length_prefixed(fields: list[str]) -> str:
    return "".join(f"{len(value)}:{value}" for value in fields)


def _validate_manifest(row: sqlite3.Row) -> tuple[str, int | None, str | None]:
    if row["manifestVersion"] != 1:
        return "unsupported-version", None, None
    fields = _parse_length_prefixed(row["manifestPayload"])
    if fields is None:
        return "malformed", None, None
    if not fields or fields[0] != "aw-shadow-manifest-v1":
        return "unsupported-version", None, None
    if len(fields) < 10 or fields[1] != row["generationId"]:
        return "malformed", None, None
    try:
        source_count = int(fields[9])
        policy_version = int(fields[4])
    except (ValueError, TypeError):
        return "malformed", None, None
    if policy_version != 1:
        return "unsupported-version", None, None
    if source_count < 3 or source_count > 11 or len(fields) != 10 + source_count * MANIFEST_FIELDS_PER_SOURCE:
        return "malformed", None, None
    if not fields[2] or not fields[3] or not SHA256_RE.fullmatch(fields[8]):
        return "malformed", None, None
    if not SHA256_RE.fullmatch(fields[7]) or fields[7] != row["manifestDigest"]:
        return "malformed", None, None
    source_fields = fields[10:]
    computed_digest = hashlib.sha256(_pack_length_prefixed(source_fields).encode("utf-8")).hexdigest()
    if computed_digest != fields[7]:
        return "malformed", None, None
    if _safe_timestamp(fields[5]) is None or _safe_timestamp(fields[6]) is None:
        return "malformed", None, None
    for i in range(source_count):
        offset = 10 + i * MANIFEST_FIELDS_PER_SOURCE
        if not fields[offset] or len(fields[offset]) > 256:
            return "malformed", None, None
        if len(fields[offset + 1]) > 64 or len(fields[offset + 2]) > 2048:
            return "malformed", None, None
        if fields[offset + 5] not in {"true", "false"} or fields[offset + 6] not in {"true", "false"}:
            return "malformed", None, None
        if len(fields[offset + 4]) > 2048 or len(fields[offset + 7]) > 256:
            return "malformed", None, None
    return "valid", source_count, row["manifestDigest"]


def _validate_metric_payload(payload: Any) -> tuple[str, dict[str, Any] | None]:
    if not isinstance(payload, str) or len(payload) > MAX_PAYLOAD_CHARS:
        return "malformed", None
    try:
        value = json.loads(payload)
    except (json.JSONDecodeError, TypeError):
        return "malformed", None
    if not isinstance(value, dict):
        return "malformed", None
    version = value.get("v")
    if not isinstance(version, int) or isinstance(version, bool):
        return "malformed", None
    if version != 1:
        return "unsupported-version", None
    keys = set(value)
    if not METRIC_REQUIRED_KEYS.issubset(keys) or not keys.issubset(METRIC_REQUIRED_KEYS | METRIC_OPTIONAL_KEYS):
        return "malformed", None
    for key in METRIC_INT_KEYS:
        number = value.get(key)
        if not isinstance(number, int) or isinstance(number, bool) or number < 0 or number > 10_000_000:
            return "malformed", None
    if value["planned"] > 11 or value["directSelected"] > 4 or value["reserved"] > 22:
        return "malformed", None
    if value["completedWireCalls"] > value["reserved"] or value["uncompletedReservedCalls"] > value["reserved"]:
        return "malformed", None
    if value["completedWireCalls"] + value["uncompletedReservedCalls"] > value["reserved"]:
        return "malformed", None
    if not isinstance(value["sourceMetrics"], list) or len(value["sourceMetrics"]) > 11:
        return "malformed", None
    safe_sources: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for source in value["sourceMetrics"]:
        if not isinstance(source, dict) or set(source) != {"instanceId", "type", "outcome", "elapsedMillis", "failureKind"}:
            return "malformed", None
        instance_id = source["instanceId"]
        if not isinstance(instance_id, str) or len(instance_id) > 256:
            return "malformed", None
        if instance_id not in SAFE_SOURCE_IDS and not DIRECT_SOURCE_ID_RE.fullmatch(instance_id):
            return "malformed", None
        if instance_id in seen_ids:
            return "malformed", None
        seen_ids.add(instance_id)
        source_type = source["type"]
        outcome = source["outcome"]
        elapsed = source["elapsedMillis"]
        failure_kind = source["failureKind"]
        if source_type not in SOURCE_TYPES or outcome not in SOURCE_OUTCOMES:
            return "malformed", None
        if not isinstance(elapsed, int) or isinstance(elapsed, bool) or elapsed < 0 or elapsed > 10_000_000:
            return "malformed", None
        if failure_kind is not None and (
            not isinstance(failure_kind, str) or not SAFE_ENUM_RE.fullmatch(failure_kind)
        ):
            return "malformed", None
        safe_sources.append({
            "instanceId": instance_id,
            "type": source_type,
            "outcome": outcome,
            "elapsedMillis": elapsed,
            "failureKind": failure_kind,
        })
    reasons = value["postponementReasons"]
    if not isinstance(reasons, dict) or len(reasons) > 12:
        return "malformed", None
    safe_reasons: dict[str, int] = {}
    for key, count in reasons.items():
        if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9_]{1,64}", key):
            return "malformed", None
        if not isinstance(count, int) or isinstance(count, bool) or count < 0 or count > 512:
            return "malformed", None
        safe_reasons[key] = count
    snapshot_hash = value.get("postponementSnapshotHash")
    if snapshot_hash is not None and (
        not isinstance(snapshot_hash, str) or not SHA256_RE.fullmatch(snapshot_hash)
    ):
        return "malformed", None
    parser_version = value.get("postponementParserVersion")
    if parser_version is not None and (
        not isinstance(parser_version, str) or not SAFE_VERSION_RE.fullmatch(parser_version)
    ):
        return "malformed", None
    sanitized = dict(value)
    sanitized["sourceMetrics"] = safe_sources
    sanitized["postponementReasons"] = safe_reasons
    return "valid", sanitized


def _role_name(value: Any) -> str:
    if value == "DIRECT":
        return "DIRECT"
    if value == "LIST":
        return "LIST"
    return "OTHER"


def _cooldown_summary(connection: sqlite3.Connection) -> dict[str, Any]:
    result: dict[str, Any] = {"scope": None, "host": None}
    rows = connection.execute(
        "SELECT scopeKey,lastAttemptAt,lastSuccessAt,failureCount,nextEligibleAt "
        "FROM v3_request_state WHERE scopeKey IN (?,?)",
        ("scope:aniworld-shadow-v1", "host:aniworld"),
    )
    for row in rows:
        key = row["scopeKey"]
        name = "scope" if key == "scope:aniworld-shadow-v1" else "host"
        failure_count = row["failureCount"]
        result[name] = {
            "lastAttemptAt": _safe_timestamp(row["lastAttemptAt"]),
            "lastSuccessAt": _safe_timestamp(row["lastSuccessAt"]),
            "failureCount": failure_count if isinstance(failure_count, int) and 0 <= failure_count <= 1000 else None,
            "nextEligibleAt": _safe_timestamp(row["nextEligibleAt"]),
        }
    return result


def _validate_schema(connection: sqlite3.Connection) -> None:
    version = connection.execute("PRAGMA user_version").fetchone()[0]
    if version != DB_SCHEMA_VERSION:
        raise ExportError(f"Unsupported Room database version {version}; expected {DB_SCHEMA_VERSION}.")
    expected = {
        "v3_poll_generation": GENERATION_COLUMNS,
        "v3_http_attempt": ATTEMPT_COLUMNS,
        "v3_request_state": REQUEST_STATE_COLUMNS,
        "v3_shadow_metric": METRIC_COLUMNS,
    }
    present = {
        row[0] for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        )
    }
    for table, columns in expected.items():
        if table not in present:
            raise ExportError(f"Room-v13 table is missing: {table}.")
        actual = {row[1] for row in connection.execute(f'PRAGMA table_info("{table}")')}
        if not columns.issubset(actual):
            raise ExportError(f"Room-v13 table layout is unknown: {table}.")


def build_report(
    connection: sqlite3.Connection,
    package: str,
    build: dict[str, Any],
    generated_at: str | None = None,
    limit: int = 10,
) -> dict[str, Any]:
    if limit < 1 or limit > MAX_GENERATIONS:
        raise ExportError(f"Generation limit must be in 1..{MAX_GENERATIONS}.")
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA query_only=ON")
    _validate_schema(connection)
    generations = connection.execute(
        "SELECT generationId,startedAt,completedAt,state,outcome,reason,manifestVersion,"
        "manifestPayload,manifestDigest FROM v3_poll_generation "
        "ORDER BY startedAt DESC,generationId DESC LIMIT ?",
        (limit,),
    ).fetchall()
    result_rows: list[dict[str, Any]] = []
    for generation in generations:
        generation_id = generation["generationId"]
        attempt_count = connection.execute(
            "SELECT COUNT(*) FROM v3_http_attempt WHERE generationId=?",
            (generation_id,),
        ).fetchone()[0]
        if attempt_count > 22:
            raise ExportError(f"Generation {generation_id} exceeds the v13 request bound.")
        attempts = connection.execute(
            "SELECT role,completedAt,outcome,retryAfterSeconds FROM v3_http_attempt "
            "WHERE generationId=? ORDER BY ordinal LIMIT 22",
            (generation_id,),
        ).fetchall()
        role_counts: dict[str, int] = {}
        completed = uncompleted = http_429 = 0
        retry_hints: list[int] = []
        for attempt in attempts:
            role = _role_name(attempt["role"])
            role_counts[role] = role_counts.get(role, 0) + 1
            if attempt["completedAt"] is None:
                uncompleted += 1
            else:
                completed += 1
            outcome = attempt["outcome"]
            if isinstance(outcome, str) and "429" in outcome:
                http_429 += 1
            hint = attempt["retryAfterSeconds"]
            if isinstance(hint, int) and 0 <= hint <= 21_600:
                retry_hints.append(hint)
        manifest_status, manifest_count, manifest_digest = _validate_manifest(generation)
        metric_row = connection.execute(
            "SELECT payloadVersion,payload FROM v3_shadow_metric WHERE generationId=?",
            (generation_id,),
        ).fetchone()
        metric_status = "missing"
        metric_version: int | None = None
        raw_metric: dict[str, Any] | None = None
        if metric_row is not None:
            metric_version = metric_row["payloadVersion"]
            if metric_version != 1:
                metric_status = "unsupported-version"
            else:
                metric_status, raw_metric = _validate_metric_payload(metric_row["payload"])
        normalized = None
        per_source = None
        if raw_metric is not None:
            field_map = {
                "planned": "planned",
                "attempted": "attempted",
                "succeeded": "succeeded",
                "partial": "partial",
                "failed": "failed",
                "skipped": "skipped",
                "directEligible": "directEligible",
                "directSelected": "directSelected",
                "reserved": "reserved",
                "redirects": "redirects",
                "postponementUnbound": "postponementUnbound",
                "postponementAmbiguous": "postponementAmbiguous",
                "postponementRejected": "postponementRejected",
                "comparable": "comparable",
                "r2Only": "r2Only",
                "v3Only": "v3Only",
                "disagreements": "disagreements",
                "stale": "stale",
                "uncomparable": "uncomparable",
                "elapsedMillis": "elapsedMillis",
                "completedWireCalls": "completedWireCalls",
                "uncompletedReservedCalls": "uncompletedReservedCalls",
            }
            normalized = {target: raw_metric[source] for target, source in field_map.items()}
            per_source = raw_metric["sourceMetrics"]
        state = generation["state"]
        if state not in {"RUNNING", "COMMITTED", "ABORTED"}:
            state = "UNKNOWN"
        outcome = generation["outcome"]
        if outcome not in {"COMMITTED", "ABORTED", "SKIPPED", "FAILED"}:
            outcome = outcome if outcome is None else "UNKNOWN"
        result_rows.append({
            "generationId": generation_id,
            "state": state,
            "outcome": outcome,
            "reason": _safe_reason(generation["reason"]),
            "startedAt": _safe_timestamp(generation["startedAt"]),
            "completedAt": _safe_timestamp(generation["completedAt"]),
            "manifest": {
                "status": manifest_status,
                "count": manifest_count,
                "digest": manifest_digest,
            },
            "requests": {
                "reserved": attempt_count,
                "completed": completed,
                "uncompleted": uncompleted,
                "byRole": role_counts,
                "http429Count": http_429,
                "retryAfterSecondsHint": {
                    "count": len(retry_hints),
                    "maximum": max(retry_hints) if retry_hints else None,
                },
            },
            "shadowMetric": {
                "status": metric_status,
                "payloadVersion": metric_version,
                "normalized": normalized,
                "perSourceOutcomes": per_source,
                "rawPayload": raw_metric,
            },
        })
    return {
        "reportVersion": 1,
        "package": {
            "applicationId": package,
            "versionName": build.get("versionName"),
            "versionCode": build.get("versionCode"),
            "debuggable": True,
        },
        "generatedAt": generated_at or _utc_now(),
        "databaseSchemaVersion": DB_SCHEMA_VERSION,
        "latestGenerationCount": len(result_rows),
        "latestGenerationId": result_rows[0]["generationId"] if result_rows else None,
        "requestCooldownSummary": _cooldown_summary(connection),
        "generations": result_rows,
    }


def _run(adb: str, args: list[str], timeout: int = 30) -> subprocess.CompletedProcess[bytes]:
    try:
        return subprocess.run([adb, *args], capture_output=True, timeout=timeout, check=False)
    except FileNotFoundError as exc:
        raise ExportError(f"adb was not found: {adb}") from exc
    except subprocess.TimeoutExpired as exc:
        raise ExportError("adb command timed out.") from exc


def validate_debuggable(adb: str, package: str) -> None:
    result = _run(adb, ["shell", "run-as", package, "id"], timeout=15)
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise ExportError(f"Package is not debuggable or run-as failed for {package}: {detail[:240]}")


def get_build_info(adb: str, package: str) -> dict[str, Any]:
    result = _run(adb, ["shell", "dumpsys", "package", package], timeout=20)
    if result.returncode != 0:
        raise ExportError(f"Could not read build identification for {package}.")
    text = result.stdout.decode("utf-8", "replace")
    version_name = re.search(r"\bversionName=([^\s]+)", text)
    version_code = re.search(r"\bversionCode=(\d+)", text)
    return {
        "versionName": version_name.group(1)[:80] if version_name else None,
        "versionCode": int(version_code.group(1)) if version_code else None,
    }


@contextlib.contextmanager
def pulled_database(adb: str, package: str) -> Iterator[sqlite3.Connection]:
    device_command = (
        "cd databases || exit 42; "
        f"[ -f {DB_NAME} ] || {{ echo DB_MISSING >&2; exit 43; }}; "
        "files='release-data.db'; "
        "for f in release-data.db-wal release-data.db-shm; do "
        "[ ! -f \"$f\" ] || files=\"$files $f\"; done; "
        "toybox tar -cf - $files"
    )
    result = _run(adb, ["exec-out", "run-as", package, "sh", "-c", device_command], timeout=30)
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", "replace").strip()
        if "DB_MISSING" in detail or result.returncode == 43:
            raise ExportError("Room database release-data.db is missing.")
        if "not debuggable" in detail.lower() or "run-as" in detail.lower():
            raise ExportError(f"Package is not debuggable or run-as failed: {detail[:240]}")
        raise ExportError(f"Could not copy Room database with adb/run-as: {detail[:240]}")
    try:
        with tempfile.TemporaryDirectory(prefix="aniworld-shadow-report-") as temporary:
            root = Path(temporary)
            os.chmod(root, 0o700)
            allowed = {DB_NAME, DB_NAME + "-wal", DB_NAME + "-shm"}
            try:
                archive = tarfile.open(fileobj=io.BytesIO(result.stdout), mode="r:*")
            except tarfile.TarError as exc:
                raise ExportError("Could not read the private adb database snapshot.") from exc
            with archive:
                for member in archive.getmembers():
                    if member.name not in allowed or not member.isfile():
                        continue
                    stream = archive.extractfile(member)
                    if stream is None:
                        continue
                    target = root / member.name
                    with target.open("wb") as output:
                        os.chmod(target, 0o600)
                        output.write(stream.read())
            database_path = root / DB_NAME
            if not database_path.is_file():
                raise ExportError("Room database release-data.db is missing from the adb snapshot.")
            uri = database_path.as_uri() + "?mode=ro"
            try:
                connection = sqlite3.connect(uri, uri=True, timeout=5)
                connection.row_factory = sqlite3.Row
                connection.execute("PRAGMA query_only=ON")
                yield connection
            except sqlite3.DatabaseError as exc:
                raise ExportError("The copied Room database is unreadable or changed during the bounded copy.") from exc
            finally:
                if "connection" in locals():
                    connection.close()
    except tarfile.TarError as exc:
        raise ExportError("The adb database snapshot was malformed.") from exc


def latest_generation_id(connection: sqlite3.Connection) -> str:
    connection.row_factory = sqlite3.Row
    _validate_schema(connection)
    row = connection.execute(
        "SELECT generationId FROM v3_poll_generation ORDER BY startedAt DESC,generationId DESC LIMIT 1"
    ).fetchone()
    return row["generationId"] if row else ""


def _new_generation(report: dict[str, Any], before_id: str) -> dict[str, Any] | None:
    generations = report["generations"]
    if not generations:
        return None
    latest = generations[0]
    return latest if latest["generationId"] != before_id else None


def _write_report(report: dict[str, Any], output: str | None) -> None:
    rendered = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if output:
        target = Path(output)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(rendered, encoding="utf-8")
        os.chmod(target, 0o600)
        print(str(target))
    else:
        sys.stdout.write(rendered)


def _export_once(adb: str, package: str, limit: int) -> dict[str, Any]:
    validate_debuggable(adb, package)
    build = get_build_info(adb, package)
    with pulled_database(adb, package) as connection:
        return build_report(connection, package, build, limit=limit)


def _wait_for_new_generation(
    adb: str,
    package: str,
    build: dict[str, Any],
    before_id: str,
    limit: int,
    timeout_seconds: int,
    poll_interval: float,
) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    last_error = "no new generation has appeared"
    validate_debuggable(adb, package)
    while time.monotonic() < deadline:
        try:
            with pulled_database(adb, package) as connection:
                report = build_report(connection, package, build, limit=limit)
            new_generation = _new_generation(report, before_id)
            if new_generation is not None:
                if new_generation["state"] == "UNKNOWN":
                    raise ExportError("New generation has an unknown state; refusing to infer completion.")
                if new_generation["state"] != "RUNNING":
                    if new_generation["shadowMetric"]["status"] == "missing":
                        raise ExportError("New generation terminated without a shadow metric.")
                    return report
            last_error = "the new generation is still running"
        except ExportError as exc:
            last_error = str(exc)
            if "Unsupported Room database version" in last_error or "unknown" in last_error.lower():
                raise
        time.sleep(max(0.5, min(poll_interval, 10.0)))
    raise ExportError(f"Timed out after {timeout_seconds}s waiting for a completed canary: {last_error}")


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="adb executable (default: adb on PATH)")
    parser.add_argument("--package", default="com.axiel7.anihyou.debug", help="debug application id")
    parser.add_argument("--limit", type=int, default=10, help=f"latest generations, 1..{MAX_GENERATIONS}")
    parser.add_argument("--output", help="write the JSON report to this local path")
    parser.add_argument("--latest-generation-id", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--allow-missing-db", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--wait-for-new-generation-after", help="wait for a terminal generation different from this id")
    parser.add_argument("--timeout-seconds", type=int, default=300)
    parser.add_argument("--poll-interval", type=float, default=3.0)
    args = parser.parse_args(argv)
    if not PACKAGE_RE.fullmatch(args.package):
        parser.error("invalid Android application id")
    if args.limit < 1 or args.limit > MAX_GENERATIONS:
        parser.error(f"--limit must be in 1..{MAX_GENERATIONS}")
    if args.timeout_seconds < 1 or args.timeout_seconds > 900:
        parser.error("--timeout-seconds must be in 1..900")
    return args


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv or sys.argv[1:])
    try:
        validate_debuggable(args.adb, args.package)
        if args.latest_generation_id:
            try:
                with pulled_database(args.adb, args.package) as connection:
                    sys.stdout.write(latest_generation_id(connection) + "\n")
            except ExportError as exc:
                if args.allow_missing_db and "database release-data.db is missing" in str(exc):
                    sys.stdout.write("\n")
                    return 0
                raise
            return 0
        build = get_build_info(args.adb, args.package)
        if args.wait_for_new_generation_after is not None:
            report = _wait_for_new_generation(
                args.adb, args.package, build, args.wait_for_new_generation_after,
                args.limit, args.timeout_seconds, args.poll_interval,
            )
        else:
            with pulled_database(args.adb, args.package) as connection:
                report = build_report(connection, args.package, build, limit=args.limit)
        _write_report(report, args.output)
        return 0
    except ExportError as exc:
        print(f"shadow report: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
