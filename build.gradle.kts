// Top-level build file where you can add configuration options common to all sub-projects/modules.
// The package of the code (namespace). The id of the installed app is separate: it is app.kiyori.
extra.set("appPackageName", "com.axiel7.anihyou")
extra.set("applicationIdBase", "app.kiyori")

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.jetbrains.kotlin.compose) apply false
    alias(libs.plugins.jetbrains.kotlin.serialization) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
    alias(libs.plugins.koin.compiler) apply false
    alias(libs.plugins.stability.analyzer) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.ksp) apply false
}