#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")" && pwd)"
rust_toolchain=1.95.0
manifest="$root/fixture/Cargo.toml"

rustup toolchain install "$rust_toolchain" --profile minimal
rustup target add --toolchain "$rust_toolchain" wasm32-unknown-unknown

target_dir="$(
  cargo +"$rust_toolchain" metadata     --no-deps     --format-version 1     --manifest-path "$manifest" |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["target_directory"])'
)"
cargo +"$rust_toolchain" build --locked --release --manifest-path "$manifest"

wasm="$target_dir/wasm32-unknown-unknown/release/ep02_runtime_fixture.wasm"
test -f "$wasm"

assets="$root/host-app/src/main/assets"
mkdir -p "$assets"
cp "$wasm" "$assets/fixture.wasm"
sha256sum "$assets/fixture.wasm" > "$assets/fixture.wasm.sha256"
