plugins { id("com.android.library") }
android {
    namespace = "labs.rtensor.decoder.ffmpeg"
    compileSdk = 35
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    providers.gradleProperty("rtensorFfmpegJniDir").orNull?.let { sourceSets.getByName("main").jniLibs.srcDir(file(it)) }
}
dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
    api("androidx.media3:media3-exoplayer:1.6.1")
    implementation("org.checkerframework:checker-qual:3.43.0")
}
