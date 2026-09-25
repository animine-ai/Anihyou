#!/usr/bin/env bash
set -euo pipefail

UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/axiel7/AniHyou-android.git}"
UPSTREAM_REF="${UPSTREAM_REF:-master}"
TARGET_BRANCH="${TARGET_BRANCH:-upstream/anihyou-master}"

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

if git remote get-url upstream >/dev/null 2>&1; then
  git remote set-url upstream "$UPSTREAM_URL"
else
  git remote add upstream "$UPSTREAM_URL"
fi

git fetch --prune upstream "$UPSTREAM_REF"
git checkout -B upstream-sync "refs/remotes/upstream/$UPSTREAM_REF"

# The mirror is source-only. Project AI context is centralized in itlspqr/master-context.
rm -rf .github/workflows .ai projects prompts handoffs docs/ai-context ai-context
rm -f PLAN.md
if [[ -d private/evidence/aniworld ]]; then
  find private/evidence/aniworld -maxdepth 1 -type f -name '*.md' -delete
fi

git add -A
if ! git diff --cached --quiet; then
  git commit -m "Mirror AniHyou upstream without workflows or project context"
fi
git push --force origin "HEAD:refs/heads/$TARGET_BRANCH"
