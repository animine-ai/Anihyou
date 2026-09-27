#!/usr/bin/env bash
set -euo pipefail
variant=${1:?debug or release required}
case "$variant" in debug|release);; *) exit 2;; esac
root="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$root/results"
adb install -r "$root/app/build/outputs/apk/$variant/app-$variant.apk"
adb logcat -c
timeout 180 adb shell am instrument -w de.kiyori.ep01/.SpikeInstrumentation | tee "$root/results/instrumentation.txt"
adb logcat -d > "$root/results/logcat.txt"
python3 - "$root/results" <<'PY'
from pathlib import Path
import json,sys
root=Path(sys.argv[1]);log=(root/'instrumentation.txt').read_text()
assert 'EP01_ANDROID_PASS' in log and 'EP01_ANDROID_FAIL' not in log,log
line=next(l for l in log.splitlines() if l.startswith('INSTRUMENTATION_RESULT: ep01='))
data=json.loads(line.split('=',1)[1]);assert data['passed'] is True
assert len(data['runtime']['checks'])>=13 and len(data['isolation'])>=8
(root/'report.json').write_text(json.dumps(data,indent=2)+'\n')
print('VERIFIED ANDROID RESULT',json.dumps(data))
PY
