#!/usr/bin/env bash
set -euo pipefail
variant=${1:?debug or release required}
expected_api=${2:?expected API required}
case "$variant" in debug|release);; *) exit 2;; esac
root="$(cd "$(dirname "$0")" && pwd)"
shopt -s nullglob
apks=("$root/host-app/build/outputs/apk/$variant/"*-"$variant".apk)
if [[ ${#apks[@]} -ne 1 ]]; then
  printf 'expected exactly one %s APK, found %d\n' "$variant" "${#apks[@]}" >&2
  exit 3
fi
apk="${apks[0]}"
mkdir -p "$root/results/android-$expected_api-$variant"
out="$root/results/android-$expected_api-$variant"
adb install -r "$apk"
adb logcat -c
timeout 240 adb shell am instrument -w -r de.kiyori.ep02/.RuntimeProofInstrumentation | tee "$out/instrumentation.txt"
timeout 15 adb logcat -d > "$out/logcat.txt" 2>&1 || true
python3 - "$out" "$expected_api" "$variant" <<'PY'
from pathlib import Path
import json,sys

root=Path(sys.argv[1])
text=(root/'instrumentation.txt').read_text()
line=next(
    (line for line in text.splitlines() if line.startswith('INSTRUMENTATION_RESULT: ep02=')),
    None,
)
assert line is not None, text
report=json.loads(line.split('=',1)[1])
report['variant']=sys.argv[3]
(root/'report.json').write_text(json.dumps(report,indent=2)+'\n')

assert report['api']==int(sys.argv[2]), report
f=report['functional']
for key in [
    'releasePlanParse','navigationOverview','navigationEpisode',
    'productionTransportFactoryBoundary','productionNavigationDispatcherGate',
    'moduleCacheEvictionRecovery','cancellation','deadline','fuel',
    'serviceKill','lateResultRejected','rebind','fixtureOnlyNoFallback'
]:
    assert key in f, (key,report)
factory=f['productionTransportFactoryBoundary']
assert factory['factoryCreated'] is True and factory['dnsDestinationBindingVerified'] is True, factory
assert factory['httpRejectedBeforeReservation'] is True, factory
assert factory['untrustedHostRejectedBeforeReservation'] is True, factory
assert factory['networkLedgerUncreated'] is True and factory['networkAttempted'] is False, factory
assert factory['successfulSocketPathExercised'] is False, factory
dispatch=f['productionNavigationDispatcherGate']
assert dispatch['unprovenTransportRejected'] is True and dispatch['planExecutedBeforeGate'] is True, dispatch
assert dispatch['transportExecuteCalls']==0 and dispatch['networkAttempted'] is False, dispatch
assert dispatch['productionTransportReached'] is False, dispatch
p=report['performance']
assert p['sampleCount']>=50 and p['fixtureParseOnlyNoNetwork'] is True
policy=p['steadyState']['policy']

if report['passed']:
    assert 'EP02_ANDROID_PASS' in text and 'EP02_ANDROID_FAIL' not in text, text
    assert 'INSTRUMENTATION_CODE: -1' in text, text
    assert policy in ['PASS','SMALL_ABSOLUTE_DIFFERENCE'], p
    print('EP02 VERIFIED',json.dumps(report,separators=(',',':')))
else:
    assert 'EP02_ANDROID_PERFORMANCE_FAIL' in text, text
    assert 'INSTRUMENTATION_CODE: 0' in text, text
    assert policy == 'FAIL', p
    print('EP02 PERFORMANCE GATE FAILED',json.dumps(report,separators=(',',':')))
    raise SystemExit(4)
PY
