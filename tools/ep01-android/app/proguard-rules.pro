# Android entry points only; runtime internals remain eligible for shrinking.
-keep public class de.kiyori.ep01.SpikeInstrumentation { public <init>(); }
-keep public class de.kiyori.ep01.SpikeService { public <init>(); }
-keep public class de.kiyori.ep01.SpikeActivity { public <init>(); }
# JNI entry points must retain their native symbol names in the R8 APK.
-keep class de.kiyori.ep01.RuntimeChecks { native <methods>; }
