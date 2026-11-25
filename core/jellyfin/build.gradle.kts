plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.github.glacier_jellyfin.androidtv.core.jellyfin"
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
    api(libs.jellyfin.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    // Nixie: the SDK logs through kotlin-logging, which needs slf4j on the JVM.
    testRuntimeOnly("org.slf4j:slf4j-api:1.7.30")
}
