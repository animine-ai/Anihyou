#!/usr/bin/env bash
set -euo pipefail

# This disposable runner owns the public-address socket. The Android production
# transport still resolves and connects to 8.8.8.8:443 with ordinary TLS checks.
out="${1:?result directory required}"
test_ip='8.8.8.8'

for attempt in 1 2 3 4 5; do
  if adb root; then break; fi
  if [[ "$attempt" == 5 ]]; then
    echo 'EP02 fail-closed: AOSP emulator did not grant adb root.' >&2
    exit 20
  fi
  adb wait-for-device || true
done
adb wait-for-device
[[ "$(adb shell id -u | tr -d '\r')" == 0 ]] || { echo 'EP02 fail-closed: adb is not root.' >&2; exit 21; }
adb remount
adb wait-for-device
[[ "$(adb shell id -u | tr -d '\r')" == 0 ]] || { echo 'EP02 fail-closed: remount lost root.' >&2; exit 22; }
adb shell "grep -F ' example.org' /system/etc/hosts >/dev/null || printf '\n$test_ip example.org wrong.example.org\n127.0.0.1 private.example.org\n' >> /system/etc/hosts"
adb unroot
adb wait-for-device
[[ "$(adb shell id -u | tr -d '\r')" != 0 ]] || { echo 'EP02 fail-closed: app must run unrooted.' >&2; exit 23; }

# adb forwards runner loopback to the app's local TLS fixture. A runner-only
# proxy listens on the isolated public-address loopback at the production port.
adb forward tcp:8443 tcp:8443
sudo ip address add "$test_ip/32" dev lo
sudo python3 -u "$(dirname "$0")/test-https-socket-proxy.py" "$out/proxy.pid" > "$out/proxy.log" 2>&1 &
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  if [[ -s "$out/proxy.pid" ]] && sudo kill -0 "$(cat "$out/proxy.pid")"; then
    echo 'EP02 runner HTTPS proxy ready; Android app is unrooted.'
    exit 0
  fi
  sleep 1
done
echo 'EP02 fail-closed: runner HTTPS proxy did not start.' >&2
cat "$out/proxy.log" >&2
exit 24
