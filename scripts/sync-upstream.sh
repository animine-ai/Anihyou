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
rm -rf .github/workflows
git add -A
if ! git diff --cached --quiet; then
  git commit -m "Mirror AniHyou upstream without workflow definitions"
fi
git push --force origin "HEAD:refs/heads/$TARGET_BRANCH"
