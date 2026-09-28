plugins {
    alias(libs.plugins.android.library)
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.axiel7.anihyou.release.data"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        getByName("test").resources.srcDir(rootProject.file("private/evidence"))
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.provider)
    implementation(libs.java.json.canonicalization)
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation(project(":core:base"))
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":private:release-core"))
    implementation("org.jsoup:jsoup:1.18.3")
    implementation(libs.androidx.datastore.preferences)
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    testImplementation(libs.junit)
    testImplementation(libs.apache.commons.compress)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.0")
    testImplementation("androidx.room:room-testing:2.8.5")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")

    androidTestImplementation("androidx.room:room-testing:2.8.5")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(libs.junit)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
