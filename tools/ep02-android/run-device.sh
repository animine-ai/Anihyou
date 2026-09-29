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
cleanup_test_network() {
  if [[ -s "$out/proxy.pid" ]]; then sudo kill "$(cat "$out/proxy.pid")" 2>/dev/null || true; fi
  sudo ip address del 8.8.8.8/32 dev lo 2>/dev/null || true
  adb forward --remove tcp:8443 2>/dev/null || true
}
trap cleanup_test_network EXIT
bash "$root/configure-test-network.sh" "$out"
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
functional=report.get('functional',{})
if functional.get('status') != 'PASS':
    assert 'EP02_ANDROID_FUNCTIONAL_FAIL' in text, text
    assert functional.get('status') == 'FAIL', report
    assert report.get('performance',{}).get('status') == 'NOT_RUN', report
    assert report.get('performance',{}).get('noiseRetryAttempted') is False, report
    print('EP02 FUNCTIONAL GATE FAILED',json.dumps({
        'api':report['api'],'variant':sys.argv[3],'functionalStatus':functional['status'],
        'message':functional.get('message')
    },separators=(',',':')))
    raise SystemExit(5)

f=functional['checks']
for key in [
    'releasePlanParse','navigationOverview','navigationEpisode',
    'productionHttpsSocketProof','productionNavigationDispatch',
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
https=f['productionHttpsSocketProof']
assert https['productionTransportReached'] is True, https
assert https['successfulSocketPathExercised'] is True, https
assert https['redirectRevalidatedAndFollowed'] is True, https
assert https['privateDestinationRejectedBeforeSocket'] is True, https
assert https['tlsHostnameMismatchRejected'] is True, https
assert https['bodyLimitAbortedStreamingResponse'] is True, https
assert https['cancellationDuringBody'] is True, https
assert https['destinationAddress']=='8.8.8.8', https
release=f['releasePlanParse']
assert release['successfulSocketPathExercised'] is True, release
assert release['packageDigestPinned'] is True and release['generationPinned'] is True, release
navigation=f['productionNavigationDispatch']
assert navigation['productionTransportReached'] is True, navigation
assert navigation['successfulSocketPathExercised'] is True, navigation
dispatch=f['productionNavigationDispatcherGate']
assert dispatch['unprovenTransportRejected'] is True and dispatch['planExecutedBeforeGate'] is True, dispatch
assert dispatch['transportExecuteCalls']==0 and dispatch['networkAttempted'] is False, dispatch
assert dispatch['productionTransportReached'] is False, dispatch
p=report['performance']
raw_attempts=p.get('attempts',[])
(root/'performance-raw.json').write_text(json.dumps(raw_attempts,indent=2)+'\n')
if p.get('status') == 'ERROR':
    if p.get('attemptCount') == 2:
        assert len(raw_attempts) == 2, p
        assert p.get('noiseRetry',{}).get('attempted') is True, p
    else:
        assert p.get('noiseRetryAttempted') is False, p
    print('EP02 PERFORMANCE MEASUREMENT ERROR',json.dumps({
        'api':report['api'],'variant':sys.argv[3],'functionalStatus':functional['status'],
        'performanceStatus':p['status'],'message':p.get('message')
    },separators=(',',':')))
    raise SystemExit(6)
assert p.get('status') in ['PASS','FAIL'], p
assert p['sampleCount']>=50 and p['fixtureParseOnlyNoNetwork'] is True
assert p['batchCount'] == 5 and p['samplesPerBatch'] == 20, p
assert p['attemptCount'] in [1,2], p
assert len(raw_attempts) == p['attemptCount'], p
policy=p['steadyState']['policy']

if report['passed']:
    assert functional['status'] == 'PASS', report
    assert p['status'] == 'PASS', report
    assert 'EP02_ANDROID_PASS' in text and 'EP02_ANDROID_FUNCTIONAL_FAIL' not in text, text
    assert 'INSTRUMENTATION_CODE: -1' in text, text
    assert policy in ['PASS','SMALL_ABSOLUTE_DIFFERENCE'], p
    print('EP02 VERIFIED',json.dumps({
        'api':report['api'],'variant':sys.argv[3],'functionalStatus':functional['status'],
        'performanceStatus':p['status'],'policy':policy,'attemptCount':p['attemptCount'],
        'noiseRetry':p['noiseRetry'],'plan':p['steadyState']['plan'],
        'parse':p['steadyState']['parse']
    },separators=(',',':')))
else:
    assert functional['status'] == 'PASS', report
    assert p['status'] == 'FAIL', report
    assert 'EP02_ANDROID_PERFORMANCE_FAIL' in text, text
    assert 'INSTRUMENTATION_CODE: 0' in text, text
    assert policy == 'FAIL', p
    print('EP02 PERFORMANCE GATE FAILED',json.dumps({
        'api':report['api'],'variant':sys.argv[3],'functionalStatus':functional['status'],
        'performanceStatus':p['status'],'policy':policy,'attemptCount':p['attemptCount'],
        'noiseRetry':p['noiseRetry'],'plan':p['steadyState']['plan'],
        'parse':p['steadyState']['parse']
    },separators=(',',':')))
    raise SystemExit(4)
PY
