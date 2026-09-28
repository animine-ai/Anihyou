#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
ndk_version=27.2.12479018
rust_toolchain=1.95.0
sdkmanager "ndk;$ndk_version"
rustup toolchain install "$rust_toolchain" --profile minimal
rustup target add --toolchain "$rust_toolchain" x86_64-linux-android
export PATH="$ANDROID_HOME/ndk/$ndk_version/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER=x86_64-linux-android24-clang
export CC_x86_64_linux_android=x86_64-linux-android24-clang
export AR_x86_64_linux_android=llvm-ar
export CARGO_RESOLVER_INCOMPATIBLE_RUST_VERSIONS=fallback
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
mkdir -p "$root/results/native" "$root/native-out"
if [[ ! -f "$root/native/Cargo.lock" ]]; then
  cargo +"$rust_toolchain" generate-lockfile --manifest-path "$root/native/Cargo.toml"
fi
cargo +"$rust_toolchain" build --locked --release --target x86_64-linux-android --manifest-path "$root/native/Cargo.toml" 2>&1 | tee "$root/results/native/build.txt"
cp "$root/native/target/x86_64-linux-android/release/libarex_runtime.so" "$root/native-out/"
cp "$root/native/Cargo.lock" "$root/native-out/"
rustc +"$rust_toolchain" --version > "$root/results/native/toolchain.txt"
printf 'WASMTIME=48.0.3\nNDK=%s\nANDROID_API=24\nTARGET=x86_64-linux-android\nNATIVE_PROFILE=release\n' "$ndk_version" >> "$root/results/native/toolchain.txt"
cargo +"$rust_toolchain" tree --locked --manifest-path "$root/native/Cargo.toml" > "$root/results/native/dependencies.txt"
llvm-readelf -h -l -d "$root/native-out/libarex_runtime.so" > "$root/results/native/elf.txt"
sha256sum "$root/native-out/libarex_runtime.so" "$root/native/Cargo.lock" > "$root/results/native/sha256.txt"
