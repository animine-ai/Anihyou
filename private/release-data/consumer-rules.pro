# Rust JNI exports are resolved by their Java binary names. Keep the bridge method names
# stable in every consuming release build, not only in the EP02 proof application.
-keep class com.axiel7.anihyou.release.data.extension.WasmtimeNativeBridge { native <methods>; }

# Android instantiates the isolated service from the library manifest.
-keep public class com.axiel7.anihyou.release.data.extension.WasmtimeRuntimeService { public <init>(); }

# Apache Commons Compress exposes optional XZ/Zstandard adapters. AREX v1 admits only
# ZIP STORED and DEFLATED entries before reading payload data, so these optional codecs
# are unreachable by an accepted package and intentionally are not shipping dependencies.
-dontwarn com.github.luben.zstd.**
-dontwarn org.tukaani.xz.**
