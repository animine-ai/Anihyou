#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
RUSTC_BIN=${RUSTC_BIN:-rustc}
args=()
if [[ -n "${AREX_SYSROOT:-}" ]]; then args+=(--sysroot "$AREX_SYSROOT"); fi
"$RUSTC_BIN" "${args[@]}" --edition=2021 --crate-type=cdylib --target wasm32-unknown-unknown -C opt-level=s -C panic=abort -C link-arg=--initial-memory=262144 -C link-arg=--max-memory=67108864 -C link-arg=-zstack-size=32768 provider.rs -o provider.wasm
