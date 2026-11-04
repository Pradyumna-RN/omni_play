import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.zip.ZipFile

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
        versionCode = 22
        versionName = "0.3.19"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // An integrated build must not install as 32-bit using a dependency's
        // unrelated JNI library when the renderer is supplied only for 64-bit.
        if (providers.gradleProperty("rtensorDspJniDir").isPresent) {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        debug {
            // Keep the legacy OTA filename/variant and signing identity while
            // avoiding a debuggable ART/Compose runtime in distributed builds.
            isDebuggable = !providers.gradleProperty("rtensorOta").map { it.toBoolean() }.getOrElse(false)
        }
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
            listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort", "javax.net.ssl.trustStore", "music.otaReleaseFixture").forEach { key ->
                System.getProperty(key)?.let { test.systemProperty(key, it) }
            }
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":dsp"))
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
    implementation("androidx.media3:media3-exoplayer-dash:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
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

// Official OTA artifacts must carry the tested native renderer inside Music.
// Contributor builds without the private source/artifact remain supported.
tasks.register("verifyOtaDspBundle") {
    dependsOn("assembleDebug")
    doLast {
        check(!android.buildTypes.getByName("debug").isDebuggable) {
            "Music OTA artifacts must be non-debuggable; build with -PrtensorOta=true"
        }
        val directory = providers.gradleProperty("rtensorDspJniDir").orNull
            ?: error("Music OTA builds require -PrtensorDspJniDir pointing to the private Android libraries")
        val artifact = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        ZipFile(artifact).use { zip ->
            listOf("arm64-v8a", "x86_64").forEach { abi ->
                val expected = file(directory).resolve("$abi/librtensor_downmix.so")
                check(expected.isFile) { "Missing private DSP for $abi" }
                val entry = zip.getEntry("lib/$abi/librtensor_downmix.so")
                    ?: error("The Music OTA APK does not contain the $abi renderer")
                val packaged = zip.getInputStream(entry).use { it.readBytes() }
                check(packaged.contentEquals(expected.readBytes())) {
                    "The Music OTA APK contains a different renderer for $abi"
                }
            }
        }
        logger.lifecycle("Verified: Music OTA APK bundles the supplied ARM64 and x86-64 DSP libraries")
    }
}

// Prototype builds opt in explicitly. Prevent an APK with only one ABI or a
// loader missing one of its shared LGPL libraries from passing verification.
tasks.register("verifyEac3DecoderBundle") {
    dependsOn("assembleDebug")
    doLast {
        val directory = providers.gradleProperty("rtensorFfmpegJniDir").orNull
            ?: error("Pass -PrtensorFfmpegJniDir for the E-AC-3 prototype")
        val artifact = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        ZipFile(artifact).use { zip ->
            listOf("arm64-v8a", "x86_64").forEach { abi ->
                val root = file(directory).resolve(abi)
                val build = JsonSlurper().parse(root.resolve("build-info.json")) as Map<*, *>
                check(build["license"] == "LGPL-2.1-or-later" && build["ffmpeg"] == "n6.1.6")
                check(build["sourceSha256"] == "7481306bddb81f4d4b42bfde4b39ea4fa097d7fa5b8cdba377142c88172b4af9")
                listOf("libavcodec.so", "libavutil.so", "libswresample.so", "libffmpegJNI.so").forEach { name ->
                    val entry = zip.getEntry("lib/$abi/$name") ?: error("Missing $abi/$name")
                    check(zip.getInputStream(entry).use { it.readBytes() }.contentEquals(root.resolve(name).readBytes())) { "Decoder artifact changed: $abi/$name" }
                }
            }
        }
        logger.lifecycle("Verified: shared LGPL AC-3/E-AC-3 decoder and JNI packaged for ARM64 and x86-64")
    }
}

// Optional experimental scene decoding must be the exact evaluated public native
// build. This is separate from the private DMR and shared LGPL bed-decoder guards.
tasks.register("verifyOpenJocBundle") {
    dependsOn("assembleDebug")
    val directory = providers.gradleProperty("rtensorOpenJocJniDir")
    doLast {
        val root = file(directory.get())
        val metadata = JsonSlurper().parse(root.parentFile.resolve("native-build.json")) as Map<*, *>
        check(metadata["sourceCommit"] == "e3e330a2000f8d0bced3460756473c8774eb16f0" && metadata["abi"] == "1.7-experimental")
        check(metadata["embeddedHrtf"] == false && metadata["speakerLayout"] == "7.1.4")
        val bridge = rootProject.file("decoder-openjoc/src/main/jni/openjoc_jni.cpp")
        val bridgeSha = MessageDigest.getInstance("SHA-256").digest(bridge.readBytes()).joinToString("") { "%02x".format(it) }
        check(metadata["bridgeSha256"] == bridgeSha) { "Scene decoder JNI is stale; rebuild the native adapter" }
        val artifacts = metadata["artifacts"] as Map<*, *>
        ZipFile(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile).use { zip ->
            for (abi in listOf("arm64-v8a", "x86_64")) {
                val expected = artifacts[abi] as Map<*, *>
                for (name in listOf("libopenjoc_capi.so", "libopenjocJNI.so")) {
                    val packaged = zip.getInputStream(checkNotNull(zip.getEntry("lib/$abi/$name"))).use { it.readBytes() }
                    check(packaged.contentEquals(root.resolve("$abi/$name").readBytes()))
                    val sha = MessageDigest.getInstance("SHA-256").digest(packaged).joinToString("") { "%02x".format(it) }
                    check(sha == expected[name]) { "Unverified scene decoder: $abi/$name" }
                }
            }
        }
        logger.lifecycle("Verified: pinned, speaker-only OpenJOC and JNI for ARM64 and x86-64")
    }
}
