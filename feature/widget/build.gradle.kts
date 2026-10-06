plugins {
    alias(libs.plugins.anihyou.feature)
}

val appPackageName = rootProject.extra["appPackageName"] as String

android {
    namespace = "$appPackageName.widget"
}

dependencies {
    testImplementation(libs.junit)
    implementation(project(":private:release-core"))
    implementation(libs.apollo.normalized.cache)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.preview)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.glance.appwidget.preview)

    implementation(libs.androidx.datastore.preferences)
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("stability_config.conf"))
}

composeStabilityAnalyzer {
    stabilityConfigurationFiles.add(isolated.rootProject.projectDirectory.file("stability_config.conf"))
}
