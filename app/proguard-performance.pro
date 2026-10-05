# Keep readable names and the full data-decision logger in optimized, temporary-signed test builds.
# Source lines and the same-build R8 mapping keep stack traces interpretable.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
-keep class com.axiel7.anihyou.release.core.log.AppLog** { *; }
-keep class com.axiel7.anihyou.App { *; }
