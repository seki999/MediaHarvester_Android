plugins { id("com.android.library"); kotlin("android") }
android {
 namespace = "com.mediaharvester.data"
 compileSdk = 35
 defaultConfig { minSdk = 29; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 
}
dependencies {
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 implementation("androidx.core:core-ktx:1.15.0")
 testImplementation("junit:junit:4.13.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test:runner:1.6.2")
 implementation(project(":domain"))
 implementation(project(":downloader"))
 api(project(":database"))
 implementation(project(":extractor"))
 implementation(project(":storage"))
 implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
}
