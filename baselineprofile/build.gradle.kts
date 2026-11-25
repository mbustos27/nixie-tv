// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// Nixie: generates the app's baseline profile (start-up and Home) on a connected emulator or box:
// .\gradlew.bat :app:generateNixieReleaseBaselineProfile with the TV test server on 127.0.0.1:18412
// (testing/fakeserver) and one emulator running. The profile lands in app/src/nixieRelease/generated/.
plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "io.github.mbustos27.nixie.tv.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    // The generator runs in its own process (not the app's), so it can reach the test server itself.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    flavorDimensions += "brand"
    productFlavors {
        create("nixie") { dimension = "brand" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.1")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
