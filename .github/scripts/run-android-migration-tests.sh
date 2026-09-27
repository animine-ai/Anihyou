#!/usr/bin/env bash

set +e
./gradlew --console=plain --stacktrace :private:release-data:connectedDebugAndroidTest
gradle_status=$?

python3 - <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

roots = [
    Path("private/release-data/build/outputs/androidTest-results/connected"),
    Path("private/release-data/build/reports/androidTests/connected"),
]
reports = sorted({path.resolve() for root in roots if root.exists() for path in root.rglob("*.xml")})
required = {
    "wp04b_migration_v12_to_v13_preservesBaselineAndR2AndReopens",
    "wp04b_migration_v11_to_v13_usesCompleteChain",
    "wp04b_migration_v10_to_v13_usesCompleteChain",
    "wp04b_migration_v8_to_v13_usesCompleteChain",
    "wp04b_migration_v12_faultAfterCreateRollsBackAllDdl",
    "wp04b_migration_v12NameCollisionFailsBeforeAnyCreate",
    "wp04b_migration_v12BadMarkerFailsBeforeAnyCreate",
    "wp04b_migration_v12IndexNameCollisionFailsBeforeAnyCreate",
}
seen = set()
tests = failures = errors = skipped = 0

for report in reports:
    try:
        root = ET.parse(report).getroot()
    except ET.ParseError as error:
        print(f"Unreadable Android XML report {report}: {error}")
        sys.exit(1)
    for case in root.iter("testcase"):
        tests += 1
        name = case.get("name", "")
        seen.add(name)
        failures += sum(child.tag == "failure" for child in case)
        errors += sum(child.tag == "error" for child in case)
        skipped += sum(child.tag == "skipped" for child in case)

missing = sorted(required - seen)
print(f"Parsed {len(reports)} Android XML report(s): {tests} executed, {failures} failure(s), "
      f"{errors} error(s), {skipped} skipped.")
if missing:
    print("Missing required WP04B migration cases: " + ", ".join(missing))
if not reports or tests == 0:
    print("No executed Android instrumentation cases were reported.")
if not reports or tests == 0 or failures or errors or skipped or missing:
    sys.exit(1)
PY
report_status=$?

if [[ "$gradle_status" -ne 0 ]]; then
  exit "$gradle_status"
fi
exit "$report_status"
