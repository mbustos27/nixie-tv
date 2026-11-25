// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// Nixie: Editorial, one of Nixie's themes. It implements the contract in core/designsystem
// (package io.github.mbustos27.nixie.tv.designsystem: NixieTokens, NixieSlots, NixieThemeSpec)
// and never sees a repository or a ViewModel.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.mbustos27.nixie.tv.theme.editorial"
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

    // The screenshot tests draw the theme's screens under Robolectric's native graphics, at 1080p. They write their
    // PNGs to build/editorial-shots, or to the folder given with -PnixieShotsDir=<folder>.
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all { test ->
        val shots = providers.gradleProperty("nixieShotsDir")
            .orElse(layout.buildDirectory.dir("editorial-shots").map { it.asFile.path })
        test.systemProperty("nixie.shots.dir", shots.get())
    }
}

dependencies {
    api(project(":core:designsystem"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.activity.compose)
}
