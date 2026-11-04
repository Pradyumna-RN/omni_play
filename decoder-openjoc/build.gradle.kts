plugins { id("com.android.library") }
android {
    namespace = "labs.rtensor.decoder.openjoc"
    compileSdk = 35
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    providers.gradleProperty("rtensorOpenJocJniDir").orNull?.let { sourceSets.getByName("main").jniLibs.srcDir(file(it)) }
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all {
        it.forkEvery = 1
        it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort", "javax.net.ssl.trustStore").forEach { key ->
            System.getProperty(key)?.let { value -> it.systemProperty(key, value) }
        }
        providers.gradleProperty("rtensorOpenJocHostLibraryDir").orNull?.let { dir ->
            it.jvmArgs("-Djava.library.path=$dir"); it.inputs.dir(file(dir)); it.systemProperty("rtensor.openjoc.expectNative", "true")
        }
        System.getProperty("rtensor.openjoc.recording")?.let { file -> it.systemProperty("rtensor.openjoc.recording", file) }
    }
}
dependencies {
    api("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.annotation:annotation:1.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
