import java.io.File

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "labs.rtensor.dsp"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("boolean", "PRIVATE_DSP_INCLUDED", providers.gradleProperty("rtensorDspJniDir").isPresent.toString())
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Only interfaces/adapters are public. The implementation is supplied by a
    // separate private build; no source, coefficients or binary is committed here.
    providers.gradleProperty("rtensorDspJniDir").orNull?.let { directory ->
        sourceSets.getByName("main").jniLibs.srcDir(file(directory))
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all {
        // A native library belongs to one JVM classloader. Isolate Robolectric's
        // sandbox JNI checks from the plain host-JVM JNI checks.
        it.forkEvery = 1
        it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort", "javax.net.ssl.trustStore").forEach { key ->
            System.getProperty(key)?.let { value -> it.systemProperty(key, value) }
        }
        val nativeDirectories = listOf("rtensorDspHostLibraryDir", "rtensorFfmpegHostLibraryDir", "rtensorOpenJocHostLibraryDir")
            .mapNotNull { key -> providers.gradleProperty(key).orNull }
        if (nativeDirectories.isNotEmpty()) {
            it.jvmArgs("-Djava.library.path=" + nativeDirectories.joinToString(File.pathSeparator))
            nativeDirectories.forEach { directory -> it.inputs.dir(file(directory)) }
        }
        if (providers.gradleProperty("rtensorDspHostLibraryDir").isPresent) {
            it.systemProperty("rtensor.dsp.expectNative", "true")
        }
        if (providers.gradleProperty("rtensorFfmpegHostLibraryDir").isPresent) it.systemProperty("rtensor.ffmpeg.expectNative", "true")
        if (providers.gradleProperty("rtensorOpenJocHostLibraryDir").isPresent) it.systemProperty("rtensor.openjoc.expectNative", "true")
        System.getProperty("rtensor.surround.recording")?.let { path -> it.systemProperty("rtensor.surround.recording", path) }
    }
}
dependencies {
    implementation(project(":decoder-ffmpeg"))
    api("androidx.media3:media3-exoplayer:1.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

providers.gradleProperty("rtensorDspJniDir").orNull?.let { directory ->
    val verifyPrivateDsp by tasks.registering {
        inputs.dir(file(directory))
        doLast {
            val root = file(directory)
            val libraries = root.walkTopDown().filter { it.isFile && it.extension == "so" }.toList()
            check(libraries.isNotEmpty()) { "The private DSP directory contains no compiled libraries" }
            libraries.forEach { library ->
                val relative = library.relativeTo(root).invariantSeparatorsPath
                check(relative in listOf("arm64-v8a/librtensor_downmix.so", "x86_64/librtensor_downmix.so")) {
                    "Unexpected native library in the private DSP directory: $relative"
                }
            }
        }
    }
    tasks.named("preBuild") { dependsOn(verifyPrivateDsp) }
}
