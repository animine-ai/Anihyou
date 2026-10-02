#!/usr/bin/env bash
set -euo pipefail
api=${1:?API required}
out="tools/ep02-android/results/ep07-product-ui-$api"
mkdir -p "$out"
mapfile -t product_apks < <(find .ci-product-apk -name '*universal*.apk' -type f)
mapfile -t test_apks < <(find .ci-test-apk -name '*.apk' -type f)
test "${#product_apks[@]}" -eq 1
test "${#test_apks[@]}" -eq 1
adb install -r "${product_apks[0]}"
adb install -r "${test_apks[0]}"
# svc wifi disable makes system_server die on the API 24 image (EP02 run 37058629871 logs "FATAL EXCEPTION IN SYSTEM
# PROCESS" at that call), so API 24 runs these assertions with the emulator network left as booted.
if [ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" -ge 28 ]; then
  adb shell svc wifi disable || true
  adb shell svc data disable || true
fi
adb logcat -c
instrumentation_status=0
timeout 600 adb shell am instrument -w -r com.axiel7.anihyou.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$out/instrumentation.txt" || instrumentation_status=$?
adb logcat -d > "$out/logcat.txt"
if [ "$instrumentation_status" -ne 0 ]; then
  echo "== PRODUCT UI DIAG: instrumentation exit $instrumentation_status =="
  grep -E -A 14 "FATAL EXCEPTION IN SYSTEM PROCESS|FATAL EXCEPTION" "$out/logcat.txt" | cut -c1-220 | tail -60 || true
  adb shell "cat /proc/meminfo | head -6; getprop ro.build.version.sdk" || true
  echo "== PRODUCT UI DIAG end =="
fi
adb pull /sdcard/Android/data/com.axiel7.anihyou.debug/files/ep07-ui/. "$out/screenshots/" || true
adb pull /sdcard/Android/data/com.axiel7.anihyou.debug/files/ep07/. "$out/update-screenshots/" || true
sha256sum "${product_apks[0]}" "${test_apks[0]}" > "$out/apk.sha256"
git rev-parse HEAD > "$out/source-commit.txt"
test "$instrumentation_status" -eq 0
python3 - "$out" "$api" <<'PY'
import json,re,sys
from pathlib import Path
out=Path(sys.argv[1]); text=(out/'instrumentation.txt').read_text()
assert 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text and 'Process crashed' not in text, text[-20000:]
match=re.search(r'OK \((\d+) tests?\)',text)
assert match and int(match[1])>0 and 'INSTRUMENTATION_CODE: -1' in text, text[-20000:]
required={'ExtensionCalendarComposeTest','ExtensionUpdateComposeTest','ExtensionSourcesUserFlowTest',
          'MainNavigationProductComposeTest','MediaDetailsNavigationComposeTest'}
for name in required:
    assert name in text, (name,text[-20000:])
assert 'INSTRUMENTATION_STATUS_CODE: -3' not in text and 'INSTRUMENTATION_STATUS_CODE: -4' not in text, 'skipped/assumption-failed test'
shots=list((out/'screenshots').glob('*.png'))
assert len(shots)>=10, [p.name for p in shots]
report={'status':'PASS','api':int(sys.argv[2]),'tests':int(match[1]),'failures':0,'skips':0,
        'requiredSuites':sorted(required),'screenshots':sorted(p.name for p in shots)}
(out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
print('EP07 PRODUCT UI VERIFIED',json.dumps(report,separators=(',',':')))
PY
