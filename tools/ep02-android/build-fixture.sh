#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
rust_toolchain=1.95.0
rustup toolchain install "$rust_toolchain" --profile minimal
rustup target add --toolchain "$rust_toolchain" wasm32-unknown-unknown
cargo +"$rust_toolchain" build --locked --release --manifest-path "$root/fixture/Cargo.toml"
mkdir -p "$root/host-app/src/main/assets"
cp "$root/fixture/target/wasm32-unknown-unknown/release/ep02_runtime_fixture.wasm" "$root/host-app/src/main/assets/fixture.wasm"
sha256sum "$root/host-app/src/main/assets/fixture.wasm" > "$root/host-app/src/main/assets/fixture.wasm.sha256"
