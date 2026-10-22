plugins { id("com.android.library"); kotlin("android") }
android {
 namespace = "com.mediaharvester.extractor"
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
 implementation("org.jsoup:jsoup:1.18.3")
 implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
 implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
