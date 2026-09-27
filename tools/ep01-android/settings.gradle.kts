pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
 repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
 repositories { google(); mavenCentral() }
 versionCatalogs { create("projectLibs") { from(files("../../gradle/libs.versions.toml")) } }
}
rootProject.name = "ep01-android-runtime-spike"
include(":app")
