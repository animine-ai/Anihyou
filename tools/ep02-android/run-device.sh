#!/usr/bin/env bash
set -euo pipefail
variant=${1:?debug or release required}
expected_api=${2:?expected API required}
case "$variant" in debug|release);; *) exit 2;; esac
root="$(cd "$(dirname "$0")" && pwd)"
apk="$root/host-app/build/outputs/apk/$variant/host-app-$variant.apk"
test -f "$apk"
mkdir -p "$root/results/android-$expected_api-$variant"
out="$root/results/android-$expected_api-$variant"
adb install -r "$apk"
adb logcat -c
timeout 240 adb shell am instrument -w -r de.kiyori.ep02/.RuntimeProofInstrumentation | tee "$out/instrumentation.txt"
timeout 15 adb logcat -d > "$out/logcat.txt" 2>&1 || true
python3 - "$out" "$expected_api" "$variant" <<'PY'
from pathlib import Path
import json,sys
root=Path(sys.argv[1]); text=(root/'instrumentation.txt').read_text()
assert 'EP02_ANDROID_PASS' in text and 'EP02_ANDROID_FAIL' not in text, text
assert 'INSTRUMENTATION_CODE: -1' in text, text
line=next(line for line in text.splitlines() if line.startswith('INSTRUMENTATION_RESULT: ep02='))
report=json.loads(line.split('=',1)[1])
assert report['passed'] is True and report['api']==int(sys.argv[2]), report
f=report['functional']
for key in ['releasePlanParse','navigationOverview','navigationEpisode','moduleCacheEvictionRecovery','cancellation','serviceKill','lateResultRejected','rebind','fixtureOnlyNoFallback']:
    assert key in f, (key,report)
p=report['performance']
assert p['sampleCount']>=50 and p['fixtureParseOnlyNoNetwork'] is True
assert p['steadyState']['policy'] in ['PASS','SMALL_ABSOLUTE_DIFFERENCE'], p
report['variant']=sys.argv[3]
(root/'report.json').write_text(json.dumps(report,indent=2)+'\n')
print('EP02 VERIFIED',json.dumps(report,separators=(',',':')))
PY
