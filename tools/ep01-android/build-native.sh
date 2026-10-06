#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
ndk_version=27.2.12479018
sdkmanager "ndk;$ndk_version"
rustup toolchain install 1.90.0 --profile minimal
rustup target add --toolchain 1.90.0 x86_64-linux-android
export PATH="$ANDROID_HOME/ndk/$ndk_version/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER=x86_64-linux-android24-clang
export CC_x86_64_linux_android=x86_64-linux-android24-clang
export AR_x86_64_linux_android=llvm-ar
export CARGO_RESOLVER_INCOMPATIBLE_RUST_VERSIONS=fallback
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
mkdir -p "$root/results/native" "$root/native-out"
if [[ ! -f "$root/native/Cargo.lock" ]]; then
  cargo +1.90.0 generate-lockfile --manifest-path "$root/native/Cargo.toml"
fi
cargo +1.90.0 build --locked --release --target x86_64-linux-android --manifest-path "$root/native/Cargo.toml" 2>&1 | tee "$root/results/native/build.txt"
cp "$root/native/target/x86_64-linux-android/release/libep01_runtime.so" "$root/native-out/"
cp "$root/native/Cargo.lock" "$root/native-out/"
rustc +1.90.0 --version > "$root/results/native/toolchain.txt"
printf 'NDK=%s\nANDROID_API=24\nTARGET=x86_64-linux-android\nNATIVE_PROFILE=release\n' "$ndk_version" >> "$root/results/native/toolchain.txt"
cargo +1.90.0 tree --locked --manifest-path "$root/native/Cargo.toml" > "$root/results/native/dependencies.txt"
llvm-readelf -h -l -d "$root/native-out/libep01_runtime.so" > "$root/results/native/elf.txt"
sha256sum "$root/native-out/libep01_runtime.so" "$root/native/Cargo.lock" > "$root/results/native/sha256.txt"
