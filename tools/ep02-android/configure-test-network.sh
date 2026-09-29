#!/usr/bin/env bash
set -euo pipefail

# Route the production socket's public 8.8.8.8:443 tuple into an in-emulator TLS fixture.
# The public address still reaches ProductionExtensionHttpTransport's DNS and socket checks;
# only this disposable, root-only Android emulator redirects the local route.
test_ip='8.8.8.8'
test_host='example.org'
private_host='private.example.org'
wrong_host='wrong.example.org'
server_port='8443'

adb root
adb wait-for-device
root_uid="$(adb shell id -u | tr -d '\r')"
if [[ "$root_uid" != 0 ]]; then
  echo 'EP02 fail-closed: AOSP emulator did not grant adb root; public-IP HTTPS fixture was not configured.' >&2
  exit 20
fi

adb remount
adb wait-for-device
root_uid="$(adb shell id -u | tr -d '\r')"
if [[ "$root_uid" != 0 ]]; then
  echo 'EP02 fail-closed: adb lost root after writable-system remount.' >&2
  exit 21
fi

adb shell "ip address show dev lo | grep -F '$test_ip/32' >/dev/null || ip address add '$test_ip/32' dev lo"
adb shell "grep -F ' $test_host' /system/etc/hosts >/dev/null || printf '\n$test_ip $test_host $wrong_host\n127.0.0.1 $private_host\n' >> /system/etc/hosts"

if ! adb shell "iptables -t nat -C OUTPUT -d '$test_ip/32' -p tcp --dport 443 -j REDIRECT --to-ports '$server_port'" >/dev/null 2>&1; then
  adb shell "iptables -t nat -A OUTPUT -d '$test_ip/32' -p tcp --dport 443 -j REDIRECT --to-ports '$server_port'"
fi

if ! adb shell "ip route get '$test_ip' | grep -F 'local $test_ip' >/dev/null"; then
  echo 'EP02 fail-closed: emulator did not install a local route for the public test address.' >&2
  exit 22
fi
if ! adb shell "iptables -t nat -C OUTPUT -d '$test_ip/32' -p tcp --dport 443 -j REDIRECT --to-ports '$server_port'" >/dev/null 2>&1; then
  echo 'EP02 fail-closed: emulator NAT redirect rule is unavailable.' >&2
  exit 23
fi

adb unroot
adb wait-for-device
if [[ "$(adb shell id -u | tr -d '\r')" == 0 ]]; then
  echo 'EP02 fail-closed: test app must run as a regular Android app UID.' >&2
  exit 24
fi
echo 'EP02 test-only public-IP loopback mapping is active; host app will run unrooted.'
