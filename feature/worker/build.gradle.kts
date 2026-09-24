plugins {
    alias(libs.plugins.anihyou.feature)
}

val appPackageName = rootProject.extra["appPackageName"] as String

android {
    namespace = "$appPackageName.feature.worker"
}

dependencies {
    implementation(project(":private:release-core"))
    implementation(project(":core:domain"))
    implementation(libs.androidx.work.runtime)

    implementation(libs.koin.workmanager)

    testImplementation(libs.junit)
}
