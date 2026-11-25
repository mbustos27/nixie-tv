// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// Nixie: Cinematic, one of Nixie's themes. It implements the contract in core/designsystem
// (package io.github.mbustos27.nixie.tv.designsystem: NixieTokens, NixieSlots, NixieThemeSpec)
// and never sees a repository or a ViewModel.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.mbustos27.nixie.tv.theme.cinematic"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The screenshot tests render on the JVM (Robolectric's native graphics) and need the fonts.
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all { test ->
        // Roborazzi always writes the images (nothing is compared yet, as in the app's ThemeShotsTest).
        test.systemProperty("roborazzi.test.record", "true")
        // Where the screenshots go: -Pnixie.shots=<folder>; build/shots/cinematic otherwise.
        test.systemProperty(
            "nixie.shots",
            providers.gradleProperty("nixie.shots")
                .getOrElse(layout.buildDirectory.dir("shots/cinematic").get().asFile.path),
        )
    }
}

dependencies {
    api(project(":core:designsystem"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    // The same Roborazzi as the app's ThemeShotsTest.
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.78.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.78.0")
}
