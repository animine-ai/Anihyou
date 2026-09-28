plugins { alias(projectLibs.plugins.android.application) }
android {
 namespace = "de.kiyori.ep01"
 compileSdk = projectLibs.versions.android.compileSdk.get().toInt()
 defaultConfig {
  applicationId = "de.kiyori.ep01"
  minSdk = projectLibs.versions.android.minSdk.get().toInt()
  targetSdk = projectLibs.versions.android.targetSdk.get().toInt()
  versionCode = 1
  versionName = "ep01-spike"
 }
 buildTypes {
  release {
   isMinifyEnabled = true
   isShrinkResources = true
   signingConfig = signingConfigs.getByName("debug")
   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro")
  }
 }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
  isCoreLibraryDesugaringEnabled = true
 }
}
dependencies {
 coreLibraryDesugaring(projectLibs.desugar.jdk.libs)
}
