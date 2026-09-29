#!/usr/bin/env bash
set -euo pipefail

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
    echo 'EP02 hermetic DNS and public-address HTTPS proxy ready.'
    exit 0
  fi
  sleep 1
done
echo 'EP02 fail-closed: runner HTTPS proxy did not start.' >&2
cat "$out/proxy.log" >&2
exit 22
