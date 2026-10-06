plugins {
    alias(libs.plugins.anihyou.feature)
}

val appPackageName = rootProject.extra["appPackageName"] as String

android {
    namespace = "$appPackageName.feature.explore"
}

dependencies {
    implementation(project(":private:release-core"))
    implementation(project(":feature:editmedia"))
    implementation(project(":feature:genrestags"))
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
