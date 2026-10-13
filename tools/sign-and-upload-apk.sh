#!/usr/bin/env bash
# Nur Signierschlüssel + GitHub-Secrets. Kein App-Build.
set -euo pipefail
umask 077
if ! command -v gh >/dev/null || ! command -v keytool >/dev/null; then
  printf -v start 'exec bash %q' "$(realpath "$0")"
  exec nix-shell -p gh jdk17 --run "$start"
fi
repo=animine-ai/Anihyou
key="$PWD/kiyori-release.jks"
gh auth status >/dev/null 2>&1 || gh auth login --web
existing=$(gh secret list --repo "$repo" --json name --jq '.[].name')
if grep -Eq '^ANDROID_(KEYSTORE_BASE64|KEYSTORE_PASSWORD|KEY_ALIAS|KEY_PASSWORD)$' <<< "$existing"; then
  echo "Signier-Secrets existieren bereits. Werden nicht ersetzt."
  exit 0
fi
read -rsp "Keystore-Passwort (mindestens 6 Zeichen): " ANDROID_SIGN_PASSWORD; echo
[[ ${#ANDROID_SIGN_PASSWORD} -ge 6 ]] || { echo "Passwort zu kurz."; exit 1; }
export ANDROID_SIGN_PASSWORD
if [[ ! -f "$key" ]]; then
  keytool -genkeypair -noprompt -keystore "$key" -storetype JKS \
    -alias kiyori -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Kiyori" -storepass:env ANDROID_SIGN_PASSWORD -keypass:env ANDROID_SIGN_PASSWORD
fi
keytool -list -keystore "$key" -alias kiyori -storepass:env ANDROID_SIGN_PASSWORD >/dev/null
base64 -w0 "$key" | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$repo"
printf '%s' "$ANDROID_SIGN_PASSWORD" | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$repo"
printf '%s' kiyori | gh secret set ANDROID_KEY_ALIAS --repo "$repo"
printf '%s' "$ANDROID_SIGN_PASSWORD" | gh secret set ANDROID_KEY_PASSWORD --repo "$repo"
unset ANDROID_SIGN_PASSWORD
echo "Fertig. Schlüssel lokal sichern: $key"
