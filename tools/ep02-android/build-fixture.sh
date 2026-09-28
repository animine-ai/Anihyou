#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")" && pwd)"
rust_toolchain=1.95.0
target=wasm32-unknown-unknown
manifest="$root/fixture/Cargo.toml"
target_dir="$root/fixture/target"

rustup toolchain install "$rust_toolchain" --profile minimal
rustup target add --toolchain "$rust_toolchain" "$target"

cargo +"$rust_toolchain" build   --locked   --release   --target "$target"   --target-dir "$target_dir"   --manifest-path "$manifest"

wasm="$target_dir/$target/release/ep02_runtime_fixture.wasm"
test -s "$wasm"

assets="$root/host-app/src/main/assets"
mkdir -p "$assets"
cp "$wasm" "$assets/fixture.wasm"
sha256sum "$assets/fixture.wasm" > "$assets/fixture.wasm.sha256"
