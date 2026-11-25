plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.github.glacier_jellyfin.androidtv.core.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    // Nixie: Android stubs answer with defaults in unit tests (the SDK wants a Context it does not use there).
    testOptions.unitTests.isReturnDefaultValues = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:log"))
    api(project(":core:jellyfin"))
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    // Nixie: the server's websocket for live row updates (OkHttpSocketLink), the version the SDK brings.
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation(libs.kotlinx.coroutines.android)
    // Nixie (tv-player-4k): DisplayCompat, the panel's real size for transcodes (DisplayPanel).
    implementation(libs.androidx.core.ktx)
    // Nixie (tv-player-4k): the app's foreground state for the live updates (LiveUpdates), the version the app has.
    implementation("androidx.lifecycle:lifecycle-process:${libs.versions.lifecycle.get()}")
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Nixie (tv-player-4k): the device's decoders stand in for MediaCapabilityDetector in PanelPlaybackTest.
    testImplementation("io.mockk:mockk:1.14.11")
    // Nixie: the SDK logs through kotlin-logging, which needs slf4j on the JVM.
    testRuntimeOnly("org.slf4j:slf4j-api:1.7.30")
}

// Nixie: NixieMarkersTest reads LibraryRepository.kt itself, so a change to its comments runs the tests again.
tasks.withType<Test>().configureEach {
    inputs.file("src/main/java/io/github/glacier_jellyfin/androidtv/core/data/media/LibraryRepository.kt")
}
