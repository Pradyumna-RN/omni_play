import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "labs.rtensor.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "labs.rtensor.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.3.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            // Android accepts Origin; JDK HttpURLConnection silently removes it by default.
            // Let the loopback Media3 regression test observe the actual Android headers.
            test.systemProperty("sun.net.http.allowRestrictedHeaders", "true")
            listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort", "javax.net.ssl.trustStore").forEach { key ->
                System.getProperty(key)?.let { test.systemProperty(key, it) }
            }
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val verifyYouTubeEngine by tasks.registering {
    val engineRoot = rootProject.file("youtube-engine")
    val sources = fileTree(engineRoot.resolve("src")) { include("**/*.mjs") }.files +
        listOf("build.mjs", "package.json", "package-lock.json").map { engineRoot.resolve(it) }
    val bundle = file("src/main/assets/engine/engine.js")
    val manifest = file("src/main/assets/engine/manifest.json")
    val proof = file("src/main/assets/engine/proof.js")
    inputs.files(sources, bundle, proof, manifest)
    doLast {
        val metadata = JsonSlurper().parse(manifest) as Map<*, *>
        fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
        val digest = MessageDigest.getInstance("SHA-256")
        sources.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }.forEach { source ->
            digest.update(source.relativeTo(rootDir).invariantSeparatorsPath.toByteArray())
            digest.update(0.toByte()); digest.update(source.readBytes()); digest.update(0.toByte())
        }
        check(digest.digest().hex() == metadata["sourceDigest"] && MessageDigest.getInstance("SHA-256").digest(bundle.readBytes()).hex() == metadata["bundleDigest"] && MessageDigest.getInstance("SHA-256").digest(proof.readBytes()).hex() == metadata["proofDigest"]) {
            "YouTube bundle is stale. Run npm ci --prefix youtube-engine, then npm run build --prefix youtube-engine."
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyYouTubeEngine) }
