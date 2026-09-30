plugins {
    alias(libs.plugins.anihyou.feature)
}

val appPackageName = rootProject.extra["appPackageName"] as String

android {
    namespace = "$appPackageName.feature.usermedialist"
}

dependencies {
    implementation(project(":private:release-core"))
    implementation(project(":feature:editmedia"))
    implementation(project(":feature:genrestags"))

    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
}
