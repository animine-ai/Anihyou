#!/usr/bin/env bash
set -euo pipefail
# Name the failing command and its status in the job log. A SIGKILL (137) of the shell itself cannot be reported by
# the shell, but a child that returns 137 is named here. No secrets are printed: only command text and numbers.
trap 'rc=$?; printf "EP02 DIAG %s failing command rc=%s line=%s: %s\n" "$(date -u +%T.%N)" "$rc" "$LINENO" "$BASH_COMMAND" >&2; { adb get-state; timeout 10 adb shell "head -4 /proc/meminfo; getprop ro.kernel.qemu.avd_name"; timeout 15 adb logcat -d -t 600 | grep -E -A 14 "FATAL EXCEPTION IN SYSTEM PROCESS" | cut -c1-220 | tail -40; } >&2 2>&1 || true' ERR
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
diag_memory() {
  printf 'EP02 DIAG %s phase=%s\n' "$(date -u +%T.%N)" "$1" >&2
  free -m >&2 || true
  ps -eo pid,rss,comm --sort=-rss 2>/dev/null | head -8 >&2 || true
}
diag_memory before-network-configuration
bash "$root/configure-test-network.sh" "$out"
diag_memory after-network-configuration
adb install -r "$apk"
adb logcat -c
timeout 240 adb shell am instrument -w -r de.kiyori.ep02/.RuntimeProofInstrumentation > "$out/instrumentation.txt" 2>&1 &
instrumentation_pid=$!
for attempt in $(seq 1 240); do
  if grep -qE 'INSTRUMENTATION_STATUS_CODE: -1|INSTRUMENTATION_CODE:' "$out/instrumentation.txt"; then break; fi
  if ! kill -0 "$instrumentation_pid" 2>/dev/null; then break; fi
  sleep 1
done
# Print the report once. A successful first phase stays alive for the external kill below.
cat "$out/instrumentation.txt"
timeout 15 adb logcat -d > "$out/logcat.txt" 2>&1 || true
# The app process's own view of its default network when it scheduled the product worker (API 26 and newer).
grep -E 'EP02NETWORK' "$out/logcat.txt" | cut -c1-500 || true
python3 - "$out" "$expected_api" "$variant" <<'PY'
from pathlib import Path
import json,sys
import re

root=Path(sys.argv[1])
text=(root/'instrumentation.txt').read_text()
line=next(
    (line for line in text.splitlines() if line.startswith(('INSTRUMENTATION_RESULT: ep02=', 'INSTRUMENTATION_STATUS: ep02='))),
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
    'moduleCacheEvictionRecovery','ep07UpdateRollback','ep04AniWorld',
    'ep05Canary','ep06SingleSourceWorker','cancellation','deadline','fuel',
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
ep07=f['ep07UpdateRollback']
assert ep07['testTrustOnly'] is True and ep07['productionPublication'] is False, ep07
assert ep07['extensionId']=='fixture.release' and ep07['providerId']=='fixture', ep07
repo=ep07['repository']
assert repo['testTrustOnly'] is True and repo['repositoryId']=='ep07.test.repository', repo
assert repo['indexSequences']==[1,2,3,4,5], repo
catalog=ep07['catalogRefresh']
assert catalog['refreshCount']==2, catalog
assert catalog['metadataRefreshReportedUpdateAvailable'] is True, catalog
assert catalog['updateStateFirst']==catalog['updateStateRepeated']=='UPDATE_AVAILABLE', catalog
assert catalog['installedVersionFirst']==catalog['installedVersionRepeated']=='1.0.0-test.1', catalog
assert catalog['latestAvailableVersionFirst']==catalog['latestAvailableVersionRepeated']=='2.0.0-test.3', catalog
assert catalog['installedV1Retained'] is True and catalog['latestAvailableV2'] is True, catalog
assert catalog['noArchiveFetchDuringMetadataRefresh'] is True, catalog
assert catalog['repeatedUnchangedRefreshFetchedNoArchive'] is True, catalog
assert catalog['archiveFetchCountBefore']==catalog['archiveFetchCountAfterFirstRefresh']==catalog['archiveFetchCountAfterRepeatedRefresh'], catalog
for name in ['v1','failedV2','v2','v3']:
    package=repo[name]
    assert re.fullmatch(r'[0-9a-f]{64}',package['packageDigest']), package
    assert re.fullmatch(r'[0-9a-f]{64}',package['manifestDigest']), package
    assert re.fullmatch(r'[0-9a-f]{64}',package['moduleDigest']), package
    assert package['packageBytes']>0 and package['moduleBytes']>0, package
assert len({repo[name]['packageDigest'] for name in ['v1','failedV2','v2','v3']})==4, repo
assert len({repo[name]['moduleDigest'] for name in ['v1','failedV2','v2','v3']})==4, repo
signed=ep07['signedPackages']
assert all(signed[key] is True for key in [
    'v1VerifiedInstalled','failedV2SignedCatalogRecognized',
    'v2CatalogVerifiedAndInstalled','v3CatalogVerifiedAndInstalled','sameExtensionIdentity'
]), signed
assert signed['testPublisherIdentity']=='fixture.publisher', signed
v1=ep07['v1Installed']
assert v1['packageDigest']==repo['v1']['packageDigest'] and v1['moduleDigest']==repo['v1']['moduleDigest'], v1
assert v1['packageGeneration']==1 and v1['knownGood']==v1['packageDigest'], v1
failed=ep07['controlledV2Failure']
assert failed['quarantined'] is True and failed['failure']=='SMOKE', failed
assert failed['packageDigest']==repo['failedV2']['packageDigest'], failed
assert failed['activeV1Preserved'] is True and failed['knownGoodV1Preserved'] is True, failed
assert failed['generationUnchanged'] is True, failed
failed_smokes=[run for run in ep07['smokeGuestRuns'] if run.get('releaseSequence')==2]
assert len(failed_smokes)==1, ep07['smokeGuestRuns']
assert failed_smokes[0]['packageDigest']==repo['failedV2']['packageDigest'], failed_smokes
assert failed_smokes[0]['moduleDigest']==repo['failedV2']['moduleDigest'], failed_smokes
assert failed_smokes[0]['planRequests'] is True and failed_smokes[0]['navigation'] is True, failed_smokes
updated=ep07['v2Update']
assert updated['version']=='2.0.0-test.3' and updated['releaseSequence']==3, updated
assert updated['packageDigest']==repo['v2']['packageDigest'] and updated['moduleDigest']==repo['v2']['moduleDigest'], updated
assert updated['packageGeneration']==2 and updated['previousGoodV1Available'] is True, updated
assert updated['archiveFetchCountAfterInstall']==catalog['archiveFetchCountAfterRepeatedRefresh']+1, updated
guest=updated['guest']
assert guest['planRequests'] is True and guest['calendarRequestCount']==1, guest
assert guest['calendarUrl']=='https://example.org/calendar', guest
assert guest['navigation'] is True and guest['navigationTargetKind']=='EPISODE', guest
assert guest['navigationPlanUrl']=='https://example.org/nav-source', guest
assert guest['navigationUrl']=='https://example.org/series/1/episode/15', guest
assert guest['navigationRequestId']=='nav-1' and re.fullmatch(r'[0-9a-f]{64}',guest['navigationSourceHash']), guest
assert guest['moduleDigest']==updated['moduleDigest'] and guest['packageDigest']==updated['packageDigest'], guest
preferences=ep07['retainedPreferences']
assert all(preferences[key] is True for key in [
    'sameIdentity','activeSourcePreserved','preferencesPreserved','releaseGenerationUnchangedByUpdate',
    'preferredNavigationProviderPreserved','visibleInProviderFieldPreserved'
]), preferences
assert preferences['enabledTracks']==['DE_SUB'] and preferences['preferredTrackOrder']==['DE_SUB'], preferences
assert preferences['languageOrder']==['de'], preferences
navigation_pref=ep07['v2NavigationPreferenceProof']
assert all(navigation_pref[key] is True for key in [
    'gatewayListedCurrentV2Provider','realV2DispatchReturnedTarget','coordinatorResolvedValidTarget',
    'preferredNavigationProviderPreserved','visibleInProviderFieldPreserved'
]), navigation_pref
assert navigation_pref['targetUrl']=='https://example.org/series/1/episode/15', navigation_pref
assert navigation_pref['packageDigest']==updated['packageDigest'] and navigation_pref['packageGeneration']==updated['packageGeneration'], navigation_pref
assert all(ep07['staleV1FencedAfterUpdate'][key] is True for key in [
    'generationTokenRejected','navigationProviderRejected','navigationDispatchRejected'
]), ep07['staleV1FencedAfterUpdate']
rollback=ep07['explicitRollback']
assert rollback['targetDigest']==repo['v1']['packageDigest'] and rollback['targetVersion']=='1.0.0-test.1', rollback
assert rollback['expectedGeneration']==2 and rollback['activeV1Restored'] is True, rollback
assert rollback['releaseHighUnchanged'] is True and rollback['packageGeneration']==3, rollback
assert rollback['packageGenerationAdvanced'] is True, rollback
assert all(rollback[key] is True for key in [
    'navigationProviderRetained','preferredNavigationProviderPreserved','visibilityPreferencePreserved'
]), rollback
rolled_guest=rollback['realV1Guest']
assert rolled_guest['planRequests'] is True and rolled_guest['navigation'] is True, rolled_guest
assert rolled_guest['packageDigest']==repo['v1']['packageDigest'] and rolled_guest['moduleDigest']==repo['v1']['moduleDigest'], rolled_guest
assert all(ep07['staleV1FencedAfterRollback'][key] is True for key in [
    'sameDigestDifferentGenerationRejected','consumedNavigationProviderRejected','consumedNavigationTokenRejected'
]), ep07['staleV1FencedAfterRollback']
assert ep07['packageGenerationHistory']==[1,2,3,4], ep07
operations=ep07['operations']
assert all(operations[key] is True for key in [
    'updateFinished','failedUpdateFinished','rollbackFinished','interruptedOperationRecovered','quarantinePersistsAcrossRestart'
]), operations
assert operations['interruptedFailure']=='INTERRUPTED', operations
revoked=ep07['revokedPreviousTarget']
assert revoked['indexSequence']==5 and revoked['indexRevocationWasSignedByTestKey'] is True, revoked
assert re.fullmatch(r'[0-9a-f]{64}',revoked['indexSignerKeyId']), revoked
assert all(revoked[key] is True for key in [
    'v1DigestRecordedRevoked','safePreviousGoodUnavailable','rollbackAttemptDenied',
    'activeV3Preserved','packageGenerationUnchanged','operationFailedClosed'
]), revoked
assert ep07['repositoryTransportUsed'] is True and ep07['productionTransportUsed'] is False, ep07
integrated=f['ep05Canary']['ep07IntegratedDataUpdate']
assert integrated['status']=='PASS' and integrated['testTrustOnly'] is True and integrated['productionPublication'] is False, integrated
assert all(integrated[key] is True for key in [
    'updateKeepsRowsMappingAndCalendar','staleFirstGenerationWorkerFencedByUpdate','fencedWorkerLeftReceiptUntouched',
    'nextDueRunUsesNewActivePackage','freshSkipAfterUpdateExecutesNothing','rollbackKeepsRowsAndMapping',
    'refreshAfterRollbackUsesRestoredPackage','releaseHighWaterKeptAcrossRollback','stalePreRollbackGenerationFenced',
    'sameModuleDigest'
]), integrated
assert integrated['firstPackageDigest']!=integrated['secondPackageDigest'] and len(integrated['generations'])==3, integrated
assert integrated['generations'][0]<integrated['generations'][1]<integrated['generations'][2], integrated
assert integrated['acceptedRowsBeforeUpdate']>0 and integrated['acceptedRowsAfterUpdate']>=integrated['acceptedRowsBeforeUpdate'], integrated
assert integrated['archiveFetches']==2, integrated
print('EP07 INTEGRATED DATA UPDATE PROOF',json.dumps({k:integrated[k] for k in ['layer','generations','acceptedRowsBeforeUpdate','acceptedRowsAfterUpdate','archiveFetches']},separators=(',',':')))
shared=f['ep05Canary']['ep07SharedLedger']
assert shared['status']=='PASS' and shared['testTrustOnly'] is True and shared['productionPublication'] is False, shared
assert all(shared[key] is True for key in [
    'softFreshnessSkipsWithZeroRequests','updateDoesNotResetFreshness','rollbackDoesNotResetFreshness',
    'receiptFollowsPackageWithoutRequest','onlyDueRolesAreAsked','rolesNotDueAreNotRefetched',
    'manualBypassesSoftFreshness','manualNeverBypassesTheHardFloor','deferralIsTypedWithATime',
    'hostDenialDoesNotDamageSourceHealth','skippedCyclesKeepRowsMappingCalendarAndLastSync'
]), shared
cycles=shared['cycles']
assert len(cycles)==8, shared
assert cycles[0]['requestsReachedFixture']>0 and cycles[4]['requestsReachedFixture']>0 and cycles[6]['requestsReachedFixture']>0, shared
assert all(cycles[i]['requestsReachedFixture']==0 and cycles[i]['outcome'].startswith('Skipped:extension-data-fresh') for i in (1,2,3)), shared
assert cycles[7]['requestsReachedFixture']==0 and cycles[7]['outcome'].startswith('Skipped:extension-budget-deferred'), shared
print('EP07 SHARED LEDGER PROOF',json.dumps({'cycles':cycles},separators=(',',':')))
data=f['ep06SingleSourceWorker']['ep07ExtensionData']
assert data['status']=='PASS' and data['testTrustOnly'] is True, data
assert data['acceptedRowsAfterReopen'] > 0, data
assert data['transportFailureRequestsReachedFixture'] > 0 and 'not whole-device offline' in data['transportFailureLayer'], data
print('EP07 TRANSPORT FAILURE PROOF',json.dumps({k:data[k] for k in ['transportFailureLayer','transportFailureRequestsReachedFixture','transportFailureOutcome']},separators=(',',':')))
assert all(data[key] is True for key in [
    'roomConnectionReopened','policyAndReceiptReopened','freshSkipsRuntimeAndNetwork',
    'controlledRefreshFailureKeepsRowsMappingAndReceipt','realTransportFailureKeepsRowsMappingAndReceipt',
    'staleRefreshUsesRealSignedGuestAndProductionTransport','manualExactMappingRetained',
    'acceptedProjectionKeysRetained','refreshedDataSkipsAgain'
]), data
# The first phase runs the actual product Worker, then leaves state for an external force-stop.
assert data['productionWorkManagerDeviceProof'] is True and data['processKillProof'] is False, data
assert data['twoStartupChecksSkipWithoutFullRefresh'] is True and data['rowsVisibleDuringWorkManagerRefresh'] is True, data
assert data['newCalendarRowAdded'] is True and data['changedCalendarRowRevisionApplied'] is True, data
assert data['persistedDatesReachProductCalendar'] is True and data['calendarEventKeysRetainedAcrossCommit'] is True, data
assert data['slotWorkDurableBeforeKill'] is True, data
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
    assert 'INSTRUMENTATION_STATUS_CODE: -1' in text, text
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

# Finish instrumentation, then kill only the now-background process. am kill does not
# set the package's force-stopped state, so its persisted WorkManager job can resume.
wait "$instrumentation_pid" || true
seed_pid=$(python3 - "$out/report.json" <<'PY'
import json,sys
print(json.load(open(sys.argv[1]))['hostPid'])
PY
)
test -n "$seed_pid"
printf '%s\n' "$seed_pid" > "$out/killed-seed-pid.txt"
current_pid=$(adb shell pidof de.kiyori.ep02 | tr -d '\r')
if [[ -n "$current_pid" ]]; then
  adb shell am kill de.kiyori.ep02
fi
for attempt in $(seq 1 30); do
  current_pid=$(adb shell pidof de.kiyori.ep02 | tr -d '\r')
  [[ -z "$current_pid" ]] && break
  sleep 1
done
if [[ -n "$current_pid" ]]; then
  echo "EP07 process kill failed: seedPid=$seed_pid currentPid=$current_pid" >&2
  exit 7
fi
timeout 90 adb shell am instrument -w -r de.kiyori.ep02/.Ep07RestartInstrumentation | tee "$out/restart-instrumentation.txt"
python3 - "$out" <<'PY'
from pathlib import Path
import json,sys
root=Path(sys.argv[1])
text=(root/'restart-instrumentation.txt').read_text()
line=next((line for line in text.splitlines() if line.startswith('INSTRUMENTATION_RESULT: ep07Restart=')),None)
assert line is not None, text
restart=json.loads(line.split('=',1)[1])
assert restart['status']=='PASS' and restart['testTrustOnly'] is True, restart
assert restart['seedPid']!=restart['restartPid'] and restart['acceptedRows']>0, restart
assert str(restart['seedPid']) == (root/'killed-seed-pid.txt').read_text().strip(), restart
assert all(restart[key] is True for key in [
    'persistedRowsReadBeforeScheduling','mappingAvailableBeforeRefresh','signedPackageReverified',
    'actualProductWorkManagerWorker','freshSkipsNetworkAndRuntime','processKillProof',
    'productCalendarDatesAvailableBeforeRefresh','slotWorkSurvivedProcessKill'
]), restart
assert 'EP07_RESTART_PASS' in text and 'INSTRUMENTATION_CODE: -1' in text, text
(root/'restart-report.json').write_text(json.dumps(restart,indent=2)+'\n')
report=json.loads((root/'report.json').read_text())
report['ep07ProcessRestart']=restart
(root/'report.json').write_text(json.dumps(report,indent=2)+'\n')
print('EP07 PROCESS RESTART VERIFIED',json.dumps(restart,separators=(',',':')))
PY
