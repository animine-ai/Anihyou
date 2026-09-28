# Rust JNI exports are resolved by their Java binary names. Keep the bridge method names
# stable in every consuming release build, not only in the EP02 proof application.
-keep class com.axiel7.anihyou.release.data.extension.WasmtimeNativeBridge { native <methods>; }

# Android instantiates the isolated service from the library manifest.
-keep public class com.axiel7.anihyou.release.data.extension.WasmtimeRuntimeService { public <init>(); }
