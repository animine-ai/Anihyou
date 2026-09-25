#!/usr/bin/env bash
set -euo pipefail

fail=0

while IFS= read -r path; do
  case "$path" in
    .ai/CONTEXT.md) ;;
    .ai/*|PLAN.md|projects/*|prompts/*|handoffs/*|docs/ai-context/*|ai-context/*)
      echo "Forbidden AI context path in application repository: $path" >&2
      fail=1
      ;;
    private/evidence/aniworld/*.md)
      echo "AniWorld context/evidence markdown belongs in master-context: $path" >&2
      fail=1
      ;;
  esac
done < <(git ls-files)

if git grep -I -n -E 'madebycli|Kiyori-Privat|kiyori-privat|github\.com/madebycli' -- .   ':(exclude).git' >/tmp/context-boundary-oldrefs.txt 2>/dev/null; then
  cat /tmp/context-boundary-oldrefs.txt >&2
  echo "Old repository/account references are forbidden." >&2
  fail=1
fi

if [[ -f .ai/CONTEXT.md ]]; then
  grep -Fq 'https://github.com/itlspqr/master-context' .ai/CONTEXT.md || { echo "Wrong context_repo" >&2; fail=1; }
  grep -Fq 'project_id: animetracker' .ai/CONTEXT.md || { echo "Wrong project_id" >&2; fail=1; }
  grep -Fq 'https://github.com/itlspqr/Animetracker' .ai/CONTEXT.md || { echo "Wrong source_repo" >&2; fail=1; }
else
  echo "Missing .ai/CONTEXT.md on maintained branch." >&2
  fail=1
fi

exit "$fail"
