#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/run-aniworld-shadow-canary.sh --build
  scripts/run-aniworld-shadow-canary.sh --install-and-run [--package APP_ID] [--timeout-seconds N]
  scripts/run-aniworld-shadow-canary.sh --report-only [--package APP_ID] [--output FILE]

--build            Build the Foss debug canary APK with -PaniworldShadowCanary=true.
--install-and-run  Build, install, launch once, wait up to a bounded timeout, and export JSON.
--report-only      Export existing Room-v13 canary telemetry from one debuggable device.
EOF
}

mode=""
package_id="com.axiel7.anihyou.debug"
timeout_seconds=300
output_path=""
while (($#)); do
  case "$1" in
    --build|--install-and-run|--report-only)
      [[ -z "$mode" ]] || { echo "Choose exactly one mode." >&2; exit 2; }
      mode="$1"
      shift
      ;;
    --package)
      [[ $# -ge 2 ]] || { echo "--package needs an application id." >&2; exit 2; }
      package_id="$2"
      shift 2
      ;;
    --timeout-seconds)
      [[ $# -ge 2 ]] || { echo "--timeout-seconds needs a value." >&2; exit 2; }
      timeout_seconds="$2"
      shift 2
      ;;
    --output)
      [[ $# -ge 2 ]] || { echo "--output needs a path." >&2; exit 2; }
      output_path="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

[[ -n "$mode" ]] || { usage >&2; exit 2; }
[[ "$package_id" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] || {
  echo "Invalid Android application id." >&2
  exit 2
}
[[ "$timeout_seconds" =~ ^[0-9]+$ ]] && ((timeout_seconds >= 1 && timeout_seconds <= 900)) || {
  echo "--timeout-seconds must be in 1..900." >&2
  exit 2
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
report_tool="$repo_root/scripts/aniworld-shadow-canary-report.py"
[[ -f "$report_tool" ]] || { echo "Missing report tool: $report_tool" >&2; exit 2; }
python_bin="${PYTHON:-python3}"
command -v "$python_bin" >/dev/null 2>&1 || { echo "Python 3 was not found: $python_bin" >&2; exit 2; }

build_canary() {
  [[ -x "$repo_root/gradlew" ]] || { echo "Gradle wrapper is missing or not executable." >&2; exit 2; }
  (cd "$repo_root" && ./gradlew --console=plain --stacktrace :app:assembleFossDebug -PaniworldShadowCanary=true) >&2
  local build_config
  build_config="$(rg --files "$repo_root/app/build/generated/source/buildConfig" 2>/dev/null | rg '/foss/debug/.*/BuildConfig\.java$' | head -n 1 || true)"
  [[ -n "$build_config" ]] || { echo "Could not find generated Foss debug BuildConfig." >&2; exit 2; }
  rg -q 'ANIWORLD_SHADOW_CANARY\s*=\s*true' "$build_config" || {
    echo "The generated APK is not marked as an explicit shadow canary build." >&2
    exit 2
  }
  local apk
  apk="$(find "$repo_root/app/build/outputs/apk/foss/debug" -maxdepth 1 -type f -name '*universal*.apk' -print -quit)"
  [[ -n "$apk" ]] || { echo "Universal Foss debug APK was not produced." >&2; exit 2; }
  printf '%s\n' "$apk"
}

select_device() {
  command -v adb >/dev/null 2>&1 || { echo "adb was not found on PATH." >&2; exit 2; }
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    local state
    state="$(adb -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)"
    [[ "$state" == "device" ]] || { echo "ANDROID_SERIAL is not a connected authorized device." >&2; exit 2; }
    return
  fi
  local -a devices=()
  mapfile -t devices < <(adb devices | awk '$2 == "device" { print $1 }')
  if (("${#devices[@]}" != 1)); then
    echo "Expected exactly one authorized adb device; found ${#devices[@]}. Set ANDROID_SERIAL to select one." >&2
    exit 2
  fi
  export ANDROID_SERIAL="${devices[0]}"
}

if [[ "$mode" == "--build" ]]; then
  apk="$(build_canary)"
  echo "Canary APK: $apk"
  exit 0
fi

if [[ "$mode" == "--report-only" ]]; then
  select_device
  args=(--package "$package_id")
  [[ -z "$output_path" ]] || args+=(--output "$output_path")
  exec "$python_bin" "$report_tool" "${args[@]}"
fi

select_device
apk="$(build_canary)"
adb install -r "$apk"
adb shell run-as "$package_id" id >/dev/null 2>&1 || {
  echo "Installed package is not debuggable; refusing to start/export." >&2
  exit 2
}
before_id="$("$python_bin" "$report_tool" --package "$package_id" --latest-generation-id --allow-missing-db 2>/dev/null || true)"
echo "Launching one explicit debug canary on ${ANDROID_SERIAL:-selected device}."
adb shell monkey -p "$package_id" -c android.intent.category.LAUNCHER 1
if [[ -z "$output_path" ]]; then
  output_path="aniworld-shadow-report-$(date -u '+%Y%m%dT%H%M%SZ').json"
fi
"$python_bin" "$report_tool" \
  --package "$package_id" \
  --limit 10 \
  --wait-for-new-generation-after "$before_id" \
  --timeout-seconds "$timeout_seconds" \
  --output "$output_path"
