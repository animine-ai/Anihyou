import java.util.Properties
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.koin.compiler)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.stability.analyzer)
}

val appPackageName = rootProject.extra["appPackageName"] as String
val aniWorldShadowCanaryDebugValue = providers.gradleProperty("aniworldShadowCanary").orNull?.also {
    require(it == "true" || it == "false") { "aniworldShadowCanary must be exactly true or false" }
} ?: "false"
// Public independently reviewed trust material only. No TEST trust or private signing key is a build input.
val extensionBuildProfile = providers.gradleProperty("extensionBuildProfile").orNull ?: "unprovisioned"
require(extensionBuildProfile in setOf("unprovisioned", "reviewed")) {
    "extensionBuildProfile must be unprovisioned or reviewed; TEST trust is not a product profile"
}
val extensionPublicFields = listOf("extensionRepositoryId", "extensionRootSha256",
    "extensionDistributionOrigins", "extensionAllowedHosts", "extensionPublisherId", "extensionSigningKeyId")
    .associateWith { providers.gradleProperty(it).orNull.orEmpty() }
require(if (extensionBuildProfile == "reviewed") extensionPublicFields.values.all(String::isNotEmpty)
    else extensionPublicFields.values.all(String::isEmpty)) {
    "The reviewed profile requires all six public trust inputs; unprovisioned accepts none"
}
require(extensionPublicFields.values.all { it.matches(Regex("[A-Za-z0-9._:/,-]*")) }) {
    "Invalid extension public build field"
}
if (extensionBuildProfile == "reviewed") {
    require(extensionPublicFields.getValue("extensionRootSha256").matches(Regex("[0-9a-f]{64}"))) {
        "extensionRootSha256 must be the independently reviewed root SHA256"
    }
    for (name in listOf("extensionRepositoryId", "extensionPublisherId", "extensionSigningKeyId")) {
        require(extensionPublicFields.getValue(name).matches(Regex("[A-Za-z0-9._-]{1,128}"))) {
            "Invalid public identity: $name"
        }
    }
    val origins = extensionPublicFields.getValue("extensionDistributionOrigins").split(',')
    require(origins.distinct().size == origins.size && origins.all { origin ->
        val uri = java.net.URI(origin)
        uri.scheme == "https" && uri.host != null && uri.rawAuthority == uri.host &&
            uri.path.isEmpty() && uri.rawQuery == null && uri.rawFragment == null
    }) { "Distribution origins must be unique canonical HTTPS origins without credentials/path/query" }
    val hosts = extensionPublicFields.getValue("extensionAllowedHosts").split(',')
    require(hosts.distinct().size == hosts.size && hosts.all { host ->
        host.length <= 253 && host.split('.').all { label ->
            label.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
        }
    }) { "Allowed hosts must be unique lowercase DNS hosts" }
}
val extensionBuildFields = mapOf("EXTENSION_REPOSITORY_ID" to "extensionRepositoryId",
    "EXTENSION_ROOT_SHA256" to "extensionRootSha256",
    "EXTENSION_DISTRIBUTION_ORIGINS" to "extensionDistributionOrigins",
    "EXTENSION_ALLOWED_HOSTS" to "extensionAllowedHosts",
    "EXTENSION_PUBLISHER_ID" to "extensionPublisherId", "EXTENSION_SIGNING_KEY_ID" to "extensionSigningKeyId")

val versionProps = Properties().also {
    it.load(project.rootProject.file("version.properties").reader())
}

android {
    namespace = appPackageName
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = appPackageName
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        buildConfigField("String", "EXTENSION_BUILD_PROFILE", "\"$extensionBuildProfile\"")
        extensionBuildFields.forEach { (field, property) ->
            buildConfigField("String", field, "\"${extensionPublicFields.getValue(property)}\"")
        }
        versionCode = versionProps.getProperty("code").toInt()
        versionName = versionProps.getProperty("name")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    androidResources {
        localeFilters += listOf(
            "en",
            "ja-rJP",
            "ru-rRU",
            "es-rES",
            "tr-rTR",
            "pt-rBR",
            "ar-rSA",
            "in-rID",
            "it-rIT",
            "uk-rUA",
            "pl-rPL",
            "az-rAZ",
            "de-rDE",
            "zh-rCN",
            "zh-rTW",
            "fr-rFR",
            "th-rTH",
            "ro-rRO"
        )
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ANIWORLD_SHADOW_CANARY", aniWorldShadowCanaryDebugValue)
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            buildConfigField("boolean", "ANIWORLD_SHADOW_CANARY", "false")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
        create("benchmarkRelease") {
            buildConfigField("boolean", "ANIWORLD_SHADOW_CANARY", "false")
            matchingFallbacks += listOf("release")
        }
        create("nonMinifiedRelease") {
            buildConfigField("boolean", "ANIWORLD_SHADOW_CANARY", "false")
            matchingFallbacks += listOf("debug")
        }
    }

    flavorDimensions += "version"
    productFlavors {
        create("foss") {
            dimension = "version"
        }
        create("gms") {
            dimension = "version"
            versionNameSuffix = "-gms"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = false
        renderScript = false
        shaders = false
    }
    packaging {
        jniLibs.keepDebugSymbols += "**/libarex_runtime.so"
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    dependenciesInfo {
        includeInApk = false
    }
}

val verifyExtensionNativeRuntime = tasks.register("verifyExtensionNativeRuntime") {
    group = "verification"
    description = "Requires the pinned, verified Wasmtime runtime for product APKs and bundles"
    val nativeDirectory = rootProject.file("tools/ep02-android/native-out")
    val nativePaths = listOf("Cargo.lock", "arm64-v8a/libarex_runtime.so", "x86_64/libarex_runtime.so")
    inputs.files(nativePaths.map { nativeDirectory.resolve(it) })
    inputs.file(nativeDirectory.resolve("SHA256SUMS"))
    doLast {
        fun sha256(file: java.io.File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val manifest = nativeDirectory.resolve("SHA256SUMS")
        check(manifest.isFile) {
            "Missing verified extension native runtime. Run tools/ep02-android/build-native.sh and " +
                ".github/scripts/verify-extension-native-packaging.py --write-checksums before packaging."
        }
        val expected = manifest.readLines().filter(String::isNotBlank).map { line ->
            val match = Regex("([0-9a-f]{64})  (Cargo\\.lock|(?:arm64-v8a|x86_64)/libarex_runtime\\.so)")
                .matchEntire(line) ?: error("Invalid extension native checksum record")
            match.groupValues[2] to match.groupValues[1]
        }
        check(expected.size == nativePaths.size && expected.map { it.first }.toSet() == nativePaths.toSet()) {
            "Incomplete or duplicate extension native checksums"
        }
        for ((path, digest) in expected) {
            val file = nativeDirectory.resolve(path)
            check(file.isFile && file.length() > 0 && sha256(file) == digest) {
                "Missing or modified extension native runtime input: $path"
            }
        }
        check(sha256(nativeDirectory.resolve("Cargo.lock")) ==
            "0f1caff29b8444068b46e96c3a3641d3d805d86c827a4b2ed9189b86a00fd7df") {
            "Extension native dependency graph drift"
        }
    }
}

// Compile/unit-test/schema tasks remain usable without native artifacts. Packaging fails closed.
tasks.configureEach {
    if ((name.startsWith("merge") && name.endsWith("NativeLibs")) ||
        name.matches(Regex("(assemble|bundle)(Foss|Gms)?(Debug|Release|BenchmarkRelease|NonMinifiedRelease)?"))) {
        dependsOn(verifyExtensionNativeRuntime)
    }
}

androidComponents {
    beforeVariants {
        if (it.buildType == "release" && it.flavorName == "foss") {
            it.shrinkResources = false
        }
    }
    onVariants {
        if (it.buildType == "release" && it.flavorName == "gms") {
            // Disable ABI splits for GMS (fix for building bundle)
            it.outputs.forEach { output ->
                if (output.filters.isNotEmpty()) {
                    output.enabled.set(false)
                }
            }
        }
    }
}

base {
    archivesName = "anihyou-${versionProps.getProperty("name")}"
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("stability_config.conf"))
}

baselineProfile {
    dexLayoutOptimization = true
}

koinCompiler {
    compileSafety = false
}

dependencies {
    implementation(project(":private:release-core"))
    implementation(project(":private:release-data"))
    implementation("androidx.room:room-runtime:2.8.5")
    implementation(project(":core:network"))
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))
    implementation(project(":feature:activitydetails"))
    implementation(project(":feature:calendar"))
    implementation(project(":feature:characterdetails"))
    implementation(project(":feature:editmedia"))
    implementation(project(":feature:genrestags"))
    implementation(project(":feature:explore"))
    implementation(project(":feature:home"))
    implementation(project(":feature:login"))
    implementation(project(":feature:mediadetails"))
    implementation(project(":feature:notifications"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:reviewdetails"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:staffdetails"))
    implementation(project(":feature:studiodetails"))
    implementation(project(":feature:thread"))
    implementation(project(":feature:usermedialist"))
    implementation(project(":feature:widget"))
    implementation(project(":feature:worker"))
    implementation(project(":feature:addrecommendation"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.core.performance)

    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.compose.animation.graphics)

    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.window.sizeclass)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(libs.androidx.glance.appwidget.preview)

    "gmsImplementation"(libs.androidx.wear.remote.interactions)

    implementation(libs.accompanist.permissions)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.annotations)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.koin.compose.navigation3)
    implementation(libs.koin.workmanager)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))
}

composeStabilityAnalyzer {
    stabilityConfigurationFiles.add(isolated.rootProject.projectDirectory.file("stability_config.conf"))
}
