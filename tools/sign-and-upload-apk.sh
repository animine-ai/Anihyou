#!/usr/bin/env bash
# bash sign-and-upload-apk.sh [release-tag]
# Builds and signs exclusively in GitHub Actions, then publishes a prerelease.
set -Eeuo pipefail
if ! command -v gh >/dev/null || ! command -v jq >/dev/null || ! command -v sha256sum >/dev/null; then
  command -v nix-shell >/dev/null || { echo "Nix oder gh, jq und coreutils installieren." >&2; exit 1; }
  printf -v launcher 'exec bash %q' "$(realpath "$0")"
  for argument in "$@"; do printf -v quoted ' %q' "$argument"; launcher+="$quoted"; done
  exec nix-shell -p gh jq coreutils --run "$launcher"
fi
[[ $# -le 1 ]] || { echo "Aufruf: bash $0 [release-tag]" >&2; exit 2; }
repo=animine-ai/Anihyou
workflow=apk-debug-quick.yml
gh auth status --hostname github.com >/dev/null 2>&1 || gh auth login --hostname github.com --web
request="apk-$(date -u +%Y%m%dT%H%M%SZ)-$$-$RANDOM"
tag=${1:-"test-$request"}
[[ "$tag" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || { echo "Ungültiger Release-Tag." >&2; exit 2; }
# Distinguish a missing release from authorization/network errors.
tags=$(gh api --paginate "repos/$repo/releases" --jq '.[].tag_name')
if printf '%s\n' "$tags" | grep -Fxq -- "$tag"; then
  echo "Release $tag existiert bereits. Anderen Tag wählen." >&2; exit 1
fi
echo "Starte Build auf GitHub. Lokal wird keine APK gebaut."
gh workflow run "$workflow" --repo "$repo" --ref main -f "request_id=$request"
run_id=""
for ((attempt=0; attempt<60; attempt++)); do
  runs=$(gh run list --repo "$repo" --workflow "$workflow" --branch main --event workflow_dispatch --limit 100 --json databaseId,displayTitle)
  run_id=$(jq -r --arg title "Performance APK $request" '[.[] | select(.displayTitle == $title)][0].databaseId // empty' <<< "$runs")
  [[ -z "$run_id" ]] || break
  sleep 5
done
[[ -n "$run_id" ]] || { echo "Build nicht gefunden. GitHub Actions prüfen." >&2; exit 1; }
echo "https://github.com/$repo/actions/runs/$run_id"
gh run watch "$run_id" --repo "$repo" --exit-status
run=$(gh run view "$run_id" --repo "$repo" --json headSha,conclusion,url)
[[ $(jq -r .conclusion <<< "$run") == success ]] || exit 1
sha=$(jq -r .headSha <<< "$run")
build_url=$(jq -r .url <<< "$run")
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
gh run download "$run_id" --repo "$repo" --name "performance-apk-$sha" --dir "$tmp"
(
  cd "$tmp"
  [[ -f SHA256.txt && -f signature-verification.txt ]] || { echo "Buildbelege fehlen." >&2; exit 1; }
  sha256sum --check SHA256.txt
)
shopt -s nullglob
apks=("$tmp"/*.apk)
[[ ${#apks[@]} -eq 1 ]] || { echo "Erwartet genau eine ARM64-APK." >&2; exit 1; }
grep -q 'Signer #1 certificate SHA-256 digest:' "$tmp/signature-verification.txt" || {
  echo "CI-Signaturnachweis fehlt." >&2; exit 1
}
# Ensure the SHA-addressed tag exists and cannot accidentally name a different commit.
if tag_ref=$(gh api "repos/$repo/git/ref/tags/$tag" 2>"$tmp/tag-error"); then
  [[ $(jq -r .object.type <<< "$tag_ref") == commit && $(jq -r .object.sha <<< "$tag_ref") == "$sha" ]] || {
    echo "Vorhandener Tag zeigt nicht direkt auf den Build-Commit." >&2; exit 1
  }
else
  grep -q 'HTTP 404' "$tmp/tag-error" || { cat "$tmp/tag-error" >&2; exit 1; }
  gh api --method POST "repos/$repo/git/refs" -f "ref=refs/tags/$tag" -f "sha=$sha" >/dev/null
fi
printf 'Commit: %s\nBuild: %s\n\nARM64 Performance-Test-APK mit temporärer CI-Testsignatur. Keine stabile Release-Signatur; bei abweichendem Zertifikat ist kein Update über die bestehende Installation möglich.\n' "$sha" "$build_url" > "$tmp/notes.md"
gh release create "$tag" "${apks[@]}" "$tmp/SHA256.txt" "$tmp/signature-verification.txt" \
  --repo "$repo" --verify-tag --prerelease --title "Kiyori Test $tag" --notes-file "$tmp/notes.md"
echo "Fertig: https://github.com/$repo/releases/tag/$tag"
