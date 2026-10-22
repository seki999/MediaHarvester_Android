import java.security.MessageDigest
plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 bundle { language { enableSplit = false } }
 sourceSets.getByName("main").assets.srcDir(rootProject.file("licenses"))
 namespace = "com.mediaharvester.app"
 compileSdk = 35
 defaultConfig { minSdk = 29; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; applicationId = "com.mediaharvester.android"; targetSdk = 35; versionCode = 1; versionName = "0.1.0"; ndk { abiFilters += (providers.gradleProperty("targetAbis").orNull?.split(",") ?: listOf("arm64-v8a", "x86_64")) } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 buildFeatures { compose = true }
}
dependencies {
 androidTestImplementation(project(":downloader"))
 androidTestImplementation(project(":storage"))
 androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
 androidTestImplementation("androidx.test:core-ktx:1.6.1")
 androidTestImplementation("androidx.test:rules:1.6.1")

 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 implementation("androidx.core:core-ktx:1.15.0")
 testImplementation("junit:junit:4.13.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test:runner:1.6.2")
 implementation(project(":ui"))
 implementation(project(":data"))
 implementation(project(":database"))
 implementation(project(":domain"))
 implementation(project(":extractor"))
 implementation("androidx.activity:activity-compose:1.9.3")
 implementation("androidx.compose.material3:material3:1.3.1")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
}

android {
 packaging { jniLibs { useLegacyPackaging = true }; resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
 signingConfigs {
  create("release") {
   if (System.getenv("MH_KEYSTORE") != null) {
    storeFile = file(System.getenv("MH_KEYSTORE")); storePassword = System.getenv("MH_STORE_PASSWORD")
    keyAlias = System.getenv("MH_KEY_ALIAS"); keyPassword = System.getenv("MH_KEY_PASSWORD")
   }
  }
 }
 buildTypes { getByName("release") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("release").takeIf {System.getenv("MH_KEYSTORE") != null} } }
}

tasks.register("dependencyInventory") {
 doLast {
  val entries=configurations.getByName("debugRuntimeClasspath").incoming.resolutionResult.allComponents.mapNotNull {it.moduleVersion}.map {it.toString()}.distinct().sorted()
  rootProject.file("docs/dependencies.tsv").writeText("coordinate\n"+entries.joinToString("\n")+"\n")
 }
}
