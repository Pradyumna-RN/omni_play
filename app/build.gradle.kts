import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The in-app "What's new" reads the repository CHANGELOG.md, so that file stays the single source of truth.
abstract class CopyChangelog : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val source: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @TaskAction fun copy() {
        val out = outputDir.get().asFile.also { it.mkdirs() }
        source.get().asFile.copyTo(out.resolve("changelog.md"), overwrite = true)
    }
}

val verificationVariant = providers.gradleProperty("rtensorVerificationVariant").orElse("release")
check(verificationVariant.get() in setOf("debug", "release"))
val verificationAssemble = verificationVariant.map { "assemble${it.replaceFirstChar(Char::uppercaseChar)}" }
val verificationApk = layout.buildDirectory.file(verificationVariant.map { "outputs/apk/$it/app-$it.apk" })

androidComponents {
    onVariants { variant ->
        val copy = tasks.register<CopyChangelog>("copy${variant.name.replaceFirstChar(Char::uppercaseChar)}Changelog") {
            source.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copy, CopyChangelog::outputDir)
    }
}

android {
    namespace = "labs.rtensor.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "labs.rtensor.music"
        minSdk = 30
        targetSdk = 35
        versionCode = 53
        versionName = "0.4.0-beta.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "APPLE_NATIVE_INCLUDED", providers.gradleProperty("rtensorAppleJniDir").isPresent.toString())
        buildConfigField("boolean", "APPLE_RUNTIME_INCLUDED", providers.gradleProperty("rtensorAppleRuntimeAssetsDir").isPresent.toString())
        // An integrated build must not install as 32-bit using a dependency's
        // unrelated JNI library when the renderer is supplied only for 64-bit.
        if (providers.gradleProperty("rtensorDspJniDir").isPresent) {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    providers.gradleProperty("rtensorAppleJniDir").orNull?.let { directory ->
        sourceSets.getByName("main").jniLibs.srcDir(file(directory))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Release builds are signed with Hum's own key. Its settings live outside the repository, in
    // HUM_SIGNING_PROPS or ~/.hum-signing/signing.properties (storeFile, storePassword, keyAlias, keyPassword).
    // Without them (a fresh clone, CI) the build falls back to the debug key and says so.
    val humSigning = Properties().also { props ->
        val file = System.getenv("HUM_SIGNING_PROPS")?.let(::File) ?: File(System.getProperty("user.home"), ".hum-signing/signing.properties")
        if (file.isFile) file.inputStream().use(props::load)
    }
    val hasReleaseKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !humSigning.getProperty(it).isNullOrBlank() } &&
        File(humSigning.getProperty("storeFile")).isFile
    if (hasReleaseKey) signingConfigs.create("hum") {
        storeFile = File(humSigning.getProperty("storeFile"))
        storePassword = humSigning.getProperty("storePassword")
        keyAlias = humSigning.getProperty("keyAlias")
        keyPassword = humSigning.getProperty("keyPassword")
    } else logger.warn("Hum release key not found: signing the release build with the debug key. Set HUM_SIGNING_PROPS.")
    buildTypes {
        debug {
            // Keep the legacy OTA filename/variant and signing identity while
            // avoiding a debuggable ART/Compose runtime in distributed builds.
            isDebuggable = !providers.gradleProperty("rtensorOta").map { it.toBoolean() }.getOrElse(false)
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // From 0.4.0-beta.8 the release APK carries Hum's own key. Installs signed with the old
            // debug key (beta.1 to beta.7) cannot update to it and must be reinstalled once.
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "hum" else "debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Resource-backed Compose tests and the bundled-runtime fixture share this JVM.
            test.maxHeapSize = "1g"
            test.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            // Android accepts Origin; JDK HttpURLConnection silently removes it by default.
            // Let the loopback Media3 regression test observe the actual Android headers.
            test.systemProperty("sun.net.http.allowRestrictedHeaders", "true")
            providers.gradleProperty("rtensorAppleHostLibraryDir").orNull?.let { directory ->
                test.jvmArgs("-Djava.library.path=$directory")
                test.inputs.dir(file(directory))
                test.systemProperty("music.apple.expectNative", "true")
            }
            listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort", "javax.net.ssl.trustStore", "music.otaReleaseFixture").forEach { key ->
                System.getProperty(key)?.let { test.systemProperty(key, it) }
            }
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // Explicit authorized build input, outside the public repository.
    providers.gradleProperty("rtensorAppleRuntimeAssetsDir").orNull?.let { directory ->
        sourceSets.getByName("main").assets.srcDir(file(directory))
    }
}

// AGP's local-test resource APK can otherwise stay up to date after an external
// asset rename. Include the authorized assets explicitly for reproducible tests.
providers.gradleProperty("rtensorAppleRuntimeAssetsDir").orNull?.let { directory ->
    tasks.matching { it.name == "packageDebugUnitTestForUnitTest" }.configureEach {
        dependsOn("mergeDebugAssets")
        inputs.dir(file(directory))
    }
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
    // Apache-2.0. Picks the "From the cover" accent from the current song's artwork.
    implementation("androidx.palette:palette:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
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
    dependsOn(verificationAssemble.get())
    doLast {
        check(!android.buildTypes.getByName(verificationVariant.get()).isDebuggable) {
            "Music OTA artifacts must be non-debuggable; build with -PrtensorOta=true"
        }
        val directory = providers.gradleProperty("rtensorDspJniDir").orNull
            ?: error("Music OTA builds require -PrtensorDspJniDir pointing to the private Android libraries")
        val artifact = verificationApk.get().asFile
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
    dependsOn(verificationAssemble.get())
    doLast {
        val directory = providers.gradleProperty("rtensorFfmpegJniDir").orNull
            ?: error("Pass -PrtensorFfmpegJniDir for the E-AC-3 prototype")
        val artifact = verificationApk.get().asFile
        ZipFile(artifact).use { zip ->
            listOf("arm64-v8a", "x86_64").forEach { abi ->
                val root = file(directory).resolve(abi)
                val build = JsonSlurper().parse(root.resolve("build-info.json")) as Map<*, *>
                check(build["license"] == "LGPL-2.1-or-later" && build["ffmpeg"] == "n6.1.6")
                check(build["sourceSha256"] == "7481306bddb81f4d4b42bfde4b39ea4fa097d7fa5b8cdba377142c88172b4af9")
                check((build["decoders"] as List<*>).containsAll(listOf("ac3", "eac3", "alac"))) { "Apple lossless playback requires the ALAC-enabled decoder build" }
                listOf("libavcodec.so", "libavutil.so", "libswresample.so", "libffmpegJNI.so").forEach { name ->
                    val entry = zip.getEntry("lib/$abi/$name") ?: error("Missing $abi/$name")
                    check(zip.getInputStream(entry).use { it.readBytes() }.contentEquals(root.resolve(name).readBytes())) { "Decoder artifact changed: $abi/$name" }
                }
            }
        }
        logger.lifecycle("Verified: shared LGPL AC-3/E-AC-3 decoder and JNI packaged for ARM64 and x86-64")
    }
}

tasks.register("verifyAppleNativeBundle") {
    dependsOn(verificationAssemble.get())
    doLast {
        val root = file(providers.gradleProperty("rtensorAppleJniDir").get())
        val source = file("src/main/cpp/apple-runtime")
        val digest = MessageDigest.getInstance("SHA-256")
        source.walkTopDown().filter { it.isFile }.toList().sortedBy { it.relativeTo(source).invariantSeparatorsPath }.forEach {
            digest.update(it.relativeTo(source).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(it.readBytes())
        }
        val sourceSha = digest.digest().joinToString("") { "%02x".format(it) }
        ZipFile(verificationApk.get().asFile).use { zip ->
            for (abi in listOf("arm64-v8a", "x86_64")) {
                val metadata = JsonSlurper().parse(root.resolve("$abi/build-info.json")) as Map<*, *>
                check(metadata["sourceSha256"] == sourceSha && metadata["minimumAndroidApi"] == 30 && metadata["containsAppleLibraries"] == false)
                val bytes = zip.getInputStream(checkNotNull(zip.getEntry("lib/$abi/libmusicappleJNI.so"))).use { it.readBytes() }
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(bytes.contentEquals(root.resolve("$abi/libmusicappleJNI.so").readBytes()) && sha == metadata["adapterSha256"])
            }
            val runtimeDirectory = providers.gradleProperty("rtensorAppleRuntimeAssetsDir").orNull
            val profile = JsonSlurper().parse(file("src/main/assets/apple/runtime-profile.json")) as Map<*, *>
            val libraries = profile["libs"] as Map<*, *>
            val allowed = mutableSetOf<String>()
            if (runtimeDirectory != null) {
                val assets = file(runtimeDirectory)
                check(!assets.canonicalFile.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
                    "Keep the authorized vendor runtime outside the public source repository"
                }
                val noticeEntry = "assets/apple-runtime/acknowledgements.html"
                allowed += noticeEntry
                val noticeBytes = zip.getInputStream(checkNotNull(zip.getEntry(noticeEntry))).use { it.readBytes() }
                check(noticeBytes.contentEquals(assets.resolve("apple-runtime/acknowledgements.html").readBytes()))
                check(MessageDigest.getInstance("SHA-256").digest(noticeBytes).joinToString("") { "%02x".format(it) } ==
                    "e9f0f1ec4f861f5d36f845a6201e76f9e5f2b695a06aa39740612e3698576c60")
                for (abi in listOf("arm64-v8a", "x86_64")) {
                    val images = libraries[abi] as Map<*, *>
                    for ((image, expected) in images) {
                        val entry = "assets/apple-runtime/$abi/$image.bin"
                        allowed += entry
                        val bytes = zip.getInputStream(checkNotNull(zip.getEntry(entry))).use { it.readBytes() }
                        check(bytes.contentEquals(assets.resolve("apple-runtime/$abi/$image.bin").readBytes()))
                        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                        check(sha == expected) { "Bundled Apple runtime does not match the verified profile: $abi/$image" }
                    }
                }
            }
            val vendorNames = (libraries.values.flatMap { (it as Map<*, *>).keys }).map { it.toString() }.toSet()
            check(zip.entries().asSequence().filter { it.name.substringAfterLast('/') in vendorNames }.all { it.name in allowed }) {
                "Unexpected vendor runtime image in the APK"
            }
            check(zip.entries().asSequence().filter { it.name.startsWith("assets/apple-runtime/") && !it.isDirectory }.all { it.name in allowed })
        }
        logger.lifecycle("Verified: source-matched Apple adapter and exact optional authorized runtime assets for ARM64/x86-64")
    }
}

// Music uses the standard decoded surround bed. Retired scene and binaural
// implementations must stay out of the distributed app.
tasks.register("verifySurroundBedBundle") {
    dependsOn(verificationAssemble.get())
    doLast {
        ZipFile(verificationApk.get().asFile).use { zip ->
            check(zip.entries().asSequence().none { it.name.contains("toa_hrir") || it.name.substringAfterLast('/') in setOf("libopenjoc_capi.so", "libopenjocJNI.so") }) {
                "Retired spatial assets or experimental scene libraries were packaged"
            }
            for (entry in zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }) {
                val dex = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) }
                check(!dex.contains("Llabs/rtensor/dsp/BinauralRenderer;") && !dex.contains("Llabs/rtensor/decoder/openjoc/OpenJocAudioRenderer;"))
            }
        }
        logger.lifecycle("Verified: surround-bed playback; no Spatial renderer, HRIR assets or OpenJOC runtime")
    }
}

tasks.register("verifyReleaseOptimization") {
    dependsOn("assembleRelease")
    doLast {
        val release = android.buildTypes.getByName("release")
        check(!release.isDebuggable && release.isMinifyEnabled && release.isShrinkResources)
        check(layout.buildDirectory.file("outputs/mapping/release/mapping.txt").get().asFile.length() > 0) {
            "The release APK must be processed by R8"
        }
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        check(apk.length() < 100_000_000) { "The release APK exceeds the 100 MB download target" }
        ZipFile(apk).use { zip ->
            val dex = zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }
                .map { entry -> zip.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) } }.toList()
            for (name in listOf("labs/rtensor/dsp/NativeBindings", "androidx/media3/decoder/ffmpeg/FfmpegLibrary",
                "androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder", "labs/rtensor/music/apple/NativeJson",
                "labs/rtensor/music/apple/NativeAppleBridge")) {
                check(dex.any { it.contains("L$name;") }) { "R8 removed or renamed a required JNI entry point: $name" }
            }
        }
        logger.lifecycle("Verified: optimized release APK below 100 MB; JNI entry points retained")
    }
}
