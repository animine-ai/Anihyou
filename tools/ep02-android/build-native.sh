#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
ndk_version=27.2.12479018
rust_toolchain=1.95.0
sdkmanager "ndk;$ndk_version"
rustup toolchain install "$rust_toolchain" --profile minimal
rustup target add --toolchain "$rust_toolchain" x86_64-linux-android aarch64-linux-android
export PATH="$ANDROID_HOME/ndk/$ndk_version/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER=x86_64-linux-android24-clang
export CC_x86_64_linux_android=x86_64-linux-android24-clang
export AR_x86_64_linux_android=llvm-ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=aarch64-linux-android24-clang
export CC_aarch64_linux_android=aarch64-linux-android24-clang
export AR_aarch64_linux_android=llvm-ar
export CARGO_RESOLVER_INCOMPATIBLE_RUST_VERSIONS=fallback
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
mkdir -p "$root/results/native" "$root/native-out"
lock="$root/native/Cargo.lock"
if [[ ! -f "$lock" ]]; then
  cargo +"$rust_toolchain" generate-lockfile --manifest-path "$root/native/Cargo.toml"
fi
expected_lock_sha256="0f1caff29b8444068b46e96c3a3641d3d805d86c827a4b2ed9189b86a00fd7df"
actual_lock_sha256="$(sha256sum "$lock" | awk '{print $1}')"
if [[ "$actual_lock_sha256" != "$expected_lock_sha256" ]]; then
  printf 'EP02 native dependency graph drift: expected %s, got %s\n' "$expected_lock_sha256" "$actual_lock_sha256" >&2
  exit 4
fi
build_target() {
  local target="$1"
  local abi="$2"
  mkdir -p "$root/native-out/$abi"
  cargo +"$rust_toolchain" build --locked --release --target "$target" --manifest-path "$root/native/Cargo.toml" \
    2>&1 | tee "$root/results/native/build-$abi.txt"
  cp "$root/native/target/$target/release/libarex_runtime.so" "$root/native-out/$abi/"
  llvm-readelf -h -l -d "$root/native-out/$abi/libarex_runtime.so" > "$root/results/native/elf-$abi.txt"
}

build_target x86_64-linux-android x86_64
build_target aarch64-linux-android arm64-v8a
cp "$root/native/Cargo.lock" "$root/native-out/"

rustc +"$rust_toolchain" --version > "$root/results/native/toolchain.txt"
printf 'WASMTIME=48.0.3\nNDK=%s\nANDROID_API=24\nTARGETS=x86_64-linux-android,aarch64-linux-android\nNATIVE_PROFILE=release\n' "$ndk_version" >> "$root/results/native/toolchain.txt"
cargo +"$rust_toolchain" tree --locked --manifest-path "$root/native/Cargo.toml" > "$root/results/native/dependencies.txt"
sha256sum \
  "$root/native-out/x86_64/libarex_runtime.so" \
  "$root/native-out/arm64-v8a/libarex_runtime.so" \
  "$root/native/Cargo.lock" > "$root/results/native/sha256.txt"
