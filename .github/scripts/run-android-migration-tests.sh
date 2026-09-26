#!/usr/bin/env bash

test_status=0
if ./gradlew --console=plain --stacktrace :private:release-data:connectedDebugAndroidTest; then
  test_status=0
else
  test_status=$?
fi

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

roots = [
    Path("private/release-data/build/outputs/androidTest-results/connected"),
    Path("private/release-data/build/reports/androidTests/connected"),
]
reports = [file for root in roots if root.exists() for file in root.rglob("*.xml")]
found_failure = False
for report in reports:
    try:
        suite = ET.parse(report).getroot()
    except ET.ParseError:
        continue
    for case in suite.iter("testcase"):
        for result in case:
            if result.tag in {"failure", "error"}:
                found_failure = True
                print(f"FAIL {case.get('classname')}#{case.get('name')}: {result.get('message')}")
                if result.text:
                    print(result.text)
if not reports:
    print("No Android instrumentation XML reports were produced.")
elif not found_failure:
    print(f"Parsed {len(reports)} Android instrumentation XML reports; no failure nodes found.")
PY

exit "$test_status"
