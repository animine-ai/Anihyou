#!/usr/bin/env bash
set -euo pipefail
# Name the failing command and its status in the job log. A SIGKILL (137) of the shell itself cannot be reported by
# the shell, but a child that returns 137 is named here. No secrets are printed: only command text and numbers.
trap 'rc=$?; printf "EP02 DIAG %s failing command rc=%s line=%s: %s\n" "$(date -u +%T.%N)" "$rc" "$LINENO" "$BASH_COMMAND" >&2' ERR

# The emulator uses the runner's test-only DNS. Its ordinary app UID resolves
# a public test address and connects through the production DNS/socket/TLS path.
out="${1:?result directory required}"
test "$(adb shell id -u | tr -d '\r')" != 0 || {
  echo 'EP02 fail-closed: fixture app must run as an unrooted Android UID.' >&2
  exit 20
}
ip route get 8.8.8.8 | grep -F 'local 8.8.8.8' >/dev/null || {
  echo 'EP02 fail-closed: runner public-address route is missing.' >&2
  exit 21
}
adb forward tcp:8443 tcp:8443
sudo python3 -u "$(dirname "$0")/test-https-socket-proxy.py" "$out/proxy.pid" > "$out/proxy.log" 2>&1 &
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  if [[ -s "$out/proxy.pid" ]] && sudo kill -0 "$(cat "$out/proxy.pid")"; then
    break
  fi
  sleep 1
done
if [[ -s "$out/proxy.pid" ]] && sudo kill -0 "$(cat "$out/proxy.pid")"; then
  # Let Android validate the hermetic network using an actual local HTTP 204 probe.
  # Keep the product WorkManager CONNECTED constraint and OS callbacks intact.
  adb shell settings put global captive_portal_http_url http://8.8.8.8/generate_204
  adb shell settings put global captive_portal_fallback_url http://8.8.8.8/generate_204
  adb shell settings put global captive_portal_use_https 0
  if [[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" -ge 29 ]]; then
    adb shell device_config put connectivity captive_portal_use_https 0
  fi
  adb shell svc wifi disable
  adb shell svc wifi enable
  for attempt in $(seq 1 45); do
    adb shell dumpsys connectivity > "$out/network-validation.txt"
    if grep -E 'NetworkAgentInfo.*VALIDATED|Capabilities:.*VALIDATED' "$out/network-validation.txt" >/dev/null; then
      echo 'EP02 hermetic DNS, HTTPS relay and Android-validated network ready.'
      exit 0
    fi
    sleep 1
  done
  echo 'EP07 fail-closed: Android did not validate the hermetic test network.' >&2
  cat "$out/network-validation.txt" >&2
  exit 23
fi
echo 'EP02 fail-closed: runner HTTPS proxy did not start.' >&2
cat "$out/proxy.log" >&2
exit 22
